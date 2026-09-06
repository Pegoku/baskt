import { cachedChatJson, chatJson } from "@/ai/client";
import type { ParsedIdea } from "@/db/schema";
import { normalizeText, sha256 } from "@/lib/text";
import type { Unit } from "@/lib/units";

export const PARSE_PROMPT_VERSION = "v1";

const SYSTEM = `You turn loose grocery "ideas" written by someone living in the Netherlands into search data for Dutch supermarkets.
The idea may be Dutch, English, Spanish or a mix (e.g. "halfvolle milk" = semi-skimmed milk).
Return ONLY a JSON object:
{
  "canonicalName": short generic English product name (e.g. "semi-skimmed milk"),
  "attributes": array of Dutch keywords that matter (e.g. ["halfvol"], ["houdbaar"], ["bio"], ["vers"]); [] if none,
  "sizeHint": {"amount": number, "unit": "kg"|"l"|"piece"} describing the total amount wanted, or null when not stated,
  "queries": object mapping each given store code to the best short Dutch search query for that store's webshop (usually 1-3 words, no brand unless the user named one),
  "ambiguous": true when the idea is vague (e.g. "something for pasta sauce"), else false
}
Do not add other keys. Do not explain.`;

const UNITS: Unit[] = ["kg", "l", "piece"];

export function fallbackParse(text: string, storeCodes: string[]): ParsedIdea {
  const clean = text.trim();
  return {
    canonicalName: clean.toLowerCase(),
    attributes: [],
    sizeHint: null,
    queries: Object.fromEntries(storeCodes.map((code) => [code, clean])),
    ambiguous: false,
  };
}

function sanitize(raw: Partial<ParsedIdea> | null, text: string, storeCodes: string[]): ParsedIdea {
  const fallback = fallbackParse(text, storeCodes);
  if (!raw || typeof raw !== "object") return fallback;
  const queries: Record<string, string> = {};
  for (const code of storeCodes) {
    const query = raw.queries && typeof raw.queries === "object" ? raw.queries[code] : undefined;
    queries[code] = typeof query === "string" && query.trim() ? query.trim() : fallback.queries[code];
  }
  const sizeHint =
    raw.sizeHint && typeof raw.sizeHint.amount === "number" && raw.sizeHint.amount > 0 && UNITS.includes(raw.sizeHint.unit as Unit)
      ? { amount: raw.sizeHint.amount, unit: raw.sizeHint.unit }
      : null;
  return {
    canonicalName: typeof raw.canonicalName === "string" && raw.canonicalName.trim() ? raw.canonicalName.trim().toLowerCase() : fallback.canonicalName,
    attributes: Array.isArray(raw.attributes) ? raw.attributes.filter((value): value is string => typeof value === "string").map((value) => value.toLowerCase()) : [],
    sizeHint,
    queries,
    ambiguous: Boolean(raw.ambiguous),
  };
}

export async function parseIdea(text: string, storeCodes: string[]): Promise<ParsedIdea> {
  const key = await sha256(`${PARSE_PROMPT_VERSION}|${normalizeText(text)}|${storeCodes.join(",")}`);
  const raw = await cachedChatJson<Partial<ParsedIdea>>(
    "parse",
    key,
    [
      { role: "system", content: SYSTEM },
      { role: "user", content: `Store codes: ${storeCodes.join(", ")}\nIdea: ${text}` },
    ],
    { maxTokens: 400 },
  );
  return sanitize(raw, text, storeCodes);
}

/** Splits a pasted free-text list into individual ideas (AI first, simple splitting as fallback). */
export async function splitShoppingText(text: string): Promise<Array<{ text: string; quantity: number }>> {
  const fallback = text
    .split(/[\n,;]+|\s+\b(?:and|en|y)\b\s+/gi)
    .map((part) => part.trim())
    .filter(Boolean)
    .slice(0, 40)
    .map((part) => {
      const match = part.match(/^(\d+)\s*x?\s+(.+)$/i);
      return match ? { text: match[2], quantity: Number(match[1]) } : { text: part, quantity: 1 };
    });
  const raw = await chatJson<{ items?: Array<{ text?: string; quantity?: number }> }>(
    [
      {
        role: "system",
        content:
          'Split the user\'s grocery request into individual items. Return ONLY {"items":[{"text": short item description keeping the user\'s wording and any size, "quantity": integer count of packs, default 1}]}. Do not translate. Do not add items.',
      },
      { role: "user", content: text },
    ],
    { maxTokens: 800 },
  );
  const items = (raw?.items ?? [])
    .filter((item) => typeof item.text === "string" && item.text.trim())
    .map((item) => ({ text: item.text!.trim(), quantity: Number.isInteger(item.quantity) && item.quantity! > 0 ? item.quantity! : 1 }));
  return items.length ? items : fallback;
}

/** Asks for a different search query after the user rejected everything shown so far. */
export async function alternativeQuery(text: string, parsed: ParsedIdea, store: string, rejectedTitles: string[], triedQueries: string[]) {
  const raw = await chatJson<{ query?: string }>(
    [
      {
        role: "system",
        content:
          'The user is shopping at a Dutch supermarket webshop. Earlier search queries did not find what they meant. Suggest ONE different short Dutch search query (1-3 words) likely to find it. Return ONLY {"query": "..."}.',
      },
      {
        role: "user",
        content: `Idea: ${text}\nCanonical: ${parsed.canonicalName}\nStore: ${store}\nQueries already tried: ${triedQueries.join(" | ")}\nProducts the user rejected: ${rejectedTitles.join(" | ") || "none"}`,
      },
    ],
    { maxTokens: 100 },
  );
  const query = raw?.query?.trim();
  if (!query || triedQueries.some((tried) => normalizeText(tried) === normalizeText(query))) return null;
  return query;
}

import { cachedChatJson } from "@/ai/client";
import type { ParsedIdea, ProductRow } from "@/db/schema";
import { appLanguageName } from "@/db/settings";
import { aiConfigured } from "@/env";
import { normalizeText, sha256, tokenize, tokenSimilarity } from "@/lib/text";
import { genericTitle } from "@/lib/units";
import { lexicalRank, referenceSimilarity } from "@/matching/rank";
import { searchStore } from "@/stores/search";
import type { StoreCode } from "@/stores/types";

export const SIMILAR_PROMPT_VERSION = "v1";

/** SAME = the identical product (brand and pack), EQUIVALENT = the store's own version of it, SUBSTITUTE = would do instead. */
export type SimilarKind = "SAME" | "EQUIVALENT" | "SUBSTITUTE";
const KINDS: SimilarKind[] = ["SAME", "EQUIVALENT", "SUBSTITUTE"];

export type SimilarMatch = { product: ProductRow; kind: SimilarKind; note: string | null };

export type SimilarStoreResult = {
  store: StoreCode;
  /** Best first; the source product itself is never listed. */
  matches: SimilarMatch[];
  /** Search queries that were sent to the store, for the "nothing found" explanation. */
  queries: string[];
  error: string | null;
};

const QUERY_SYSTEM = `A shopper in the Netherlands has a specific supermarket product open and wants the same or a similar product at other supermarkets (Albert Heijn, Jumbo, ...).
Work out WHAT the product is: its type, brand, variant (e.g. halfvol, biologisch, zonder suiker), and pack size.
Return ONLY {"canonical": generic Dutch product type without brand or size (e.g. "halfvolle melk"), "attributes": [Dutch words for the variants that matter, e.g. "biologisch"], "queries": [3 to 5 short Dutch webshop search queries, best first]}.
Queries: first the exact product with its brand (so the identical product is found where it is sold), then the type plus size ("halfvolle melk 1 liter"), then the store's own-brand equivalent or a close sibling. No store names in queries.`;

const RANK_SYSTEM = `You compare supermarket products for a shopper in the Netherlands. Given a REFERENCE product and CANDIDATES from one store, order the candidates from closest to farthest and label each:
SAME = the identical product (same brand, same variant, same or nearly the same pack size), EQUIVALENT = the same product type and variant but another brand (typically the store brand) or a different pack size, SUBSTITUTE = a different product that could stand in.
Put SAME first, then EQUIVALENT, then SUBSTITUTE; within a label prefer the closest pack size and the better price per unit. Leave out candidates that are clearly a different kind of product.
Return ONLY {"ranking":[{"idx": candidate index, "kind": "SAME"|"EQUIVALENT"|"SUBSTITUTE", "note": at most 8 words in LANGUAGE saying what differs (empty for SAME)}]}.`;

function describe(product: ProductRow) {
  const price = `€${(product.priceCents / 100).toFixed(2)}`;
  const unit = product.unitPriceCents && product.unitPriceUnit ? ` (€${(product.unitPriceCents / 100).toFixed(2)}/${product.unitPriceUnit})` : "";
  return `${product.title} | ${product.quantityText} | ${price}${unit}${product.brand ? ` | ${product.brand}` : ""}${product.category ? ` | ${product.category}` : ""}${product.available ? "" : " UNAVAILABLE"}`;
}

function escapeRegExp(value: string) {
  return value.replace(/[.*+?^${}()|[\]\\]/g, "\\$&");
}

/** The title without the store prefix and, separately, without the brand: what to type into another store's search box. */
export function heuristicQueries(product: ProductRow) {
  const withoutStore = product.title.replace(/^(AH|Jumbo(?:'s)?|Lidl|Aldi|Plus|Dirk)\b\s*/i, "").trim() || product.title;
  const brandless = product.brand ? withoutStore.replace(new RegExp(escapeRegExp(product.brand), "i"), "").replace(/\s{2,}/g, " ").trim() : withoutStore;
  return dedupeQueries([withoutStore, brandless || withoutStore, genericTitle(brandless || withoutStore, product.store)]);
}

function dedupeQueries(list: string[]) {
  const seen = new Set<string>();
  const out: string[] = [];
  for (const query of list) {
    const key = normalizeText(query);
    if (!key || seen.has(key)) continue;
    seen.add(key);
    out.push(query.trim());
  }
  return out;
}

type Understanding = { canonical: string; attributes: string[]; queries: string[] };

async function understand(product: ProductRow): Promise<Understanding> {
  const fallback: Understanding = { canonical: genericTitle(heuristicQueries(product)[1] ?? product.title, product.store), attributes: [], queries: heuristicQueries(product) };
  if (!aiConfigured()) return fallback;
  const key = await sha256(`${SIMILAR_PROMPT_VERSION}|${product.id}|${normalizeText(product.title)}|${product.quantityText}`);
  const raw = await cachedChatJson<{ canonical?: unknown; attributes?: unknown; queries?: unknown }>(
    "similar-queries",
    key,
    [
      { role: "system", content: QUERY_SYSTEM },
      { role: "user", content: describe(product) },
    ],
    { maxTokens: 300 },
  );
  const strings = (value: unknown, limit: number) =>
    Array.isArray(value) ? value.filter((entry): entry is string => typeof entry === "string" && entry.trim().length > 0).map((entry) => entry.trim()).slice(0, limit) : [];
  const queries = strings(raw?.queries, 5);
  if (!queries.length) return fallback;
  return {
    canonical: typeof raw?.canonical === "string" && raw.canonical.trim() ? raw.canonical.trim() : fallback.canonical,
    attributes: strings(raw?.attributes, 6),
    // The AI's queries first, then the literal title as a safety net for stores that sell the exact product.
    queries: dedupeQueries([...queries, ...fallback.queries]).slice(0, 6),
  };
}

/** Without AI: same brand and a matching pack is the same product; a close pack of another brand is the equivalent. */
export function heuristicKind(reference: ProductRow, product: ProductRow): SimilarKind {
  const sameBrand = Boolean(reference.brand && product.brand && normalizeText(reference.brand) === normalizeText(product.brand));
  const referenceTokens = tokenize(reference.title).filter((token) => !/^\d+([.,]\d+)?$/.test(token));
  const productTokens = tokenize(product.title);
  const overlap = referenceTokens.length
    ? referenceTokens.filter((token) => productTokens.some((candidate) => tokenSimilarity(token, candidate) >= 0.8)).length / referenceTokens.length
    : 0;
  const samePack =
    reference.unit && product.unit && reference.unit === product.unit && reference.unitAmount && product.unitAmount
      ? Math.min(reference.unitAmount, product.unitAmount) / Math.max(reference.unitAmount, product.unitAmount) >= 0.8
      : false;
  if (sameBrand && samePack && overlap >= 0.6) return "SAME";
  return referenceSimilarity(reference, product) >= 0.55 ? "EQUIVALENT" : "SUBSTITUTE";
}

async function aiRank(reference: ProductRow, store: StoreCode, candidates: ProductRow[]): Promise<SimilarMatch[] | null> {
  if (!aiConfigured() || !candidates.length) return null;
  const key = await sha256([SIMILAR_PROMPT_VERSION, appLanguageName(), reference.id, store, candidates.map((product) => `${product.id}@${product.priceCents}`).join(",")].join("|"));
  const raw = await cachedChatJson<{ ranking?: Array<{ idx?: unknown; kind?: unknown; note?: unknown }> }>(
    "similar-rank",
    key,
    [
      { role: "system", content: RANK_SYSTEM.replaceAll("LANGUAGE", appLanguageName()) },
      {
        role: "user",
        content: [`Reference (${reference.store}): ${describe(reference)}`, `Candidates at ${store}:`, ...candidates.map((product, index) => `${index}. ${describe(product)}`)].join("\n"),
      },
    ],
    { maxTokens: 600 },
  );
  if (!Array.isArray(raw?.ranking)) return null;
  const seen = new Set<string>();
  const matches: SimilarMatch[] = [];
  for (const entry of raw!.ranking) {
    const product = typeof entry.idx === "number" ? candidates[entry.idx] : undefined;
    if (!product || seen.has(product.id)) continue;
    seen.add(product.id);
    const kind = KINDS.includes(entry.kind as SimilarKind) ? (entry.kind as SimilarKind) : heuristicKind(reference, product);
    const note = typeof entry.note === "string" && entry.note.trim() ? entry.note.trim().slice(0, 80) : null;
    matches.push({ product, kind, note: kind === "SAME" ? null : note });
  }
  return matches;
}

const KIND_ORDER: Record<SimilarKind, number> = { SAME: 0, EQUIVALENT: 1, SUBSTITUTE: 2 };

/**
 * "Find something like this elsewhere": searches every given store for the product (or its closest
 * equivalent) and returns the best matches per store, labelled SAME / EQUIVALENT / SUBSTITUTE. The
 * product's own store is searched too (for alternatives), but the product itself is never returned.
 */
export async function findSimilar(product: ProductRow, stores: StoreCode[], options: { limit?: number } = {}): Promise<SimilarStoreResult[]> {
  const limit = Math.max(1, Math.min(options.limit ?? 5, 10));
  const understanding = await understand(product);
  const parsed: ParsedIdea = {
    canonicalName: understanding.canonical,
    attributes: understanding.attributes,
    sizeHint: product.unitAmount && product.unit ? { amount: product.unitAmount, unit: product.unit } : null,
    queries: {},
    fallbackQuery: null,
    ambiguous: false,
  };
  const text = heuristicQueries(product)[1] ?? product.title;

  return Promise.all(
    stores.map(async (store): Promise<SimilarStoreResult> => {
      const seen = new Set<string>([product.id]);
      const fresh: ProductRow[] = [];
      const tried: string[] = [];
      let lastError: string | null = null;
      for (const query of understanding.queries) {
        tried.push(query);
        try {
          const result = await searchStore(store, query, { limit: 20 });
          for (const candidate of result.products) {
            if (seen.has(candidate.id)) continue;
            seen.add(candidate.id);
            fresh.push(candidate);
          }
        } catch (error) {
          lastError = error instanceof Error ? error.message : String(error);
          console.warn(`[similar] ${store} "${query}" failed: ${lastError}`);
        }
        // Enough material once the top of the list is stable; the rest of the queries only add noise.
        if (fresh.length >= 12) break;
      }
      if (!fresh.length) return { store, matches: [], queries: tried, error: lastError };

      const shortlist = lexicalRank(text, parsed, fresh, product)
        .slice(0, 8)
        .map((entry) => entry.product);
      const ranked =
        (await aiRank(product, store, shortlist)) ??
        shortlist
          .map((candidate) => ({ product: candidate, kind: heuristicKind(product, candidate), note: null }))
          .sort((a, b) => KIND_ORDER[a.kind] - KIND_ORDER[b.kind]);
      return { store, matches: ranked.slice(0, limit), queries: tried, error: null };
    }),
  );
}

import { and, eq } from "drizzle-orm";
import { cachedChatJson, type ChatMessage, type ChatPart } from "@/ai/client";
import { db, now } from "@/db";
import { similarFeedback, type ParsedIdea, type ProductRow } from "@/db/schema";
import { appLanguageName } from "@/db/settings";
import { aiConfigured, visionConfigured } from "@/env";
import { normalizeText, sha256, tokenize, tokenSimilarity } from "@/lib/text";
import { genericTitle } from "@/lib/units";
import { lexicalRank, referenceSimilarity } from "@/matching/rank";
import { productsByIds, searchStore } from "@/stores/search";
import type { StoreCode } from "@/stores/types";

export const SIMILAR_PROMPT_VERSION = "v3";

/** How many candidates per store are ranked and returned at most. */
export const SIMILAR_MAX = 12;
/** Vision providers cap the pictures per request (Groq: 5), so candidates are compared in small groups. */
const IMAGES_PER_CALL = 5;

/** SAME = the identical product (brand and pack), EQUIVALENT = the store's own version of it, SUBSTITUTE = would do instead. */
export type SimilarKind = "SAME" | "EQUIVALENT" | "SUBSTITUTE";
const KINDS: SimilarKind[] = ["SAME", "EQUIVALENT", "SUBSTITUTE"];
const KIND_ORDER: Record<SimilarKind, number> = { SAME: 0, EQUIVALENT: 1, SUBSTITUTE: 2 };

export type SimilarMatch = {
  product: ProductRow;
  kind: SimilarKind;
  note: string | null;
  /** The user's thumbs on this pairing, when given. */
  feedback: "UP" | "DOWN" | null;
};

export type SimilarStoreResult = {
  store: StoreCode;
  /** Best first; the source product itself and products the user rejected are never listed. */
  matches: SimilarMatch[];
  /** Search queries that were sent to the store, for the "nothing found" explanation. */
  queries: string[];
  /** True when the product pictures were compared, not only the titles. */
  vision: boolean;
  error: string | null;
};

const QUERY_SYSTEM = `A shopper in the Netherlands has a specific supermarket product open and wants the same or a similar product at other supermarkets (Albert Heijn, Jumbo, ...).
Work out WHAT the product is: its type, brand, variant (e.g. halfvol, biologisch, zonder suiker, flavour), and pack size. When a picture is given, read the packaging: brand, variant, flavour, claims and size printed on it count more than the title.
Return ONLY {"canonical": generic Dutch product type without brand or size (e.g. "halfvolle melk"), "attributes": [Dutch words for the variants that matter, e.g. "biologisch", "naturel"], "description": one sentence in English describing the product as seen (packaging type, flavour, variant), "queries": [3 to 5 short Dutch webshop search queries, best first]}.
Queries: first the exact product with its brand (so the identical product is found where it is sold), then the type plus size ("halfvolle melk 1 liter"), then the store's own-brand equivalent or a close sibling. No store names in queries.`;

const RANK_SYSTEM = `You compare supermarket products for a shopper in the Netherlands. Given a REFERENCE product and CANDIDATES from one store, label every candidate:
SAME = the identical product (same brand, same variant, same or nearly the same pack size), EQUIVALENT = the same product type and variant but another brand (typically the store brand) or a different pack size, SUBSTITUTE = a different product that could stand in.
When pictures are given, compare the packaging: the same product looks the same; a different flavour, variant or claim on the pack makes it EQUIVALENT or SUBSTITUTE even if the titles look alike.
Also give a similarity score from 0 (nothing alike) to 100 (identical): within a label the single pack closest to the reference size scores highest; multipacks, cases and bulk packs of the same product score lower than its single pack. Leave out candidates that are clearly a different kind of product.
The user's earlier feedback, when given, is the truth: rank candidates that resemble the ones they confirmed higher and the ones that resemble what they rejected lower.
Return ONLY {"ranking":[{"idx": candidate index, "kind": "SAME"|"EQUIVALENT"|"SUBSTITUTE", "score": 0-100, "note": at most 8 words in LANGUAGE saying what differs (empty for SAME)}]}.`;

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

// ---- feedback -------------------------------------------------------------------------------------

/** Records (or with `up` null, forgets) the user's verdict on a similar-product pairing. */
export function setSimilarFeedback(referenceProductId: string, productId: string, up: boolean | null) {
  const database = db();
  const where = and(eq(similarFeedback.referenceProductId, referenceProductId), eq(similarFeedback.productId, productId));
  if (up === null) {
    database.delete(similarFeedback).where(where).run();
    return null;
  }
  database
    .insert(similarFeedback)
    .values({ referenceProductId, productId, up, createdAt: now() })
    .onConflictDoUpdate({ target: [similarFeedback.referenceProductId, similarFeedback.productId], set: { up, createdAt: now() } })
    .run();
  return up ? "UP" : "DOWN";
}

export function similarFeedbackFor(referenceProductId: string): Map<string, boolean> {
  return new Map(
    db()
      .select({ productId: similarFeedback.productId, up: similarFeedback.up })
      .from(similarFeedback)
      .where(eq(similarFeedback.referenceProductId, referenceProductId))
      .all()
      .map((row) => [row.productId, row.up] as const),
  );
}

/** The feedback as prompt lines, with titles so the model can generalise to look-alikes. */
function feedbackContext(feedback: Map<string, boolean>) {
  if (!feedback.size) return "";
  const rows = productsByIds(Array.from(feedback.keys()));
  const confirmed = rows.filter((row) => feedback.get(row.id)).map((row) => `"${row.title} ${row.quantityText}" (${row.store})`);
  const rejected = rows.filter((row) => feedback.get(row.id) === false).map((row) => `"${row.title} ${row.quantityText}" (${row.store})`);
  const lines: string[] = [];
  if (confirmed.length) lines.push(`The user confirmed these stand in for the reference: ${confirmed.join(", ")}.`);
  if (rejected.length) lines.push(`The user rejected these as not fitting: ${rejected.join(", ")}.`);
  return lines.length ? `Earlier feedback from the user (any store):\n${lines.join("\n")}` : "";
}

// ---- understanding the reference ----------------------------------------------------------------

type Understanding = { canonical: string; attributes: string[]; description: string | null; queries: string[] };

function parseUnderstanding(raw: { canonical?: unknown; attributes?: unknown; description?: unknown; queries?: unknown } | null, fallback: Understanding): Understanding | null {
  const strings = (value: unknown, limit: number) =>
    Array.isArray(value) ? value.filter((entry): entry is string => typeof entry === "string" && entry.trim().length > 0).map((entry) => entry.trim()).slice(0, limit) : [];
  const queries = strings(raw?.queries, 5);
  if (!queries.length) return null;
  return {
    canonical: typeof raw?.canonical === "string" && raw.canonical.trim() ? raw.canonical.trim() : fallback.canonical,
    attributes: strings(raw?.attributes, 6),
    description: typeof raw?.description === "string" && raw.description.trim() ? raw.description.trim().slice(0, 300) : null,
    // The AI's queries first, then the literal title as a safety net for stores that sell the exact product.
    queries: dedupeQueries([...queries, ...fallback.queries]).slice(0, 6),
  };
}

async function understand(product: ProductRow): Promise<Understanding & { vision: boolean }> {
  const fallback: Understanding = {
    canonical: genericTitle(heuristicQueries(product)[1] ?? product.title, product.store),
    attributes: [],
    description: null,
    queries: heuristicQueries(product),
  };
  if (!aiConfigured() && !visionConfigured()) return { ...fallback, vision: false };
  const useVision = visionConfigured() && Boolean(product.imageUrl);
  const key = await sha256(`${SIMILAR_PROMPT_VERSION}|${useVision ? "vision" : "text"}|${product.id}|${normalizeText(product.title)}|${product.quantityText}`);
  type Raw = { canonical?: unknown; attributes?: unknown; description?: unknown; queries?: unknown };
  if (useVision) {
    const raw = await cachedChatJson<Raw>(
      "similar-queries",
      key,
      [
        { role: "system", content: QUERY_SYSTEM },
        { role: "user", content: [{ type: "text", text: describe(product) }, { type: "image_url", image_url: { url: product.imageUrl! } }] },
      ],
      { maxTokens: 400, profile: "vision" },
    );
    const parsed = parseUnderstanding(raw, fallback);
    if (parsed) return { ...parsed, vision: true };
  }
  if (!aiConfigured()) return { ...fallback, vision: false };
  const raw = await cachedChatJson<Raw>(
    "similar-queries",
    await sha256(`${SIMILAR_PROMPT_VERSION}|text|${product.id}|${normalizeText(product.title)}|${product.quantityText}`),
    [
      { role: "system", content: QUERY_SYSTEM },
      { role: "user", content: describe(product) },
    ],
    { maxTokens: 300 },
  );
  return { ...(parseUnderstanding(raw, fallback) ?? fallback), vision: false };
}

// ---- ranking -------------------------------------------------------------------------------------

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

type Verdict = { product: ProductRow; kind: SimilarKind; note: string | null; score: number };

function parseRanking(raw: { ranking?: Array<{ idx?: unknown; kind?: unknown; score?: unknown; note?: unknown }> } | null, reference: ProductRow, candidates: ProductRow[]): Verdict[] | null {
  if (!Array.isArray(raw?.ranking)) return null;
  const seen = new Set<string>();
  const verdicts: Verdict[] = [];
  for (const entry of raw!.ranking) {
    const product = typeof entry.idx === "number" ? candidates[entry.idx] : undefined;
    if (!product || seen.has(product.id)) continue;
    seen.add(product.id);
    const kind = KINDS.includes(entry.kind as SimilarKind) ? (entry.kind as SimilarKind) : heuristicKind(reference, product);
    const note = typeof entry.note === "string" && entry.note.trim() ? entry.note.trim().slice(0, 80) : null;
    const score = typeof entry.score === "number" && Number.isFinite(entry.score) ? Math.min(100, Math.max(0, entry.score)) : 100 - KIND_ORDER[kind] * 30;
    verdicts.push({ product, kind, note: kind === "SAME" ? null : note, score });
  }
  return verdicts;
}

function rankUser(reference: ProductRow, description: string | null, store: StoreCode, candidates: ProductRow[], feedback: string, withImages: boolean): ChatMessage {
  const header = [
    `Reference (${reference.store}): ${describe(reference)}`,
    description ? `Reference as seen on its picture: ${description}` : "",
    feedback,
    `Candidates at ${store}:`,
    ...candidates.map((product, index) => `${index}. ${describe(product)}${withImages && !product.imageUrl ? " (no picture)" : ""}`),
  ]
    .filter(Boolean)
    .join("\n");
  if (!withImages) return { role: "user", content: header };
  const parts: ChatPart[] = [{ type: "text", text: header }];
  if (reference.imageUrl) parts.push({ type: "text", text: "Picture of the reference:" }, { type: "image_url", image_url: { url: reference.imageUrl } });
  candidates.forEach((product, index) => {
    if (!product.imageUrl) return;
    parts.push({ type: "text", text: `Picture of candidate ${index}:` }, { type: "image_url", image_url: { url: product.imageUrl } });
  });
  return { role: "user", content: parts };
}

/** Text ranking of the whole shortlist in one call. */
async function textRank(reference: ProductRow, description: string | null, store: StoreCode, candidates: ProductRow[], feedback: string): Promise<Verdict[] | null> {
  if (!aiConfigured() || !candidates.length) return null;
  const key = await sha256([SIMILAR_PROMPT_VERSION, "text", appLanguageName(), reference.id, store, feedback, candidates.map((product) => `${product.id}@${product.priceCents}`).join(",")].join("|"));
  const raw = await cachedChatJson<{ ranking?: Array<{ idx?: unknown; kind?: unknown; score?: unknown; note?: unknown }> }>(
    "similar-rank",
    key,
    [
      { role: "system", content: RANK_SYSTEM.replaceAll("LANGUAGE", appLanguageName()) },
      rankUser(reference, description, store, candidates, feedback, false),
    ],
    { maxTokens: 900 },
  );
  return parseRanking(raw, reference, candidates);
}

/** Vision ranking: the reference picture plus a few candidate pictures per call, merged by kind and score. */
async function visionRank(reference: ProductRow, description: string | null, store: StoreCode, candidates: ProductRow[], feedback: string): Promise<Verdict[] | null> {
  if (!visionConfigured() || !candidates.length) return null;
  const perCall = Math.max(1, IMAGES_PER_CALL - (reference.imageUrl ? 1 : 0));
  const chunks: ProductRow[][] = [];
  for (let start = 0; start < candidates.length; start += perCall) chunks.push(candidates.slice(start, start + perCall));
  const verdicts: Verdict[] = [];
  for (const chunk of chunks) {
    const key = await sha256([SIMILAR_PROMPT_VERSION, "vision", appLanguageName(), reference.id, store, feedback, chunk.map((product) => `${product.id}@${product.priceCents}`).join(",")].join("|"));
    const raw = await cachedChatJson<{ ranking?: Array<{ idx?: unknown; kind?: unknown; score?: unknown; note?: unknown }> }>(
      "similar-rank",
      key,
      [
        { role: "system", content: RANK_SYSTEM.replaceAll("LANGUAGE", appLanguageName()) },
        rankUser(reference, description, store, chunk, feedback, true),
      ],
      { maxTokens: 600, profile: "vision" },
    );
    const parsed = parseRanking(raw, reference, chunk);
    // One failed group means the pictures cannot be trusted for this store: the caller falls back to text.
    if (!parsed) return null;
    verdicts.push(...parsed);
  }
  return verdicts;
}

function orderVerdicts(verdicts: Verdict[], feedback: Map<string, boolean>): SimilarMatch[] {
  return verdicts
    .map((verdict, index) => ({ verdict, index }))
    .sort((a, b) => {
      // Confirmed pairings first, then by label, then by the model's score; ties keep the lexical order.
      const upA = feedback.get(a.verdict.product.id) ? 0 : 1;
      const upB = feedback.get(b.verdict.product.id) ? 0 : 1;
      return upA - upB || KIND_ORDER[a.verdict.kind] - KIND_ORDER[b.verdict.kind] || b.verdict.score - a.verdict.score || a.index - b.index;
    })
    .map(({ verdict }) => ({
      product: verdict.product,
      kind: verdict.kind,
      note: verdict.note,
      feedback: feedback.has(verdict.product.id) ? (feedback.get(verdict.product.id) ? "UP" : "DOWN") : null,
    }));
}

/**
 * "Find something like this elsewhere": searches every given store for the product (or its closest
 * equivalent) and returns the best matches per store, labelled SAME / EQUIVALENT / SUBSTITUTE. The
 * product's own store is searched too (for alternatives), but the product itself is never returned.
 * With a vision provider the packaging pictures are compared; the user's thumbs are honoured and
 * fed back into the ranking.
 */
export async function findSimilar(product: ProductRow, stores: StoreCode[], options: { limit?: number } = {}): Promise<SimilarStoreResult[]> {
  const limit = Math.max(1, Math.min(options.limit ?? 8, SIMILAR_MAX));
  const understanding = await understand(product);
  const feedback = similarFeedbackFor(product.id);
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
            // A rejected pairing stays out; its slot goes to the next candidate.
            if (feedback.get(candidate.id) === false) continue;
            fresh.push(candidate);
          }
        } catch (error) {
          lastError = error instanceof Error ? error.message : String(error);
          console.warn(`[similar] ${store} "${query}" failed: ${lastError}`);
        }
        // Enough material once the top of the list is stable; the rest of the queries only add noise.
        if (fresh.length >= SIMILAR_MAX * 2) break;
      }
      if (!fresh.length) return { store, matches: [], queries: tried, vision: false, error: lastError };

      const shortlist = lexicalRank(text, parsed, fresh, product)
        .slice(0, SIMILAR_MAX)
        .map((entry) => entry.product);
      const context = feedbackContext(feedback);
      let verdicts = await visionRank(product, understanding.description, store, shortlist, context);
      const vision = verdicts !== null;
      verdicts ??= await textRank(product, understanding.description, store, shortlist, context);
      verdicts ??= shortlist.map((candidate) => ({ product: candidate, kind: heuristicKind(product, candidate), note: null, score: 0 }));
      return { store, matches: orderVerdicts(verdicts, feedback).slice(0, limit), queries: tried, vision, error: null };
    }),
  );
}

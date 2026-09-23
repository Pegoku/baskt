import { and, asc, eq, inArray } from "drizzle-orm";
import { db } from "@/db";
import { basketItems, basketMatches, type ProductRow } from "@/db/schema";
import { enabledStoreCodes } from "@/db/settings";
import { lexicalScore } from "@/matching/rank";
import { productsByIds, searchStore } from "@/stores/search";
import { cachedChatJson } from "@/ai/client";
import { aiConfigured } from "@/env";
import { normalizeText, sha256 } from "@/lib/text";

/** "leche" → ["melk", "zuivel", ...]: Dutch keywords the store promotions can be matched against. */
export async function expandDealQuery(query: string): Promise<string[]> {
  const base = query.trim();
  if (!base) return [];
  if (!aiConfigured()) return [base];
  const key = await sha256(`deal-terms|v1|${normalizeText(base)}`);
  const raw = await cachedChatJson<{ terms?: unknown }>(
    "deal-terms",
    key,
    [
      { role: "system", content: 'The user searches supermarket promotions in the Netherlands, in any language. Return ONLY {"terms": [3-6 short Dutch product words that mean what they typed, most specific first, e.g. for "leche": ["melk", "halfvolle melk", "zuivel"]]}.' },
      { role: "user", content: base },
    ],
    { maxTokens: 150 },
  );
  const terms = Array.isArray(raw?.terms) ? raw!.terms.filter((term): term is string => typeof term === "string" && term.trim().length > 0).map((term) => term.trim()) : [];
  return Array.from(new Set([base, ...terms]));
}

export const DEAL_JUDGE_VERSION = "v1";

const JUDGE_RULES =
  "You judge supermarket promotions for a shopping list. A deal suits an item when it is the same kind of product the shopper meant, " +
  "or a close alternative of the same type (another brand or flavour of the same product). It does not suit when it is a different product " +
  "that merely shares a word (e.g. bread rolls for a snack called 'bollen', canned carrots for fresh carrot snacks). " +
  'Return ONLY {"suitable": [numbers of the deals that suit the item]} (an empty array when none do).';

/** The deal numbers the judge accepted, 1-based as they were listed; null when the reply is not a verdict. */
export function judgedSuitable(raw: unknown, count: number): Set<number> | null {
  const list = (raw as { suitable?: unknown } | null)?.suitable;
  if (!Array.isArray(list)) return null;
  const picked = new Set<number>();
  for (const value of list) {
    const index = Number(String(value).trim().split(".").pop());
    if (Number.isInteger(index) && index >= 1 && index <= count) picked.add(index);
  }
  return picked;
}

/**
 * One call per item asking the model which of the word-matched promotions really are that product. Measured
 * (scripts/deal-judge-bench.ts, 2026-09-23): one call per item costs about 500 tokens and 0.2 s and judges
 * 29/32 pairs right on gpt-oss-20b; one call per pair is 3.5× the tokens for no gain, and one call for the
 * whole basket is cheaper still but confuses brands. Failures fall back to the lexical verdict.
 */
async function judgeDeals(item: { text: string; canonical: string; current: string | null }, deals: ProductRow[]): Promise<Set<string> | null> {
  if (!deals.length || !aiConfigured()) return null;
  const describe = (product: ProductRow) => `${product.title} (${product.quantityText ?? "?"}, ${product.dealText ?? "deal"})`;
  const itemLine = `${item.text}${item.canonical !== item.text ? ` (${item.canonical})` : ""}${item.current ? `; currently buying: ${item.current}` : ""}`;
  const key = await sha256(`deal-judge|${DEAL_JUDGE_VERSION}|${normalizeText(itemLine)}|${deals.map((deal) => deal.id).sort().join(",")}`);
  const raw = await cachedChatJson<{ suitable?: unknown }>(
    "deal-judge",
    key,
    [
      { role: "system", content: JUDGE_RULES },
      { role: "user", content: `Item: ${itemLine}\nDeals:\n${deals.map((deal, i) => `${i + 1}. ${describe(deal)}`).join("\n")}` },
    ],
    { maxTokens: 300 },
  );
  const picked = judgedSuitable(raw, deals.length);
  if (!picked) return null;
  return new Set(deals.filter((_, i) => picked.has(i + 1)).map((deal) => deal.id));
}

export type Deal = {
  itemId: string;
  itemText: string;
  store: string;
  product: ProductRow;
  /** The product currently picked/suggested for this item at this store, if any. */
  currentProductId: string | null;
  currentPriceCents: number | null;
  savingCents: number | null;
  equivalence: string;
  /** How much the promotion looks like the entry itself (word overlap plus the matcher's verdict); higher first. */
  relevance: number;
};

/**
 * Finds promotions for the open items of a basket: candidates already known for the item plus a fresh
 * store search, keeping products that are on a deal and look like the same kind of product.
 */
export async function findDeals(basketId: string, options: { live?: boolean } = {}): Promise<Deal[]> {
  const stores = enabledStoreCodes();
  const items = db()
    .select()
    .from(basketItems)
    .where(and(eq(basketItems.basketId, basketId), eq(basketItems.kind, "item"), eq(basketItems.checked, false)))
    .orderBy(asc(basketItems.sortOrder))
    .all();
  if (!items.length) return [];
  const matches = db().select().from(basketMatches).where(inArray(basketMatches.itemId, items.map((item) => item.id))).all();
  const deals: Deal[] = [];
  const judgements: Array<Promise<void>> = [];
  for (const item of items) {
    const parsed = item.parsedJson ?? { canonicalName: item.text, attributes: [], sizeHint: null, queries: {}, fallbackQuery: null, ambiguous: false };
    const own: Deal[] = [];
    for (const store of stores) {
      const match = matches.find((entry) => entry.itemId === item.id && entry.store === store);
      if (match?.status === "NONE") continue;
      const currentId = match?.chosenProductId ?? match?.candidateIds[0] ?? null;
      const known = productsByIds(match?.candidateIds ?? []);
      let pool = known;
      if (options.live) {
        try {
          const fresh = await searchStore(store, parsed.queries[store] ?? item.text, { limit: 20 });
          const seen = new Set(pool.map((product) => product.id));
          pool = [...pool, ...fresh.products.filter((product) => !seen.has(product.id))];
        } catch {
          // keep what we have
        }
      }
      const current = currentId ? productsByIds([currentId])[0] : undefined;
      const seenIds = new Set<string>();
      for (const product of pool) {
        if (!product.isDeal || seenIds.has(product.id) || !product.available) continue;
        // Must still be the same kind of product as the idea.
        const lexical = lexicalScore(item.text, parsed, product);
        if (lexical < 2) continue;
        seenIds.add(product.id);
        const equivalence = match?.equivalences[product.id] ?? "EQUIVALENT";
        const currentLine = current ? current.priceCents * item.quantity : null;
        const dealLine = product.priceCents * item.quantity;
        own.push({
          itemId: item.id,
          itemText: item.text,
          store,
          product,
          currentProductId: current?.id ?? null,
          currentPriceCents: current?.priceCents ?? null,
          savingCents: currentLine !== null ? currentLine - dealLine : null,
          equivalence,
          relevance: lexical + (equivalence === "EXACT" ? 2 : equivalence === "EQUIVALENT" ? 1 : 0),
        });
      }
    }
    if (!own.length) continue;
    // Word overlap lets "bollen" bread rolls through for a wasabi snack; the model weeds those out per item.
    const currentId = own.find((deal) => deal.currentProductId)?.currentProductId ?? null;
    const currentTitle = currentId ? productsByIds([currentId])[0]?.title ?? null : null;
    judgements.push(
      judgeDeals({ text: item.text, canonical: parsed.canonicalName, current: currentTitle }, own.map((deal) => deal.product)).then((suitable) => {
        deals.push(...(suitable ? own.filter((deal) => suitable.has(deal.product.id)) : own));
      }),
    );
  }
  await Promise.all(judgements);
  // Best savings first; unknown savings (no current pick) after.
  return deals.sort((a, b) => (b.savingCents ?? -1) - (a.savingCents ?? -1));
}

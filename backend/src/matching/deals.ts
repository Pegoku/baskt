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

  for (const item of items) {
    const parsed = item.parsedJson ?? { canonicalName: item.text, attributes: [], sizeHint: null, queries: {}, fallbackQuery: null, ambiguous: false };
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
        deals.push({
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
  }
  // Best savings first; unknown savings (no current pick) after.
  return deals.sort((a, b) => (b.savingCents ?? -1) - (a.savingCents ?? -1));
}

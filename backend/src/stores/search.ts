import { and, eq, inArray } from "drizzle-orm";
import { db, now } from "@/db";
import { priceHistory, products, searchCache, type ProductRow } from "@/db/schema";
import { env } from "@/env";
import { normalizeText } from "@/lib/text";
import { getAdapter } from "@/stores/registry";
import { productKey, type StoreCode, type StoreProduct } from "@/stores/types";

const inFlight = new Map<string, Promise<ProductRow[]>>();

export type SearchResult = { products: ProductRow[]; source: "cache" | "live" | "stale" };

export function toRow(product: StoreProduct, fetchedAt = now()): ProductRow {
  return { id: productKey(product.store, product.sourceId), fetchedAt, ...product };
}

/** Upserts products and appends a price-history point whenever the price changed. */
export function upsertProducts(list: StoreProduct[]): ProductRow[] {
  if (!list.length) return [];
  const database = db();
  const rows = list.map((product) => toRow(product));
  const existing = new Map(
    database
      .select({ id: products.id, priceCents: products.priceCents })
      .from(products)
      .where(inArray(products.id, rows.map((row) => row.id)))
      .all()
      .map((row) => [row.id, row.priceCents] as const),
  );
  database.transaction((tx) => {
    for (const row of rows) {
      tx.insert(products).values(row).onConflictDoUpdate({ target: products.id, set: { ...row, id: undefined } }).run();
      const previous = existing.get(row.id);
      if (previous === undefined || previous !== row.priceCents) {
        tx.insert(priceHistory).values({ productId: row.id, priceCents: row.priceCents, isDeal: row.isDeal, capturedAt: row.fetchedAt }).run();
      }
    }
  });
  return rows;
}

export function productsByIds(ids: string[]): ProductRow[] {
  if (!ids.length) return [];
  const rows = db().select().from(products).where(inArray(products.id, ids)).all();
  const byId = new Map(rows.map((row) => [row.id, row]));
  return ids.map((id) => byId.get(id)).filter((row): row is ProductRow => Boolean(row));
}

/** Searches a store through the cache. `force` bypasses a fresh cache entry. */
export async function searchStore(store: StoreCode, query: string, options: { force?: boolean; limit?: number } = {}): Promise<SearchResult> {
  const normalizedQuery = normalizeText(query);
  if (!normalizedQuery) return { products: [], source: "cache" };
  const database = db();
  const cached = database
    .select()
    .from(searchCache)
    .where(and(eq(searchCache.store, store), eq(searchCache.normalizedQuery, normalizedQuery)))
    .get();

  if (cached && !options.force && cached.expiresAt > now()) {
    return { products: productsByIds(cached.productIds), source: "cache" };
  }

  const key = `${store}:${normalizedQuery}`;
  let pending = inFlight.get(key);
  if (!pending) {
    pending = (async () => {
      const adapter = getAdapter(store);
      const results = await adapter.search(query, options.limit ?? 20);
      const rows = upsertProducts(results);
      const fetchedAt = now();
      database
        .insert(searchCache)
        .values({ store, normalizedQuery, productIds: rows.map((row) => row.id), fetchedAt, expiresAt: fetchedAt + env.searchCacheTtlMs })
        .onConflictDoUpdate({
          target: [searchCache.store, searchCache.normalizedQuery],
          set: { productIds: rows.map((row) => row.id), fetchedAt, expiresAt: fetchedAt + env.searchCacheTtlMs },
        })
        .run();
      return rows;
    })().finally(() => inFlight.delete(key));
    inFlight.set(key, pending);
  }

  try {
    return { products: await pending, source: "live" };
  } catch (error) {
    if (cached) {
      console.warn(`[search] ${store} "${query}" failed, serving stale cache: ${error instanceof Error ? error.message : error}`);
      return { products: productsByIds(cached.productIds), source: "stale" };
    }
    throw error;
  }
}

/** Re-fetches the cached queries that reference the given product ids (used by the refresh job). */
export async function refreshQueriesFor(productIds: Set<string>) {
  const entries = db().select().from(searchCache).all().filter((entry) => entry.productIds.some((id) => productIds.has(id)));
  let refreshed = 0;
  for (const entry of entries) {
    try {
      await searchStore(entry.store, entry.normalizedQuery, { force: true });
      refreshed += 1;
    } catch (error) {
      console.warn(`[refresh] ${entry.store} "${entry.normalizedQuery}" failed: ${error instanceof Error ? error.message : error}`);
    }
  }
  return { queries: entries.length, refreshed };
}

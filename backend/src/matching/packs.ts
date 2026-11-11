import { db } from "@/db";
import { products, type ProductRow } from "@/db/schema";
import { dealKind, packOf, sameItem, titleTokens } from "@/stores/promotions";
import { productsByIds, searchStore } from "@/stores/search";
import { like, or } from "drizzle-orm";

/**
 * A multi-pack of another product ("Knorr Wortel wraps 2-pack", "AH Gelderse gekookte worst 4 pck"): AH lists
 * them as products of their own with a "volume voordeel". A pack that is a product in its own right ("AH Doperwt
 * wortel 3-pack" of 600 g) is not one: its size is a weight, not the number of packs.
 */
export function multipackOf(product: Pick<ProductRow, "title" | "dealText" | "unit" | "unitAmount" | "quantityText">) {
  const pack = packOf(product.title);
  if (!pack) return null;
  if (dealKind(product.dealText) === "volume") return pack;
  if (product.unit === "piece" && product.unitAmount === pack.count) return pack;
  if (new RegExp(`^${pack.count}\\s*x\\b`, "i").test(product.quantityText)) return pack;
  return null;
}

/** The single item a multi-pack is made of, found with a search for its name; null when the store has none. */
async function findSingle(store: string, base: string, tokens: string[]) {
  try {
    const result = await searchStore(store, base, { limit: 10 });
    return result.products.find((product) => !multipackOf(product) && sameItem(titleTokens(product.title), tokens)) ?? null;
  } catch (error) {
    console.warn(`[packs] single for "${base}" at ${store} failed: ${error instanceof Error ? error.message : error}`);
    return null;
  }
}

/**
 * Search results with multi-packs folded into the item they are made of, so the options are single items and
 * the packs show as that item's volume deals. A pack whose single item is not in the list is replaced by it
 * (looked up by name, at most [lookups] searches); when the store has no single, the smallest pack stands in.
 */
export async function foldMultipacks(store: string, list: ProductRow[], options: { lookups?: number; known?: ProductRow[] } = {}): Promise<ProductRow[]> {
  const singleTokens = [...list, ...(options.known ?? [])].filter((product) => !multipackOf(product)).map((product) => titleTokens(product.title));
  const orphans = new Map<string, { base: string; tokens: string[]; smallest: ProductRow; count: number }>();
  const orphanOf = new Map<string, string>();
  for (const product of list) {
    const pack = multipackOf(product);
    if (!pack) continue;
    const tokens = titleTokens(pack.base);
    if (singleTokens.some((single) => sameItem(single, tokens))) continue;
    const key = [...orphans.entries()].find(([, group]) => sameItem(group.tokens, tokens))?.[0] ?? tokens.join(" ");
    const group = orphans.get(key);
    if (!group) orphans.set(key, { base: pack.base, tokens, smallest: product, count: pack.count });
    else if (pack.count < group.count) Object.assign(group, { smallest: product, count: pack.count });
    orphanOf.set(product.id, key);
  }
  let budget = options.lookups ?? 3;
  const stand = new Map<string, ProductRow>();
  await Promise.all(
    [...orphans.entries()].map(async ([key, group]) => {
      const single = budget-- > 0 ? await findSingle(store, group.base, group.tokens) : null;
      stand.set(key, single ?? group.smallest);
    }),
  );
  const seen = new Set<string>();
  const out: ProductRow[] = [];
  for (const product of list) {
    const key = orphanOf.get(product.id);
    // A pack whose single is listed is dropped; the first pack of an orphan group takes the group's place.
    const shown = multipackOf(product) ? (key && !seen.has(`group:${key}`) ? stand.get(key) : undefined) : product;
    if (key) seen.add(`group:${key}`);
    if (!shown || seen.has(shown.id)) continue;
    seen.add(shown.id);
    out.push(shown);
  }
  return out;
}

type PackEntry = { id: string; store: string; tokens: string[] };
let packIndex: { at: number; entries: PackEntry[] } | null = null;
const PACK_INDEX_TTL_MS = 30_000;

/** Every known multi-pack, by the words of the item it is made of (rebuilt every half minute; a few thousand rows). */
function knownPacks() {
  if (packIndex && Date.now() - packIndex.at < PACK_INDEX_TTL_MS) return packIndex.entries;
  const rows = db()
    .select({ id: products.id, store: products.store, title: products.title, dealText: products.dealText, unit: products.unit, unitAmount: products.unitAmount, quantityText: products.quantityText })
    .from(products)
    .where(or(like(products.title, "%pack"), like(products.title, "%pck"), like(products.title, "%pk")))
    .all();
  const entries: PackEntry[] = [];
  for (const row of rows) {
    const pack = multipackOf(row);
    if (pack) entries.push({ id: row.id, store: row.store, tokens: titleTokens(pack.base) });
  }
  packIndex = { at: Date.now(), entries };
  return entries;
}

/** The multi-packs of each given single product, smallest pack first, for the "volume deals" of an option. */
export function packsFor(list: ProductRow[]): Record<string, ProductRow[]> {
  const singles = list.filter((product) => !multipackOf(product));
  if (!singles.length) return {};
  const index = knownPacks();
  const found = new Map<string, string[]>();
  for (const single of singles) {
    const tokens = titleTokens(single.title);
    const ids = index.filter((entry) => entry.store === single.store && sameItem(entry.tokens, tokens)).map((entry) => entry.id);
    if (ids.length) found.set(single.id, ids);
  }
  if (!found.size) return {};
  const rows = new Map(productsByIds([...new Set([...found.values()].flat())]).map((row) => [row.id, row]));
  const count = (row: ProductRow) => multipackOf(row)?.count ?? 0;
  return Object.fromEntries(
    [...found.entries()]
      .map(([id, ids]) => [id, ids.map((packId) => rows.get(packId)).filter((row): row is ProductRow => Boolean(row && row.available)).sort((a, b) => count(a) - count(b))] as const)
      .filter(([, packs]) => packs.length),
  );
}

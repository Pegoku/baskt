import { and, eq } from "drizzle-orm";
import { db, now } from "@/db";
import { barcodeLinks, type ParsedIdea, type ProductRow } from "@/db/schema";
import { cachedUpstream } from "@/lib/cache";
import { lexicalScore } from "@/matching/rank";
import { getAdapter } from "@/stores/registry";
import { productsByIds, searchStore } from "@/stores/search";
import type { StoreCode } from "@/stores/types";

/** What Open Food Facts says is behind a barcode that no store recognises. */
export type BarcodeLabel = { name: string; brand: string | null; quantityText: string | null; imageUrl: string | null };

/** Leading zeros differ between scanners and stores (EAN-13 vs GTIN-14), so links are keyed without them. */
export const linkKey = (gtin: string) => gtin.replace(/\D/g, "").replace(/^0+/, "");

let labelSource: ((gtin: string) => Promise<BarcodeLabel | null>) | null = null;
/** Tests answer from a fixture instead of calling Open Food Facts. */
export function setLabelSourceForTests(source: typeof labelSource) { labelSource = source; }

export async function openFoodFactsLabel(gtin: string): Promise<BarcodeLabel | null> {
  if (labelSource) return labelSource(linkKey(gtin));
  return cachedUpstream(`off-label:v1:${linkKey(gtin)}`, 7 * 86400000, async () => {
    const response = await fetch(
      `https://world.openfoodfacts.org/api/v2/product/${linkKey(gtin)}.json?fields=product_name,product_name_nl,product_name_en,brands,quantity,image_front_small_url,image_small_url`,
      // Open Food Facts asks every client to identify itself, and blocks anonymous ones.
      { headers: { "User-Agent": "baskt/0.1 (server; https://github.com/pegoku/baskt)" }, signal: AbortSignal.timeout(8000) },
    );
    if (response.status === 404) return null;
    if (!response.ok) throw new Error(`Open Food Facts ${response.status}`);
    return parseOpenFoodFacts(await response.json());
  }, { staleMs: 30 * 86400000 });
}

export function parseOpenFoodFacts(body: unknown): BarcodeLabel | null {
  const product = (body as { product?: Record<string, unknown> } | null)?.product;
  if (!product) return null;
  const field = (key: string) => (typeof product[key] === "string" && (product[key] as string).trim()) || null;
  const name = field("product_name_nl") ?? field("product_name") ?? field("product_name_en");
  if (!name) return null;
  const brand = field("brands")?.split(",")[0]?.trim() || null;
  return { name, brand, quantityText: field("quantity"), imageUrl: field("image_front_small_url") ?? field("image_small_url") };
}

/** "1000 gram" → 1 kg, "500 ml" → 0.5 l; multipacks and counts give no hint. */
export function sizeHint(quantityText: string | null): ParsedIdea["sizeHint"] {
  if (!quantityText || /\d\s*[x×]\s*\d/i.test(quantityText)) return null;
  const match = quantityText.match(/(\d+(?:[.,]\d+)?)\s*(kg|kilo|g|gr|gram|grammes?|l|ltr|liter|litre|ml|cl)\b/i);
  if (!match) return null;
  const amount = Number(match[1].replace(",", "."));
  const unit = match[2].toLowerCase();
  if (!(amount > 0)) return null;
  if (unit === "kg" || unit === "kilo") return { amount, unit: "kg" };
  if (unit.startsWith("g")) return { amount: amount / 1000, unit: "kg" };
  if (unit === "ml") return { amount: amount / 1000, unit: "l" };
  if (unit === "cl") return { amount: amount / 100, unit: "l" };
  return { amount, unit: "l" };
}

/** Each store brands its own products differently than Open Food Facts does ("Albert Heijn" vs "AH"). */
function storeBrand(brand: string | null, store: StoreCode): "this" | "other" | null {
  if (!brand) return null;
  const normal = brand.toLowerCase().replace(/[^a-z0-9]/g, "");
  const owners: Record<string, string[]> = { AH: ["ah", "albertheijn", "ahexcellent", "ahbiologisch", "ahterra"], JUMBO: ["jumbo"] };
  for (const [code, names] of Object.entries(owners)) if (names.includes(normal)) return code === store ? "this" : "other";
  return null;
}

/** The store search for a label: the store's own brand prefix, a third-party brand, or the bare name for a rival's own brand. */
export function labelQuery(label: BarcodeLabel, store: StoreCode): string {
  const owner = storeBrand(label.brand, store);
  if (owner === "this") return `${store === "AH" ? "AH" : getAdapter(store).info.name} ${label.name}`;
  if (owner === "other" || !label.brand || label.name.toLowerCase().includes(label.brand.toLowerCase())) return label.name;
  return `${label.brand} ${label.name}`;
}

/** Packs within a few percent of each other count as the same size; kilos and litres are treated alike (oil, yoghurt). */
function sizeScore(hint: ParsedIdea["sizeHint"], product: ProductRow) {
  if (!hint || !product.unitAmount || !product.unit || product.unit === "piece") return 0;
  return Math.max(0, 1 - Math.abs(Math.log(product.unitAmount / hint.amount)));
}

/** Ranks one store's search results against the label; the store's own order breaks ties. */
export function rankForLabel(label: BarcodeLabel, store: StoreCode, candidates: ProductRow[]) {
  const hint = sizeHint(label.quantityText);
  const parsed: ParsedIdea = { canonicalName: label.name.toLowerCase(), attributes: [], sizeHint: null, queries: {}, fallbackQuery: null, ambiguous: false };
  const own = storeBrand(label.brand, store) === "this";
  return candidates
    .map((product, index) => {
      let score = lexicalScore(label.name, parsed, product) + 1.5 * sizeScore(hint, product) + ((candidates.length - index) / candidates.length) * 0.3;
      // An own-brand bottle is most likely still the own-brand product, under a new code.
      const productOwn = /^(ah|jumbo)\b/i.test(product.title) || storeBrand(product.brand, store) === "this";
      if (own && productOwn) score += 1;
      return { product, score };
    })
    .sort((a, b) => b.score - a.score);
}

/** The closest products to a label, best first: each store's top pick, then runners-up. */
export async function suggestForLabel(stores: StoreCode[], label: BarcodeLabel, limit = 4): Promise<ProductRow[]> {
  const perStore = await Promise.all(
    stores.map(async (store) => {
      try {
        const { products } = await searchStore(store, labelQuery(label, store), { limit: 20 });
        return rankForLabel(label, store, products.filter((product) => product.available)).slice(0, limit);
      } catch {
        return [];
      }
    }),
  );
  const firsts = perStore.flatMap((ranked) => ranked.slice(0, 1));
  const rest = perStore.flatMap((ranked) => ranked.slice(1)).sort((a, b) => b.score - a.score);
  return [...firsts.sort((a, b) => b.score - a.score), ...rest].slice(0, limit).map((entry) => entry.product);
}

export function linkedProduct(gtin: string, store: StoreCode): ProductRow | null {
  const link = db().select().from(barcodeLinks).where(and(eq(barcodeLinks.gtin, linkKey(gtin)), eq(barcodeLinks.store, store))).get();
  return link ? productsByIds([link.productId])[0] ?? null : null;
}

/** Remembers that this code is this product, so the next scan resolves straight away. */
export function linkBarcode(gtin: string, product: ProductRow) {
  const row = { gtin: linkKey(gtin), store: product.store, productId: product.id, createdAt: now() };
  db().insert(barcodeLinks).values(row).onConflictDoUpdate({ target: [barcodeLinks.gtin, barcodeLinks.store], set: row }).run();
}

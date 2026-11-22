import { eurosToCents } from "@/lib/units";
import { mapAhProduct, type AhProduct } from "@/stores/ah";
import { graphql, isConnected } from "@/stores/ah-account";
import { productsByIds, upsertProducts } from "@/stores/search";
import { productKey } from "@/stores/types";

/**
 * AH's "Laatste Kans Koopjes": in-store markdowns of products close to their date or leaving the range, per
 * store. The app's Flutter bargain corner reads them through bargainItemsV2, which needs a member token, so
 * this uses the logged-in account and its favourite store.
 */

const STORE_QUERY = `query FavoriteStoreQuery { storesFavouriteStore { id address { street houseNumber houseNumberExtra city } } }`;

const ITEMS_QUERY = `query BargainItems($storeId: String!) {
  bargainItemsV2(storeId: $storeId) {
    items {
      categoryTitle
      bestBeforeDateLabel
      markdown { markdownType }
      bargainPrice { priceNow priceWas }
      dynamicMarkDownPromotionLabel { title topText centerText bottomText }
      product { id title brand salesUnitSize category imagePack(angles: [HERO]) { medium { url } } }
    }
  }
}`;

type GqlStore = { id?: number | string | null; address?: { street?: string | null; houseNumber?: string | null; houseNumberExtra?: string | null; city?: string | null } | null };

type GqlBargain = {
  categoryTitle?: string | null;
  bestBeforeDateLabel?: string | null;
  markdown?: { markdownType?: string | null } | null;
  bargainPrice?: { priceNow?: string | number | null; priceWas?: string | number | null } | null;
  dynamicMarkDownPromotionLabel?: { title?: string | null; topText?: string | null; centerText?: string | null; bottomText?: string | null } | null;
  product?: {
    id?: number | null;
    title?: string | null;
    brand?: string | null;
    salesUnitSize?: string | null;
    category?: string | null;
    imagePack?: Array<{ medium?: { url?: string | null } | null }> | null;
  } | null;
};

export type LastChanceItem = {
  productId: string;
  title: string;
  brand: string | null;
  quantityText: string | null;
  imageUrl: string | null;
  category: string | null;
  /** EXPIRATION (close to its date) or OUT_OF_ASSORTMENT (leaving the range). */
  reason: "EXPIRATION" | "OUT_OF_ASSORTMENT" | "OTHER";
  /** The best-before date, or the day it leaves the range, as YYYY-MM-DD. */
  date: string | null;
  discountPercent: number | null;
  /** AH's label, e.g. "70% korting" or "40% extra korting". */
  discountText: string | null;
  /** How many marked-down packs the store still had ("nog 3 met"), null when AH does not say. */
  left: number | null;
  priceCents: number | null;
  regularPriceCents: number | null;
};

export type LastChanceView = {
  connected: boolean;
  store: { id: string; name: string } | null;
  items: LastChanceItem[];
  fetchedAt: number;
};

const TTL_MS = 5 * 60 * 1000;
const STORE_TTL_MS = 6 * 60 * 60 * 1000;
let cache: LastChanceView | null = null;
let storeCache: { at: number; store: { id: string; name: string } | null } | null = null;
let inFlight: Promise<LastChanceView> | null = null;

/** "Houdbaarheid: 22/11/2026" or "Uit assortiment: 22/11/2026" as "2026-11-22". */
export function labelDate(label: string | null | undefined): string | null {
  const match = label?.match(/(\d{1,2})[/-](\d{1,2})[/-](\d{4})/);
  if (!match) return null;
  return `${match[3]}-${match[2]!.padStart(2, "0")}-${match[1]!.padStart(2, "0")}`;
}

export function mapBargain(entry: GqlBargain): LastChanceItem | null {
  const product = entry.product;
  if (!product?.id || !product.title) return null;
  const label = entry.dynamicMarkDownPromotionLabel;
  const texts = [label?.topText, label?.centerText, label?.bottomText].map((text) => text?.trim()).filter((text): text is string => Boolean(text));
  const discountText = texts.find((text) => /korting|%/i.test(text)) ?? null;
  const percent = discountText?.match(/(\d{1,3})\s*%/);
  const left = label?.title?.match(/nog\s+(\d+)/i);
  const type = entry.markdown?.markdownType;
  const quantity = product.salesUnitSize?.trim() || null;
  return {
    productId: productKey("AH", String(product.id)),
    title: product.title.trim(),
    brand: product.brand?.trim() || null,
    quantityText: quantity,
    imageUrl: product.imagePack?.find((image) => image.medium?.url)?.medium?.url ?? null,
    category: entry.categoryTitle?.trim() || product.category?.split("/")[0]?.trim() || null,
    reason: type === "EXPIRATION" || type === "OUT_OF_ASSORTMENT" ? type : "OTHER",
    date: labelDate(entry.bestBeforeDateLabel),
    discountPercent: percent ? Number(percent[1]) : null,
    discountText,
    left: left ? Number(left[1]) : null,
    priceCents: eurosToCents(entry.bargainPrice?.priceNow == null ? null : Number(entry.bargainPrice.priceNow)),
    regularPriceCents: eurosToCents(entry.bargainPrice?.priceWas == null ? null : Number(entry.bargainPrice.priceWas)),
  };
}

function storeName(store: GqlStore) {
  const address = store.address;
  const street = [address?.street, [address?.houseNumber, address?.houseNumberExtra].filter(Boolean).join("")].filter(Boolean).join(" ");
  return [street, address?.city].filter(Boolean).join(", ") || `AH ${store.id}`;
}

async function favouriteStore() {
  if (storeCache && Date.now() - storeCache.at < STORE_TTL_MS) return storeCache.store;
  const data = await graphql<{ storesFavouriteStore?: GqlStore | null }>(STORE_QUERY);
  const store = data.storesFavouriteStore?.id != null ? { id: String(data.storesFavouriteStore.id), name: storeName(data.storesFavouriteStore) } : null;
  storeCache = { at: Date.now(), store };
  return store;
}

/**
 * Catalogue rows for products the server has never seen, so the app can open and add them. The markdown is
 * store-only, so these rows carry the regular price; products already known are left untouched.
 */
function ensureCatalogue(items: LastChanceItem[], raw: GqlBargain[]) {
  const known = new Set(productsByIds(items.map((item) => item.productId)).map((row) => row.id));
  const missing = raw
    .filter((entry) => entry.product?.id && !known.has(productKey("AH", String(entry.product.id))))
    .map((entry): AhProduct => {
      const product = entry.product!;
      const was = entry.bargainPrice?.priceWas == null ? null : Number(entry.bargainPrice.priceWas);
      const [main, sub] = (product.category ?? "").split("/");
      const url = product.imagePack?.find((image) => image.medium?.url)?.medium?.url;
      return {
        webshopId: product.id!,
        title: product.title ?? "",
        brand: product.brand,
        salesUnitSize: product.salesUnitSize,
        currentPrice: was,
        priceBeforeBonus: was,
        mainCategory: main?.trim() || null,
        subCategory: sub?.trim() || null,
        images: url ? [{ width: 400, url }] : null,
      };
    })
    .map(mapAhProduct)
    .filter((product) => product !== null);
  if (missing.length) upsertProducts(missing);
}

async function fetchLastChance(): Promise<LastChanceView> {
  const store = await favouriteStore();
  if (!store) return { connected: true, store: null, items: [], fetchedAt: Date.now() };
  const data = await graphql<{ bargainItemsV2?: { items?: GqlBargain[] | null } | null }>(ITEMS_QUERY, { storeId: store.id });
  const raw = data.bargainItemsV2?.items ?? [];
  const items = raw.map(mapBargain).filter((item): item is LastChanceItem => item !== null);
  try {
    ensureCatalogue(items, raw);
  } catch (error) {
    console.warn(`[ah-last-chance] catalogue upsert failed: ${error instanceof Error ? error.message : error}`);
  }
  return { connected: true, store, items, fetchedAt: Date.now() };
}

export async function lastChance(options: { force?: boolean } = {}): Promise<LastChanceView> {
  if (!isConnected()) return { connected: false, store: null, items: [], fetchedAt: Date.now() };
  if (!options.force && cache && Date.now() - cache.fetchedAt < TTL_MS) return cache;
  if (!inFlight) inFlight = fetchLastChance().then((view) => (cache = view)).finally(() => (inFlight = null));
  return inFlight;
}

/** Forgets the store and items, e.g. after logging out or into another account. */
export function clearLastChance() {
  cache = null;
  storeCache = null;
}

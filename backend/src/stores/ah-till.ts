import type { ProductRow } from "@/db/schema";
import { getSetting, setSetting } from "@/db/settings";
import { graphql } from "@/stores/ah-account";
import { mapAhProduct, type AhProduct } from "@/stores/ah";
import { webshopIds } from "@/stores/ah-receipts";
import { productsByIds, upsertProducts } from "@/stores/search";
import { productKey } from "@/stores/types";

/**
 * Catalogue products for AH till article numbers (the `nasaNr` on every self-scan line). A label printed by a store
 * scale (an EAN-13 starting with 2) is a code no product API knows, but AH's own line for it carries the till number,
 * and `productConvertId` turns that into the webshop product, so the app can show its name and picture. Checked
 * 2026-11-23 against captured lines: PAPRIKA MIX 21006 → AH Paprika mix, KIPFILET 805097 → AH Scharrel kipfilet.
 * The article digits inside the label itself are a different numbering (26500 there is a cake).
 */
const IDS_KEY = "ahTillWebshopIds";
/** Till numbers AH has no webshop product for are remembered too (as 0), so they are not asked again. */
type IdMap = Record<string, number>;

type GqlProduct = {
  id?: number | null;
  title?: string | null;
  brand?: string | null;
  salesUnitSize?: string | null;
  category?: string | null;
  priceV2?: { now?: { amount?: number | null } | null; was?: { amount?: number | null } | null } | null;
  imagePack?: Array<{ medium?: { url?: string | null } | null }> | null;
};

const PRODUCT_FIELDS = "id title brand salesUnitSize category priceV2(forcePromotionVisibility: true) { now { amount } was { amount } } imagePack(angles: [HERO]) { medium { url } }";

/** Webshop products by GraphQL, for ids the catalogue does not hold yet (the REST detail endpoint answers 404). */
async function fetchProducts(webshopIds: number[]): Promise<void> {
  if (!webshopIds.length) return;
  const aliases = webshopIds.map((id, index) => `p${index}: product(id: ${Math.trunc(id)}) { ${PRODUCT_FIELDS} }`).join(" ");
  const data = await graphql<Record<string, GqlProduct | null>>(`query { ${aliases} }`);
  const products = Object.values(data)
    .filter((product): product is GqlProduct => product?.id != null && !!product.title)
    .map((product): AhProduct => {
      const [main, sub] = (product.category ?? "").split("/");
      const url = product.imagePack?.find((image) => image?.medium?.url)?.medium?.url;
      const now = product.priceV2?.now?.amount ?? null;
      return {
        webshopId: product.id!,
        title: product.title!,
        brand: product.brand,
        salesUnitSize: product.salesUnitSize,
        currentPrice: now,
        priceBeforeBonus: product.priceV2?.was?.amount ?? now,
        mainCategory: main?.trim() || null,
        subCategory: sub?.trim() || null,
        images: url ? [{ width: 400, url }] : null,
      };
    })
    .map(mapAhProduct)
    .filter((product) => product !== null);
  if (products.length) upsertProducts(products);
}

/** Till number → catalogue row, for the ones AH sells online; the rest are left out. */
export async function tillProducts(nasaNrs: string[]): Promise<Record<string, ProductRow>> {
  const wanted = [...new Set(nasaNrs.map((nr) => nr.trim()).filter((nr) => /^\d{1,9}$/.test(nr)))].slice(0, 60);
  const ids = getSetting<IdMap>(IDS_KEY, {});
  const unknown = wanted.filter((nr) => !(nr in ids));
  if (unknown.length) {
    const converted = await webshopIds(unknown.map(Number));
    for (const nr of unknown) ids[nr] = converted.get(Number(nr)) ?? 0;
    setSetting(IDS_KEY, ids);
  }
  const keyOf = (nr: string) => (ids[nr] ? productKey("AH", String(ids[nr])) : null);
  const keys = wanted.map(keyOf).filter((key): key is string => key != null);
  const known = new Set(productsByIds(keys).map((row) => row.id));
  const missing = wanted.filter((nr) => { const key = keyOf(nr); return key != null && !known.has(key); }).map((nr) => ids[nr]!);
  if (missing.length) await fetchProducts(missing);
  const rows = new Map(productsByIds(keys).map((row) => [row.id, row]));
  return Object.fromEntries(wanted.flatMap((nr) => { const row = keyOf(nr) ? rows.get(keyOf(nr)!) : undefined; return row ? [[nr, row]] : []; }));
}

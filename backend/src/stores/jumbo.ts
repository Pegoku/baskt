import { fetchWithRetry } from "@/lib/http";
import { normalizeUnit, parseQuantity, quantityTextFromTitle, unitPriceFrom } from "@/lib/units";
import { StoreThrottle } from "@/stores/throttle";
import type { StoreAdapter, StoreProduct } from "@/stores/types";

const BASE = "https://www.jumbo.com";
const GRAPHQL = `${BASE}/api/graphql`;
const CLIENT_VERSION = "master-v30.11.0-web";
const HEADERS = {
  "content-type": "application/json",
  accept: "application/json",
  "apollographql-client-name": "JUMBO_WEB-search",
  "apollographql-client-version": CLIENT_VERSION,
  "x-source": "JUMBO_WEB-search",
  origin: BASE,
  "user-agent": "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/145.0.0.0 Safari/537.36",
  "accept-language": "nl-NL,nl;q=0.9,en;q=0.8",
};

export const SEARCH_QUERY = `
  query SearchProducts($input: ProductSearchInput!) {
    searchProducts(input: $input) {
      count
      products {
        id: sku
        title
        subtitle: packSizeDisplay
        category: rootCategory
        brand
        image
        link
        availability { isAvailable }
        prices: price { price promoPrice pricePerUnit { price unit } }
        promotions { tags { text } durationTexts { shortTitle } }
      }
    }
  }
`;

export type JumboProduct = {
  id?: string | null;
  title?: string | null;
  subtitle?: string | null;
  category?: string | null;
  brand?: string | null;
  image?: string | null;
  link?: string | null;
  availability?: { isAvailable?: boolean | null } | null;
  prices?: { price?: number | null; promoPrice?: number | null; pricePerUnit?: { price?: number | null; unit?: string | null } | null } | null;
  promotions?: Array<{ tags?: Array<{ text?: string | null } | null> | null; durationTexts?: Array<{ shortTitle?: string | null } | null> | null } | null> | null;
};

export type JumboSearchResponse = { data?: { searchProducts?: { count?: number; products?: JumboProduct[] } }; errors?: Array<{ message: string }> };

function asArray<T>(value: T | T[] | null | undefined): T[] {
  if (Array.isArray(value)) return value;
  return value ? [value] : [];
}

function absolute(path: string | null | undefined) {
  if (!path) return null;
  return path.startsWith("http") ? path : `${BASE}${path.startsWith("/") ? path : `/${path}`}`;
}

export function mapJumboProduct(product: JumboProduct): StoreProduct | null {
  const regular = product.prices?.price;
  const promo = product.prices?.promoPrice;
  const priceCents = promo ?? regular;
  if (priceCents === null || priceCents === undefined || !product.id || !product.title) return null;
  const quantityText = product.subtitle?.trim() || quantityTextFromTitle(product.title) || "per stuk";
  const quantity = parseQuantity(quantityText);
  const perUnit = product.prices?.pricePerUnit;
  const unitPrice =
    perUnit?.price != null && normalizeUnit(perUnit.unit) ? { cents: perUnit.price, unit: normalizeUnit(perUnit.unit)! } : unitPriceFrom(priceCents, quantity);
  const promoText = asArray(product.promotions)
    .flatMap((promotion) => [
      ...asArray(promotion?.tags).map((tag) => tag?.text?.trim()),
      ...asArray(promotion?.durationTexts).map((item) => item?.shortTitle?.trim()),
    ])
    .filter(Boolean)
    .join(" • ");
  const isDeal = (promo != null && regular != null && promo < regular) || Boolean(promoText);

  return {
    store: "JUMBO",
    sourceId: product.id,
    title: product.title.trim(),
    brand: product.brand?.trim() || null,
    quantityText,
    unitAmount: quantity?.amount ?? null,
    unit: quantity?.unit ?? null,
    priceCents,
    regularPriceCents: promo != null && regular != null && promo < regular ? regular : null,
    unitPriceCents: unitPrice?.cents ?? null,
    unitPriceUnit: unitPrice?.unit ?? null,
    dealText: promoText || (isDeal ? "Aanbieding" : null),
    isDeal,
    imageUrl: absolute(product.image),
    sourceUrl: absolute(product.link),
    category: product.category?.trim() || null,
    available: product.availability?.isAvailable ?? true,
  };
}

export class JumboAdapter implements StoreAdapter {
  readonly info = { code: "JUMBO", name: "Jumbo", color: "#FDC500", coverage: "full" as const };
  private readonly throttle = new StoreThrottle("JUMBO");

  health() {
    return this.throttle.health();
  }

  async search(query: string, limit = 20): Promise<StoreProduct[]> {
    const encoded = encodeURIComponent(query);
    const currentUrl = `/producten/?searchType=keyword&searchTerms=${encoded}`;
    const body = await this.throttle.run(async () => {
      const response = await fetchWithRetry(
        GRAPHQL,
        {
          method: "POST",
          headers: { ...HEADERS, referer: `${BASE}${currentUrl}` },
          body: JSON.stringify({
            operationName: "SearchProducts",
            variables: { input: { searchType: "keyword", searchTerms: query, offSet: 0, currentUrl, previousUrl: "", bloomreachCookieId: "" } },
            query: SEARCH_QUERY,
          }),
        },
        { onRateLimited: () => this.throttle.noteRateLimited() },
      );
      return (await response.json()) as JumboSearchResponse;
    });
    if (body.errors?.length) throw new Error(`Jumbo GraphQL: ${body.errors.map((e) => e.message).join("; ")}`);
    return (body.data?.searchProducts?.products ?? [])
      .map(mapJumboProduct)
      .filter((product): product is StoreProduct => product !== null)
      .slice(0, limit);
  }
}

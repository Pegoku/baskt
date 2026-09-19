import { fetchWithRetry } from "@/lib/http";
import { normalizeUnit, parseQuantity, quantityTextFromTitle, unitPriceFrom } from "@/lib/units";
import { StoreThrottle } from "@/stores/throttle";
import type { StoreAdapter, StoreProduct } from "@/stores/types";
import { extractNuxtData, findDevalueObjects, matchesQuery, type DealCard } from "@/stores/promotions";

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

export function mapJumboPromotions(html: string): DealCard[] {
  const payload = extractNuxtData(html);
  if (!payload) return [];
  const seen = new Set<string>();
  const cards: DealCard[] = [];
  for (const raw of findDevalueObjects(payload, ["durationTexts", "title", "tags"])) {
    const id = String(raw.id ?? raw.uuid ?? "");
    if (!id || seen.has(id) || raw.hidden === true) continue;
    seen.add(id);
    const tags = Array.isArray(raw.tags) ? (raw.tags as Array<{ text?: unknown }>).map((tag) => (typeof tag?.text === "string" ? tag.text : "")).filter(Boolean) : [];
    const duration = raw.durationTexts && typeof raw.durationTexts === "object" ? (raw.durationTexts as { shortTitle?: unknown; description?: unknown }) : null;
    cards.push({
      store: "JUMBO",
      id,
      title: String(raw.title ?? "").trim(),
      subtitle: typeof raw.subtitle === "string" ? raw.subtitle.replace(/<br\s*\/?>/gi, " · ").replace(/<[^>]+>/g, "").trim() || null : null,
      dealText: tags.join(" · ") || (typeof duration?.shortTitle === "string" ? duration.shortTitle : null),
      imageUrl: typeof raw.image === "string" ? raw.image : null,
      url: typeof raw.url === "string" ? `${BASE}${raw.url}` : null,
      priceCents: null,
      regularPriceCents: null,
      productId: null,
      validFrom: typeof raw.start === "string" ? raw.start : null,
      validUntil: typeof raw.end === "string" ? raw.end : null,
    });
  }
  return cards.filter((card) => card.title);
}

export class JumboAdapter implements StoreAdapter {
  readonly info = { code: "JUMBO", name: "Jumbo", color: "#FDC500", coverage: "full" as const, logoUrl: "https://www.google.com/s2/favicons?domain=jumbo.com&sz=128" };
  private readonly throttle = new StoreThrottle("JUMBO");
  private promoCache: { at: number; cards: DealCard[] } | null = null;

  /** Weekly promotions from the aanbiedingen page (cached for an hour); a keyword narrows the list. */
  async promotions(query: string): Promise<DealCard[]> {
    if (!this.promoCache || Date.now() - this.promoCache.at > 60 * 60 * 1000) {
      const cards = await this.throttle.run(async () => {
        const response = await fetchWithRetry(`${BASE}/aanbiedingen/nu`, { headers: { "user-agent": HEADERS["user-agent"], accept: "text/html", "accept-language": HEADERS["accept-language"] } }, { onRateLimited: () => this.throttle.noteRateLimited() });
        return mapJumboPromotions(await response.text());
      });
      this.promoCache = { at: Date.now(), cards };
    }
    return this.promoCache.cards.filter((card) => matchesQuery(card, query));
  }

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

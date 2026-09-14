import { fetchWithRetry, HttpError } from "@/lib/http";
import { eurosToCents, parseQuantity, parseUnitPriceDescription, quantityTextFromTitle, unitPriceFrom } from "@/lib/units";
import { StoreThrottle } from "@/stores/throttle";
import type { StoreAdapter, StoreProduct } from "@/stores/types";
import { realDate, type DealCard } from "@/stores/promotions";
import { productKey } from "@/stores/types";

const BASE = "https://api.ah.nl";
const HEADERS = {
  "x-application": "AHWEBSHOP",
  "user-agent": "Appie/8.22.3 Model/phone Android/14-API34",
  accept: "application/json",
};

export type AhProduct = {
  webshopId: number;
  title: string;
  brand?: string | null;
  salesUnitSize?: string | null;
  unitPriceDescription?: string | null;
  priceBeforeBonus?: number | null;
  currentPrice?: number | null;
  isBonus?: boolean | null;
  bonusMechanism?: string | null;
  bonusPeriodDescription?: string | null;
  images?: Array<{ width?: number; height?: number; url?: string }> | null;
  mainCategory?: string | null;
  subCategory?: string | null;
  orderAvailabilityStatus?: string | null;
  isOrderable?: boolean | null;
  availableOnline?: boolean | null;
  bonusStartDate?: string | null;
  bonusEndDate?: string | null;
};

export type AhSearchResponse = {
  products?: AhProduct[];
  page?: { size: number; totalElements: number; totalPages: number; number: number };
};

type Token = { accessToken: string; refreshToken: string; expiresAt: number };

export function mapAhProduct(product: AhProduct): StoreProduct | null {
  const priceEuros = product.currentPrice ?? product.priceBeforeBonus;
  const priceCents = eurosToCents(priceEuros);
  if (priceCents === null || !product.title) return null;
  const isDeal = Boolean(product.isBonus) && product.currentPrice != null && product.priceBeforeBonus != null && product.currentPrice < product.priceBeforeBonus;
  const quantityText = product.salesUnitSize?.trim() || quantityTextFromTitle(product.title) || "per stuk";
  const quantity = parseQuantity(quantityText);
  const unitPrice = parseUnitPriceDescription(product.unitPriceDescription) ?? unitPriceFrom(priceCents, quantity);
  const image =
    product.images?.find((img) => img.width === 400)?.url ??
    product.images?.slice().sort((a, b) => (a.width ?? 0) - (b.width ?? 0)).find((img) => (img.width ?? 0) >= 200)?.url ??
    product.images?.[0]?.url ??
    null;
  const dealText = product.bonusMechanism?.trim() || (product.isBonus ? "Bonus" : null);
  const category = [product.mainCategory, product.subCategory].filter(Boolean).join(" / ") || null;

  return {
    store: "AH",
    sourceId: String(product.webshopId),
    title: product.title.trim(),
    brand: product.brand?.trim() || null,
    quantityText,
    unitAmount: quantity?.amount ?? null,
    unit: quantity?.unit ?? null,
    priceCents,
    regularPriceCents: isDeal ? eurosToCents(product.priceBeforeBonus) : null,
    unitPriceCents: unitPrice?.cents ?? null,
    unitPriceUnit: unitPrice?.unit ?? null,
    dealText: isDeal || product.isBonus ? dealText : null,
    isDeal: Boolean(product.isBonus),
    imageUrl: image,
    sourceUrl: `https://www.ah.nl/producten/product/wi${product.webshopId}`,
    category,
    available: product.orderAvailabilityStatus !== "UNAVAILABLE" && product.isOrderable !== false,
  };
}

export class AhAdapter implements StoreAdapter {
  readonly info = { code: "AH", name: "Albert Heijn", color: "#00ADE6", coverage: "full" as const };
  private token: Token | null = null;
  private readonly throttle = new StoreThrottle("AH");

  health() {
    return this.throttle.health();
  }

  private async anonymousToken(): Promise<Token> {
    const response = await fetchWithRetry(`${BASE}/mobile-auth/v1/auth/token/anonymous`, {
      method: "POST",
      headers: { ...HEADERS, "content-type": "application/json" },
      body: JSON.stringify({ clientId: "appie" }),
    });
    const body = (await response.json()) as { access_token: string; refresh_token: string; expires_in: number };
    return { accessToken: body.access_token, refreshToken: body.refresh_token, expiresAt: Date.now() + (body.expires_in - 60) * 1000 };
  }

  private async refreshToken(refreshToken: string): Promise<Token | null> {
    try {
      const response = await fetchWithRetry(
        `${BASE}/mobile-auth/v1/auth/token/refresh`,
        {
          method: "POST",
          headers: { ...HEADERS, "content-type": "application/json" },
          body: JSON.stringify({ clientId: "appie", refreshToken }),
        },
        { retries: 1 },
      );
      const body = (await response.json()) as { access_token: string; refresh_token: string; expires_in: number };
      return { accessToken: body.access_token, refreshToken: body.refresh_token, expiresAt: Date.now() + (body.expires_in - 60) * 1000 };
    } catch {
      return null;
    }
  }

  private async accessToken(force = false) {
    if (!force && this.token && this.token.expiresAt > Date.now()) return this.token.accessToken;
    const refreshed = this.token && !force ? await this.refreshToken(this.token.refreshToken) : null;
    this.token = refreshed ?? (await this.anonymousToken());
    return this.token.accessToken;
  }

  private async get<T>(path: string, retryAuth = true): Promise<T> {
    const token = await this.accessToken();
    try {
      const response = await fetchWithRetry(
        `${BASE}${path}`,
        { headers: { ...HEADERS, authorization: `Bearer ${token}` } },
        { onRateLimited: () => this.throttle.noteRateLimited() },
      );
      return (await response.json()) as T;
    } catch (error) {
      if (error instanceof HttpError && error.status === 401 && retryAuth) {
        await this.accessToken(true);
        return this.get<T>(path, false);
      }
      throw error;
    }
  }

  async search(query: string, limit = 20): Promise<StoreProduct[]> {
    const size = Math.min(Math.max(limit, 1), 40);
    const params = new URLSearchParams({ query, sortOn: "RELEVANCE", size: String(size), page: "0" });
    const body = await this.throttle.run(() => this.get<AhSearchResponse>(`/mobile-services/product/search/v2?${params}`));
    return (body.products ?? []).map(mapAhProduct).filter((product): product is StoreProduct => product !== null);
  }

  /** Bonus products via the search endpoint's bonus filter (empty query = the whole bonus). */
  async promotions(query: string): Promise<DealCard[]> {
    const cards: DealCard[] = [];
    const pages = query ? 2 : 4;
    for (let page = 0; page < pages; page += 1) {
      const params = new URLSearchParams({ query, sortOn: "RELEVANCE", size: "40", page: String(page), filters: "bonus=true" });
      const body = await this.throttle.run(() => this.get<AhSearchResponse>(`/mobile-services/product/search/v2?${params}`));
      for (const raw of body.products ?? []) {
        const product = mapAhProduct(raw);
        if (!product) continue;
        cards.push({
          store: "AH",
          id: product.sourceId,
          title: product.title,
          subtitle: product.quantityText,
          dealText: product.dealText,
          imageUrl: product.imageUrl,
          url: product.sourceUrl,
          priceCents: product.priceCents,
          regularPriceCents: product.regularPriceCents,
          productId: productKey("AH", product.sourceId),
          validFrom: realDate(raw.bonusStartDate),
          validUntil: realDate(raw.bonusEndDate),
        });
      }
      if ((body.page?.totalPages ?? 1) <= page + 1) break;
    }
    return cards;
  }

  async byBarcode(gtin: string): Promise<StoreProduct | null> {
    try {
      const body = await this.throttle.run(() => this.get<AhProduct | { productCard?: AhProduct }>(`/mobile-services/product/search/v1/gtin/${encodeURIComponent(gtin)}`));
      return mapAhProduct("productCard" in body && body.productCard ? body.productCard : (body as AhProduct));
    } catch (error) {
      if (error instanceof HttpError && error.status === 404) return null;
      throw error;
    }
  }

  async details(sourceId: string): Promise<{ description: string | null; imageUrls: string[] }> {
    const body = await this.throttle.run(() => this.get<Record<string, unknown>>(`/mobile-services/product/detail/v4/fir/${encodeURIComponent(sourceId)}`));
    const card = (body.productCard ?? body) as AhProduct & { description?: string };
    const summaries = [body.description, body.summary, card.description];
    const text: string[] = [];
    for (const value of summaries) {
      if (typeof value === "string") text.push(value);
      else if (value && typeof value === "object") {
        const data = value as Record<string, unknown>;
        for (const field of [data.description, data.text, data.summary]) if (typeof field === "string") text.push(field);
        if (Array.isArray(data.bullets)) text.push(...data.bullets.filter((line): line is string => typeof line === "string"));
      }
    }
    return { description: [...new Set(text)].join("\n\n") || null, imageUrls: [...new Set((card.images ?? []).map((image) => image.url).filter((url): url is string => !!url))] };
  }

  async refresh(sourceIds: string[]): Promise<StoreProduct[]> {
    const results: StoreProduct[] = [];
    for (const id of sourceIds) {
      try {
        const body = await this.throttle.run(() => this.get<{ productCard?: AhProduct } & AhProduct>(`/mobile-services/product/detail/v4/fir/${id}`));
        const mapped = mapAhProduct(body.productCard ?? body);
        if (mapped) results.push(mapped);
      } catch (error) {
        console.warn(`[AH] refresh ${id} failed: ${error instanceof Error ? error.message : error}`);
      }
    }
    return results;
  }
}

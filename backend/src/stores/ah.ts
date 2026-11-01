import { fetchWithRetry, HttpError } from "@/lib/http";
import { eurosToCents, parseQuantity, parseUnitPriceDescription, quantityTextFromTitle, unitPriceFrom } from "@/lib/units";
import { StoreThrottle } from "@/stores/throttle";
import type { StoreAdapter, StoreProduct } from "@/stores/types";
import { matchesQuery, periodOf, realDate, type DealCard } from "@/stores/promotions";
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
  bonusSegmentId?: number | null;
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

type AhTradeItem = {
  description?: { regulatedProductName?: unknown };
  marketingInformationModule?: { tradeItemMarketingMessage?: unknown; tradeItemFeatureBenefit?: unknown };
  foodAndBeverageIngredientStatement?: unknown;
  allergenInformation?: Array<{ items?: Array<{ typeCode?: { label?: unknown }; levelOfContainmentCode?: { value?: unknown } }> }>;
  consumerInstructions?: { storageInstructions?: unknown; usageInstructions?: unknown };
  healthRelatedInformation?: { compulsoryAdditiveLabelInformation?: unknown };
};

const strings = (value: unknown): string[] => (Array.isArray(value) ? value : [value]).filter((entry): entry is string => typeof entry === "string" && !!entry.trim()).map((entry) => entry.trim());

/** Many AH products carry no card copy; the GS1 trade item still has the label text shoppers expect. */
export function tradeItemDescription(item: AhTradeItem | undefined): string[] {
  if (!item) return [];
  const sections: string[] = [];
  const marketing = item.marketingInformationModule;
  sections.push(...strings(marketing?.tradeItemMarketingMessage), ...strings(marketing?.tradeItemFeatureBenefit));
  const name = strings(item.description?.regulatedProductName).join(" ");
  if (name) sections.push(name);
  sections.push(...strings(item.foodAndBeverageIngredientStatement));
  const allergens = new Map<string, string[]>();
  for (const group of item.allergenInformation ?? []) {
    for (const entry of group.items ?? []) {
      const label = entry.typeCode?.label, level = entry.levelOfContainmentCode?.value;
      if (typeof label !== "string" || typeof level !== "string") continue;
      const heading = level === "CONTAINS" ? "Bevat" : level === "MAY_CONTAIN" ? "Kan bevatten" : null;
      if (heading) allergens.set(heading, [...(allergens.get(heading) ?? []), label.toLowerCase()]);
    }
  }
  const allergenLines = [...allergens].map(([heading, labels]) => `${heading}: ${[...new Set(labels)].join(", ")}`);
  if (allergenLines.length) sections.push(`Allergenen:\n${allergenLines.join("\n")}`);
  const storage = strings(item.consumerInstructions?.storageInstructions);
  if (storage.length) sections.push(`Bewaren: ${storage.join(" ")}`);
  const usage = strings(item.consumerInstructions?.usageInstructions);
  if (usage.length) sections.push(`Gebruik: ${usage.join(" ")}`);
  sections.push(...strings(item.healthRelatedInformation?.compulsoryAdditiveLabelInformation));
  return sections;
}

export function mapAhDetails(body: Record<string, unknown>): { description: string | null; imageUrls: string[] } {
    const card = (body.productCard ?? body) as AhProduct & { description?: string; descriptionFull?: string; descriptionHighlights?: string; extraDescriptions?: Array<{ title?: string; description?: string }> };
    const summaries = [card.descriptionHighlights, card.descriptionFull, body.description, body.summary, card.description, ...(card.extraDescriptions ?? []).map((entry) => entry.description)];
    const text: string[] = [];
    for (const value of summaries) {
      if (typeof value === "string" && value.trim()) text.push(value);
      else if (value && typeof value === "object") {
        const data = value as Record<string, unknown>;
        for (const field of [data.description, data.text, data.summary]) if (typeof field === "string" && field.trim()) text.push(field);
        if (Array.isArray(data.bullets)) text.push(...data.bullets.filter((line): line is string => typeof line === "string"));
      }
    }
    text.push(...tradeItemDescription(body.tradeItem as AhTradeItem | undefined));
    return { description: [...new Set(text)].join("\n\n") || null, imageUrls: (card.images ?? []).filter((image) => !!image.url).sort((a, b) => (b.width ?? 0) - (a.width ?? 0)).slice(0, 1).map((image) => image.url!) };
}

/** One promotion group of the national bonus ("Alle Oreo koek", "Sinterklaas accessoires"). */
export type AhBonusGroup = {
  id?: string | null;
  title?: string | null;
  subtitle?: string | null;
  periodStart?: string | null;
  periodEnd?: string | null;
  productIds?: Array<number | string> | null;
  webPath?: string | null;
  images?: Array<{ url?: string | null; width?: number | null }> | null;
  price?: { now?: { amount?: number | null } | null; was?: { amount?: number | null } | null } | null;
  rawPromotionLabels?: Array<{ defaultDescription?: string | null }> | null;
};

const BONUS_GROUPS_QUERY = `query BonusGroups {
  bonusCategories { type promotions { id title subtitle periodStart periodEnd productIds webPath images { url width } price { now { amount } was { amount } } rawPromotionLabels { defaultDescription } } }
}`;

export function mapAhBonusGroup(group: AhBonusGroup, featured: boolean): DealCard | null {
  if (!group.id || !group.title) return null;
  const images = (group.images ?? []).filter((image) => image.url);
  const image = images.find((entry) => entry.width === 708) ?? images.sort((a, b) => (b.width ?? 0) - (a.width ?? 0))[0];
  const validFrom = realDate(group.periodStart);
  const validUntil = realDate(group.periodEnd);
  const count = group.productIds?.length ?? 0;
  return {
    store: "AH",
    id: `group-${group.id}`,
    title: group.title.trim(),
    subtitle: group.subtitle?.trim() || (count > 1 ? `${count} products` : null),
    dealText: group.rawPromotionLabels?.find((label) => label.defaultDescription)?.defaultDescription ?? null,
    imageUrl: image?.url ?? null,
    url: group.webPath ? `https://www.ah.nl${group.webPath}` : null,
    priceCents: group.price?.now?.amount != null ? eurosToCents(group.price.now.amount) : null,
    regularPriceCents: group.price?.was?.amount != null ? eurosToCents(group.price.was.amount) : null,
    productId: null,
    validFrom,
    validUntil,
    period: periodOf(validFrom, validUntil),
    featured,
  };
}

function amsterdamToday() {
  return new Date().toLocaleDateString("sv-SE", { timeZone: "Europe/Amsterdam" });
}

export class AhAdapter implements StoreAdapter {
  readonly info = { code: "AH", name: "Albert Heijn", color: "#00ADE6", coverage: "full" as const, logoUrl: "https://www.google.com/s2/favicons?domain=ah.nl&sz=128" };
  private token: Token | null = null;
  private readonly throttle = new StoreThrottle("AH");
  private bonusCache: { at: number; groups: AhBonusGroup[]; spotlight: Set<string> } | null = null;

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

  private async post<T>(path: string, body: unknown, retryAuth = true): Promise<T> {
    const token = await this.accessToken();
    try {
      const response = await fetchWithRetry(
        `${BASE}${path}`,
        { method: "POST", headers: { ...HEADERS, "content-type": "application/json", authorization: `Bearer ${token}` }, body: JSON.stringify(body) },
        { onRateLimited: () => this.throttle.noteRateLimited() },
      );
      return (await response.json()) as T;
    } catch (error) {
      if (error instanceof HttpError && error.status === 401 && retryAuth) {
        await this.accessToken(true);
        return this.post<T>(path, body, false);
      }
      throw error;
    }
  }

  /**
   * The week's national bonus groups (with their run dates) and the ids AH features "Uit de Bonusfolder".
   * Two requests, cached for an hour; a failure leaves the deals list without the extra tagging.
   */
  private async bonusOverview() {
    if (this.bonusCache && Date.now() - this.bonusCache.at < 60 * 60 * 1000) return this.bonusCache;
    const [groups, spotlight] = await Promise.all([
      this.throttle
        .run(() => this.post<{ data?: { bonusCategories?: Array<{ type?: string | null; promotions?: AhBonusGroup[] | null }> | null } }>("/graphql", { query: BONUS_GROUPS_QUERY }))
        .then((body) => (body.data?.bonusCategories ?? []).filter((category) => category.type === "NATIONAL").flatMap((category) => category.promotions ?? []))
        .catch((error) => (console.warn(`[AH] bonus groups failed: ${error instanceof Error ? error.message : error}`), null)),
      this.throttle
        .run(() => this.get<{ bonusGroupOrProducts?: Array<{ bonusGroup?: { id?: string | number | null } | null }> }>(`/mobile-services/bonuspage/v2/section/spotlight?application=AHWEBSHOP&date=${amsterdamToday()}`))
        .then((body) => new Set((body.bonusGroupOrProducts ?? []).map((entry) => String(entry.bonusGroup?.id ?? "")).filter(Boolean)))
        .catch((error) => (console.warn(`[AH] bonus spotlight failed: ${error instanceof Error ? error.message : error}`), null)),
    ]);
    if (!groups && !spotlight) return this.bonusCache ?? { at: 0, groups: [], spotlight: new Set<string>() };
    this.bonusCache = { at: Date.now(), groups: groups ?? [], spotlight: spotlight ?? new Set() };
    return this.bonusCache;
  }

  /**
   * Bonus products via the search endpoint's bonus filter (empty query = the whole bonus), tagged as this week's
   * or seasonal. Seasonal and featured bonus groups the product pages did not reach are added as group cards.
   */
  async promotions(query: string): Promise<DealCard[]> {
    const overview = await this.bonusOverview();
    const cards: DealCard[] = [];
    const segments = new Set<string>();
    const pages = query ? 2 : 4;
    for (let page = 0; page < pages; page += 1) {
      const params = new URLSearchParams({ query, sortOn: "RELEVANCE", size: "40", page: String(page), filters: "bonus=true" });
      const body = await this.throttle.run(() => this.get<AhSearchResponse>(`/mobile-services/product/search/v2?${params}`));
      for (const raw of body.products ?? []) {
        const product = mapAhProduct(raw);
        if (!product) continue;
        const segment = raw.bonusSegmentId != null ? String(raw.bonusSegmentId) : null;
        if (segment) segments.add(segment);
        const validFrom = realDate(raw.bonusStartDate);
        const validUntil = realDate(raw.bonusEndDate);
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
          validFrom,
          validUntil,
          period: periodOf(validFrom, validUntil),
          featured: segment !== null && overview.spotlight.has(segment),
        });
      }
      if ((body.page?.totalPages ?? 1) <= page + 1) break;
    }
    for (const group of overview.groups) {
      if (!group.id || segments.has(group.id)) continue;
      const card = mapAhBonusGroup(group, overview.spotlight.has(group.id));
      if (card && (card.period === "season" || card.featured) && matchesQuery(card, query)) cards.push(card);
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
    return mapAhDetails(body);
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

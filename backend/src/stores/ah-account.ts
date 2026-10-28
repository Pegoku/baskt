import { getSetting, setSetting } from "@/db/settings";
import { fetchWithRetry, HttpError } from "@/lib/http";
import { eurosToCents } from "@/lib/units";
import { mapAhProduct, type AhProduct } from "@/stores/ah";
import { setPersonalOffers, type PersonalOffer } from "@/stores/personal-overlay";
import { productsByIds, upsertProducts } from "@/stores/search";
import { getAdapter } from "@/stores/registry";
import { productKey } from "@/stores/types";

/**
 * The user's own AH account (Appie login), used for personal Bonus offers. Search and prices stay on the
 * shared anonymous token so cached results never depend on who is logged in.
 */
const BASE = "https://api.ah.nl";
const CLIENT_ID = "appie";
const HEADERS = {
  "x-application": "AHWEBSHOP",
  "user-agent": "Appie/8.22.3 Model/phone Android/14-API34",
  accept: "application/json",
  "content-type": "application/json",
};

/** login.ah.nl ends on appie://login-exit?code=...; the app's WebView catches that and posts the code here. */
export const AH_LOGIN_URL = `https://login.ah.nl/secure/oauth/authorize?client_id=${CLIENT_ID}&redirect_uri=appie://login-exit&response_type=code`;
export const AH_LOGIN_REDIRECT = "appie://login-exit";

type Account = { accessToken: string; refreshToken: string; expiresAt: number; connectedAt: number; memberName: string | null; error: string | null };
type TokenResponse = { access_token: string; refresh_token: string; expires_in: number };

const ACCOUNT_KEY = "ahAccount";
const OFFERS_KEY = "ahPersonalOffers";
const AUTO_KEY = "ahAutoActivate";
const AUTO_LOG_KEY = "ahAutoActivated";

export class AhAccountError extends Error {
  constructor(
    public readonly code: "NOT_CONNECTED" | "LOGIN_EXPIRED" | "AH_ERROR",
    message: string,
  ) {
    super(message);
  }
}

function account(): Account | null {
  return getSetting<Account | null>(ACCOUNT_KEY, null);
}

function saveTokens(body: TokenResponse, previous: Account | null) {
  const next: Account = {
    accessToken: body.access_token,
    refreshToken: body.refresh_token,
    expiresAt: Date.now() + (body.expires_in - 60) * 1000,
    connectedAt: previous?.connectedAt ?? Date.now(),
    memberName: previous?.memberName ?? null,
    error: null,
  };
  setSetting(ACCOUNT_KEY, next);
  return next;
}

export function autoActivateEnabled() {
  return getSetting<boolean>(AUTO_KEY, true);
}

export function setAutoActivate(enabled: boolean) {
  setSetting(AUTO_KEY, enabled);
}

export function accountStatus() {
  const current = account();
  return {
    connected: Boolean(current && !current.error),
    memberName: current?.memberName ?? null,
    connectedAt: current?.connectedAt ?? null,
    error: current?.error ?? null,
    autoActivate: autoActivateEnabled(),
    loginUrl: AH_LOGIN_URL,
    redirectPrefix: AH_LOGIN_REDIRECT,
  };
}

/** Accepts the code itself or the whole appie://login-exit?code=... URL. */
export function extractCode(input: string) {
  const trimmed = input.trim();
  const match = /[?&]code=([^&\s#]+)/.exec(trimmed);
  return decodeURIComponent(match ? match[1] : trimmed);
}

export async function connect(codeOrUrl: string) {
  const code = extractCode(codeOrUrl);
  if (!code) throw new AhAccountError("AH_ERROR", "no login code");
  const response = await fetchWithRetry(`${BASE}/mobile-auth/v1/auth/token`, { method: "POST", headers: HEADERS, body: JSON.stringify({ clientId: CLIENT_ID, code }) }, { retries: 1 });
  saveTokens((await response.json()) as TokenResponse, null);
  offersCache = null;
  const name = await memberName().catch(() => null);
  const current = account();
  if (current) setSetting(ACCOUNT_KEY, { ...current, memberName: name });
  return accountStatus();
}

export function disconnect() {
  setSetting(ACCOUNT_KEY, null);
  setSetting(OFFERS_KEY, null);
  offersCache = null;
  setPersonalOffers([]);
}

async function accessToken(force = false): Promise<string> {
  const current = account();
  if (!current) throw new AhAccountError("NOT_CONNECTED", "no AH account connected");
  if (current.error) throw new AhAccountError("LOGIN_EXPIRED", current.error);
  if (!force && current.expiresAt > Date.now()) return current.accessToken;
  try {
    const response = await fetchWithRetry(
      `${BASE}/mobile-auth/v1/auth/token/refresh`,
      { method: "POST", headers: HEADERS, body: JSON.stringify({ clientId: CLIENT_ID, refreshToken: current.refreshToken }) },
      { retries: 1 },
    );
    return saveTokens((await response.json()) as TokenResponse, current).accessToken;
  } catch (error) {
    // A rejected refresh token means logging in again; network trouble does not.
    if (error instanceof HttpError && error.status >= 400 && error.status < 500) {
      setSetting(ACCOUNT_KEY, { ...current, error: "AH login expired, log in again" });
      throw new AhAccountError("LOGIN_EXPIRED", "AH login expired, log in again");
    }
    throw error;
  }
}

async function authorized<T>(path: string, init: RequestInit = {}, retryAuth = true): Promise<T> {
  const token = await accessToken();
  try {
    const response = await fetchWithRetry(`${BASE}${path}`, { ...init, headers: { ...HEADERS, authorization: `Bearer ${token}` } }, { retries: 2 });
    return (await response.json()) as T;
  } catch (error) {
    if (error instanceof HttpError && error.status === 401 && retryAuth) {
      await accessToken(true);
      return authorized<T>(path, init, false);
    }
    throw error;
  }
}

type GraphqlResponse<T> = { data?: T; errors?: Array<{ message?: string }> };

export async function graphql<T>(query: string, variables: Record<string, unknown> = {}): Promise<T> {
  const body = await authorized<GraphqlResponse<T>>("/graphql", { method: "POST", body: JSON.stringify({ query, variables }) });
  if (!body.data) throw new AhAccountError("AH_ERROR", body.errors?.map((error) => error.message).join("; ") || "empty GraphQL reply");
  if (body.errors?.length) console.warn(`[ah-account] GraphQL partial errors: ${body.errors.map((error) => error.message).join("; ")}`);
  return body.data;
}

async function memberName(): Promise<string | null> {
  const data = await graphql<{ member?: { name?: { first?: string | null } | null } | null }>("query { member { name { first } } }");
  return data.member?.name?.first?.trim() || null;
}

/** AH bonus weeks run Monday to Sunday (Dutch time). */
export function bonusWeek(at = new Date()) {
  const local = new Date(at.toLocaleString("en-US", { timeZone: "Europe/Amsterdam" }));
  const monday = new Date(Date.UTC(local.getFullYear(), local.getMonth(), local.getDate()));
  monday.setUTCDate(monday.getUTCDate() - ((monday.getUTCDay() + 6) % 7));
  const sunday = new Date(monday);
  sunday.setUTCDate(sunday.getUTCDate() + 6);
  const day = (date: Date) => date.toISOString().slice(0, 10);
  return { start: day(monday), end: day(sunday) };
}

const BONUS_BOX_QUERY = `query FetchBonusBoxOffers($periodStart: String, $periodEnd: String) {
  bonusPersonalPromotionBundles(validOn: $periodStart) { maximumActivations validityPeriod { start end } error }
  bonusPromotions(filterSet: APP_BONUS_BOX, input: { periodStart: $periodStart periodEnd: $periodEnd forcePromotionVisibility: true filterUnavailableProducts: false states: [ACTIVATABLE, ACTIVATED] }) {
    id hqId title subtitle activationStatus periodStart periodEnd productIds
    images { url width }
    price { now { amount } was { amount } label }
    rawPromotionLabels { defaultDescription }
  }
}`;

type GqlPromotion = {
  id?: string | null;
  hqId?: number | null;
  title?: string | null;
  subtitle?: string | null;
  activationStatus?: string | null;
  periodStart?: string | null;
  periodEnd?: string | null;
  productIds?: Array<number | string> | null;
  images?: Array<{ url?: string | null; width?: number | null }> | null;
  price?: { now?: { amount?: number | null } | null; was?: { amount?: number | null } | null; label?: string | null } | null;
  rawPromotionLabels?: Array<{ defaultDescription?: string | null }> | null;
};

type BonusBoxData = {
  bonusPersonalPromotionBundles?: Array<{ maximumActivations?: number | null; validityPeriod?: { start?: string | null; end?: string | null } | null; error?: string | null }> | null;
  bonusPromotions?: GqlPromotion[] | null;
};

function bestImage(images: Array<{ url?: string | null; width?: number | null }> | null | undefined) {
  const list = (images ?? []).filter((image) => image.url);
  return (list.find((image) => image.width === 400) ?? list.slice().sort((a, b) => (b.width ?? 0) - (a.width ?? 0))[0])?.url ?? null;
}

export function mapPromotion(promotion: GqlPromotion, autoLog: string[]): PersonalOffer | null {
  if (!promotion.id || !promotion.title) return null;
  const label = promotion.rawPromotionLabels?.map((entry) => entry.defaultDescription?.trim()).find(Boolean) ?? promotion.price?.label?.trim() ?? null;
  return {
    id: promotion.id,
    hqId: promotion.hqId ?? null,
    source: "BONUS_BOX",
    title: promotion.title.trim(),
    subtitle: promotion.subtitle?.trim() || null,
    dealText: label || null,
    imageUrl: bestImage(promotion.images),
    priceCents: eurosToCents(promotion.price?.now?.amount ?? null),
    regularPriceCents: eurosToCents(promotion.price?.was?.amount ?? null),
    status: promotion.activationStatus ?? "UNKNOWN",
    periodStart: promotion.periodStart ?? null,
    periodEnd: promotion.periodEnd ?? null,
    productIds: (promotion.productIds ?? []).map((id) => productKey("AH", String(id))),
    autoActivated: autoLog.includes(promotion.id),
  };
}

type RestBonusGroup = {
  id?: string | null;
  segmentId?: number | string | null;
  segmentDescription?: string | null;
  discountDescription?: string | null;
  activationStatus?: string | null;
  exampleFromPrice?: number | null;
  exampleForPrice?: number | null;
  images?: Array<{ url?: string | null; width?: number | null }> | null;
  products?: Array<AhProduct & { activationStatus?: string | null }> | null;
};
type RestPersonal = { bonusGroupOrProducts?: Array<{ product?: AhProduct & { activationStatus?: string | null }; bonusGroup?: RestBonusGroup }> | null };

/** The "Persoonlijke Bonus" section (offers AH picked for the card, usually already active). */
export function mapPersonalSection(body: RestPersonal, week: { start: string; end: string }): { offers: PersonalOffer[]; products: AhProduct[] } {
  const offers: PersonalOffer[] = [];
  const products: AhProduct[] = [];
  for (const entry of body.bonusGroupOrProducts ?? []) {
    if (entry.product?.webshopId) {
      const product = entry.product;
      products.push(product);
      const mapped = mapAhProduct(product);
      offers.push({
        id: `p${product.webshopId}`,
        hqId: null,
        source: "PERSONAL",
        title: product.title,
        subtitle: product.salesUnitSize ?? null,
        dealText: product.bonusMechanism?.trim() || null,
        imageUrl: mapped?.imageUrl ?? null,
        priceCents: eurosToCents(product.currentPrice ?? null),
        regularPriceCents: eurosToCents(product.priceBeforeBonus ?? null),
        status: product.activationStatus ?? "ACTIVATED",
        periodStart: product.bonusStartDate ?? week.start,
        periodEnd: product.bonusEndDate ?? week.end,
        productIds: [productKey("AH", String(product.webshopId))],
        autoActivated: false,
      });
    } else if (entry.bonusGroup?.id) {
      const group = entry.bonusGroup;
      const groupProducts = (group.products ?? []).filter((product) => product.webshopId);
      products.push(...groupProducts.filter((product) => product.title));
      offers.push({
        id: `g${group.id}`,
        hqId: null,
        source: "PERSONAL",
        title: group.segmentDescription?.trim() || "Persoonlijke Bonus",
        subtitle: null,
        dealText: group.discountDescription?.trim() || null,
        imageUrl: bestImage(group.images) ?? (groupProducts[0] ? mapAhProduct(groupProducts[0])?.imageUrl ?? null : null),
        priceCents: eurosToCents(group.exampleForPrice ?? null),
        regularPriceCents: eurosToCents(group.exampleFromPrice ?? null),
        status: group.activationStatus ?? "ACTIVATED",
        periodStart: week.start,
        periodEnd: week.end,
        productIds: groupProducts.map((product) => productKey("AH", String(product.webshopId))),
        autoActivated: false,
      });
    }
  }
  return { offers, products };
}

export type PersonalOffersView = {
  connected: boolean;
  period: { start: string; end: string };
  maximumActivations: number | null;
  activatedCount: number;
  /** AH's own reason the Bonus Box is empty (e.g. BONUS_BOX_NOT_FILLED). */
  bonusBoxError: string | null;
  offers: PersonalOffer[];
  fetchedAt: number;
};

const OFFERS_TTL_MS = 15 * 60 * 1000;
let offersCache: PersonalOffersView | null = null;
let inFlight: Promise<PersonalOffersView> | null = null;

/** Restores the last fetched offers after a restart so prices stay personal before the next fetch. */
export function loadPersistedOffers() {
  const saved = getSetting<PersonalOffersView | null>(OFFERS_KEY, null);
  if (!saved || !account() || saved.period.end < bonusWeek().start) return;
  offersCache = saved;
  setPersonalOffers(saved.offers);
}

async function fetchOffers(): Promise<PersonalOffersView> {
  const week = bonusWeek();
  const autoLog = getSetting<string[]>(AUTO_LOG_KEY, []);
  const box = await graphql<BonusBoxData>(BONUS_BOX_QUERY, { periodStart: week.start, periodEnd: week.end });
  const bundle = box.bonusPersonalPromotionBundles?.[0] ?? null;
  const period = { start: bundle?.validityPeriod?.start?.slice(0, 10) || week.start, end: bundle?.validityPeriod?.end?.slice(0, 10) || week.end };
  const offers = (box.bonusPromotions ?? []).map((promotion) => mapPromotion(promotion, autoLog)).filter((offer): offer is PersonalOffer => offer !== null);
  try {
    const rest = await authorized<RestPersonal>(`/mobile-services/bonuspage/v1/personal?bonusStartDate=${period.start}`);
    const section = mapPersonalSection(rest, period);
    const known = new Set(offers.flatMap((offer) => offer.productIds));
    offers.push(...section.offers.filter((offer) => !offer.productIds.every((id) => known.has(id))));
    const mapped = section.products.map(mapAhProduct).filter((product) => product !== null);
    if (mapped.length) upsertProducts(mapped);
  } catch (error) {
    if (error instanceof AhAccountError) throw error;
    console.warn(`[ah-account] personal section failed: ${error instanceof Error ? error.message : error}`);
  }
  await ensureProducts(offers);
  const view: PersonalOffersView = {
    connected: true,
    period,
    maximumActivations: bundle?.maximumActivations ?? null,
    activatedCount: offers.filter((offer) => offer.source === "BONUS_BOX" && offer.status === "ACTIVATED").length,
    bonusBoxError: bundle?.error ?? null,
    offers,
    fetchedAt: Date.now(),
  };
  setSetting(OFFERS_KEY, view);
  setPersonalOffers(offers);
  return view;
}

/** Bonus Box offers only list product ids; fetch the ones the catalogue has not seen so they can be matched and shown. */
async function ensureProducts(offers: PersonalOffer[]) {
  const wanted = Array.from(new Set(offers.flatMap((offer) => offer.productIds.slice(0, 12))));
  const known = new Set(productsByIds(wanted).map((row) => row.id));
  const missing = wanted.filter((id) => !known.has(id)).slice(0, 80);
  if (!missing.length) return;
  const adapter = getAdapter("AH");
  if (!adapter.refresh) return;
  const fetched = await adapter.refresh(missing.map((id) => id.slice(3)));
  if (fetched.length) upsertProducts(fetched);
}

export async function personalOffers(options: { force?: boolean } = {}): Promise<PersonalOffersView> {
  if (!account()) return { connected: false, period: bonusWeek(), maximumActivations: null, activatedCount: 0, bonusBoxError: null, offers: [], fetchedAt: Date.now() };
  if (!options.force && offersCache && Date.now() - offersCache.fetchedAt < OFFERS_TTL_MS && offersCache.period.end >= bonusWeek().start) return offersCache;
  if (!inFlight) inFlight = fetchOffers().then((view) => (offersCache = view)).finally(() => (inFlight = null));
  return inFlight;
}

const ACTIVATE_MUTATION = `mutation ActivatePersonalPromotion($externalId: Int!, $startDate: String!) {
  bonusActivatePersonalPromotion(externalId: $externalId, startDate: $startDate) { status message }
}`;

export async function activateOffer(offerId: string, options: { auto?: boolean } = {}) {
  const view = await personalOffers();
  const offer = view.offers.find((entry) => entry.id === offerId);
  if (!offer) throw new AhAccountError("AH_ERROR", "offer not found");
  if (offer.status === "ACTIVATED") return { status: "ALREADY_ACTIVATED", message: null, offers: view };
  if (offer.hqId == null) throw new AhAccountError("AH_ERROR", "this offer cannot be activated");
  const data = await graphql<{ bonusActivatePersonalPromotion?: { status?: string | null; message?: string | null } | null }>(ACTIVATE_MUTATION, {
    externalId: offer.hqId,
    startDate: offer.periodStart?.slice(0, 10) ?? view.period.start,
  });
  const result = data.bonusActivatePersonalPromotion ?? {};
  console.log(`[ah-account] activate ${offer.id} (${offer.title})${options.auto ? " [auto]" : ""}: ${result.status ?? "?"} ${result.message ?? ""}`);
  if (options.auto) setSetting(AUTO_LOG_KEY, [...getSetting<string[]>(AUTO_LOG_KEY, []), offer.id].slice(-100));
  const refreshed = await personalOffers({ force: true });
  const ok = refreshed.offers.find((entry) => entry.id === offerId)?.status === "ACTIVATED";
  if (!ok && result.status !== "SUCCESS") throw new AhAccountError("AH_ERROR", `AH refused the activation (${result.status ?? "no status"}${result.message ? `: ${result.message}` : ""})`);
  return { status: result.status ?? "SUCCESS", message: result.message ?? null, offers: refreshed };
}

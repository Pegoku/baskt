import type { StoreCode } from "@/stores/types";

/** A store-wide promotion card: either a concrete product on bonus (AH) or a promotion group (Jumbo "1+1 gratis on all Rummo pasta"). */
export type DealCard = {
  store: StoreCode;
  id: string;
  title: string;
  subtitle: string | null;
  dealText: string | null;
  imageUrl: string | null;
  url: string | null;
  priceCents: number | null;
  regularPriceCents: number | null;
  /** Set when the card is a single product we know (can be added directly). */
  productId: string | null;
  validFrom: string | null;
  validUntil: string | null;
  /** "week" = this bonus week only, "season" = runs for several weeks (Sinterklaas, Christmas, Jumbo "Seizoen"). */
  period?: DealPeriod | null;
  /** Highlighted by the store this week (AH "Uit de Bonusfolder", Jumbo "Nu in de aanbieding"). */
  featured?: boolean;
  /** The deal mechanism, read from the deal text (see `classifyDeal`). */
  kind?: DealKind;
  /** Only valid in the webshop ("Alleen online"). */
  onlineOnly?: boolean;
};

/**
 * volume = AH multi-pack "x% volume voordeel", free = 1+1 / 2e gratis, half = 2e halve prijs, multibuy = "2 voor 5,00",
 * percent = "25% korting", price = "voor 1,99" or "1,00 korting", gift = a free extra, other = plain "Bonus" and the rest.
 */
export type DealKind = "volume" | "free" | "half" | "multibuy" | "percent" | "price" | "gift" | "other";

const DEAL_KINDS: Array<[DealKind, RegExp]> = [
  ["volume", /volume\s*voordeel/],
  ["gift", /^gratis\b/],
  ["half", /halve\s*prijs/],
  ["free", /\d+\s*\+\s*\d+\s*gratis|\d+e\s*gratis/],
  ["multibuy", /^\d+\s*voor\s/],
  ["percent", /%\s*korting/],
  ["price", /\bvoor\s*\d|\d\s*korting/],
];

export function dealKind(dealText: string | null | undefined): DealKind {
  const text = (dealText ?? "").toLowerCase().replace(/\s+/g, " ").trim();
  return DEAL_KINDS.find(([, pattern]) => pattern.test(text))?.[0] ?? "other";
}

/** Adds the deal mechanism and the online-only flag, so the app can hide kinds of deals. */
export function classifyDeal(card: DealCard): DealCard {
  return { ...card, kind: dealKind(card.dealText), onlineOnly: /alleen online/i.test(card.dealText ?? "") };
}

export type DealPeriod = "week" | "season";

/** A deal that runs longer than one bonus week counts as seasonal; open-ended ones (multi-packs) are neither. */
export function periodOf(validFrom: string | null, validUntil: string | null): DealPeriod | null {
  if (!validFrom || !validUntil) return null;
  const days = (Date.parse(validUntil.slice(0, 10)) - Date.parse(validFrom.slice(0, 10))) / 86_400_000;
  if (!Number.isFinite(days)) return null;
  return days > 7 ? "season" : "week";
}

/**
 * Decodes Nuxt 3 "devalue" payloads (`<script id="__NUXT_DATA__">`): a flat array where objects and arrays
 * hold indices into the same array. Reactive wrappers like ["Reactive", 12] are unwrapped.
 */
export function decodeDevalue(payload: unknown[]): (index: number) => unknown {
  const cache = new Map<number, unknown>();
  const resolve = (index: number, depth = 0): unknown => {
    if (depth > 40 || index < 0 || index >= payload.length) return null;
    if (cache.has(index)) return cache.get(index);
    const value = payload[index];
    if (Array.isArray(value)) {
      if (value.length === 2 && typeof value[0] === "string" && ["Reactive", "ShallowReactive", "Ref", "ShallowRef", "EmptyRef", "EmptyShallowRef"].includes(value[0])) {
        return resolve(value[1] as number, depth + 1);
      }
      const out: unknown[] = [];
      cache.set(index, out);
      for (const entry of value) out.push(typeof entry === "number" ? resolve(entry, depth + 1) : entry);
      return out;
    }
    if (value && typeof value === "object") {
      const out: Record<string, unknown> = {};
      cache.set(index, out);
      for (const [key, entry] of Object.entries(value as Record<string, unknown>)) out[key] = typeof entry === "number" ? resolve(entry, depth + 1) : entry;
      return out;
    }
    return value;
  };
  return resolve;
}

/** Finds every object in a devalue payload that has all of the given keys. */
export function findDevalueObjects(payload: unknown[], keys: string[]): Record<string, unknown>[] {
  const resolve = decodeDevalue(payload);
  const results: Record<string, unknown>[] = [];
  payload.forEach((value, index) => {
    if (value && typeof value === "object" && !Array.isArray(value) && keys.every((key) => key in (value as Record<string, unknown>))) {
      results.push(resolve(index) as Record<string, unknown>);
    }
  });
  return results;
}

export function extractNuxtData(html: string): unknown[] | null {
  const match = html.match(/<script type="application\/json"[^>]*id="__NUXT_DATA__"[^>]*>([\s\S]*?)<\/script>/);
  if (!match) return null;
  try {
    const parsed = JSON.parse(match[1]);
    return Array.isArray(parsed) ? parsed : null;
  } catch {
    return null;
  }
}

/** Keyword filter used when the user searches inside the deals overview. */
export function matchesQuery(card: DealCard, query: string) {
  const tokens = query.toLowerCase().split(/\s+/).filter(Boolean);
  if (!tokens.length) return true;
  const haystack = `${card.title} ${card.subtitle ?? ""} ${card.dealText ?? ""}`.toLowerCase();
  return tokens.every((token) => haystack.includes(token));
}

/** How many of the search terms a card matches (word-prefix match on title/subtitle); 0 = unrelated. */
export function termRelevance(card: DealCard, terms: string[]) {
  const words = `${card.title} ${card.subtitle ?? ""}`.toLowerCase().normalize("NFKD").replace(/[\u0300-\u036f]/g, "").split(/[^a-z0-9]+/).filter(Boolean);
  let score = 0;
  for (const term of terms) {
    const tokens = term.toLowerCase().split(/\s+/).filter((token) => token.length > 2);
    if (tokens.length && tokens.every((token) => words.some((word) => word.startsWith(token) || token.startsWith(word) && word.length > 3))) score += 1;
  }
  return score;
}

/** Store placeholder "no end date" values (e.g. 2999-12-31) are not real dates. */
export function realDate(value: string | null | undefined) {
  if (!value) return null;
  return /^2[89]\d\d-|^9\d{3}-/.test(value) ? null : value;
}

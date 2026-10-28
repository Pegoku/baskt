import type { ProductRow } from "@/db/schema";

/** One AH personal offer (Bonus Box or "Persoonlijke Bonus") as the app and the matcher see it. */
export type PersonalOffer = {
  id: string;
  /** What bonusActivatePersonalPromotion wants as externalId. */
  hqId: number | null;
  source: "BONUS_BOX" | "PERSONAL";
  title: string;
  subtitle: string | null;
  dealText: string | null;
  imageUrl: string | null;
  priceCents: number | null;
  regularPriceCents: number | null;
  /** ACTIVATED, ACTIVATABLE, or whatever else AH reports (kept verbatim). */
  status: string;
  periodStart: string | null;
  periodEnd: string | null;
  /** Product keys ("AH:123") the offer applies to. */
  productIds: string[];
  autoActivated: boolean;
};

export type PersonalTag = { offerId: string; status: string; source: PersonalOffer["source"]; dealText: string | null };

/** A product row as served to the app: the shelf price, plus what the logged-in AH account gets on it. */
export type PricedRow = ProductRow & { personal?: PersonalTag };

let byProduct = new Map<string, PersonalOffer>();
let all: PersonalOffer[] = [];

/** Replaces the offers the overlay applies; activated ones win when several cover a product. */
export function setPersonalOffers(offers: PersonalOffer[]) {
  const next = new Map<string, PersonalOffer>();
  for (const offer of offers) {
    for (const id of offer.productIds) {
      const existing = next.get(id);
      if (!existing || (existing.status !== "ACTIVATED" && offer.status === "ACTIVATED")) next.set(id, offer);
    }
  }
  byProduct = next;
  all = offers;
}

export function allPersonalOffers() {
  return all;
}

export function personalOfferFor(productId: string) {
  return byProduct.get(productId) ?? null;
}

const PERCENT_OFF = /(\d{1,2})\s*%\s*(korting|off)/i;
const FIXED_PRICE = /^\s*voor\s*€?\s*(\d+)[.,](\d{2})\s*$/i;

/** What one pack costs with an activated offer; null when the mechanism is not a plain price cut (multi-buy etc.). */
export function personalPrice(row: ProductRow, offer: PersonalOffer): number | null {
  const base = row.regularPriceCents && row.regularPriceCents > row.priceCents ? row.regularPriceCents : row.priceCents;
  const text = offer.dealText ?? "";
  const percent = PERCENT_OFF.exec(text);
  if (percent) return Math.round(base * (1 - Number(percent[1]) / 100));
  const fixed = FIXED_PRICE.exec(text);
  if (fixed) return Number(fixed[1]) * 100 + Number(fixed[2]);
  // Single-product offers carry their own price; for groups it is only an example product's.
  if (offer.productIds.length === 1 && offer.priceCents != null) return offer.priceCents;
  return null;
}

/** Applies the account's personal offers to rows read from the catalogue (never written back). */
export function withPersonal<T extends ProductRow>(rows: T[]): Array<T & { personal?: PersonalTag }> {
  if (!byProduct.size) return rows;
  return rows.map((row) => {
    const offer = byProduct.get(row.id);
    if (!offer) return row;
    const personal: PersonalTag = { offerId: offer.id, status: offer.status, source: offer.source, dealText: offer.dealText };
    if (offer.status !== "ACTIVATED") return { ...row, personal };
    const price = personalPrice(row, offer);
    const cheaper = price != null && price > 0 && price < row.priceCents;
    return {
      ...row,
      personal,
      isDeal: true,
      dealText: offer.dealText ?? row.dealText ?? "Persoonlijke Bonus",
      priceCents: cheaper ? price : row.priceCents,
      regularPriceCents: cheaper ? (row.regularPriceCents ?? row.priceCents) : row.regularPriceCents,
      unitPriceCents: cheaper && row.unitPriceCents != null ? Math.round((row.unitPriceCents * price) / row.priceCents) : row.unitPriceCents,
    };
  });
}

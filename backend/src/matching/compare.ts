import type { ParsedIdea, ProductRow } from "@/db/schema";

export type CompareItem = { id: string; text: string; quantity: number; parsed: ParsedIdea | null };
export type CompareMatch = { itemId: string; store: string; product: ProductRow | null; confirmed: boolean };

export type StoreLine = {
  productId: string;
  unitsToBuy: number;
  lineCents: number;
  unitPriceCents: number | null;
  unitPriceUnit: string | null;
  confirmed: boolean;
};

export type Comparison = {
  stores: Array<{
    store: string;
    rank: number;
    fullTotalCents: number;
    comparableTotalCents: number;
    matchedCount: number;
    unconfirmedCount: number;
    missingItemIds: string[];
  }>;
  items: Array<{
    itemId: string;
    text: string;
    cheapestStore: string | null;
    cheapestByUnitPriceStore: string | null;
    packSizeDiffers: boolean;
    perStore: Record<string, StoreLine | null>;
  }>;
  mixAndMatchTotalCents: number;
  missingEverywhere: string[];
  computedAt: number;
};

export function unitsToBuy(item: CompareItem, product: ProductRow) {
  const hint = item.parsed?.sizeHint;
  const packs = hint && product.unitAmount && product.unit === hint.unit ? Math.max(1, Math.ceil(hint.amount / product.unitAmount - 1e-9)) : 1;
  return Math.max(1, item.quantity) * packs;
}

export function compareBasket(items: CompareItem[], matches: CompareMatch[], stores: string[]): Comparison {
  const lines = new Map<string, Map<string, StoreLine | null>>();
  for (const item of items) lines.set(item.id, new Map(stores.map((store) => [store, null])));
  for (const match of matches) {
    const item = items.find((entry) => entry.id === match.itemId);
    if (!item || !stores.includes(match.store) || !match.product) continue;
    const units = unitsToBuy(item, match.product);
    lines.get(item.id)!.set(match.store, {
      productId: match.product.id,
      unitsToBuy: units,
      lineCents: units * match.product.priceCents,
      unitPriceCents: match.product.unitPriceCents,
      unitPriceUnit: match.product.unitPriceUnit,
      confirmed: match.confirmed,
    });
  }

  const everywhere = items.filter((item) => stores.every((store) => lines.get(item.id)!.get(store)));

  const storeSummaries = stores.map((store) => {
    let fullTotal = 0;
    let comparable = 0;
    let matched = 0;
    let unconfirmed = 0;
    const missing: string[] = [];
    for (const item of items) {
      const line = lines.get(item.id)!.get(store);
      if (!line) {
        missing.push(item.id);
        continue;
      }
      matched += 1;
      fullTotal += line.lineCents;
      if (!line.confirmed) unconfirmed += 1;
      if (everywhere.includes(item)) comparable += line.lineCents;
    }
    return { store, rank: 0, fullTotalCents: fullTotal, comparableTotalCents: comparable, matchedCount: matched, unconfirmedCount: unconfirmed, missingItemIds: missing };
  });
  storeSummaries
    .sort((a, b) => a.comparableTotalCents - b.comparableTotalCents || a.missingItemIds.length - b.missingItemIds.length || a.fullTotalCents - b.fullTotalCents)
    .forEach((summary, index) => (summary.rank = index + 1));

  let mixAndMatch = 0;
  const missingEverywhere: string[] = [];
  const itemRows = items.map((item) => {
    const perStore = Object.fromEntries(lines.get(item.id)!.entries());
    const present = Object.entries(perStore).filter(([, line]) => line) as Array<[string, StoreLine]>;
    if (!present.length) {
      missingEverywhere.push(item.id);
      return { itemId: item.id, text: item.text, cheapestStore: null, cheapestByUnitPriceStore: null, packSizeDiffers: false, perStore };
    }
    const cheapest = present.reduce((best, entry) => (entry[1].lineCents < best[1].lineCents ? entry : best));
    mixAndMatch += cheapest[1].lineCents;
    const withUnit = present.filter(([, line]) => line.unitPriceCents != null && line.unitPriceUnit === cheapest[1].unitPriceUnit);
    const cheapestByUnit = withUnit.length ? withUnit.reduce((best, entry) => (entry[1].unitPriceCents! < best[1].unitPriceCents! ? entry : best)) : null;
    const products = present.map(([, line]) => line.productId);
    const packSizeDiffers = present.length > 1 && new Set(present.map(([, line]) => `${line.unitsToBuy}`)).size > 1 ? true : cheapestByUnit ? cheapestByUnit[0] !== cheapest[0] : false;
    void products;
    return {
      itemId: item.id,
      text: item.text,
      cheapestStore: cheapest[0],
      cheapestByUnitPriceStore: cheapestByUnit?.[0] ?? null,
      packSizeDiffers,
      perStore,
    };
  });

  return { stores: storeSummaries, items: itemRows, mixAndMatchTotalCents: mixAndMatch, missingEverywhere, computedAt: Date.now() };
}

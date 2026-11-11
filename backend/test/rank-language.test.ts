import { describe, expect, test } from "bun:test";
import { lexicalRank } from "@/matching/rank";
import { toRow } from "@/stores/search";
import type { StoreProduct } from "@/stores/types";

function product(sourceId: string, title: string, quantityText: string, extra: Partial<StoreProduct> = {}): StoreProduct {
  const pieces = quantityText.match(/^(\d+) stuks$/);
  const grams = quantityText.match(/^(\d+) g$/);
  return {
    store: "AH",
    sourceId,
    title,
    brand: null,
    quantityText,
    unitAmount: pieces ? Number(pieces[1]) : grams ? Number(grams[1]) / 1000 : null,
    unit: pieces ? "piece" : grams ? "kg" : null,
    priceCents: 199,
    regularPriceCents: null,
    unitPriceCents: null,
    unitPriceUnit: null,
    dealText: null,
    isDeal: false,
    imageUrl: null,
    sourceUrl: null,
    category: null,
    available: true,
    ...extra,
  };
}

const volume = (percent: number) => ({ dealText: `${percent}% volume voordeel`, isDeal: true });

describe("ranking an idea in another language", () => {
  test("the Dutch store query lets carrots beat carrot wraps for '2 zanahorias'", () => {
    const parsed = { canonicalName: "zanahoria", attributes: [], sizeHint: { amount: 2, unit: "piece" as const }, queries: { AH: "wortelen" }, fallbackQuery: "wortelen", ambiguous: false };
    const ranked = lexicalRank("2 zanahorias", parsed, [
      toRow(product("k2", "Knorr Wortel wraps met 35% wortel 2-pack", "2 stuks", volume(15))),
      toRow(product("hw", "AH Wortelen", "500 g")),
    ]);
    expect(ranked[0].product.sourceId).toBe("hw");
  });
});

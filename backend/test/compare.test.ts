import { describe, expect, test } from "bun:test";
import type { ProductRow } from "@/db/schema";
import type { Unit } from "@/lib/units";
import { compareBasket, unitsToBuy } from "@/matching/compare";

function product(id: string, store: string, priceCents: number, unitAmount: number | null = 1, unit: Unit | null = "l"): ProductRow {
  return {
    id,
    store,
    sourceId: id,
    title: id,
    brand: null,
    quantityText: `${unitAmount ?? 1} ${unit ?? ""}`,
    unitAmount,
    unit,
    priceCents,
    regularPriceCents: null,
    unitPriceCents: unitAmount ? Math.round(priceCents / unitAmount) : null,
    unitPriceUnit: unit,
    dealText: null,
    isDeal: false,
    imageUrl: null,
    sourceUrl: null,
    category: null,
    available: true,
    fetchedAt: 0,
  };
}

const parsedMilk = { canonicalName: "milk", attributes: [], sizeHint: null, queries: {}, fallbackQuery: null, ambiguous: false };

describe("compareBasket", () => {
  test("ranks stores by comparable total and finds cheapest per item", () => {
    const items = [
      { id: "milk", text: "melk", quantity: 2, parsed: parsedMilk },
      { id: "eggs", text: "eieren", quantity: 1, parsed: null },
    ];
    const result = compareBasket(
      items,
      [
        { itemId: "milk", store: "AH", product: product("ah-milk", "AH", 100), confirmed: true },
        { itemId: "milk", store: "JUMBO", product: product("ju-milk", "JUMBO", 90), confirmed: false },
        { itemId: "eggs", store: "AH", product: product("ah-eggs", "AH", 250, 10, "piece"), confirmed: true },
      ],
      ["AH", "JUMBO"],
    );
    const ah = result.stores.find((store) => store.store === "AH")!;
    const jumbo = result.stores.find((store) => store.store === "JUMBO")!;
    expect(ah.fullTotalCents).toBe(450);
    expect(ah.comparableTotalCents).toBe(200);
    expect(jumbo.fullTotalCents).toBe(180);
    expect(jumbo.comparableTotalCents).toBe(180);
    expect(jumbo.missingItemIds).toEqual(["eggs"]);
    expect(jumbo.unconfirmedCount).toBe(1);
    expect(jumbo.rank).toBe(1);
    expect(result.items[0].cheapestStore).toBe("JUMBO");
    expect(result.items[1].cheapestStore).toBe("AH");
    expect(result.mixAndMatchTotalCents).toBe(180 + 250);
    expect(result.missingEverywhere).toEqual([]);
  });

  test("size hints change the number of packs to buy", () => {
    const item = { id: "milk", text: "2 liter melk", quantity: 1, parsed: { ...parsedMilk, sizeHint: { amount: 2, unit: "l" as const } } };
    expect(unitsToBuy(item, product("a", "AH", 100, 1, "l"))).toBe(2);
    expect(unitsToBuy(item, product("b", "AH", 100, 2, "l"))).toBe(1);
    expect(unitsToBuy(item, product("c", "AH", 100, 0.5, "kg"))).toBe(1);
    const result = compareBasket(
      [item],
      [
        { itemId: "milk", store: "AH", product: product("a", "AH", 100, 1, "l"), confirmed: true },
        { itemId: "milk", store: "JUMBO", product: product("b", "JUMBO", 190, 2, "l"), confirmed: true },
      ],
      ["AH", "JUMBO"],
    );
    expect(result.items[0].perStore.AH?.lineCents).toBe(200);
    expect(result.items[0].perStore.JUMBO?.lineCents).toBe(190);
    expect(result.items[0].cheapestStore).toBe("JUMBO");
    expect(result.items[0].cheapestByUnitPriceStore).toBe("JUMBO");
    expect(result.items[0].packSizeDiffers).toBe(true);
  });

  test("items missing everywhere are reported", () => {
    const result = compareBasket([{ id: "x", text: "unicorn", quantity: 1, parsed: null }], [], ["AH"]);
    expect(result.missingEverywhere).toEqual(["x"]);
    expect(result.stores[0].missingItemIds).toEqual(["x"]);
  });
});

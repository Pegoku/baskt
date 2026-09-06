import { describe, expect, test } from "bun:test";
import { mapAhProduct, type AhSearchResponse } from "@/stores/ah";
import { mapJumboProduct, type JumboSearchResponse } from "@/stores/jumbo";
import ahFixture from "./fixtures/ah/search-halfvolle-melk.json";
import jumboFixture from "./fixtures/jumbo/search-halfvolle-melk.json";

describe("AH mapper", () => {
  const products = ((ahFixture as AhSearchResponse).products ?? []).map(mapAhProduct);
  test("maps every fixture product", () => {
    expect(products.length).toBeGreaterThan(0);
    expect(products.every(Boolean)).toBe(true);
  });
  test("maps price, size and unit price", () => {
    const milk = products.find((product) => product?.title === "AH Houdbare halfvolle melk");
    expect(milk).toBeDefined();
    expect(milk!.store).toBe("AH");
    expect(milk!.priceCents).toBe(85);
    expect(milk!.unitAmount).toBe(1);
    expect(milk!.unit).toBe("l");
    expect(milk!.unitPriceCents).toBe(85);
    expect(milk!.unitPriceUnit).toBe("l");
    expect(milk!.imageUrl).toContain("static.ah.nl");
    expect(milk!.sourceUrl).toContain("/producten/product/wi");
    expect(milk!.available).toBe(true);
    expect(milk!.isDeal).toBe(false);
  });
  test("bonus products expose regular price", () => {
    const mapped = mapAhProduct({ webshopId: 1, title: "Test", salesUnitSize: "1 l", priceBeforeBonus: 2, currentPrice: 1.5, isBonus: true, bonusMechanism: "25% korting" });
    expect(mapped?.priceCents).toBe(150);
    expect(mapped?.regularPriceCents).toBe(200);
    expect(mapped?.dealText).toBe("25% korting");
    expect(mapped?.isDeal).toBe(true);
  });
});

describe("Jumbo mapper", () => {
  const products = ((jumboFixture as JumboSearchResponse).data?.searchProducts?.products ?? []).map(mapJumboProduct);
  test("maps every fixture product", () => {
    expect(products.length).toBeGreaterThan(0);
    expect(products.every(Boolean)).toBe(true);
  });
  test("keeps cents and pack sizes", () => {
    const milk = products.find((product) => product?.title === "Jumbo Verse Halfvolle Melk 1 L");
    expect(milk).toBeDefined();
    expect(milk!.store).toBe("JUMBO");
    expect(milk!.priceCents).toBe(129);
    expect(milk!.unitAmount).toBe(1);
    expect(milk!.unit).toBe("l");
    expect(milk!.unitPriceCents).toBe(129);
    expect(milk!.imageUrl).toStartWith("https://www.jumbo.com/");
    expect(milk!.sourceUrl).toStartWith("https://www.jumbo.com/producten/");
    expect(milk!.brand).toBe("Jumbo");
  });
  test("promo price becomes the current price", () => {
    const mapped = mapJumboProduct({ id: "1", title: "Test", subtitle: "500 g", prices: { price: 300, promoPrice: 200, pricePerUnit: { price: 400, unit: "kg" } }, promotions: [{ tags: [{ text: "2 voor 4" }] }] });
    expect(mapped?.priceCents).toBe(200);
    expect(mapped?.regularPriceCents).toBe(300);
    expect(mapped?.isDeal).toBe(true);
    expect(mapped?.dealText).toBe("2 voor 4");
    expect(mapped?.unitPriceCents).toBe(400);
  });
});

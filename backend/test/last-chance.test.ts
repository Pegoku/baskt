import { describe, expect, test } from "bun:test";
import { labelDate, mapBargain } from "@/stores/ah-last-chance";

describe("AH last chance bargains", () => {
  test("reads AH's date labels", () => {
    expect(labelDate("Houdbaarheid: 22/11/2026")).toBe("2026-11-22");
    expect(labelDate("Uit assortiment: 3/1/2027")).toBe("2027-01-03");
    expect(labelDate(null)).toBeNull();
  });

  test("maps an expiring markdown", () => {
    const item = mapBargain({
      categoryTitle: "Groente, aardappelen",
      bestBeforeDateLabel: "Houdbaarheid: 22/11/2026",
      markdown: { markdownType: "EXPIRATION" },
      bargainPrice: { priceNow: "0.81", priceWas: "2.69" },
      dynamicMarkDownPromotionLabel: { title: "nog 5 met", topText: "70% korting", centerText: null, bottomText: null },
      product: { id: 185200, title: "AH Gemengde salade", brand: "AH", salesUnitSize: "150 g", category: "Groente, aardappelen/Sla", imagePack: [{ medium: { url: "https://static.ah.nl/x.webp" } }] },
    });
    expect(item).toEqual({
      productId: "AH:185200",
      title: "AH Gemengde salade",
      brand: "AH",
      quantityText: "150 g",
      imageUrl: "https://static.ah.nl/x.webp",
      category: "Groente, aardappelen",
      reason: "EXPIRATION",
      date: "2026-11-22",
      discountPercent: 70,
      discountText: "70% korting",
      left: 5,
      priceCents: 81,
      regularPriceCents: 269,
    });
  });

  test("maps a product leaving the range and skips entries without a product", () => {
    const item = mapBargain({
      bestBeforeDateLabel: "Uit assortiment: 22/11/2026",
      markdown: { markdownType: "OUT_OF_ASSORTMENT" },
      bargainPrice: { priceNow: "1.00", priceWas: "2.00" },
      dynamicMarkDownPromotionLabel: { title: "nu met", topText: "50% extra korting" },
      product: { id: 1, title: "Thee", category: "Koffie, thee/Thee" },
    });
    expect(item?.reason).toBe("OUT_OF_ASSORTMENT");
    expect(item?.left).toBeNull();
    expect(item?.discountPercent).toBe(50);
    expect(item?.category).toBe("Koffie, thee");
    expect(mapBargain({ product: null })).toBeNull();
  });
});

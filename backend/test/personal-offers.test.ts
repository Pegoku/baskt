import { afterEach, describe, expect, test } from "bun:test";
import type { ProductRow } from "@/db/schema";
import { bonusWeek, extractCode, mapPersonalSection, mapPromotion } from "@/stores/ah-account";
import { setPersonalOffers, withPersonal, type PersonalOffer } from "@/stores/personal-overlay";

const row = (id: string, priceCents: number, extra: Partial<ProductRow> = {}): ProductRow => ({
  id, store: "AH", sourceId: id.slice(3), title: "Product", brand: null, quantityText: "500 g", unitAmount: 0.5, unit: "kg",
  priceCents, regularPriceCents: null, unitPriceCents: priceCents * 2, unitPriceUnit: "kg", dealText: null, isDeal: false,
  imageUrl: null, sourceUrl: null, category: null, available: true, fetchedAt: 0, ...extra,
});

const offer = (extra: Partial<PersonalOffer>): PersonalOffer => ({
  id: "1", hqId: 10, source: "BONUS_BOX", title: "Offer", subtitle: null, dealText: null, imageUrl: null, priceCents: null,
  regularPriceCents: null, status: "ACTIVATED", periodStart: "2026-10-26", periodEnd: "2026-11-01", productIds: ["AH:1"], autoActivated: false, ...extra,
});

afterEach(() => setPersonalOffers([]));

describe("personal overlay", () => {
  test("percent off lowers the price of an activated offer's products", () => {
    setPersonalOffers([offer({ dealText: "25% korting", productIds: ["AH:1", "AH:2"] })]);
    const [priced] = withPersonal([row("AH:1", 400)]);
    expect(priced.priceCents).toBe(300);
    expect(priced.regularPriceCents).toBe(400);
    expect(priced.unitPriceCents).toBe(600);
    expect(priced.isDeal).toBe(true);
    expect(priced.personal?.status).toBe("ACTIVATED");
  });

  test("activatable offers only tag the product", () => {
    setPersonalOffers([offer({ status: "ACTIVATABLE", dealText: "25% korting" })]);
    const [priced] = withPersonal([row("AH:1", 400)]);
    expect(priced.priceCents).toBe(400);
    expect(priced.isDeal).toBe(false);
    expect(priced.personal?.status).toBe("ACTIVATABLE");
  });

  test("multi-buy keeps the shelf price and passes the mechanism on", () => {
    setPersonalOffers([offer({ dealText: "2 VOOR 3.00", priceCents: 150, productIds: ["AH:1", "AH:2"] })]);
    const [priced] = withPersonal([row("AH:1", 229)]);
    expect(priced.priceCents).toBe(229);
    expect(priced.dealText).toBe("2 VOOR 3.00");
  });

  test("single-product offers use their own price", () => {
    setPersonalOffers([offer({ dealText: "Bonus Box", priceCents: 199 })]);
    expect(withPersonal([row("AH:1", 279)])[0].priceCents).toBe(199);
    expect(withPersonal([row("AH:9", 279)])[0].personal).toBeUndefined();
  });
});

describe("ah account helpers", () => {
  test("login code from the redirect or bare", () => {
    expect(extractCode("appie://login-exit?code=abc%2D123&state=x")).toBe("abc-123");
    expect(extractCode(" abc ")).toBe("abc");
  });

  test("bonus week runs Monday to Sunday", () => {
    expect(bonusWeek(new Date("2026-10-28T12:00:00Z"))).toEqual({ start: "2026-10-26", end: "2026-11-01" });
    expect(bonusWeek(new Date("2026-11-01T21:00:00Z"))).toEqual({ start: "2026-10-26", end: "2026-11-01" });
    expect(bonusWeek(new Date("2026-11-01T23:30:00Z"))).toEqual({ start: "2026-11-02", end: "2026-11-08" });
  });

  test("maps a Bonus Box promotion", () => {
    const mapped = mapPromotion(
      { id: "385568", hqId: 894018, title: "Ben & Jerry's", activationStatus: "ACTIVATABLE", productIds: [123, "456"], rawPromotionLabels: [{ defaultDescription: "VOOR 2.00" }], price: { now: { amount: 2 }, was: { amount: 3.49 } }, images: [{ url: "a", width: 200 }, { url: "b", width: 400 }] },
      ["385568"],
    );
    expect(mapped).toMatchObject({ id: "385568", hqId: 894018, dealText: "VOOR 2.00", priceCents: 200, regularPriceCents: 349, productIds: ["AH:123", "AH:456"], imageUrl: "b", autoActivated: true });
  });

  test("maps the Persoonlijke Bonus section", () => {
    const { offers, products } = mapPersonalSection(
      {
        bonusGroupOrProducts: [
          { product: { webshopId: 7, title: "Melk", currentPrice: 1, priceBeforeBonus: 1.3, bonusMechanism: "25% korting", isBonus: true } },
          { bonusGroup: { id: "55", segmentDescription: "Alle kaas", discountDescription: "2e halve prijs", products: [{ webshopId: 8, title: "Kaas" }] } },
        ],
      },
      { start: "2026-10-26", end: "2026-11-01" },
    );
    expect(offers.map((entry) => [entry.id, entry.status, entry.productIds])).toEqual([["p7", "ACTIVATED", ["AH:7"]], ["g55", "ACTIVATED", ["AH:8"]]]);
    expect(products).toHaveLength(2);
  });
});

describe("ah receipts", () => {
  test("discounts land on the product they name; unknown ones stay a line of their own", async () => {
    const { receiptLines } = await import("@/stores/ah-receipts");
    const tissues = { id: "AH:632", title: "AH Tissues zacht & sterk" } as ProductRow;
    const lines = receiptLines(
      {
        products: [
          { id: 10636, quantity: 2, name: "AH TISSUES", price: { amount: 1.19 }, amount: { amount: 2.38 } },
          { id: 1, quantity: 1, name: "STOKBROOD", price: null, amount: { amount: 1.39 } },
        ],
        discounts: [{ type: "BBOX", name: "AH Tissues", amount: { amount: -0.6 } }, { type: "BONUS", name: "MBSPECIALITE", amount: { amount: -0.58 } }],
      },
      new Map([[10636, tissues]]),
    );
    expect(lines.map((line) => [line.name, line.totalPriceCents, line.dealText, line.productId])).toEqual([
      ["AH Tissues zacht & sterk", 178, "Bonus Box", "AH:632"],
      ["STOKBROOD", 139, null, null],
      ["Bonus: MBSPECIALITE", -58, "Bonus", null],
    ]);
  });
});

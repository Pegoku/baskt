import { describe, expect, test } from "bun:test";
import { parseQuantity, parseUnitPriceDescription, unitPriceFrom, type Unit } from "@/lib/units";

describe("parseQuantity", () => {
  const cases: Array<[string, { amount: number; unit: Unit } | null]> = [
    ["1 l", { amount: 1, unit: "l" }],
    ["1,5 liter", { amount: 1.5, unit: "l" }],
    ["500 g", { amount: 0.5, unit: "kg" }],
    ["ca. 900 g", { amount: 0.9, unit: "kg" }],
    ["1 kg", { amount: 1, unit: "kg" }],
    ["6 stuks", { amount: 6, unit: "piece" }],
    ["2 x 250 g", { amount: 0.5, unit: "kg" }],
    ["6 x 1 L", { amount: 6, unit: "l" }],
    ["330 ml", { amount: 0.33, unit: "l" }],
    ["per stuk", { amount: 1, unit: "piece" }],
    ["10", { amount: 10, unit: "piece" }],
    ["", null],
  ];
  for (const [input, expected] of cases) {
    test(`"${input}"`, () => {
      const parsed = parseQuantity(input);
      if (!expected) expect(parsed).toBeNull();
      else {
        expect(parsed?.unit).toBe(expected.unit);
        expect(parsed?.amount).toBeCloseTo(expected.amount, 5);
      }
    });
  }
});

describe("unit prices", () => {
  test("parses AH unitPriceDescription", () => {
    expect(parseUnitPriceDescription("prijs per liter €0.85")).toEqual({ cents: 85, unit: "l" });
    expect(parseUnitPriceDescription("prijs per kilo €12,50")).toEqual({ cents: 1250, unit: "kg" });
    expect(parseUnitPriceDescription("prijs per stuk €0.30")).toEqual({ cents: 30, unit: "piece" });
    expect(parseUnitPriceDescription(null)).toBeNull();
  });
  test("derives unit price from pack size", () => {
    expect(unitPriceFrom(250, { amount: 0.5, unit: "kg" })).toEqual({ cents: 500, unit: "kg" });
    expect(unitPriceFrom(250, null)).toBeNull();
  });
});

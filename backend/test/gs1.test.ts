import { describe, expect, test } from "bun:test";
import { parseScannedCode } from "@/lib/gs1";

describe("scanned codes", () => {
  test("plain EAN-13 and UPC", () => {
    expect(parseScannedCode("8718907471534")).toEqual({ gtin: "8718907471534", priceCents: null, weightGrams: null, variable: false });
    expect(parseScannedCode("711144224855")?.gtin).toBe("711144224855");
  });

  test("in-store weighed EAN-13 starting with 2", () => {
    expect(parseScannedCode("2301234005994")).toMatchObject({ gtin: "2301234005994", variable: true });
  });

  test("GS1 DataMatrix on fresh meat: GTIN, weight and price", () => {
    const code = parseScannedCode("]d2010871234567890131030004521700105\u001d10LOT42\u001d3922000699");
    expect(code).toEqual({ gtin: "8712345678901", priceCents: 699, weightGrams: 452, variable: false });
  });

  test("human-readable element string and digital link", () => {
    expect(parseScannedCode("(01)08712345678901(3922)000499")).toMatchObject({ gtin: "8712345678901", priceCents: 499 });
    expect(parseScannedCode("https://id.gs1.org/01/08712345678901/10/ABC")?.gtin).toBe("8712345678901");
  });

  test("garbage", () => {
    expect(parseScannedCode("hello")).toBeNull();
    expect(parseScannedCode("123")).toBeNull();
  });
});

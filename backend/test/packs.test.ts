import { describe, expect, test } from "bun:test";
import { cleanAhTitle } from "@/stores/ah";
import { foldMultipacks, multipackOf, packsFor } from "@/matching/packs";
import { toRow, upsertProducts } from "@/stores/search";
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

describe("multi-packs", () => {
  test("AH's doubled brand is dropped from multi-pack titles", () => {
    expect(cleanAhTitle("Unox Unox Pittige erwtensoep 2-pack", "Unox")).toBe("Unox Pittige erwtensoep 2-pack");
    expect(cleanAhTitle("AH Wortelen", "AH")).toBe("AH Wortelen");
  });

  test("only packs of another product count as multi-packs", () => {
    expect(multipackOf(toRow(product("1", "Knorr Wortel wraps 2-pack", "2 stuks", volume(15))))?.count).toBe(2);
    expect(multipackOf(toRow(product("2", "AH Gelderse gekookte worst 4 pck", "4 stuks", volume(10))))?.count).toBe(4);
    expect(multipackOf(toRow(product("3", "AH Houdbare halfvolle melk 6-pack", "6 x 200 ml")))?.count).toBe(6);
    // A 600 g bag that happens to be called a 3-pack is a product of its own.
    expect(multipackOf(toRow(product("4", "AH Doperwt wortel 3-pack", "600 g")))).toBeNull();
  });

  test("packs fold into their single item, which lists them as volume deals", async () => {
    const rows = upsertProducts([
      product("p2", "Knorr Wortel wraps 2-pack", "2 stuks", volume(15)),
      product("w1", "AH Wortelen", "500 g"),
      product("p3", "Knorr Wortel wraps 3-pack", "3 stuks", volume(20)),
      product("k1", "Knorr Wortel wraps", "1 stuks"),
      product("s3", "AH AH Snoepgroente worteltjes 3-pack", "3 stuks", volume(10)),
      product("s4", "AH Snoepgroente worteltjes 4-pack", "4 stuks", volume(12)),
    ]);
    const folded = await foldMultipacks("AH", rows, { lookups: 0 });
    // Wraps: the single was listed, so both packs go. Snoepgroente: no single known, the smallest pack stands in.
    expect(folded.map((row) => row.sourceId)).toEqual(["w1", "k1", "s3"]);
    const packs = packsFor(rows.filter((row) => row.sourceId === "k1" || row.sourceId === "w1"));
    expect(packs["AH:k1"].map((row) => row.sourceId)).toEqual(["p2", "p3"]);
    expect(packs["AH:w1"]).toBeUndefined();
  });
});

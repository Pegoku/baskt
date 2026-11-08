import { describe, expect, test } from "bun:test";
import { categoryFromStore } from "@/stock-categories";

describe("categoryFromStore", () => {
  test("folds AH aisles onto stock groups", () => {
    expect(categoryFromStore("Groente, aardappelen / Ui")).toBe("produce");
    expect(categoryFromStore("Fruit, verse sappen / Bananen")).toBe("produce");
    expect(categoryFromStore("Zuivel, eieren / Kookroom")).toBe("dairy");
    expect(categoryFromStore("Kaas / Jong belegen")).toBe("dairy");
    expect(categoryFromStore("Vleeswaren / Ham (voorverpakt)")).toBe("meat");
    expect(categoryFromStore("Vis / Tonijn in olijfolie")).toBe("meat");
    expect(categoryFromStore("Koek, snoep, chocolade / Koekjes met chocolade")).toBe("snacks");
    expect(categoryFromStore("Drogisterij / Deospray mannen")).toBe("household");
    expect(categoryFromStore("Maaltijden, salades / Pizzabodem (vers)")).toBe("meals");
    expect(categoryFromStore("Diepvries / IJs")).toBe("frozen");
  });

  test("splits spices and oils out of the soups-and-sauces aisle", () => {
    expect(categoryFromStore("Soepen, sauzen, kruiden, olie / Zwarte peper")).toBe("spices");
    expect(categoryFromStore("Soepen, sauzen, kruiden, olie / Olijfolie extra vierge")).toBe("spices");
    expect(categoryFromStore("Soepen, sauzen, kruiden, olie / Tafelzout, keukenzout")).toBe("spices");
    expect(categoryFromStore("Soepen, sauzen, kruiden, olie / Soep in blik")).toBe("pantry");
    expect(categoryFromStore("Pasta, rijst, wereldkeuken / Tomatenblokjes")).toBe("pantry");
  });

  test("folds Jumbo's top-level aisles", () => {
    expect(categoryFromStore("Aardappelen, groente en fruit")).toBe("produce");
    expect(categoryFromStore("Ontbijt, broodbeleg en bakproducten")).toBe("breakfast");
    expect(categoryFromStore("Brood en gebak")).toBe("bakery");
    expect(categoryFromStore("Vleeswaren, kaas en tapas")).toBe("meat");
    expect(categoryFromStore("Wereldkeukens, kruiden, pasta en rijst")).toBe("pantry");
    expect(categoryFromStore("Koek, snoep, chocolade en chips")).toBe("snacks");
    expect(categoryFromStore("Huishouden en dieren")).toBe("household");
  });

  test("leaves unknown aisles unsorted", () => {
    expect(categoryFromStore(null)).toBeNull();
    expect(categoryFromStore("Bijzondere promoties")).toBeNull();
  });
});

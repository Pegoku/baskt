import { describe, expect, test } from "bun:test";
import { extractRecipe, extractSteps, looksLikeRecipe, parseServings, scaleLine, scaleLines } from "@/matching/recipes";

describe("recipes", () => {
  test("extracts schema.org Recipe data from an Allerhande page", async () => {
    const html = await Bun.file(`${import.meta.dir}/fixtures/recipes/allerhande-cookies.html`).text();
    const recipe = extractRecipe(html, "https://www.ah.nl/allerhande/recept/x");
    expect(recipe?.title).toBe("Chocolate chip barbe-cookies");
    expect(recipe?.servings).toBe("8");
    expect(recipe?.ingredientLines).toHaveLength(8);
    expect(recipe?.ingredientLines[0]).toBe("80 g ongezouten roomboter");
    expect(recipe?.imageUrl).toContain("static.ah.nl");
    expect(recipe?.steps).toHaveLength(7);
    expect(recipe?.steps?.[0].text).toStartWith("Laat de boter");
    expect(extractRecipe("<html></html>", "u")).toBeNull();
  });

  test("flattens instruction shapes into steps", () => {
    expect(extractSteps("Mix.\nBake.")).toEqual([{ text: "Mix.", imageUrl: null }, { text: "Bake.", imageUrl: null }]);
    expect(extractSteps([{ "@type": "HowToSection", itemListElement: [{ "@type": "HowToStep", text: "Chop", image: { url: "https://x/1.jpg" } }] }])).toEqual([{ text: "Chop", imageUrl: "https://x/1.jpg" }]);
  });

  test("scales ingredient amounts to another number of servings", () => {
    expect(scaleLine("160 g tarwebloem", 1.5)).toBe("240 g tarwebloem");
    expect(scaleLine("0.25 tl baksoda", 2)).toBe("0,5 tl baksoda");
    expect(scaleLine("1 scharrelei", 3)).toBe("3 scharrelei");
    expect(scaleLine("½ citroen", 2)).toBe("1 citroen");
    expect(scaleLine("2-3 tenen knoflook", 2)).toBe("4-6 tenen knoflook");
    expect(scaleLine("snufje zout", 2)).toBe("snufje zout");
    expect(scaleLines(["100 g suiker"], 4, 4)).toEqual(["100 g suiker"]);
    expect(scaleLines(["100 g suiker"], 4, 2)).toEqual(["50 g suiker"]);
    expect(parseServings("8")).toBe(8);
    expect(parseServings("4 personen")).toBe(4);
    expect(parseServings(null)).toBeNull();
  });

  test("detects recipe intent in mixed languages", () => {
    expect(looksLikeRecipe("ingredients for chocolate cookies")).toBe(true);
    expect(looksLikeRecipe("alles voor lasagne")).toBe(true);
    expect(looksLikeRecipe("receta de paella")).toBe(true);
    expect(looksLikeRecipe("halfvolle melk")).toBe(false);
    expect(looksLikeRecipe("cookies")).toBe(false);
  });
});

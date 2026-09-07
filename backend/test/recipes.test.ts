import { describe, expect, test } from "bun:test";
import { extractRecipe, looksLikeRecipe } from "@/matching/recipes";

describe("recipes", () => {
  test("extracts schema.org Recipe data from an Allerhande page", async () => {
    const html = await Bun.file(`${import.meta.dir}/fixtures/recipes/allerhande-cookies.html`).text();
    const recipe = extractRecipe(html, "https://www.ah.nl/allerhande/recept/x");
    expect(recipe?.title).toBe("Chocolate chip barbe-cookies");
    expect(recipe?.servings).toBe("8");
    expect(recipe?.ingredientLines).toHaveLength(8);
    expect(recipe?.ingredientLines[0]).toBe("80 g ongezouten roomboter");
    expect(extractRecipe("<html></html>", "u")).toBeNull();
  });

  test("detects recipe intent in mixed languages", () => {
    expect(looksLikeRecipe("ingredients for chocolate cookies")).toBe(true);
    expect(looksLikeRecipe("alles voor lasagne")).toBe(true);
    expect(looksLikeRecipe("receta de paella")).toBe(true);
    expect(looksLikeRecipe("halfvolle melk")).toBe(false);
    expect(looksLikeRecipe("cookies")).toBe(false);
  });
});

import { mapAhDetails } from "@/stores/ah";
import { describe, expect, test } from "bun:test";
import { completeTranslation, translateContent } from "@/matching/translate";
import { parseProductDetails } from "@/stores/details";
import { localizeRecipe } from "@/matching/localize";

describe("source-preserving content", () => {
  test("rejects incomplete translations and empty required fields", () => {
    expect(completeTranslation(["Milk"], ["Melk", "1 litre"])).toBe(false);
    expect(completeTranslation(["Milk", ""], ["Melk", "1 litre"])).toBe(false);
    expect(completeTranslation(["Milk", ""], ["Melk", ""])).toBe(true);
    expect(completeTranslation(["Milk", 4], ["Melk", "1 litre"])).toBe(false);
  });
  test("unavailable AI preserves source and reports no translation", async () => {
    expect(await translateContent(["250 g bloem"], "en")).toEqual({ texts: ["250 g bloem"], language: "en", translated: false });
    const source = { title: "Koekjes", sourceUrl: null, servings: "4", ingredientLines: ["250 g bloem"], steps: [{ text: "Bak 20 minuten op 180°C.", imageUrl: "https://example.com/step.jpg" }] };
    const result = await localizeRecipe(source);
    expect(result.original).toEqual(source);
    expect(result.steps).toEqual(source.steps);
    expect(result.translationAvailable).toBe(false);
    expect(result.language).toBeNull();
  });
  test("AH detail fields include source highlights and full description", () => {
    const result = mapAhDetails({ productCard: {
      descriptionHighlights: "<p>Halfvolle melk.</p>", descriptionFull: "Koel bewaren na openen.",
      extraDescriptions: [{ description: "Bevat melk." }],
      images: [{ width: 100, url: "https://example.com/small.jpg" }, { width: 800, url: "https://example.com/large.jpg" }],
    } });
    expect(result.description).toContain("Halfvolle melk.");
    expect(result.description).toContain("Koel bewaren na openen.");
    expect(result.description).toContain("Bevat melk.");
    expect(result.imageUrls).toEqual(["https://example.com/large.jpg"]);
  });
  test("extracts real descriptions and images without unrelated site content", () => {
    const html = `<script type="application/ld+json">broken</script><script type="application/ld+json">${JSON.stringify({ "@graph": [
      { "@type": "WebSite", description: "Do not use this" },
      { "@type": "Product", description: "<p>Melk &amp; cacao.</p><p>Bevat melk.</p>", image: ["https://example.com/front.jpg", { url: "https://example.com/back.jpg" }] },
    ] })}</script>`;
    expect(parseProductDetails(html)).toEqual({ description: "Melk & cacao.\nBevat melk.", imageUrls: ["https://example.com/front.jpg", "https://example.com/back.jpg"] });
    expect(parseProductDetails("<html>Blocked</html>").description).toBeNull();
  });
});

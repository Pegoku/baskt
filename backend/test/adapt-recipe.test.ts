import { describe, expect, test } from "bun:test";
import { cleanDraft } from "@/matching/cook";

describe("cleanDraft", () => {
  test("keeps well-formed fields, step photos and plain-string steps", () => {
    expect(cleanDraft({ title: " Ensalada de pasta ", servings: 4, ingredientLines: ["200 g pasta", " ", 3], steps: ["Cuece la pasta.", { text: "Mezcla.", imageUrl: "https://x/1.jpg" }, ""] })).toEqual({
      title: "Ensalada de pasta", description: null, servings: "4", ingredientLines: ["200 g pasta"],
      steps: [{ text: "Cuece la pasta.", imageUrl: null }, { text: "Mezcla.", imageUrl: "https://x/1.jpg" }],
    });
  });
  test("rejects answers without a title or ingredients", () => {
    expect(cleanDraft(null)).toBeNull();
    expect(cleanDraft({ title: "", ingredientLines: ["a"] })).toBeNull();
    expect(cleanDraft({ title: "x", ingredientLines: [] })).toBeNull();
  });
});

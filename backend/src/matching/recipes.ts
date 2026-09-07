import { chatJson } from "@/ai/client";
import { appLanguageName } from "@/db/settings";
import { aiConfigured, env } from "@/env";
import { fetchWithRetry } from "@/lib/http";
import { tokenize } from "@/lib/text";

const BROWSER_UA = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/145.0.0.0 Safari/537.36";
const ALLERHANDE = "https://www.ah.nl";

export type Recipe = {
  title: string;
  sourceUrl: string | null;
  servings: string | null;
  /** Raw ingredient lines as published, e.g. "160 g tarwebloem". */
  ingredientLines: string[];
};

export type RecipeItem = { text: string; quantity: number; staple: boolean };

/** Words that signal "I want the ingredients for a dish" in the languages the user mixes. */
export const RECIPE_INTENT = /\b(ingredi[eë]nt\w*|ingredientes?|recipe|recept\w*|receta|to make|for making|om te maken|para hacer|alles voor|everything for|todo para)\b/i;

export function looksLikeRecipe(text: string) {
  return RECIPE_INTENT.test(text);
}

function extractRecipe(html: string, url: string): Recipe | null {
  for (const match of html.matchAll(/<script[^>]*type="application\/ld\+json"[^>]*>([\s\S]*?)<\/script>/gi)) {
    let data: unknown;
    try {
      data = JSON.parse(match[1]);
    } catch {
      continue;
    }
    const nodes = Array.isArray(data) ? data : [data, ...(((data as { "@graph"?: unknown[] })["@graph"] ?? []) as unknown[])];
    for (const node of nodes) {
      if (!node || typeof node !== "object") continue;
      const record = node as { "@type"?: unknown; name?: unknown; recipeYield?: unknown; recipeIngredient?: unknown };
      const type = Array.isArray(record["@type"]) ? record["@type"].join(",") : String(record["@type"] ?? "");
      if (!type.includes("Recipe") || !Array.isArray(record.recipeIngredient)) continue;
      const lines = record.recipeIngredient.filter((line): line is string => typeof line === "string" && line.trim().length > 0).map((line) => line.trim());
      if (!lines.length) continue;
      const servings = Array.isArray(record.recipeYield) ? record.recipeYield[0] : record.recipeYield;
      return {
        title: (typeof record.name === "string" ? record.name.trim() : "Recipe").replace(/\s*[-|–]\s*Allerhande\s*$/i, ""),
        sourceUrl: url,
        servings: servings != null ? String(servings) : null,
        ingredientLines: lines,
      };
    }
  }
  return null;
}

export { extractRecipe };

function scoreSlug(slug: string, wanted: string[]) {
  const slugTokens = slug.split("/").pop()?.split("-") ?? [];
  const hits = wanted.filter((token) => slugTokens.includes(token)).length;
  // Prefer high overlap, then shorter slugs (plainer recipes).
  return hits * 10 - slugTokens.length * 0.1;
}

/** Searches AH Allerhande (schema.org Recipe pages) for the dish and returns the best matching recipe. */
export async function findRecipeOnline(dishNl: string, dishAlt?: string): Promise<Recipe | null> {
  if (!env.recipeLookup) return null;
  try {
    const search = await fetchWithRetry(
      `${ALLERHANDE}/allerhande/recepten-zoeken?query=${encodeURIComponent(dishNl)}`,
      { headers: { "user-agent": BROWSER_UA, accept: "text/html" } },
      { retries: 1 },
    );
    const html = await search.text();
    const slugs = Array.from(new Set(html.match(/\/allerhande\/recept\/R-R\d+\/[a-z0-9-]+/g) ?? []));
    if (!slugs.length) return null;
    const wanted = Array.from(new Set([...tokenize(dishNl), ...tokenize(dishAlt ?? "")]));
    const ranked = slugs.map((slug) => ({ slug, score: scoreSlug(slug, wanted) })).sort((a, b) => b.score - a.score);
    for (const candidate of ranked.slice(0, 3)) {
      if (candidate.score <= 0) break;
      const url = `${ALLERHANDE}${candidate.slug}`;
      const page = await fetchWithRetry(url, { headers: { "user-agent": BROWSER_UA, accept: "text/html" } }, { retries: 1 });
      const recipe = extractRecipe(await page.text(), url);
      if (recipe) return recipe;
    }
  } catch (error) {
    console.warn(`[recipes] lookup failed for "${dishNl}": ${error instanceof Error ? error.message : error}`);
  }
  return null;
}

export type RecipeIntent = { dish: string; dishNl: string; servings: number | null };

/** "ingredients for chocolate cookies" → the dish name (user language) and its Dutch search term. */
export async function detectRecipeIntent(text: string): Promise<RecipeIntent> {
  const fallback = { dish: text.replace(RECIPE_INTENT, "").replace(/\b(for|voor|para|de|the|of)\b/gi, " ").replace(/\s+/g, " ").trim() || text, dishNl: "", servings: null };
  if (!aiConfigured()) return { ...fallback, dishNl: fallback.dish };
  const raw = await chatJson<{ dish?: string; dishNl?: string; servings?: number | null }>(
    [
      {
        role: "system",
        content: `The user wants the ingredients for a dish. Return ONLY {"dish": dish name in ${appLanguageName()}, "dishNl": the dish name as Dutch people search for it on a recipe site (e.g. "chocolate chip cookies", "lasagne", "pannenkoeken"), "servings": number of people if stated else null}.`,
      },
      { role: "user", content: text },
    ],
    { maxTokens: 150 },
  );
  return {
    dish: raw?.dish?.trim() || fallback.dish,
    dishNl: raw?.dishNl?.trim() || fallback.dish,
    servings: typeof raw?.servings === "number" ? raw.servings : null,
  };
}

/** Turns raw ingredient lines into shopping-list items in the user's language. */
export async function ingredientsToItems(lines: string[], dish: string): Promise<RecipeItem[]> {
  const fallback = lines.filter((line) => !/^water$/i.test(line.trim())).map((line) => ({ text: line, quantity: 1, staple: false }));
  if (!aiConfigured() || !lines.length) return fallback;
  const raw = await chatJson<{ items?: Array<{ text?: string; quantity?: number; staple?: boolean }> }>(
    [
      {
        role: "system",
        content: `Convert recipe ingredient lines into supermarket shopping-list items written in ${appLanguageName()}.
Return ONLY {"items":[{"text": product to buy with the amount when it matters (e.g. "flour 500 g", "eggs 2", "dark chocolate 200 g"), "quantity": 1, "staple": true when it is a pantry staple most kitchens already have (salt, pepper, water, sugar, oil, flour, baking soda)}]}.
Merge duplicates, drop water, keep the order of the recipe. Translate carefully (Dutch: ei/eieren = egg(s), bloem = flour, boter = butter, suiker = sugar, room = cream, ui = onion, knoflook = garlic); when unsure of a word keep the original Dutch word instead of guessing.`,
      },
      { role: "user", content: `Dish: ${dish}\nIngredients:\n${lines.join("\n")}` },
    ],
    { maxTokens: 1200 },
  );
  const items = (raw?.items ?? [])
    .filter((item) => typeof item.text === "string" && item.text.trim())
    .map((item) => ({ text: item.text!.trim(), quantity: Number.isInteger(item.quantity) && item.quantity! > 0 ? item.quantity! : 1, staple: Boolean(item.staple) }));
  return items.length ? items : fallback;
}

/** Ingredient list from the model's own knowledge, used when no recipe page is found. */
export async function ingredientsFromKnowledge(dish: string): Promise<RecipeItem[]> {
  if (!aiConfigured()) return [];
  const raw = await chatJson<{ items?: Array<{ text?: string; quantity?: number; staple?: boolean }> }>(
    [
      {
        role: "system",
        content: `List the shopping-list ingredients for a home-cooked dish, in ${appLanguageName()}. Return ONLY {"items":[{"text": product with amount when it matters, "quantity": 1, "staple": true for pantry staples like salt, pepper, oil, sugar, flour}]}. 6-15 items, in cooking order.`,
      },
      { role: "user", content: dish },
    ],
    { maxTokens: 1000 },
  );
  return (raw?.items ?? [])
    .filter((item) => typeof item.text === "string" && item.text.trim())
    .map((item) => ({ text: item.text!.trim(), quantity: Number.isInteger(item.quantity) && item.quantity! > 0 ? item.quantity! : 1, staple: Boolean(item.staple) }));
}

/** Full flow: detect dish → find a recipe online → convert to items (falls back to model knowledge). */
export async function buildRecipeGroup(text: string): Promise<{ intent: RecipeIntent; recipe: Recipe | null; items: RecipeItem[] }> {
  const intent = await detectRecipeIntent(text);
  const recipe = await findRecipeOnline(intent.dishNl || intent.dish, intent.dish);
  const items = recipe ? await ingredientsToItems(recipe.ingredientLines, intent.dish) : await ingredientsFromKnowledge(intent.dish);
  return { intent, recipe, items };
}

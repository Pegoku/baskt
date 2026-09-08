import { chatJson } from "@/ai/client";
import { appLanguageName } from "@/db/settings";
import { aiConfigured } from "@/env";
import type { RecipeStep } from "@/matching/recipes";
import { listStock } from "@/stock";

export type StockDish = { title: string; searchQuery: string; uses: string[]; missing: string[]; minutes: number | null; imageUrl?: string | null; recipeUrl?: string | null; source?: string | null };

const dishCache = new Map<string, { at: number; dishes: StockDish[] }>();

/** Attaches a picture and link from the recipe sites to each proposed dish (Allerhande + BBC Good Food, in parallel). */
export async function illustrateDishes(dishes: StockDish[]): Promise<StockDish[]> {
  const { searchSite, SITE_SOURCES } = await import("@/matching/sources");
  const sites = SITE_SOURCES.filter((site) => site.id === "allerhande" || site.id === "bbcgoodfood");
  return Promise.all(
    dishes.map(async (dish) => {
      for (const site of sites) {
        try {
          const hit = (await searchSite(site, dish.searchQuery, 3)).find((entry) => entry.imageUrl);
          if (hit) return { ...dish, imageUrl: hit.imageUrl, recipeUrl: hit.url, source: hit.source };
        } catch {
          // try the next site
        }
      }
      return dish;
    }),
  );
}

/** Dishes that can (mostly) be cooked from what is in stock, with what would still need buying. */
export async function dishesFromStock(): Promise<StockDish[]> {
  const stock = listStock().map((row) => row.text);
  if (!stock.length || !aiConfigured()) return [];
  const cacheKey = `${appLanguageName()}|${stock.slice().sort().join("|")}`;
  const cached = dishCache.get(cacheKey);
  if (cached && Date.now() - cached.at < 6 * 60 * 60 * 1000) return cached.dishes;
  const dishes = await illustrateDishes(await proposeDishes(stock));
  dishCache.set(cacheKey, { at: Date.now(), dishes });
  return dishes;
}

async function proposeDishes(stock: string[]): Promise<StockDish[]> {
  const raw = await chatJson<{ dishes?: Array<Partial<StockDish>> }>(
    [
      {
        role: "system",
        content: `The user has these ingredients at home. Propose 8 realistic home-cooked dishes they can make using mostly what they have, in ${appLanguageName()}.
Return ONLY {"dishes":[{"title": dish name, "searchQuery": short name to search on recipe sites (English or Dutch), "uses": [stock items used], "missing": [ingredients they still need to buy, keep it short; [] when none], "minutes": approximate cooking time or null}]}.
Order from fewest missing ingredients to most. Assume salt, pepper, water are available.`,
      },
      { role: "user", content: `In stock: ${stock.join(", ")}` },
    ],
    { maxTokens: 1500 },
  );
  return (raw?.dishes ?? [])
    .filter((dish) => typeof dish.title === "string" && dish.title.trim())
    .map((dish) => ({
      title: dish.title!.trim(),
      searchQuery: typeof dish.searchQuery === "string" && dish.searchQuery.trim() ? dish.searchQuery.trim() : dish.title!.trim(),
      uses: Array.isArray(dish.uses) ? dish.uses.filter((value): value is string => typeof value === "string") : [],
      missing: Array.isArray(dish.missing) ? dish.missing.filter((value): value is string => typeof value === "string") : [],
      minutes: typeof dish.minutes === "number" ? dish.minutes : null,
    }));
}

export type DraftRecipe = { title: string; description: string | null; servings: string | null; ingredientLines: string[]; steps: RecipeStep[] };

/** Writes a complete recipe from a free-text description, in the app language. */
export async function draftRecipe(description: string): Promise<DraftRecipe | null> {
  if (!aiConfigured()) return null;
  const raw = await chatJson<Partial<DraftRecipe> & { steps?: unknown }>(
    [
      {
        role: "system",
        content: `Write a complete home-cooking recipe from the user's description, in ${appLanguageName()}. Return ONLY {"title": string, "description": one sentence, "servings": e.g. "2", "ingredientLines": ["200 g rice", ...] (with amounts, for the servings), "steps": [step text, ...] (6-12 clear steps)}.`,
      },
      { role: "user", content: description },
    ],
    { maxTokens: 2500 },
  );
  if (!raw || typeof raw.title !== "string") return null;
  const steps = Array.isArray(raw.steps) ? (raw.steps as unknown[]).filter((step): step is string => typeof step === "string" && step.trim().length > 0) : [];
  return {
    title: raw.title.trim(),
    description: typeof raw.description === "string" ? raw.description.trim() : null,
    servings: typeof raw.servings === "string" || typeof raw.servings === "number" ? String(raw.servings) : null,
    ingredientLines: Array.isArray(raw.ingredientLines) ? raw.ingredientLines.filter((line): line is string => typeof line === "string" && line.trim().length > 0) : [],
    steps: steps.map((text) => ({ text: text.trim(), imageUrl: null })),
  };
}

export type DishQueries = { dish: string; queries: { en: string; nl: string; es: string } };

/** "arroz cubano" → what to type into English, Dutch and Spanish recipe sites. */
export async function dishQueries(text: string): Promise<DishQueries> {
  const fallback = { dish: text.trim(), queries: { en: text.trim(), nl: text.trim(), es: text.trim() } };
  if (!aiConfigured()) return fallback;
  const raw = await chatJson<{ dish?: string; en?: string; nl?: string; es?: string }>(
    [
      {
        role: "system",
        content: 'The user names or describes a dish. Return ONLY {"dish": the dish name in the user\'s language, "en": how to search it on English recipe sites, "nl": on Dutch sites, "es": on Spanish sites}. Use the common local name (e.g. "arroz a la cubana", "Cuban rice", "Cubaanse rijst"). 1-4 words each.',
      },
      { role: "user", content: text },
    ],
    { maxTokens: 200 },
  );
  return {
    dish: raw?.dish?.trim() || fallback.dish,
    queries: { en: raw?.en?.trim() || fallback.dish, nl: raw?.nl?.trim() || fallback.dish, es: raw?.es?.trim() || fallback.dish },
  };
}

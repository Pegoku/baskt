import { chatJson } from "@/ai/client";
import { appLanguageName } from "@/db/settings";
import { aiConfigured } from "@/env";
import type { RecipeStep } from "@/matching/recipes";
import { db } from "@/db";
import { recipeFavourites, userRecipes } from "@/db/schema";
import { listStock } from "@/stock";

export type Dish = { title: string; searchQuery: string; uses: string[]; missing: string[]; minutes: number | null; imageUrl?: string | null; recipeUrl?: string | null; source?: string | null };

export type DiscoverMode = "stock" | "new" | "cuisine";
/** How much of a dish must already be at home: everything, most of it, or at least half (the rest is bought). */
export type StockCoverage = "all" | "most" | "half";
export type DiscoverParams = {
  mode: DiscoverMode;
  coverage: StockCoverage;
  /** Free hint for the "new" mode, e.g. "vegetarian" or "comfort food". */
  style: string | null;
  /** Country or cuisine for the "cuisine" mode, e.g. "Mexico" or "Thai". */
  cuisine: string | null;
  /** Upper bound on total preparation time, in minutes. */
  maxMinutes: number | null;
};

export const DEFAULT_DISCOVER: DiscoverParams = { mode: "stock", coverage: "most", style: null, cuisine: null, maxMinutes: null };

const dishCache = new Map<string, { at: number; dishes: Dish[] }>();

/** Reads discover parameters from a query string, falling back to the defaults for anything missing or unknown. */
export function parseDiscoverParams(query: Record<string, string | undefined>): DiscoverParams {
  const mode: DiscoverMode = query.mode === "new" || query.mode === "cuisine" ? query.mode : "stock";
  const coverage: StockCoverage = query.coverage === "all" || query.coverage === "half" ? query.coverage : "most";
  const minutes = Number(query.maxMinutes);
  return {
    mode,
    coverage,
    style: query.style?.trim() || null,
    cuisine: query.cuisine?.trim() || null,
    maxMinutes: Number.isFinite(minutes) && minutes > 0 ? Math.round(minutes) : null,
  };
}

/** Whether a dish's shopping list is small enough for the chosen coverage level. */
export function fitsCoverage(dish: Pick<Dish, "uses" | "missing">, coverage: StockCoverage): boolean {
  const missing = dish.missing.length;
  const total = dish.uses.length + missing;
  if (total === 0) return true;
  switch (coverage) {
    case "all":
      return missing === 0;
    case "most":
      return missing <= 2 && missing * 3 <= total;
    case "half":
      return missing * 2 <= total;
  }
}

/** Whether a dish fits under the time limit; dishes with an unknown time are kept when no limit is set. */
export function fitsTime(dish: Pick<Dish, "minutes">, maxMinutes: number | null): boolean {
  return maxMinutes == null || (dish.minutes != null && dish.minutes <= maxMinutes);
}

/** Attaches a picture and link from the recipe sites to each proposed dish (Allerhande + BBC Good Food, in parallel). */
export async function illustrateDishes(dishes: Dish[]): Promise<Dish[]> {
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

/**
 * Dish ideas for the Discover tab. "stock" cooks from what is at home (filtered by how much must be in stock),
 * "new" proposes dishes the user has not saved before, "cuisine" picks classics from a country. All modes
 * respect the time limit and report which stock items are used and what still needs buying.
 */
export async function discoverDishes(params: DiscoverParams = DEFAULT_DISCOVER): Promise<Dish[]> {
  if (!aiConfigured()) return [];
  const stock = listStock().map((row) => row.text);
  if (params.mode === "stock" && !stock.length) return [];
  if (params.mode === "cuisine" && !params.cuisine) return [];
  const known = params.mode === "new" ? knownDishes() : [];
  const cacheKey = JSON.stringify([appLanguageName(), params, stock.slice().sort(), known]);
  const cached = dishCache.get(cacheKey);
  if (cached && Date.now() - cached.at < 6 * 60 * 60 * 1000) return cached.dishes;
  const proposed = (await proposeDishes(params, stock, known)).filter((dish) => fitsTime(dish, params.maxMinutes));
  const kept = params.mode === "stock" ? proposed.filter((dish) => fitsCoverage(dish, params.coverage)) : proposed;
  const dishes = await illustrateDishes(kept);
  dishCache.set(cacheKey, { at: Date.now(), dishes });
  return dishes;
}

/** Dishes the user already keeps (own recipes and favourites), so "new" ideas can steer clear of them. */
function knownDishes(): string[] {
  const own = db().select({ title: userRecipes.title }).from(userRecipes).all().map((row) => row.title);
  const liked = db().select({ title: recipeFavourites.title }).from(recipeFavourites).all().map((row) => row.title);
  return [...new Set([...own, ...liked])].slice(0, 40);
}

/** Kept for older clients: the previous "from stock" list, with most ingredients at home. */
export async function dishesFromStock(): Promise<Dish[]> {
  return discoverDishes(DEFAULT_DISCOVER);
}

function discoverBrief(params: DiscoverParams, stock: string[], known: string[]): string {
  const time = params.maxMinutes ? ` Every dish must take at most ${params.maxMinutes} minutes in total, including preparation.` : "";
  switch (params.mode) {
    case "stock": {
      const coverage = {
        all: "Only propose dishes that need nothing beyond what is in stock: \"missing\" must be [] for every dish.",
        most: "Most ingredients must already be in stock; at most 1-2 small things may be missing.",
        half: "At least half of the ingredients must be in stock; the rest can be bought.",
      }[params.coverage];
      return `The user has these ingredients at home. Propose 8 realistic home-cooked dishes they can make with them. ${coverage}${time} Order from fewest missing ingredients to most.`;
    }
    case "new": {
      const avoid = known.length ? ` Do not propose these dishes or close variants, the user already knows them: ${known.join(", ")}.` : "";
      const style = params.style ? ` Style: ${params.style}.` : "";
      return `Propose 8 varied home-cooked dishes the user has probably never made: different cuisines, techniques and main ingredients, but realistic for a home kitchen.${style}${avoid}${time}${stock.length ? " Where it fits naturally, favour their stock, but do not force it." : ""}`;
    }
    case "cuisine": {
      return `Propose 8 well-known home-cooked dishes from ${params.cuisine} (the country or cuisine), mixing everyday classics with one or two less obvious ones.${time}${stock.length ? " Compare each dish with the user's stock to fill in what they have and what they need." : ""}`;
    }
  }
}

async function proposeDishes(params: DiscoverParams, stock: string[], known: string[]): Promise<Dish[]> {
  const raw = await chatJson<{ dishes?: Array<Partial<Dish>> }>(
    [
      {
        role: "system",
        content: `${discoverBrief(params, stock, known)} Answer in ${appLanguageName()}.
Return ONLY {"dishes":[{"title": dish name, "searchQuery": short name to search on recipe sites (English or Dutch), "uses": [stock items used], "missing": [ingredients they still need to buy, keep it short; [] when none], "minutes": total time in minutes as a number}]}.
Assume salt, pepper, oil and water are available.`,
      },
      { role: "user", content: stock.length ? `In stock: ${stock.join(", ")}` : "Nothing is in stock yet." },
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
      minutes: typeof dish.minutes === "number" && Number.isFinite(dish.minutes) ? Math.round(dish.minutes) : typeof dish.minutes === "string" && /^\d+/.test(dish.minutes) ? parseInt(dish.minutes, 10) : null,
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

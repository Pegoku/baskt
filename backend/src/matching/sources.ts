import { fetchWithRetry } from "@/lib/http";
import { extractRecipe, type Recipe } from "@/matching/recipes";

const BROWSER_UA = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/145.0.0.0 Safari/537.36";

export type RecipeHit = { title: string; url: string; imageUrl: string | null; source: string; language: "en" | "nl" | "es" };

/** A recipe website we can search: a search URL and how recipe links look on its result page. */
export type SiteSource = {
  id: string;
  name: string;
  language: "en" | "nl" | "es";
  searchUrl: (query: string) => string;
  /** Matches recipe URLs (absolute or relative) in the search page HTML; group 1 = slug used for the title. */
  linkPattern: RegExp;
  base: string;
  imagePattern?: RegExp;
};

export const SITE_SOURCES: SiteSource[] = [
  {
    id: "allerhande",
    name: "Allerhande",
    language: "nl",
    base: "https://www.ah.nl",
    searchUrl: (query) => `https://www.ah.nl/allerhande/recepten-zoeken?query=${encodeURIComponent(query)}`,
    linkPattern: /\/allerhande\/recept\/R-R\d+\/([a-z0-9-]+)/g,
  },
  {
    id: "leukerecepten",
    name: "Leuke Recepten",
    language: "nl",
    base: "https://www.leukerecepten.nl",
    searchUrl: (query) => `https://www.leukerecepten.nl/?s=${encodeURIComponent(query)}`,
    linkPattern: /https:\/\/www\.leukerecepten\.nl\/recepten\/([a-z0-9-]+)\//g,
  },
  {
    id: "bbcgoodfood",
    name: "BBC Good Food",
    language: "en",
    base: "https://www.bbcgoodfood.com",
    searchUrl: (query) => `https://www.bbcgoodfood.com/search?q=${encodeURIComponent(query)}`,
    linkPattern: /https:\/\/www\.bbcgoodfood\.com\/recipes\/([a-z0-9-]+)/g,
  },
  {
    id: "recetasgratis",
    name: "RecetasGratis",
    language: "es",
    base: "https://recetas.elperiodico.com",
    searchUrl: (query) => `https://recetas.elperiodico.com/busqueda/q/${encodeURIComponent(query)}`,
    linkPattern: /(?:https:\/\/recetas\.elperiodico\.com)?\/receta-de-([a-z0-9-]+)-\d+\.html/g,
  },
  {
    id: "cookpad",
    name: "Cookpad",
    language: "es",
    base: "https://cookpad.com",
    searchUrl: (query) => `https://cookpad.com/es/buscar/${encodeURIComponent(query)}`,
    linkPattern: /\/es\/recetas\/(\d{5,})(?![\d-])/g,
  },
];

function titleFromSlug(slug: string) {
  return slug.replace(/-recept$/, "").split("-").filter(Boolean).map((part, index) => (index === 0 ? part[0].toUpperCase() + part.slice(1) : part)).join(" ");
}

async function fetchHtml(url: string) {
  const response = await fetchWithRetry(url, { headers: { "user-agent": BROWSER_UA, accept: "text/html", "accept-language": "nl-NL,nl;q=0.9,en;q=0.8,es;q=0.7" } }, { retries: 1 });
  return response.text();
}

const STOPWORDS = new Set(["de", "het", "een", "met", "van", "en", "the", "a", "an", "with", "and", "of", "con", "la", "el", "los", "las", "y", "recept", "recipe", "receta"]);

function relevance(title: string, query: string) {
  const norm = (value: string) => value.toLowerCase().normalize("NFKD").replace(/[\u0300-\u036f]/g, "").split(/[^a-z0-9]+/).filter((token) => token.length > 2 && !STOPWORDS.has(token));
  const wanted = norm(query);
  const have = new Set(norm(title));
  return wanted.filter((token) => have.has(token) || Array.from(have).some((other) => other.startsWith(token) || token.startsWith(other))).length;
}

/** Sites with fuzzy search return unrelated dishes when nothing matches; keep only titles that share a word with the query. */
function filterRelevant(hits: RecipeHit[], query: string, limit: number) {
  const scored = hits.map((hit) => ({ hit, score: relevance(hit.title, query) }));
  const relevant = scored.filter((entry) => entry.score > 0).sort((a, b) => b.score - a.score);
  return (relevant.length ? relevant : []).slice(0, limit).map((entry) => entry.hit);
}

export async function searchSite(site: SiteSource, query: string, limit = 8): Promise<RecipeHit[]> {
  const html = await fetchHtml(site.searchUrl(query));
  const seen = new Set<string>();
  const hits: RecipeHit[] = [];
  for (const match of html.matchAll(site.linkPattern)) {
    const path = match[0];
    const url = path.startsWith("http") ? path : `${site.base}${path}`;
    if (seen.has(url)) continue;
    seen.add(url);
    // Prefer the anchor text when it is right next to the link, else derive a title from the slug.
    const around = html.slice(match.index!, match.index! + 600);
    const anchor = around.match(/>([^<>]{4,120})<\/a>/)?.[1]?.replace(/\s+/g, " ").trim();
    const image = html.slice(Math.max(0, match.index! - 1500), match.index! + 1500).match(/https:\/\/[^"'\s]+\.(?:jpe?g|png|webp)(?:\?[^"'\s]*)?/)?.[0] ?? null;
    hits.push({ title: anchor && !/^(lees|bekijk|meer|read|ver)/i.test(anchor) ? anchor : titleFromSlug(match[1]), url, imageUrl: image, source: site.name, language: site.language });
    if (hits.length >= limit * 3) break;
  }
  return filterRelevant(hits, query, limit);
}

/** TheMealDB: a free JSON API with English recipes (small catalogue, but instant and structured). */
export { relevance as recipeRelevance };

export async function searchMealDb(query: string): Promise<RecipeHit[]> {
  const response = await fetchWithRetry(`https://www.themealdb.com/api/json/v1/1/search.php?s=${encodeURIComponent(query)}`, {}, { retries: 1 });
  const body = (await response.json()) as { meals?: Array<{ idMeal: string; strMeal: string; strMealThumb?: string }> | null };
  return (body.meals ?? []).slice(0, 10).map((meal) => ({ title: meal.strMeal, url: `https://www.themealdb.com/meal/${meal.idMeal}`, imageUrl: meal.strMealThumb ?? null, source: "TheMealDB", language: "en" as const }));
}

export async function fetchMealDb(id: string): Promise<Recipe | null> {
  const response = await fetchWithRetry(`https://www.themealdb.com/api/json/v1/1/lookup.php?i=${encodeURIComponent(id)}`, {}, { retries: 1 });
  const body = (await response.json()) as { meals?: Array<Record<string, string | null>> | null };
  const meal = body.meals?.[0];
  if (!meal) return null;
  const lines: string[] = [];
  for (let index = 1; index <= 20; index += 1) {
    const ingredient = meal[`strIngredient${index}`]?.trim();
    const measure = meal[`strMeasure${index}`]?.trim();
    if (ingredient) lines.push([measure, ingredient].filter(Boolean).join(" "));
  }
  const steps = (meal.strInstructions ?? "").split(/\r?\n+/).map((text) => text.trim()).filter((text) => text.length > 2).map((text) => ({ text: text.replace(/^STEP \d+\s*/i, ""), imageUrl: null }));
  return { title: meal.strMeal ?? "Recipe", sourceUrl: `https://www.themealdb.com/meal/${id}`, servings: null, ingredientLines: lines, imageUrl: meal.strMealThumb ?? null, steps };
}

/** Fetches a recipe from any supported URL: TheMealDB by id, everything else via schema.org data on the page. */
export async function fetchRecipeFromUrl(url: string): Promise<Recipe | null> {
  const mealDb = url.match(/themealdb\.com\/meal\/(\d+)/);
  if (mealDb) return fetchMealDb(mealDb[1]);
  const parsed = new URL(url);
  if (!/^https?:$/.test(parsed.protocol)) return null;
  return extractRecipe(await fetchHtml(url), url);
}

/** Searches every source with the query in its language; failures of one site never hide the others. */
export async function searchAllSources(queries: { en: string; nl: string; es: string }, options: { sources?: string[] } = {}): Promise<RecipeHit[]> {
  const wanted = options.sources;
  const tasks: Array<Promise<RecipeHit[]>> = [];
  for (const site of SITE_SOURCES) {
    if (wanted && !wanted.includes(site.id)) continue;
    tasks.push(searchSite(site, queries[site.language]).catch(() => []));
  }
  if (!wanted || wanted.includes("themealdb")) tasks.push(searchMealDb(queries.en).catch(() => []));
  const results = await Promise.all(tasks);
  const seen = new Set<string>();
  const merged: RecipeHit[] = [];
  // Interleave sources so the first screen shows a mix instead of one site's whole list.
  const max = Math.max(...results.map((list) => list.length), 0);
  for (let index = 0; index < max; index += 1) {
    for (const list of results) {
      const hit = list[index];
      if (hit && !seen.has(hit.url)) {
        seen.add(hit.url);
        merged.push(hit);
      }
    }
  }
  return merged;
}

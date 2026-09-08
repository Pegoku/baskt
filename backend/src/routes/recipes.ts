import { desc, eq } from "drizzle-orm";
import { Hono } from "hono";
import { db, newId, now } from "@/db";
import { recipeFavourites } from "@/db/schema";
import { fetchWithRetry } from "@/lib/http";
import { extractRecipe, type Recipe } from "@/matching/recipes";
import { localizeRecipe, localizeTitles } from "@/matching/localize";

export const recipes = new Hono();

const BROWSER_UA = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/145.0.0.0 Safari/537.36";
const ALLERHANDE = "https://www.ah.nl";

export type RecipeSummary = { title: string; titleLocalized?: string; url: string; imageUrl: string | null; slug: string };

/** Searches AH Allerhande and returns recipe cards (title from the slug, image when present in the page). */
export async function searchRecipes(query: string): Promise<RecipeSummary[]> {
  const response = await fetchWithRetry(
    `${ALLERHANDE}/allerhande/recepten-zoeken?query=${encodeURIComponent(query)}`,
    { headers: { "user-agent": BROWSER_UA, accept: "text/html" } },
    { retries: 1 },
  );
  const html = await response.text();
  const seen = new Set<string>();
  const results: RecipeSummary[] = [];
  for (const match of html.matchAll(/\/allerhande\/recept\/(R-R\d+)\/([a-z0-9-]+)/g)) {
    const slug = match[0];
    if (seen.has(slug)) continue;
    seen.add(slug);
    const title = match[2].split("-").map((part, index) => (index === 0 ? part[0].toUpperCase() + part.slice(1) : part)).join(" ");
    // Recipe cards carry an image URL near the link; grab the closest one.
    const window = html.slice(Math.max(0, match.index! - 1500), match.index! + 1500);
    const image = window.match(/https:\/\/static\.ah\.nl\/static\/recepten\/img_[A-Za-z0-9_]+\/[a-z0-9_]+\/[^"'\s]+/)?.[0] ?? null;
    results.push({ title, url: `${ALLERHANDE}${slug}`, imageUrl: image, slug });
    if (results.length >= 20) break;
  }
  return results;
}

export async function fetchRecipe(url: string): Promise<Recipe | null> {
  const parsed = new URL(url);
  if (!/^https?:$/.test(parsed.protocol)) return null;
  const response = await fetchWithRetry(url, { headers: { "user-agent": BROWSER_UA, accept: "text/html" } }, { retries: 1 });
  return extractRecipe(await response.text(), url);
}

recipes.get("/search", async (c) => {
  const query = c.req.query("q")?.trim();
  if (!query) return c.json({ error: { code: "BAD_REQUEST", message: "q is required" } }, 400);
  try {
    const results = await searchRecipes(query);
    const localized = await localizeTitles(results.map((result) => result.title));
    return c.json({ query, results: results.map((result, index) => ({ ...result, titleLocalized: localized[index] })) });
  } catch (error) {
    return c.json({ error: { code: "UPSTREAM", message: error instanceof Error ? error.message : String(error) } }, 502);
  }
});

/** Full recipe (ingredients) for a URL: Allerhande or any site publishing schema.org Recipe data. */
recipes.get("/fetch", async (c) => {
  const url = c.req.query("url")?.trim();
  if (!url) return c.json({ error: { code: "BAD_REQUEST", message: "url is required" } }, 400);
  try {
    const recipe = await fetchRecipe(url);
    if (!recipe) return c.json({ error: { code: "NOT_FOUND", message: "no recipe data found on that page" } }, 404);
    return c.json(await localizeRecipe(recipe));
  } catch (error) {
    return c.json({ error: { code: "UPSTREAM", message: error instanceof Error ? error.message : String(error) } }, 502);
  }
});

recipes.get("/favourites", (c) => c.json({ favourites: db().select().from(recipeFavourites).orderBy(desc(recipeFavourites.createdAt)).all() }));

recipes.post("/favourites", async (c) => {
  const body = (await c.req.json().catch(() => ({}))) as { title?: string; url?: string; imageUrl?: string | null };
  if (!body.title?.trim() || !body.url?.trim()) return c.json({ error: { code: "BAD_REQUEST", message: "title and url are required" } }, 400);
  const existing = db().select().from(recipeFavourites).where(eq(recipeFavourites.url, body.url.trim())).get();
  if (existing) return c.json(existing);
  const row = { id: newId(), title: body.title.trim(), url: body.url.trim(), imageUrl: body.imageUrl?.trim() || null, createdAt: now() };
  db().insert(recipeFavourites).values(row).run();
  return c.json(row, 201);
});

recipes.delete("/favourites/:id", (c) => {
  const removed = db().delete(recipeFavourites).where(eq(recipeFavourites.id, c.req.param("id"))).returning({ id: recipeFavourites.id }).all();
  return removed.length ? c.body(null, 204) : c.json({ error: { code: "NOT_FOUND", message: "favourite not found" } }, 404);
});

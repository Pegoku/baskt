import { desc, eq } from "drizzle-orm";
import { Hono } from "hono";
import { db, newId, now } from "@/db";
import { recipeFavourites, userRecipes, type UserRecipeRow } from "@/db/schema";
import { appLanguage } from "@/db/settings";
import { dishQueries, dishesFromStock, draftRecipe } from "@/matching/cook";
import { localizeRecipe, localizeTitles } from "@/matching/localize";
import type { Recipe } from "@/matching/recipes";
import { fetchRecipeFromUrl, searchAllSources, SITE_SOURCES } from "@/matching/sources";

export const recipes = new Hono();

export async function fetchRecipe(url: string): Promise<Recipe | null> {
  return fetchRecipeFromUrl(url);
}

export function getUserRecipe(id: string): UserRecipeRow | null {
  return db().select().from(userRecipes).where(eq(userRecipes.id, id)).get() ?? null;
}

export function userRecipeAsRecipe(row: UserRecipeRow): Recipe {
  return { title: row.title, sourceUrl: row.sourceUrl ?? `baskt://recipe/${row.id}`, servings: row.servings, ingredientLines: row.ingredientLines, imageUrl: row.imageUrl, steps: row.steps };
}

recipes.get("/sources", (c) => c.json({ sources: [...SITE_SOURCES.map((site) => ({ id: site.id, name: site.name, language: site.language })), { id: "themealdb", name: "TheMealDB", language: "en" }] }));

/** Multi-site search: the dish is translated into English/Dutch/Spanish queries first, results are interleaved. */
recipes.get("/search", async (c) => {
  const query = c.req.query("q")?.trim();
  if (!query) return c.json({ error: { code: "BAD_REQUEST", message: "q is required" } }, 400);
  const understood = await dishQueries(query);
  const hits = await searchAllSources(understood.queries);
  const localized = await localizeTitles(hits.map((hit) => hit.title));
  return c.json({ query, dish: understood.dish, queries: understood.queries, results: hits.map((hit, index) => ({ ...hit, titleLocalized: localized[index], slug: "" })) });
});

/** Full recipe (ingredients, steps) for a URL from any supported site, in the app language. */
recipes.get("/fetch", async (c) => {
  const url = c.req.query("url")?.trim();
  if (!url) return c.json({ error: { code: "BAD_REQUEST", message: "url is required" } }, 400);
  try {
    const own = url.match(/^baskt:\/\/recipe\/(.+)$/);
    const recipe = own ? (getUserRecipe(own[1]) ? userRecipeAsRecipe(getUserRecipe(own[1])!) : null) : await fetchRecipe(url);
    if (!recipe) return c.json({ error: { code: "NOT_FOUND", message: "no recipe data found on that page" } }, 404);
    return c.json(own ? { ...recipe, originalTitle: recipe.title, language: appLanguage() } : await localizeRecipe(recipe));
  } catch (error) {
    return c.json({ error: { code: "UPSTREAM", message: error instanceof Error ? error.message : String(error) } }, 502);
  }
});

/** What can be cooked from the stock list. */
recipes.get("/from-stock", async (c) => c.json({ dishes: await dishesFromStock() }));

/**
 * Own recipe from a description. Step 1 looks for matching recipes on the sites (the user picks one or says
 * none fits); step 2 (`draft=true`) lets the AI write the recipe for review.
 */
recipes.post("/generate", async (c) => {
  const body = (await c.req.json().catch(() => ({}))) as { description?: string; draft?: boolean };
  if (!body.description?.trim()) return c.json({ error: { code: "BAD_REQUEST", message: "description is required" } }, 400);
  if (body.draft) {
    const draft = await draftRecipe(body.description);
    if (!draft) return c.json({ error: { code: "AI_UNAVAILABLE", message: "AI is not configured" } }, 503);
    return c.json({ draft });
  }
  const understood = await dishQueries(body.description);
  const hits = (await searchAllSources(understood.queries)).slice(0, 8);
  const localized = await localizeTitles(hits.map((hit) => hit.title));
  return c.json({ dish: understood.dish, matches: hits.map((hit, index) => ({ ...hit, titleLocalized: localized[index] })) });
});

recipes.get("/mine", (c) => c.json({ recipes: db().select().from(userRecipes).orderBy(desc(userRecipes.updatedAt)).all() }));

recipes.post("/mine", async (c) => {
  const body = (await c.req.json().catch(() => ({}))) as Partial<UserRecipeRow> & { steps?: unknown; ingredientLines?: unknown; fromUrl?: string };
  let title = typeof body.title === "string" ? body.title.trim() : "";
  let lines = Array.isArray(body.ingredientLines) ? (body.ingredientLines as unknown[]).filter((line): line is string => typeof line === "string" && line.trim().length > 0) : [];
  let steps = Array.isArray(body.steps)
    ? (body.steps as unknown[]).map((step) => (typeof step === "string" ? { text: step.trim(), imageUrl: null } : step && typeof step === "object" && typeof (step as { text?: unknown }).text === "string" ? { text: (step as { text: string }).text.trim(), imageUrl: typeof (step as { imageUrl?: unknown }).imageUrl === "string" ? (step as { imageUrl: string }).imageUrl : null } : null)).filter((step): step is { text: string; imageUrl: string | null } => Boolean(step && step.text))
    : [];
  let imageUrl = typeof body.imageUrl === "string" ? body.imageUrl : null;
  let sourceUrl = typeof body.sourceUrl === "string" ? body.sourceUrl : null;
  let servings = body.servings != null ? String(body.servings) : null;
  let origin = body.origin === "ai" || body.origin === "site" ? body.origin : "manual";
  if (body.fromUrl) {
    // Copy a site recipe into "mine" so it can be edited and kept even if the page disappears.
    const fetched = await fetchRecipe(body.fromUrl).catch(() => null);
    if (!fetched) return c.json({ error: { code: "NOT_FOUND", message: "no recipe data found on that page" } }, 404);
    const localized = await localizeRecipe(fetched);
    title = title || localized.title;
    lines = lines.length ? lines : localized.ingredientLines;
    steps = steps.length ? steps : localized.steps ?? [];
    imageUrl = imageUrl ?? fetched.imageUrl ?? null;
    sourceUrl = body.fromUrl;
    servings = servings ?? fetched.servings;
    origin = "site";
  }
  if (!title || !lines.length) return c.json({ error: { code: "BAD_REQUEST", message: "title and ingredientLines are required" } }, 400);
  const row: UserRecipeRow = { id: newId(), title, description: typeof body.description === "string" ? body.description : null, servings, ingredientLines: lines, steps, imageUrl, sourceUrl, origin, createdAt: now(), updatedAt: now() };
  db().insert(userRecipes).values(row).run();
  return c.json(row, 201);
});

recipes.patch("/mine/:id", async (c) => {
  const existing = getUserRecipe(c.req.param("id"));
  if (!existing) return c.json({ error: { code: "NOT_FOUND", message: "recipe not found" } }, 404);
  const body = (await c.req.json().catch(() => ({}))) as Partial<UserRecipeRow> & { steps?: unknown; ingredientLines?: unknown };
  const patch: Partial<UserRecipeRow> = { updatedAt: now() };
  if (typeof body.title === "string" && body.title.trim()) patch.title = body.title.trim();
  if (typeof body.description === "string") patch.description = body.description;
  if (body.servings !== undefined) patch.servings = body.servings == null ? null : String(body.servings);
  if (Array.isArray(body.ingredientLines)) patch.ingredientLines = (body.ingredientLines as unknown[]).filter((line): line is string => typeof line === "string" && line.trim().length > 0);
  if (Array.isArray(body.steps)) patch.steps = (body.steps as unknown[]).map((step) => (typeof step === "string" ? { text: step.trim(), imageUrl: null } : (step as { text: string; imageUrl: string | null }))).filter((step) => step && step.text);
  if (typeof body.imageUrl === "string" || body.imageUrl === null) patch.imageUrl = body.imageUrl;
  db().update(userRecipes).set(patch).where(eq(userRecipes.id, existing.id)).run();
  return c.json(getUserRecipe(existing.id));
});

recipes.delete("/mine/:id", (c) => {
  const removed = db().delete(userRecipes).where(eq(userRecipes.id, c.req.param("id"))).returning({ id: userRecipes.id }).all();
  return removed.length ? c.body(null, 204) : c.json({ error: { code: "NOT_FOUND", message: "recipe not found" } }, 404);
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

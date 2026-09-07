import { and, asc, desc, eq, gt, inArray } from "drizzle-orm";
import { Hono } from "hono";
import { db, newId, now } from "@/db";
import { basketItems, basketMatches, tombstones, DEFAULT_BASKET_ID, type BasketItemRow } from "@/db/schema";
import { basketExists, transferItem } from "@/routes/baskets";
import { defaultServings, skipInStock } from "@/db/settings";
import { inStock, listStock } from "@/stock";
import { enabledStoreCodes } from "@/db/settings";
import { compareBasket, type CompareMatch } from "@/matching/compare";
import { chooseMatch, enqueue, getItem, getMatches, isRunning, rejectShown, rematchItem, searchMoreCandidates } from "@/matching/pipeline";
import { splitShoppingText } from "@/matching/parse";
import { suggest } from "@/matching/suggest";
import { buildRecipeGroup, itemsForServings, looksLikeRecipe, type RecipeItem } from "@/matching/recipes";
import { collectProductIds, itemView } from "@/serialize";
import { hasStore } from "@/stores/registry";
import { productsByIds } from "@/stores/search";

export const basket = new Hono();

function loadViews(items: BasketItemRow[]) {
  if (!items.length) return [];
  const matches = db()
    .select()
    .from(basketMatches)
    .where(inArray(basketMatches.itemId, items.map((item) => item.id)))
    .orderBy(asc(basketMatches.store))
    .all();
  const products = new Map(productsByIds(collectProductIds(matches)).map((product) => [product.id, product]));
  const views = items.map((item) =>
    itemView(
      item,
      matches.filter((match) => match.itemId === item.id),
      products,
    ),
  );
  // A folder needs attention when any of its children does.
  for (const view of views) {
    if (view.kind === "group") view.needsChoice = views.some((child) => child.parentId === view.id && child.needsChoice);
  }
  return views;
}

function childrenOf(groupId: string) {
  return db().select().from(basketItems).where(eq(basketItems.parentId, groupId)).orderBy(asc(basketItems.sortOrder), asc(basketItems.createdAt)).all();
}

function tombstone(ids: string[]) {
  const database = db();
  for (const id of ids) database.insert(tombstones).values({ collection: "basket", entityId: id, deletedAt: now() }).onConflictDoNothing().run();
}

function viewOf(itemId: string) {
  const item = getItem(itemId);
  return item ? loadViews([item])[0] : null;
}

function createItem(text: string, quantity: number, parentId: string | null = null, kind: BasketItemRow["kind"] = "item", basketId = DEFAULT_BASKET_ID) {
  const database = db();
  const last = database.select({ sortOrder: basketItems.sortOrder }).from(basketItems).orderBy(desc(basketItems.sortOrder)).get();
  const item: BasketItemRow = {
    id: newId(),
    basketId: parentId ? getItem(parentId)?.basketId ?? basketId : basketId,
    kind,
    parentId,
    recipeJson: null,
    text: text.trim(),
    quantity: Math.max(1, Math.floor(quantity || 1)),
    checked: false,
    sortOrder: (last?.sortOrder ?? -1) + 1,
    status: "NEW",
    error: null,
    parsedJson: null,
    createdAt: now(),
    updatedAt: now(),
  };
  database.insert(basketItems).values(item).run();
  enqueue(item.id);
  return item;
}

/** Creates child items, leaving out (and reporting) ingredients already in stock. */
function createChildren(groupId: string, items: RecipeItem[]) {
  const stockRows = skipInStock() ? listStock() : [];
  const skipped: Array<{ text: string; quantity: number; reason: string }> = [];
  for (const child of items) {
    const have = stockRows.length ? inStock(child.text, stockRows) : null;
    if (have) skipped.push({ text: child.text, quantity: child.quantity, reason: `in stock: ${have.text}` });
    else createItem(child.text, child.quantity, groupId);
  }
  return skipped;
}

/** Creates a folder; with `items` the children are given, otherwise a recipe is looked up in the background. */
function createGroup(text: string, itemTexts?: string[], basketId = DEFAULT_BASKET_ID) {
  const group = createItem(text, 1, null, "group", basketId);
  if (itemTexts?.length) {
    for (const child of itemTexts) createItem(child, 1, group.id);
    return group;
  }
  db().update(basketItems).set({ status: "PARSING" }).where(eq(basketItems.id, group.id)).run();
  void buildRecipeGroup(text, defaultServings())
    .then(({ intent, recipe, items, baseServings, servings }) => {
      if (!getItem(group.id)) return;
      const skipped = createChildren(group.id, items);
      db()
        .update(basketItems)
        .set({
          // Folder name in the user's language; the recipe's own title stays in recipeJson.
          text: intent.dish,
          recipeJson: {
            title: recipe?.title ?? intent.dish,
            sourceUrl: recipe?.sourceUrl ?? null,
            servings: recipe?.servings ?? null,
            ingredientLines: recipe?.ingredientLines ?? items.map((item) => item.text),
            skipped,
            baseServings,
            currentServings: servings,
          },
          status: items.length ? "MATCHED" : "ERROR",
          error: items.length ? null : "No ingredients found for this dish",
          updatedAt: now(),
        })
        .where(eq(basketItems.id, group.id))
        .run();
    })
    .catch((error) => {
      console.error(`[recipes] group ${group.id} failed:`, error);
      db().update(basketItems).set({ status: "ERROR", error: error instanceof Error ? error.message : String(error), updatedAt: now() }).where(eq(basketItems.id, group.id)).run();
    });
  return group;
}

function requestedBasket(c: { req: { query: (name: string) => string | undefined } }) {
  const id = c.req.query("basketId") ?? DEFAULT_BASKET_ID;
  return basketExists(id) ? id : null;
}

basket.get("/", (c) => {
  const since = Number(c.req.query("since") ?? 0);
  const basketId = requestedBasket(c);
  if (!basketId) return c.json({ error: { code: "NOT_FOUND", message: "basket not found" } }, 404);
  const database = db();
  const items = database
    .select()
    .from(basketItems)
    .where(and(eq(basketItems.basketId, basketId), since ? gt(basketItems.updatedAt, since) : undefined))
    .orderBy(asc(basketItems.sortOrder), asc(basketItems.createdAt))
    .all();
  const deleted = database
    .select({ entityId: tombstones.entityId })
    .from(tombstones)
    .where(and(eq(tombstones.collection, "basket"), since ? gt(tombstones.deletedAt, since) : undefined))
    .all()
    .map((row) => row.entityId);
  const anyRunning = items.some((item) => isRunning(item.id) || item.status === "NEW" || item.status === "PARSING" || item.status === "MATCHING");
  return c.json({ serverTime: now(), basketId, items: loadViews(items), deletedIds: deleted, processing: anyRunning, stores: enabledStoreCodes() });
});

/** Autocomplete for the idea input: personal history plus AI interpretations of descriptions. */
basket.get("/suggest", async (c) => {
  const text = c.req.query("q")?.trim() ?? "";
  if (text.length < 2) return c.json({ suggestions: [], source: "none" });
  return c.json(await suggest(text));
});

basket.post("/items", async (c) => {
  const body = (await c.req.json().catch(() => ({}))) as { text?: string; quantity?: number; parentId?: string | null; basketId?: string };
  if (!body.text?.trim()) return c.json({ error: { code: "BAD_REQUEST", message: "text is required" } }, 400);
  if (body.parentId && getItem(body.parentId)?.kind !== "group") return c.json({ error: { code: "BAD_REQUEST", message: "parentId is not a folder" } }, 400);
  const basketId = body.basketId ?? DEFAULT_BASKET_ID;
  if (!basketExists(basketId)) return c.json({ error: { code: "NOT_FOUND", message: "basket not found" } }, 404);
  // "ingredients for chocolate cookies" becomes a folder filled from a recipe instead of a single item.
  const item = !body.parentId && looksLikeRecipe(body.text) ? createGroup(body.text, undefined, basketId) : createItem(body.text, body.quantity ?? 1, body.parentId ?? null, "item", basketId);
  return c.json(viewOf(item.id), 201);
});

basket.post("/groups", async (c) => {
  const body = (await c.req.json().catch(() => ({}))) as { text?: string; items?: string[]; basketId?: string };
  if (!body.text?.trim()) return c.json({ error: { code: "BAD_REQUEST", message: "text is required" } }, 400);
  const basketId = body.basketId ?? DEFAULT_BASKET_ID;
  if (!basketExists(basketId)) return c.json({ error: { code: "NOT_FOUND", message: "basket not found" } }, 404);
  const items = Array.isArray(body.items) ? body.items.filter((value): value is string => typeof value === "string" && value.trim().length > 0) : undefined;
  const group = createGroup(body.text, items, basketId);
  return c.json({ group: viewOf(group.id), items: loadViews(childrenOf(group.id)) }, 201);
});

/** Rescales a recipe folder to another number of servings; children are rebuilt from the recipe. */
basket.post("/groups/:id/servings", async (c) => {
  const group = getItem(c.req.param("id"));
  if (!group || group.kind !== "group" || !group.recipeJson) return c.json({ error: { code: "NOT_FOUND", message: "recipe folder not found" } }, 404);
  const body = (await c.req.json().catch(() => ({}))) as { servings?: number };
  if (typeof body.servings !== "number" || body.servings <= 0 || body.servings > 50) return c.json({ error: { code: "BAD_REQUEST", message: "servings must be 1-50" } }, 400);
  const info = group.recipeJson;
  const recipe = info.sourceUrl ? { title: info.title, sourceUrl: info.sourceUrl, servings: info.servings, ingredientLines: info.ingredientLines } : null;
  db().update(basketItems).set({ status: "PARSING", updatedAt: now() }).where(eq(basketItems.id, group.id)).run();
  const items = await itemsForServings(recipe, group.text, info.baseServings ?? null, body.servings);
  const database = db();
  const oldChildren = childrenOf(group.id).map((child) => child.id);
  if (oldChildren.length) {
    database.delete(basketItems).where(inArray(basketItems.id, oldChildren)).run();
    tombstone(oldChildren);
  }
  const skipped = createChildren(group.id, items);
  database
    .update(basketItems)
    .set({ recipeJson: { ...info, skipped, currentServings: body.servings }, status: "MATCHED", error: null, updatedAt: now() })
    .where(eq(basketItems.id, group.id))
    .run();
  return c.json({ group: viewOf(group.id), items: loadViews(childrenOf(group.id)) });
});

/** Adds the ingredients that were skipped because they were in stock. */
basket.post("/groups/:id/add-skipped", (c) => {
  const group = getItem(c.req.param("id"));
  if (!group || group.kind !== "group") return c.json({ error: { code: "NOT_FOUND", message: "folder not found" } }, 404);
  const skipped = group.recipeJson?.skipped ?? [];
  const created = skipped.map((entry) => createItem(entry.text, entry.quantity, group.id));
  db().update(basketItems).set({ recipeJson: { ...group.recipeJson!, skipped: [] }, updatedAt: now() }).where(eq(basketItems.id, group.id)).run();
  return c.json({ items: loadViews(created) });
});

basket.post("/groups/:id/items", async (c) => {
  const group = getItem(c.req.param("id"));
  if (!group || group.kind !== "group") return c.json({ error: { code: "NOT_FOUND", message: "folder not found" } }, 404);
  const body = (await c.req.json().catch(() => ({}))) as { text?: string; quantity?: number };
  if (!body.text?.trim()) return c.json({ error: { code: "BAD_REQUEST", message: "text is required" } }, 400);
  const item = createItem(body.text, body.quantity ?? 1, group.id);
  return c.json(viewOf(item.id), 201);
});

basket.post("/from-text", async (c) => {
  const body = (await c.req.json().catch(() => ({}))) as { text?: string; basketId?: string };
  if (!body.text?.trim()) return c.json({ error: { code: "BAD_REQUEST", message: "text is required" } }, 400);
  const basketId = body.basketId ?? DEFAULT_BASKET_ID;
  if (!basketExists(basketId)) return c.json({ error: { code: "NOT_FOUND", message: "basket not found" } }, 404);
  const parts = await splitShoppingText(body.text);
  const items = parts.map((part) => createItem(part.text, part.quantity, null, "item", basketId));
  return c.json({ items: loadViews(items) }, 201);
});

basket.patch("/items/:id", async (c) => {
  const item = getItem(c.req.param("id"));
  if (!item) return c.json({ error: { code: "NOT_FOUND", message: "item not found" } }, 404);
  const body = (await c.req.json().catch(() => ({}))) as { text?: string; quantity?: number; checked?: boolean; sortOrder?: number; parentId?: string | null };
  const patch: Partial<BasketItemRow> = { updatedAt: now() };
  if (typeof body.text === "string" && body.text.trim()) patch.text = body.text.trim();
  if (typeof body.quantity === "number" && body.quantity >= 1) patch.quantity = Math.floor(body.quantity);
  if (typeof body.checked === "boolean") patch.checked = body.checked;
  if (typeof body.sortOrder === "number") patch.sortOrder = body.sortOrder;
  if (body.parentId !== undefined && item.kind === "item") {
    if (body.parentId && getItem(body.parentId)?.kind !== "group") return c.json({ error: { code: "BAD_REQUEST", message: "parentId is not a folder" } }, 400);
    patch.parentId = body.parentId;
  }
  db().update(basketItems).set(patch).where(eq(basketItems.id, item.id)).run();
  if (item.kind === "group" && typeof body.checked === "boolean") {
    db().update(basketItems).set({ checked: body.checked, updatedAt: now() }).where(eq(basketItems.parentId, item.id)).run();
  }
  if (item.kind === "item" && patch.text && patch.text !== item.text) {
    db().delete(basketMatches).where(eq(basketMatches.itemId, item.id)).run();
    enqueue(item.id);
  }
  return c.json(viewOf(item.id));
});

basket.delete("/items/:id", (c) => {
  const id = c.req.param("id");
  const children = childrenOf(id).map((child) => child.id);
  const removed = db().delete(basketItems).where(eq(basketItems.id, id)).returning({ id: basketItems.id }).all();
  if (!removed.length) return c.json({ error: { code: "NOT_FOUND", message: "item not found" } }, 404);
  // Explicit, so it works even on databases whose FK was created without ON DELETE CASCADE.
  if (children.length) db().delete(basketItems).where(inArray(basketItems.id, children)).run();
  tombstone([id, ...children]);
  return c.body(null, 204);
});

/** Moves (default) or copies an item into another basket. */
basket.post("/items/:id/transfer", async (c) => {
  const item = getItem(c.req.param("id"));
  if (!item) return c.json({ error: { code: "NOT_FOUND", message: "item not found" } }, 404);
  const body = (await c.req.json().catch(() => ({}))) as { basketId?: string; copy?: boolean };
  if (!body.basketId || !basketExists(body.basketId)) return c.json({ error: { code: "BAD_REQUEST", message: "basketId must be an existing basket" } }, 400);
  const result = transferItem(item, body.basketId, body.copy === true);
  return c.json(viewOf(result.id));
});

basket.delete("/", (c) => {
  const onlyChecked = c.req.query("checked") === "true";
  const basketId = requestedBasket(c);
  if (!basketId) return c.json({ error: { code: "NOT_FOUND", message: "basket not found" } }, 404);
  const database = db();
  const victims = database
    .select({ id: basketItems.id })
    .from(basketItems)
    .where(and(eq(basketItems.basketId, basketId), onlyChecked ? eq(basketItems.checked, true) : undefined))
    .all();
  for (const victim of victims) {
    const children = childrenOf(victim.id).map((child) => child.id);
    database.delete(basketItems).where(eq(basketItems.id, victim.id)).run();
    if (children.length) database.delete(basketItems).where(inArray(basketItems.id, children)).run();
    tombstone([victim.id, ...children]);
  }
  // Folders whose children were all removed disappear too.
  for (const group of database.select().from(basketItems).where(and(eq(basketItems.kind, "group"), eq(basketItems.basketId, basketId))).all()) {
    if (onlyChecked && group.status !== "PARSING" && childrenOf(group.id).length === 0) {
      database.delete(basketItems).where(eq(basketItems.id, group.id)).run();
      tombstone([group.id]);
    }
  }
  return c.json({ deleted: victims.length });
});

basket.post("/items/:id/rematch", async (c) => {
  const item = getItem(c.req.param("id"));
  if (!item) return c.json({ error: { code: "NOT_FOUND", message: "item not found" } }, 404);
  await rematchItem(item.id);
  return c.json(viewOf(item.id));
});

function storeGuard(store: string) {
  return hasStore(store) ? null : { error: { code: "BAD_REQUEST", message: `unknown store ${store}` } };
}

basket.post("/items/:id/matches/:store/choose", async (c) => {
  const store = c.req.param("store");
  const invalid = storeGuard(store);
  if (invalid) return c.json(invalid, 400);
  const body = (await c.req.json().catch(() => ({}))) as { productId?: string | null };
  const match = chooseMatch(c.req.param("id"), store, body.productId ?? null);
  if (!match) return c.json({ error: { code: "NOT_FOUND", message: "item, store or product not found" } }, 404);
  return c.json(viewOf(c.req.param("id")));
});

basket.post("/items/:id/matches/:store/reject", async (c) => {
  const store = c.req.param("store");
  const invalid = storeGuard(store);
  if (invalid) return c.json(invalid, 400);
  const match = await rejectShown(c.req.param("id"), store);
  if (!match) return c.json({ error: { code: "NOT_FOUND", message: "item or store not found" } }, 404);
  return c.json(viewOf(c.req.param("id")));
});

basket.post("/items/:id/matches/:store/search", async (c) => {
  const store = c.req.param("store");
  const invalid = storeGuard(store);
  if (invalid) return c.json(invalid, 400);
  const body = (await c.req.json().catch(() => ({}))) as { query?: string };
  if (!body.query?.trim()) return c.json({ error: { code: "BAD_REQUEST", message: "query is required" } }, 400);
  const match = await searchMoreCandidates(c.req.param("id"), store, body.query.trim());
  if (!match) return c.json({ error: { code: "NOT_FOUND", message: "item or store not found" } }, 404);
  return c.json(viewOf(c.req.param("id")));
});

basket.get("/compare", (c) => {
  const stores = enabledStoreCodes();
  const basketId = requestedBasket(c);
  if (!basketId) return c.json({ error: { code: "NOT_FOUND", message: "basket not found" } }, 404);
  const items = db()
    .select()
    .from(basketItems)
    .where(and(eq(basketItems.basketId, basketId), eq(basketItems.checked, false), eq(basketItems.kind, "item")))
    .orderBy(asc(basketItems.sortOrder))
    .all();
  const views = loadViews(items);
  const matches: CompareMatch[] = views.flatMap((view) =>
    view.matches.map((match) => ({
      itemId: view.id,
      store: match.store,
      product: match.status === "CHOSEN" ? match.chosen : match.status === "PENDING" ? match.provisional : null,
      confirmed: match.status === "CHOSEN",
    })),
  );
  const comparison = compareBasket(
    items.map((item) => ({ id: item.id, text: item.text, quantity: item.quantity, parsed: item.parsedJson ?? null })),
    matches,
    stores,
  );
  const products = new Map(views.flatMap((view) => view.matches.flatMap((match) => [match.chosen, match.provisional])).filter(Boolean).map((product) => [product!.id, product!]));
  return c.json({ ...comparison, products: Object.fromEntries(products) });
});

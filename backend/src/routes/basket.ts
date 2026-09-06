import { and, asc, desc, eq, gt, inArray } from "drizzle-orm";
import { Hono } from "hono";
import { db, newId, now } from "@/db";
import { basketItems, basketMatches, tombstones, type BasketItemRow } from "@/db/schema";
import { enabledStoreCodes } from "@/db/settings";
import { compareBasket, type CompareMatch } from "@/matching/compare";
import { chooseMatch, enqueue, getItem, getMatches, isRunning, rejectShown, rematchItem, searchMoreCandidates } from "@/matching/pipeline";
import { splitShoppingText } from "@/matching/parse";
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
  return items.map((item) =>
    itemView(
      item,
      matches.filter((match) => match.itemId === item.id),
      products,
    ),
  );
}

function viewOf(itemId: string) {
  const item = getItem(itemId);
  return item ? loadViews([item])[0] : null;
}

function createItem(text: string, quantity: number) {
  const database = db();
  const last = database.select({ sortOrder: basketItems.sortOrder }).from(basketItems).orderBy(desc(basketItems.sortOrder)).get();
  const item: BasketItemRow = {
    id: newId(),
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

basket.get("/", (c) => {
  const since = Number(c.req.query("since") ?? 0);
  const database = db();
  const items = database
    .select()
    .from(basketItems)
    .where(since ? gt(basketItems.updatedAt, since) : undefined)
    .orderBy(asc(basketItems.sortOrder), asc(basketItems.createdAt))
    .all();
  const deleted = database
    .select({ entityId: tombstones.entityId })
    .from(tombstones)
    .where(and(eq(tombstones.collection, "basket"), since ? gt(tombstones.deletedAt, since) : undefined))
    .all()
    .map((row) => row.entityId);
  const anyRunning = items.some((item) => isRunning(item.id) || item.status === "NEW" || item.status === "PARSING" || item.status === "MATCHING");
  return c.json({ serverTime: now(), items: loadViews(items), deletedIds: deleted, processing: anyRunning, stores: enabledStoreCodes() });
});

basket.post("/items", async (c) => {
  const body = (await c.req.json().catch(() => ({}))) as { text?: string; quantity?: number };
  if (!body.text?.trim()) return c.json({ error: { code: "BAD_REQUEST", message: "text is required" } }, 400);
  const item = createItem(body.text, body.quantity ?? 1);
  return c.json(viewOf(item.id), 201);
});

basket.post("/from-text", async (c) => {
  const body = (await c.req.json().catch(() => ({}))) as { text?: string };
  if (!body.text?.trim()) return c.json({ error: { code: "BAD_REQUEST", message: "text is required" } }, 400);
  const parts = await splitShoppingText(body.text);
  const items = parts.map((part) => createItem(part.text, part.quantity));
  return c.json({ items: loadViews(items) }, 201);
});

basket.patch("/items/:id", async (c) => {
  const item = getItem(c.req.param("id"));
  if (!item) return c.json({ error: { code: "NOT_FOUND", message: "item not found" } }, 404);
  const body = (await c.req.json().catch(() => ({}))) as { text?: string; quantity?: number; checked?: boolean; sortOrder?: number };
  const patch: Partial<BasketItemRow> = { updatedAt: now() };
  if (typeof body.text === "string" && body.text.trim()) patch.text = body.text.trim();
  if (typeof body.quantity === "number" && body.quantity >= 1) patch.quantity = Math.floor(body.quantity);
  if (typeof body.checked === "boolean") patch.checked = body.checked;
  if (typeof body.sortOrder === "number") patch.sortOrder = body.sortOrder;
  db().update(basketItems).set(patch).where(eq(basketItems.id, item.id)).run();
  if (patch.text && patch.text !== item.text) {
    db().delete(basketMatches).where(eq(basketMatches.itemId, item.id)).run();
    enqueue(item.id);
  }
  return c.json(viewOf(item.id));
});

basket.delete("/items/:id", (c) => {
  const id = c.req.param("id");
  const removed = db().delete(basketItems).where(eq(basketItems.id, id)).returning({ id: basketItems.id }).all();
  if (!removed.length) return c.json({ error: { code: "NOT_FOUND", message: "item not found" } }, 404);
  db().insert(tombstones).values({ collection: "basket", entityId: id, deletedAt: now() }).onConflictDoNothing().run();
  return c.body(null, 204);
});

basket.delete("/", (c) => {
  const onlyChecked = c.req.query("checked") === "true";
  const database = db();
  const victims = database
    .select({ id: basketItems.id })
    .from(basketItems)
    .where(onlyChecked ? eq(basketItems.checked, true) : undefined)
    .all();
  for (const victim of victims) {
    database.delete(basketItems).where(eq(basketItems.id, victim.id)).run();
    database.insert(tombstones).values({ collection: "basket", entityId: victim.id, deletedAt: now() }).onConflictDoNothing().run();
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
  const items = db().select().from(basketItems).where(eq(basketItems.checked, false)).orderBy(asc(basketItems.sortOrder)).all();
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

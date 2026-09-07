import { and, asc, eq, inArray } from "drizzle-orm";
import { Hono } from "hono";
import { db, newId, now } from "@/db";
import { basketItems, basketMatches, baskets, tombstones, DEFAULT_BASKET_ID, type BasketItemRow } from "@/db/schema";
import { enqueue } from "@/matching/pipeline";

export const basketsRoute = new Hono();

export function listBaskets() {
  return db().select().from(baskets).orderBy(asc(baskets.sortOrder), asc(baskets.createdAt)).all();
}

export function getBasket(id: string) {
  return db().select().from(baskets).where(eq(baskets.id, id)).get() ?? null;
}

function withCounts() {
  const rows = db().select({ basketId: basketItems.basketId, checked: basketItems.checked, kind: basketItems.kind }).from(basketItems).all();
  return listBaskets().map((basket) => ({
    ...basket,
    itemCount: rows.filter((row) => row.basketId === basket.id && row.kind === "item").length,
    openCount: rows.filter((row) => row.basketId === basket.id && row.kind === "item" && !row.checked).length,
  }));
}

basketsRoute.get("/", (c) => c.json({ baskets: withCounts() }));

basketsRoute.post("/", async (c) => {
  const body = (await c.req.json().catch(() => ({}))) as { name?: string; emoji?: string };
  if (!body.name?.trim()) return c.json({ error: { code: "BAD_REQUEST", message: "name is required" } }, 400);
  const last = listBaskets().at(-1);
  const basket = { id: newId(), name: body.name.trim(), emoji: body.emoji?.trim() || null, sortOrder: (last?.sortOrder ?? -1) + 1, createdAt: now(), updatedAt: now() };
  db().insert(baskets).values(basket).run();
  return c.json({ ...basket, itemCount: 0, openCount: 0 }, 201);
});

basketsRoute.patch("/:id", async (c) => {
  const basket = getBasket(c.req.param("id"));
  if (!basket) return c.json({ error: { code: "NOT_FOUND", message: "basket not found" } }, 404);
  const body = (await c.req.json().catch(() => ({}))) as { name?: string; emoji?: string | null; sortOrder?: number };
  db()
    .update(baskets)
    .set({
      ...(typeof body.name === "string" && body.name.trim() ? { name: body.name.trim() } : {}),
      ...(body.emoji !== undefined ? { emoji: body.emoji?.trim() || null } : {}),
      ...(typeof body.sortOrder === "number" ? { sortOrder: body.sortOrder } : {}),
      updatedAt: now(),
    })
    .where(eq(baskets.id, basket.id))
    .run();
  return c.json(withCounts().find((entry) => entry.id === basket.id));
});

basketsRoute.delete("/:id", (c) => {
  const basket = getBasket(c.req.param("id"));
  if (!basket) return c.json({ error: { code: "NOT_FOUND", message: "basket not found" } }, 404);
  if (listBaskets().length <= 1) return c.json({ error: { code: "BAD_REQUEST", message: "the last basket cannot be deleted" } }, 400);
  const database = db();
  const victims = database.select({ id: basketItems.id }).from(basketItems).where(eq(basketItems.basketId, basket.id)).all();
  database.delete(basketItems).where(eq(basketItems.basketId, basket.id)).run();
  for (const victim of victims) database.insert(tombstones).values({ collection: "basket", entityId: victim.id, deletedAt: now() }).onConflictDoNothing().run();
  database.delete(baskets).where(eq(baskets.id, basket.id)).run();
  database.insert(tombstones).values({ collection: "baskets", entityId: basket.id, deletedAt: now() }).onConflictDoNothing().run();
  return c.body(null, 204);
});

/** Moves or copies an item (with its children and matches) into another basket. */
export function transferItem(item: BasketItemRow, targetBasketId: string, copy: boolean): BasketItemRow {
  const database = db();
  const children = database.select().from(basketItems).where(eq(basketItems.parentId, item.id)).all();
  if (!copy) {
    database.update(basketItems).set({ basketId: targetBasketId, parentId: null, updatedAt: now() }).where(eq(basketItems.id, item.id)).run();
    if (children.length) database.update(basketItems).set({ basketId: targetBasketId, updatedAt: now() }).where(inArray(basketItems.id, children.map((child) => child.id))).run();
    return database.select().from(basketItems).where(eq(basketItems.id, item.id)).get()!;
  }
  const cloneRow = (row: BasketItemRow, parentId: string | null) => {
    const clone: BasketItemRow = { ...row, id: newId(), basketId: targetBasketId, parentId, checked: false, createdAt: now(), updatedAt: now() };
    database.insert(basketItems).values(clone).run();
    const matches = database.select().from(basketMatches).where(eq(basketMatches.itemId, row.id)).all();
    for (const match of matches) database.insert(basketMatches).values({ ...match, id: newId(), itemId: clone.id, updatedAt: now() }).run();
    if (row.kind === "item" && !matches.length) enqueue(clone.id);
    return clone;
  };
  const clone = cloneRow(item, null);
  for (const child of children) cloneRow(child, clone.id);
  return clone;
}

export function basketExists(id: string) {
  return Boolean(getBasket(id));
}

export { DEFAULT_BASKET_ID };
export const basketFilter = (basketId: string) => and(eq(basketItems.basketId, basketId));

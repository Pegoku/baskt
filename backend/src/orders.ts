import { and, desc, eq, gt, inArray } from "drizzle-orm";
import { db, newId, now } from "@/db";
import { purchaseLines, purchases, type PurchaseLineRow, type PurchaseRow, type ProductRow } from "@/db/schema";
import { productsByIds } from "@/stores/search";

/** Ticks within this window at the same store belong to one shopping trip. */
const TRIP_WINDOW_MS = 6 * 60 * 60 * 1000;

export type BoughtInput = {
  store: string;
  name: string;
  quantity: number;
  /** Price for the whole line (unit price × quantity), when known. */
  totalPriceCents: number | null;
  unitPriceCents: number | null;
  productId: string | null;
  itemId: string | null;
  barcode: string | null;
  dealText?: string | null;
  purchasedAt?: number | null;
};

/** The shopping-mode purchase this tick belongs to: the open trip at that store, or a new one. */
function tripFor(store: string, at: number): PurchaseRow {
  const database = db();
  const open = database
    .select()
    .from(purchases)
    .where(and(eq(purchases.store, store), eq(purchases.source, "shop"), gt(purchases.purchasedAt, at - TRIP_WINDOW_MS)))
    .orderBy(desc(purchases.purchasedAt))
    .get();
  if (open && open.purchasedAt - TRIP_WINDOW_MS < at) return open;
  const row: PurchaseRow = { id: newId(), store, purchasedAt: at, totalCents: 0, source: "shop", createdAt: now() };
  database.insert(purchases).values(row).run();
  return row;
}

function retotal(purchaseId: string) {
  const database = db();
  const lines = database.select({ total: purchaseLines.totalPriceCents }).from(purchaseLines).where(eq(purchaseLines.purchaseId, purchaseId)).all();
  if (!lines.length) {
    database.delete(purchases).where(eq(purchases.id, purchaseId)).run();
    return null;
  }
  const totalCents = lines.reduce((sum, line) => sum + line.total, 0);
  database.update(purchases).set({ totalCents }).where(eq(purchases.id, purchaseId)).run();
  return database.select().from(purchases).where(eq(purchases.id, purchaseId)).get() ?? null;
}

/** Records something bought in the store as a line of today's trip; returns the trip and the line. */
export function recordBought(input: BoughtInput): { purchase: PurchaseRow; line: PurchaseLineRow } {
  const database = db();
  const at = input.purchasedAt ?? now();
  const trip = tripFor(input.store, at);
  const count = database.select({ id: purchaseLines.id }).from(purchaseLines).where(eq(purchaseLines.purchaseId, trip.id)).all().length;
  const line: PurchaseLineRow = {
    id: newId(),
    purchaseId: trip.id,
    name: input.name,
    productId: input.productId,
    quantity: input.quantity,
    unitPriceCents: input.unitPriceCents,
    totalPriceCents: input.totalPriceCents ?? 0,
    dealText: input.dealText ?? null,
    sortOrder: count,
    itemId: input.itemId,
    barcode: input.barcode,
  };
  database.insert(purchaseLines).values(line).run();
  return { purchase: retotal(trip.id) ?? trip, line };
}

/** Un-ticking in the store: the line disappears again (and the trip, if it was the only one). */
export function forgetBought(itemId: string) {
  const database = db();
  const lines = database.select().from(purchaseLines).where(eq(purchaseLines.itemId, itemId)).all();
  if (!lines.length) return 0;
  database.delete(purchaseLines).where(eq(purchaseLines.itemId, itemId)).run();
  for (const id of new Set(lines.map((line) => line.purchaseId))) retotal(id);
  return lines.length;
}

export type PurchaseHistory = {
  purchases: Array<{ purchase: PurchaseRow; lines: PurchaseLineRow[] }>;
  products: Record<string, ProductRow>;
};

/** Every purchase with its lines, newest first, plus the products the lines point at (for pictures). */
export function purchaseHistory(limit = 200): PurchaseHistory {
  const database = db();
  const rows = database.select().from(purchases).orderBy(desc(purchases.purchasedAt)).limit(limit).all();
  const lines = rows.length ? database.select().from(purchaseLines).where(inArray(purchaseLines.purchaseId, rows.map((row) => row.id))).orderBy(purchaseLines.sortOrder).all() : [];
  const products = productsByIds([...new Set(lines.map((line) => line.productId).filter((id): id is string => Boolean(id)))]);
  return {
    purchases: rows.map((purchase) => ({ purchase, lines: lines.filter((line) => line.purchaseId === purchase.id) })),
    products: Object.fromEntries(products.map((product) => [product.id, product])),
  };
}

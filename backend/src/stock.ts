import { desc, eq } from "drizzle-orm";
import { db, newId, now } from "@/db";
import { stock, type StockRow } from "@/db/schema";
import { expandTokens, normalizeText, tokenize } from "@/lib/text";

export function listStock(): StockRow[] {
  return db().select().from(stock).orderBy(desc(stock.updatedAt)).all();
}

export type StockInput = {
  text: string;
  quantityText?: string | null;
  productId?: string | null;
  imageUrl?: string | null;
  barcode?: string | null;
};

export function addStock(
  input: string | StockInput,
  quantityText: string | null = null,
): StockRow {
  const data: StockInput =
    typeof input === "string" ? { text: input, quantityText } : input;
  const canonical = normalizeText(data.text);
  const rows = listStock();
  const existing = rows.find(
    (row) =>
      (data.productId && row.productId === data.productId) ||
      (data.barcode && row.barcode === data.barcode) ||
      row.canonical === canonical,
  );
  const patch = {
    quantityText: data.quantityText ?? existing?.quantityText ?? null,
    productId: data.productId ?? existing?.productId ?? null,
    imageUrl: data.imageUrl ?? existing?.imageUrl ?? null,
    barcode: data.barcode ?? existing?.barcode ?? null,
  };
  if (existing) {
    db()
      .update(stock)
      .set({ ...patch, updatedAt: now() })
      .where(eq(stock.id, existing.id))
      .run();
    return { ...existing, ...patch, updatedAt: now() };
  }
  const row: StockRow = {
    id: newId(),
    text: data.text.trim(),
    canonical,
    ...patch,
    addedAt: now(),
    updatedAt: now(),
  };
  db().insert(stock).values(row).run();
  return row;
}

/** Finds the stock entry for a scanned product (by product id or barcode). */
export function stockForProduct(
  productId: string | null,
  barcode: string | null,
): StockRow | null {
  return (
    listStock().find(
      (row) =>
        (productId && row.productId === productId) ||
        (barcode && row.barcode === barcode),
    ) ?? null
  );
}

export function removeStock(id: string) {
  return (
    db().delete(stock).where(eq(stock.id, id)).returning({ id: stock.id }).all()
      .length > 0
  );
}

/** True when a shopping-list text (e.g. "flour 500 g") is covered by something in stock (e.g. "flour"). */
export function inStock(
  text: string,
  rows: StockRow[] = listStock(),
): StockRow | null {
  return matchByText(text, rows);
}

/** Finds the row whose text means the same thing as `text` (either one covering the other's words). */
export function matchByText<T extends { text: string }>(
  text: string,
  rows: T[],
): T | null {
  const itemTokens = new Set(
    expandTokens(
      tokenize(text).filter((token) => !/^\d+([.,]\d+)?$/.test(token)),
    ),
  );
  for (const row of rows) {
    const stockTokens = tokenize(row.text).filter(
      (token) => !/^\d+([.,]\d+)?$/.test(token),
    );
    if (!stockTokens.length) continue;
    const covered = stockTokens.every(
      (token) =>
        itemTokens.has(token) ||
        expandTokens([token]).some((alternative) =>
          itemTokens.has(alternative),
        ),
    );
    if (covered) return row;
    // Also the other way round: a short idea ("penne") is in stock when a longer stock line ("Penne Durum Tarwe 500 g") covers it.
    const stockSet = new Set(expandTokens(stockTokens));
    const ideaTokens = [...itemTokens].filter((token) => token.length > 2);
    if (ideaTokens.length && ideaTokens.every((token) => stockSet.has(token)))
      return row;
  }
  return null;
}

import { desc, eq } from "drizzle-orm";
import { db, newId, now } from "@/db";
import { stock, type StockRow } from "@/db/schema";
import { expandTokens, normalizeText, tokenize } from "@/lib/text";

export function listStock(): StockRow[] {
  return db().select().from(stock).orderBy(desc(stock.updatedAt)).all();
}

export function addStock(text: string, quantityText: string | null = null): StockRow {
  const canonical = normalizeText(text);
  const existing = listStock().find((row) => row.canonical === canonical);
  if (existing) {
    db().update(stock).set({ quantityText: quantityText ?? existing.quantityText, updatedAt: now() }).where(eq(stock.id, existing.id)).run();
    return { ...existing, quantityText: quantityText ?? existing.quantityText, updatedAt: now() };
  }
  const row: StockRow = { id: newId(), text: text.trim(), canonical, quantityText, addedAt: now(), updatedAt: now() };
  db().insert(stock).values(row).run();
  return row;
}

export function removeStock(id: string) {
  return db().delete(stock).where(eq(stock.id, id)).returning({ id: stock.id }).all().length > 0;
}

/** True when a shopping-list text (e.g. "flour 500 g") is covered by something in stock (e.g. "flour"). */
export function inStock(text: string, rows: StockRow[] = listStock()): StockRow | null {
  const itemTokens = new Set(expandTokens(tokenize(text).filter((token) => !/^\d+([.,]\d+)?$/.test(token))));
  for (const row of rows) {
    const stockTokens = tokenize(row.text).filter((token) => !/^\d+([.,]\d+)?$/.test(token));
    if (!stockTokens.length) continue;
    const covered = stockTokens.every((token) => itemTokens.has(token) || expandTokens([token]).some((alternative) => itemTokens.has(alternative)));
    if (covered) return row;
  }
  return null;
}

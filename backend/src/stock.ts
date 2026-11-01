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

/** Recipe measures and fillers ("1 chorro de aceite", "2 cloves of garlic") that say nothing about what the item is. */
const MEASURE_WORDS = new Set([
  // es
  "taza", "tazas", "cucharada", "cucharadas", "cucharadita", "cucharaditas", "chorro", "chorrito", "pizca",
  "pizcas", "pellizco", "unidad", "unidades", "loncha", "lonchas", "rebanada", "rebanadas", "gramo", "gramos",
  "litro", "litros", "diente", "dientes", "punado", "ramita", "ramitas", "vaso", "vasos", "lata", "latas",
  "sobre", "sobres", "trozo", "trozos", "gusto", "de", "del", "al", "la", "el", "un", "una",
  // en
  "cup", "cups", "tbsp", "tsp", "tablespoon", "tablespoons", "teaspoon", "teaspoons", "pinch", "dash", "splash",
  "clove", "cloves", "slice", "slices", "piece", "pieces", "gram", "grams", "handful", "can", "of", "a",
  // nl
  "eetlepel", "eetlepels", "theelepel", "theelepels", "snufje", "scheutje", "teen", "teentjes", "plak", "plakken",
  "stuk", "stuks", "blik", "handje", "van",
  // units
  "g", "gr", "kg", "ml", "cl", "dl", "l", "tl",
]);

/** Words after which a stock line names what the product comes in or with ("tonijn in olijfolie", "zout met jodium"). */
const STOCK_HEAD_BREAKS = new Set(["in", "met", "en", "con", "with", "op", "a"]);

function withoutMeasures(text: string) {
  const kept = tokenize(text).filter((token) => !MEASURE_WORDS.has(token));
  return kept.length ? kept.join(" ") : text;
}

/** The part of a stock line that says what it is: "Tonijn in olijfolie" is tuna, not oil. */
function stockHead(text: string) {
  const head: string[] = [];
  for (const token of tokenize(text)) {
    if (head.length && STOCK_HEAD_BREAKS.has(token)) break;
    head.push(token);
  }
  return head;
}

/** True when a shopping-list text (e.g. "flour 500 g", "1 pizca de sal") is covered by something in stock. */
export function inStock(
  text: string,
  rows: StockRow[] = listStock(),
): StockRow | null {
  const heads = rows.map((row) => ({ text: stockHead(row.text).join(" "), row }));
  return matchByText(withoutMeasures(text), heads)?.row ?? null;
}

/**
 * Stock match for a parsed item, using its canonical name and the per-store search terms, so a Spanish
 * recipe line ("sal") finds Dutch stock ("Keukenzout met jodium"). A search term also matches the end of
 * a compound stock word (olie → olijfolie), but only in the stock line's head, so "Tonijn in olijfolie"
 * does not count as oil.
 */
export function inStockParsed(
  parsed: { canonicalName: string; queries?: Record<string, string>; fallbackQuery?: string | null },
  rows: StockRow[] = listStock(),
): StockRow | null {
  const byName = inStock(parsed.canonicalName, rows);
  if (byName) return byName;
  const terms = new Set(
    [...Object.values(parsed.queries ?? {}), parsed.fallbackQuery ?? ""]
      .map((term) => tokenize(withoutMeasures(term)))
      .filter((tokens) => tokens.length)
      .map((tokens) => tokens.join(" ")),
  );
  for (const row of rows) {
    const head = stockHead(row.text);
    for (const term of terms) {
      const covered = term
        .split(" ")
        .every((token) =>
          head.some(
            (word) =>
              word === token ||
              (token.length >= 3 && word.length > token.length && word.endsWith(token)),
          ),
        );
      if (covered) return row;
    }
  }
  return null;
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

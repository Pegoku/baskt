import { desc, eq } from "drizzle-orm";
import { db, newId, now } from "@/db";
import { purchaseLines, purchases, type ProductRow, type PurchaseLineRow, type PurchaseRow } from "@/db/schema";
import { attemptOrder, noteCall, noteFailure, noteUsage } from "@/ai/pool";
import { normalizeText, tokenize, tokenSimilarity } from "@/lib/text";
import { hasStore } from "@/stores/registry";
import { searchStore } from "@/stores/search";

export type ReceiptLine = { name: string; quantity: number; unitPriceCents: number | null; totalPriceCents: number; dealText: string | null };
export type ReceiptScan = { store: string | null; purchasedAt: string | null; totalCents: number | null; lines: ReceiptLine[]; notes: string | null };

const SYSTEM = `You read photos of Dutch supermarket receipts (Albert Heijn, Jumbo, Lidl, ...). Return ONLY JSON:
{"store": "AH"|"JUMBO"|"LIDL"|other short store code or null, "purchasedAt": "YYYY-MM-DD" or null, "totalCents": integer total paid in cents or null,
 "lines": [{"name": product text exactly as printed, "quantity": number (default 1), "unitPriceCents": integer or null, "totalPriceCents": integer, "dealText": bonus/discount text or null}], "notes": null or a short remark}.
Include every product line, also from continued pages; skip totals, deposits (statiegeld) and payment lines; negative discount lines become dealText on the product above.`;

/** Sends receipt images (data URLs) to a vision model and parses the lines; tries the vision pool in order. */
export async function scanReceipt(images: string[]): Promise<ReceiptScan> {
  const order = attemptOrder("vision");
  if (!order.length) throw new Error("AI is not configured");
  let content = "";
  let lastError = "no vision provider answered";
  for (const target of order) {
    noteCall(target.id);
    try {
      const response = await fetch(`${target.baseUrl}/chat/completions`, {
        method: "POST",
        headers: { authorization: `Bearer ${target.apiKey}`, "content-type": "application/json" },
        body: JSON.stringify({
          model: target.model,
          temperature: 0,
          max_tokens: 6000,
          response_format: { type: "json_object" },
          messages: [
            { role: "system", content: SYSTEM },
            { role: "user", content: [{ type: "text", text: `Read ${images.length > 1 ? "these receipt pages as one purchase" : "this receipt"}.` }, ...images.map((url) => ({ type: "image_url", image_url: { url } }))] },
          ],
        }),
        signal: AbortSignal.timeout(120_000),
      });
      if (!response.ok) {
        const text = await response.text();
        const retryAfter = Number(response.headers.get("retry-after"));
        lastError = `Receipt model HTTP ${response.status}: ${text.slice(0, 200)}`;
        noteFailure(target.id, lastError, response.status === 429 ? Math.min(60_000, (Number.isFinite(retryAfter) && retryAfter > 0 ? retryAfter : 5) * 1000) : response.status >= 500 ? 15_000 : 0);
        continue;
      }
      const payload = (await response.json()) as { choices?: Array<{ message?: { content?: string } }>; usage?: { prompt_tokens?: number; completion_tokens?: number } };
      noteUsage(target.id, payload.usage?.prompt_tokens ?? 0, payload.usage?.completion_tokens ?? 0);
      content = payload.choices?.[0]?.message?.content?.trim().replace(/^```(?:json)?\s*|```$/g, "") ?? "";
      if (content) break;
      lastError = "Receipt model returned nothing";
      noteFailure(target.id, lastError);
    } catch (error) {
      lastError = error instanceof Error ? error.message : String(error);
      noteFailure(target.id, lastError, 15_000);
    }
  }
  if (!content) throw new Error(lastError);
  const raw = JSON.parse(content) as Partial<ReceiptScan> & { lines?: Array<Partial<ReceiptLine>> };
  const lines = (raw.lines ?? [])
    .filter((line) => typeof line.name === "string" && line.name.trim() && typeof line.totalPriceCents === "number")
    .map((line) => ({
      name: line.name!.trim(),
      quantity: typeof line.quantity === "number" && line.quantity > 0 ? line.quantity : 1,
      unitPriceCents: typeof line.unitPriceCents === "number" ? Math.round(line.unitPriceCents) : null,
      totalPriceCents: Math.round(line.totalPriceCents!),
      dealText: typeof line.dealText === "string" && line.dealText.trim() ? line.dealText.trim() : null,
    }));
  const store = typeof raw.store === "string" && hasStore(raw.store.toUpperCase()) ? raw.store.toUpperCase() : typeof raw.store === "string" ? raw.store.toUpperCase() : null;
  return {
    store,
    purchasedAt: typeof raw.purchasedAt === "string" ? raw.purchasedAt : null,
    totalCents: typeof raw.totalCents === "number" ? Math.round(raw.totalCents) : lines.reduce((sum, line) => sum + line.totalPriceCents, 0),
    lines,
    notes: typeof raw.notes === "string" ? raw.notes : null,
  };
}

/** Best-effort match of a receipt line to a known product at that store (searches the store when needed). */
export async function matchReceiptLine(store: string, name: string): Promise<ProductRow | null> {
  if (!hasStore(store)) return null;
  try {
    const result = await searchStore(store, name, { limit: 10 });
    const tokens = tokenize(name);
    let best: { product: ProductRow; score: number } | null = null;
    for (const product of result.products) {
      const productTokens = tokenize(product.title);
      let score = 0;
      for (const token of tokens) score += Math.max(0, ...productTokens.map((candidate) => tokenSimilarity(token, candidate)));
      score /= Math.max(tokens.length, 1);
      if (!best || score > best.score) best = { product, score };
    }
    return best && best.score >= 0.55 ? best.product : null;
  } catch {
    return null;
  }
}

export function savePurchase(input: { store: string; purchasedAt: string | null; totalCents: number; source: string; lines: Array<ReceiptLine & { productId: string | null }> }): PurchaseRow {
  const database = db();
  const purchasedAt = input.purchasedAt ? Date.parse(`${input.purchasedAt}T12:00:00`) || now() : now();
  const row: PurchaseRow = { id: newId(), store: input.store, purchasedAt, totalCents: input.totalCents, source: input.source, createdAt: now() };
  database.insert(purchases).values(row).run();
  for (const [index, line] of input.lines.entries()) {
    database
      .insert(purchaseLines)
      .values({
        id: newId(),
        purchaseId: row.id,
        name: line.name,
        productId: line.productId,
        quantity: line.quantity,
        unitPriceCents: line.unitPriceCents,
        totalPriceCents: line.totalPriceCents,
        dealText: line.dealText,
        sortOrder: index,
      })
      .run();
  }
  return row;
}

export function listPurchases(limit = 50): Array<PurchaseRow & { lineCount: number }> {
  const rows = db().select().from(purchases).orderBy(desc(purchases.purchasedAt)).limit(limit).all();
  return rows.map((row) => ({ ...row, lineCount: db().select({ id: purchaseLines.id }).from(purchaseLines).where(eq(purchaseLines.purchaseId, row.id)).all().length }));
}

export function purchaseWithLines(id: string): { purchase: PurchaseRow; lines: PurchaseLineRow[] } | null {
  const purchase = db().select().from(purchases).where(eq(purchases.id, id)).get();
  if (!purchase) return null;
  return { purchase, lines: db().select().from(purchaseLines).where(eq(purchaseLines.purchaseId, id)).orderBy(purchaseLines.sortOrder).all() };
}

export function deletePurchase(id: string) {
  db().delete(purchaseLines).where(eq(purchaseLines.purchaseId, id)).run();
  return db().delete(purchases).where(eq(purchases.id, id)).returning({ id: purchases.id }).all().length > 0;
}

/** Spend per month and per store, plus the most bought products, for the dashboard. */
export function spendSummary(months = 6) {
  const since = new Date();
  since.setMonth(since.getMonth() - months + 1, 1);
  since.setHours(0, 0, 0, 0);
  const rows = db().select().from(purchases).all().filter((row) => row.purchasedAt >= since.getTime());
  const byMonth = new Map<string, { month: string; totalCents: number; perStore: Record<string, number>; count: number }>();
  for (const row of rows) {
    const date = new Date(row.purchasedAt);
    const key = `${date.getFullYear()}-${String(date.getMonth() + 1).padStart(2, "0")}`;
    const entry = byMonth.get(key) ?? { month: key, totalCents: 0, perStore: {}, count: 0 };
    entry.totalCents += row.totalCents;
    entry.perStore[row.store] = (entry.perStore[row.store] ?? 0) + row.totalCents;
    entry.count += 1;
    byMonth.set(key, entry);
  }
  const lines = rows.length ? db().select().from(purchaseLines).all().filter((line) => rows.some((row) => row.id === line.purchaseId)) : [];
  const products = new Map<string, { name: string; times: number; totalCents: number }>();
  for (const line of lines) {
    const key = line.productId ?? normalizeText(line.name);
    const entry = products.get(key) ?? { name: line.name, times: 0, totalCents: 0 };
    entry.times += 1;
    entry.totalCents += line.totalPriceCents;
    products.set(key, entry);
  }
  return {
    months: Array.from(byMonth.values()).sort((a, b) => a.month.localeCompare(b.month)),
    totalCents: rows.reduce((sum, row) => sum + row.totalCents, 0),
    purchases: rows.length,
    topProducts: Array.from(products.values()).sort((a, b) => b.totalCents - a.totalCents).slice(0, 15),
  };
}

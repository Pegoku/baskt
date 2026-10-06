import { and, desc, eq, inArray, isNull } from "drizzle-orm";
import { mkdtemp, readdir, rm } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { chatJson } from "@/ai/client";
import { db, newId, now } from "@/db";
import { basketItems, basketMatches, purchaseLines, purchases, type ProductRow, type PurchaseLineRow, type PurchaseRow } from "@/db/schema";
import { addStock } from "@/stock";
import { productsByIds } from "@/stores/search";
import { attemptOrder, cooldownFor, noteCall, noteFailure, noteUsage } from "@/ai/pool";
import { normalizeText, tokenize, tokenSimilarity } from "@/lib/text";
import { hasStore } from "@/stores/registry";
import { searchStore } from "@/stores/search";

export type ReceiptLine = { name: string; quantity: number; unitPriceCents: number | null; totalPriceCents: number; dealText: string | null; /** The open basket item this line pays for, when one was found. */ itemId?: string | null };
export type ReceiptScan = { store: string | null; purchasedAt: string | null; totalCents: number | null; lines: ReceiptLine[]; notes: string | null };

const SCHEMA = `Return ONLY JSON:
{"store": "AH"|"JUMBO"|"LIDL"|other short store code or null, "purchasedAt": "YYYY-MM-DD" or null, "totalCents": integer total paid in cents or null,
 "lines": [{"name": product text exactly as printed, "quantity": number (default 1), "unitPriceCents": integer or null, "totalPriceCents": integer, "dealText": bonus/discount text or null}], "notes": null or a short remark}.
Include every product line, also from continued pages; skip totals, deposits (statiegeld) and payment lines; negative discount lines become dealText on the product above.`;

const SYSTEM = `You read photos of Dutch supermarket receipts (Albert Heijn, Jumbo, Lidl, ...). ${SCHEMA}`;

const TEXT_SYSTEM = `You read the text extracted from Dutch supermarket receipts (Albert Heijn, Jumbo, Lidl, ...), including digital receipts (kassabon) saved as PDF. Column order and spacing may be scrambled by the extraction; reconstruct each product line from name, quantity and price. ${SCHEMA}`;

/** A receipt as uploaded: a photo or a PDF (digital receipt from the store app, or a scanned paper one). */
export type ReceiptUpload = { type: string; bytes: Uint8Array };

function parseScan(content: string): ReceiptScan {
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

/** Text of a PDF with real text in it (a digital receipt); empty for scans that are just pictures. */
export async function pdfText(bytes: Uint8Array): Promise<string> {
  const { extractText, getDocumentProxy } = await import("unpdf");
  // pdf.js takes the buffer over (it is detached afterwards), so it gets its own copy.
  const pdf = await getDocumentProxy(new Uint8Array(bytes));
  const { text } = await extractText(pdf, { mergePages: true });
  return text.replace(/[ \t]+\n/g, "\n").trim();
}

/** Renders every page of a PDF to a PNG data URL with poppler's pdftoppm (scanned receipts saved as PDF). */
export async function pdfPages(bytes: Uint8Array, maxPages = 6): Promise<string[]> {
  const dir = await mkdtemp(join(tmpdir(), "baskt-receipt-"));
  try {
    const source = join(dir, "receipt.pdf");
    await Bun.write(source, bytes);
    const proc = Bun.spawn(["pdftoppm", "-r", "150", "-png", "-f", "1", "-l", String(maxPages), source, join(dir, "page")], { stdout: "ignore", stderr: "pipe" });
    const code = await proc.exited;
    if (code !== 0) throw new Error(`pdftoppm failed: ${(await new Response(proc.stderr).text()).trim().slice(0, 200)}`);
    const pages = (await readdir(dir)).filter((name) => name.startsWith("page") && name.endsWith(".png")).sort();
    return Promise.all(pages.map(async (name) => `data:image/png;base64,${Buffer.from(await Bun.file(join(dir, name)).arrayBuffer()).toString("base64")}`));
  } catch (error) {
    if (error instanceof Error && /ENOENT|not found|executable/i.test(error.message)) throw new Error("This PDF has no text and the server cannot render it (pdftoppm is not installed)");
    throw error;
  } finally {
    await rm(dir, { recursive: true, force: true });
  }
}

/**
 * Reads uploaded receipts: photos go to the vision model; a PDF goes by its text when it has any (cheaper, and it
 * works without a vision provider), otherwise its pages are rendered and read like photos.
 */
export async function scanReceiptUploads(uploads: ReceiptUpload[]): Promise<ReceiptScan> {
  const images: string[] = [];
  const texts: string[] = [];
  for (const upload of uploads) {
    if (upload.type === "application/pdf" || (upload.bytes.length > 4 && upload.bytes[0] === 0x25 && upload.bytes[1] === 0x50 && upload.bytes[2] === 0x44 && upload.bytes[3] === 0x46)) {
      const text = await pdfText(upload.bytes).catch(() => "");
      if (text.length >= 40) texts.push(text);
      else images.push(...(await pdfPages(upload.bytes)));
    } else {
      images.push(`data:${upload.type || "image/jpeg"};base64,${Buffer.from(upload.bytes).toString("base64")}`);
    }
  }
  if (images.length) {
    const scan = await scanReceipt(images);
    if (!texts.length) return scan;
    const fromText = await scanReceiptText(texts);
    return { ...fromText, lines: [...fromText.lines, ...scan.lines], totalCents: (fromText.totalCents ?? 0) + (scan.totalCents ?? 0) };
  }
  return scanReceiptText(texts);
}

/** Parses receipt text (from a digital PDF) with the text model. */
export async function scanReceiptText(texts: string[]): Promise<ReceiptScan> {
  const body = texts.map((text, index) => (texts.length > 1 ? `--- Receipt ${index + 1} ---\n${text}` : text)).join("\n\n").slice(0, 24_000);
  const raw = await chatJson<Record<string, unknown>>(
    [
      { role: "system", content: TEXT_SYSTEM },
      { role: "user", content: `Read ${texts.length > 1 ? "these receipts as one purchase" : "this receipt"}:\n\n${body}` },
    ],
    { maxTokens: 6000, retries: 3 },
  );
  if (!raw) throw new Error("AI is not configured or did not answer");
  return parseScan(JSON.stringify(raw));
}

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
        lastError = `Receipt model HTTP ${response.status}: ${text.slice(0, 200)}`;
        noteFailure(target.id, lastError, cooldownFor(response.status, response.headers.get("retry-after")));
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
  return parseScan(content);
}

/** Receipt-line words that say nothing about the product: the store's own prefix, sizes, counts, stray letters. */
const NOISE = /^(\d+([.,]\d+)?(l|ml|cl|g|kg|gr|st|stuks|x)?|ah|jumbo|lidl|aldi|plus|dirk|bonus)$/;

/** How much of the receipt line's words show up in a title (0..1). */
function lineScore(name: string, title: string): number {
  const all = tokenize(name);
  const tokens = all.filter((token) => token.length > 2 && !NOISE.test(token));
  if (!tokens.length) return 0;
  const titleTokens = tokenize(title);
  let score = 0;
  for (const token of tokens) score += Math.max(0, ...titleTokens.map((candidate) => tokenSimilarity(token, candidate)));
  return score / tokens.length;
}

export type BasketLineMatch = { itemId: string; itemText: string; product: ProductRow | null; score: number };

/**
 * The open basket items (any basket) with the product each would be bought as at this store, so a receipt line can
 * be matched to what was on the list before the store catalogue is searched.
 */
export function openItemsAt(store: string): Array<{ itemId: string; text: string; product: ProductRow | null }> {
  const database = db();
  const items = database.select().from(basketItems).where(and(eq(basketItems.kind, "item"), eq(basketItems.checked, false), isNull(basketItems.boughtAt))).all();
  if (!items.length) return [];
  const matches = database.select().from(basketMatches).where(and(inArray(basketMatches.itemId, items.map((item) => item.id)), eq(basketMatches.store, store))).all();
  const ids = matches.map((match) => match.chosenProductId ?? (match.status === "PENDING" ? match.candidateIds[0] ?? null : null)).filter((id): id is string => Boolean(id));
  const products = new Map(productsByIds(ids).map((product) => [product.id, product]));
  return items.map((item) => {
    const match = matches.find((entry) => entry.itemId === item.id);
    const productId = match?.chosenProductId ?? (match?.status === "PENDING" ? match.candidateIds[0] ?? null : null);
    return { itemId: item.id, text: item.parsedJson?.canonicalName ?? item.text, product: productId ? products.get(productId) ?? null : null };
  });
}

/** The open basket item a receipt line pays for, judged by its product's title and by the item's own words. */
export function matchReceiptLineToBasket(store: string, name: string, candidates = openItemsAt(store), taken = new Set<string>()): BasketLineMatch | null {
  let best: BasketLineMatch | null = null;
  for (const candidate of candidates) {
    if (taken.has(candidate.itemId)) continue;
    const score = Math.max(candidate.product ? lineScore(name, candidate.product.title) : 0, lineScore(name, candidate.text) * 0.95);
    if (!best || score > best.score) best = { itemId: candidate.itemId, itemText: candidate.text, product: candidate.product, score };
  }
  return best && best.score >= 0.6 ? best : null;
}

/** Best-effort match of a receipt line to a known product at that store (searches the store when needed). */
export async function matchReceiptLine(store: string, name: string): Promise<ProductRow | null> {
  if (!hasStore(store)) return null;
  try {
    const result = await searchStore(store, name, { limit: 10 });
    let best: { product: ProductRow; score: number } | null = null;
    for (const product of result.products) {
      const score = lineScore(name, product.title);
      if (!best || score > best.score) best = { product, score };
    }
    return best && best.score >= 0.55 ? best.product : null;
  } catch {
    return null;
  }
}

/**
 * The receipt paid for these list items: they leave the open list the way an in-store tick does (bought, stocked),
 * and items a corrected receipt no longer mentions come back.
 */
function settleItems(lines: Array<{ itemId?: string | null; productId: string | null }>, previous: string[], store: string, at: number) {
  const database = db();
  const wanted = new Map(lines.filter((line) => line.itemId).map((line) => [line.itemId!, line.productId]));
  for (const itemId of previous) {
    if (wanted.has(itemId)) continue;
    database.update(basketItems).set({ checked: false, boughtAt: null, updatedAt: now() }).where(eq(basketItems.id, itemId)).run();
  }
  if (!wanted.size) return;
  const items = database.select().from(basketItems).where(inArray(basketItems.id, [...wanted.keys()])).all();
  const products = new Map(productsByIds([...wanted.values()].filter((id): id is string => Boolean(id))).map((product) => [product.id, product]));
  for (const item of items) {
    const product = wanted.get(item.id) ? products.get(wanted.get(item.id)!) ?? null : null;
    addStock({ text: item.parsedJson?.canonicalName ?? item.text, quantityText: product?.quantityText ?? null, productId: product?.id ?? null, imageUrl: product?.imageUrl ?? null });
    database.update(basketItems).set({ checked: true, boughtAt: at, assignedStore: store, updatedAt: now() }).where(eq(basketItems.id, item.id)).run();
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
        itemId: line.itemId ?? null,
      })
      .run();
  }
  settleItems(input.lines, [], row.store, purchasedAt);
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

/** Replaces a saved purchase's header and lines (the receipt was corrected by hand). */
export function updatePurchase(id: string, input: { store: string; purchasedAt: string | null; totalCents: number; lines: Array<ReceiptLine & { productId: string | null }> }): PurchaseRow | null {
  const database = db();
  const existing = database.select().from(purchases).where(eq(purchases.id, id)).get();
  if (!existing) return null;
  const purchasedAt = input.purchasedAt ? Date.parse(`${input.purchasedAt}T12:00:00`) || existing.purchasedAt : existing.purchasedAt;
  const row: PurchaseRow = { ...existing, store: input.store, purchasedAt, totalCents: input.totalCents };
  database.update(purchases).set({ store: row.store, purchasedAt: row.purchasedAt, totalCents: row.totalCents }).where(eq(purchases.id, id)).run();
  const previous = database.select({ itemId: purchaseLines.itemId }).from(purchaseLines).where(eq(purchaseLines.purchaseId, id)).all().map((line) => line.itemId).filter((itemId): itemId is string => Boolean(itemId));
  database.delete(purchaseLines).where(eq(purchaseLines.purchaseId, id)).run();
  for (const [index, line] of input.lines.entries()) {
    database
      .insert(purchaseLines)
      .values({ id: newId(), purchaseId: id, name: line.name, productId: line.productId, quantity: line.quantity, unitPriceCents: line.unitPriceCents, totalPriceCents: line.totalPriceCents, dealText: line.dealText, sortOrder: index, itemId: line.itemId ?? null })
      .run();
  }
  settleItems(input.lines, previous, row.store, purchasedAt);
  return row;
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

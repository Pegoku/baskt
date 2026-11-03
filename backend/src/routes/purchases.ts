import { Hono } from "hono";
import { deletePurchase, listPurchases, matchReceiptLine, matchReceiptLineToBasket, openItemsAt, purchaseWithLines, savePurchase, scanReceiptUploads, spendSummary, updatePurchase, type ReceiptLine } from "@/receipts";
import { eq, inArray } from "drizzle-orm";
import { db } from "@/db";
import { basketItems, basketMatches, type ProductRow } from "@/db/schema";
import { parseScannedCode } from "@/lib/gs1";
import { markItemBought } from "@/routes/basket";
import { hasStore } from "@/stores/registry";
import { lookupBarcode, productsByIds } from "@/stores/search";
import { enabledStoreCodes } from "@/db/settings";
import { forgetScan, purchaseHistory, recordBought } from "@/orders";
import { addStock } from "@/stock";
import { barcodeKinds, recordBarcodeKind, type BarcodeKind } from "@/barcode-kinds";

export const purchasesRoute = new Hono();

/** Multipart upload (`files`: photos or PDFs, optional `store`) → parsed receipt with product matches; nothing is saved yet. */
purchasesRoute.post("/scan", async (c) => {
  const form = await c.req.formData().catch(() => null);
  if (!form) return c.json({ error: { code: "BAD_REQUEST", message: "multipart form expected" } }, 400);
  const files = form.getAll("files").filter((entry): entry is File => entry instanceof File);
  if (!files.length) return c.json({ error: { code: "BAD_REQUEST", message: "at least one image or PDF is required" } }, 400);
  const uploads = await Promise.all(files.map(async (file) => ({ type: file.type || (file.name.toLowerCase().endsWith(".pdf") ? "application/pdf" : "image/jpeg"), bytes: new Uint8Array(await file.arrayBuffer()) })));
  try {
    const scan = await scanReceiptUploads(uploads);
    const hint = form.get("store");
    const store = typeof hint === "string" && hasStore(hint) ? hint : scan.store && hasStore(scan.store) ? scan.store : null;
    // What is on the list comes first: a line that pays for an open item takes that item's product and ticks it
    // off when the purchase is saved. Only then is the store catalogue searched.
    const candidates = store ? openItemsAt(store) : [];
    const taken = new Set<string>();
    const lines = await Promise.all(
      scan.lines.map(async (line) => {
        const onList = store ? matchReceiptLineToBasket(store, line.name, candidates, taken) : null;
        if (onList) {
          taken.add(onList.itemId);
          const product = onList.product ?? (await matchReceiptLine(store!, line.name));
          return { ...line, productId: product?.id ?? null, product, itemId: onList.itemId, itemText: onList.itemText };
        }
        const product = store ? await matchReceiptLine(store, line.name) : null;
        return { ...line, productId: product?.id ?? null, product, itemId: null, itemText: null };
      }),
    );
    return c.json({ ...scan, store, lines });
  } catch (error) {
    return c.json({ error: { code: "SCAN_FAILED", message: error instanceof Error ? error.message : String(error) } }, 502);
  }
});

purchasesRoute.post("/", async (c) => {
  const body = (await c.req.json().catch(() => ({}))) as { store?: string; purchasedAt?: string | null; totalCents?: number; source?: string; lines?: Array<ReceiptLine & { productId?: string | null }> };
  if (!body.store?.trim()) return c.json({ error: { code: "BAD_REQUEST", message: "store is required" } }, 400);
  const lines = (body.lines ?? []).filter((line) => typeof line.name === "string" && typeof line.totalPriceCents === "number");
  const total = typeof body.totalCents === "number" ? body.totalCents : lines.reduce((sum, line) => sum + line.totalPriceCents, 0);
  const saved = savePurchase({
    store: body.store.trim().toUpperCase(),
    purchasedAt: body.purchasedAt ?? null,
    totalCents: total,
    source: body.source ?? "receipt",
    lines: lines.map((line) => ({ ...line, quantity: line.quantity ?? 1, unitPriceCents: line.unitPriceCents ?? null, dealText: line.dealText ?? null, productId: line.productId ?? null })),
  });
  return c.json(purchaseWithLines(saved.id), 201);
});

purchasesRoute.get("/", (c) => c.json({ purchases: listPurchases() }));

/** Everything bought, with lines and product pictures, for the day-by-day orders view. */
purchasesRoute.get("/history", (c) => c.json(purchaseHistory(Math.min(Number(c.req.query("limit") ?? 200) || 200, 1000))));

/**
 * Something picked up in the store that was not on the list: the barcode is looked up at that store (then the
 * others); the line carries the product when found and just the number otherwise. Either way it goes into stock.
 */
/**
 * A barcode scanned in the store. When it is something on the list (the same product, or one the list item's
 * words describe) that item is ticked off; otherwise it is recorded as an extra purchase.
 */
purchasesRoute.post("/scan-item", async (c) => {
  const body = (await c.req.json().catch(() => ({}))) as { store?: string; barcode?: string; purchasedAt?: number | null; basketId?: string | null };
  if (!body.store || !hasStore(body.store)) return c.json({ error: { code: "BAD_REQUEST", message: "store is required" } }, 400);
  const code = parseScannedCode(body.barcode ?? "");
  if (!code) return c.json({ error: { code: "BAD_REQUEST", message: "not a product barcode" } }, 400);
  const store = body.store;
  const gtin = code.gtin;
  const stores = [store, ...enabledStoreCodes().filter((entry) => entry !== store)];
  const results = await lookupBarcode(stores, gtin);
  const product = results.find((entry) => entry.store === store)?.product ?? results.find((entry) => entry.product)?.product ?? null;
  const at = typeof body.purchasedAt === "number" && body.purchasedAt > 0 ? body.purchasedAt : undefined;
  const scanned = { barcode: gtin, priceCents: code.priceCents };

  const listItem = product ? listItemFor(store, product, body.basketId ?? null) : null;
  if (listItem) {
    const ticked = markItemBought(listItem, store, product!.id, at ?? Date.now(), scanned);
    recordBarcodeKind({ store, productId: product!.id, itemText: ticked.item?.text ?? null, code: body.barcode ?? null, at });
    return c.json({ ...ticked, product, matchedItemId: listItem }, 201);
  }

  const weighed = code.weightGrams != null ? `${code.weightGrams} g` : null;
  const name = product?.title ?? (code.variable || code.priceCents != null ? "Weighed product" : gtin);
  const price = code.priceCents ?? product?.priceCents ?? null;
  const { purchase, line } = recordBought({
    store,
    name,
    quantity: 1,
    unitPriceCents: price,
    totalPriceCents: price,
    productId: product?.id ?? null,
    itemId: null,
    barcode: gtin,
    dealText: product?.dealText ?? null,
    purchasedAt: at,
  });
  addStock({ text: product?.title ?? name, quantityText: weighed ?? product?.quantityText ?? null, productId: product?.id ?? null, imageUrl: product?.imageUrl ?? null, barcode: gtin });
  return c.json({ purchase, line, product, item: null, matchedItemId: null }, 201);
});

/** Undoes an extra scanned at [store] at [purchasedAt] (the phone's scan time): its line and its stock entry. */
purchasesRoute.post("/unscan", async (c) => {
  const body = (await c.req.json().catch(() => ({}))) as { store?: string; barcode?: string; purchasedAt?: number | null };
  if (!body.store || !hasStore(body.store)) return c.json({ error: { code: "BAD_REQUEST", message: "store is required" } }, 400);
  const code = parseScannedCode(body.barcode ?? "");
  if (!code) return c.json({ error: { code: "BAD_REQUEST", message: "not a product barcode" } }, 400);
  const at = typeof body.purchasedAt === "number" && body.purchasedAt > 0 ? body.purchasedAt : Date.now();
  return c.json({ removed: forgetScan(body.store, code.gtin, at) });
});

/**
 * The till-code map for a store: which products carry a pack barcode, which a weighed label, and which nothing.
 * Scans that tick a list item feed it on their own; the phone reports the rest (a label linked to an item by hand,
 * an item that had to be keyed in at the till).
 */
purchasesRoute.get("/barcode-kinds", (c) => c.json({ kinds: barcodeKinds(c.req.query("store") || undefined) }));

purchasesRoute.post("/barcode-kinds", async (c) => {
  const body = (await c.req.json().catch(() => ({}))) as { store?: string; productId?: string | null; itemText?: string | null; code?: string | null; kind?: string | null; at?: number | null };
  if (!body.store || !hasStore(body.store)) return c.json({ error: { code: "BAD_REQUEST", message: "store is required" } }, 400);
  const kind = body.kind === "pack" || body.kind === "label" || body.kind === "none" ? (body.kind as BarcodeKind) : null;
  const entry = recordBarcodeKind({ store: body.store, productId: body.productId, itemText: body.itemText, code: body.code, kind, at: typeof body.at === "number" && body.at > 0 ? body.at : undefined });
  if (!entry) return c.json({ error: { code: "BAD_REQUEST", message: "a product or item text and a code or kind are required" } }, 400);
  return c.json({ entry }, 201);
});

/** The open list item a scanned product stands for: the very product first, then a list entry that describes it. */
function listItemFor(store: string, product: ProductRow, basketId: string | null): string | null {
  let open = openItemsAt(store);
  if (basketId) {
    const inBasket = new Set(db().select({ id: basketItems.id }).from(basketItems).where(eq(basketItems.basketId, basketId)).all().map((row) => row.id));
    open = open.filter((entry) => inBasket.has(entry.itemId));
  }
  if (!open.length) return null;
  const exact = open.find((entry) => entry.product?.id === product.id);
  if (exact) return exact.itemId;
  const candidates = db().select().from(basketMatches).where(inArray(basketMatches.itemId, open.map((entry) => entry.itemId))).all();
  const listed = candidates.find((match) => match.chosenProductId === product.id || match.candidateIds.includes(product.id));
  if (listed) return listed.itemId;
  return matchReceiptLineToBasket(store, product.title, open)?.itemId ?? null;
}

purchasesRoute.get("/summary", (c) => c.json(spendSummary(Math.min(Number(c.req.query("months") ?? 6) || 6, 24))));

purchasesRoute.get("/:id", (c) => {
  const found = purchaseWithLines(c.req.param("id"));
  if (!found) return c.json({ error: { code: "NOT_FOUND", message: "purchase not found" } }, 404);
  const products = productsByIds(found.lines.map((line) => line.productId).filter((id): id is string => Boolean(id)));
  return c.json({ ...found, products: Object.fromEntries(products.map((product) => [product.id, product])) });
});

/** The corrected receipt: same shape as saving, every line replaced. */
purchasesRoute.put("/:id", async (c) => {
  const body = (await c.req.json().catch(() => ({}))) as { store?: string; purchasedAt?: string | null; totalCents?: number | null; lines?: Array<Partial<ReceiptLine> & { productId?: string | null }> };
  if (!body.store?.trim()) return c.json({ error: { code: "BAD_REQUEST", message: "store is required" } }, 400);
  const lines = (body.lines ?? []).filter((line) => typeof line.name === "string" && typeof line.totalPriceCents === "number") as Array<ReceiptLine & { productId?: string | null }>;
  const total = typeof body.totalCents === "number" ? body.totalCents : lines.reduce((sum, line) => sum + line.totalPriceCents, 0);
  const updated = updatePurchase(c.req.param("id"), {
    store: body.store.trim().toUpperCase(),
    purchasedAt: body.purchasedAt ?? null,
    totalCents: total,
    lines: lines.map((line) => ({ ...line, quantity: line.quantity ?? 1, unitPriceCents: line.unitPriceCents ?? null, dealText: line.dealText ?? null, productId: line.productId ?? null })),
  });
  if (!updated) return c.json({ error: { code: "NOT_FOUND", message: "purchase not found" } }, 404);
  const found = purchaseWithLines(updated.id)!;
  const products = productsByIds(found.lines.map((line) => line.productId).filter((id): id is string => Boolean(id)));
  return c.json({ ...found, products: Object.fromEntries(products.map((product) => [product.id, product])) });
});

purchasesRoute.delete("/:id", (c) => (deletePurchase(c.req.param("id")) ? c.body(null, 204) : c.json({ error: { code: "NOT_FOUND", message: "purchase not found" } }, 404)));

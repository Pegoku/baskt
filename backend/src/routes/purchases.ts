import { Hono } from "hono";
import { deletePurchase, listPurchases, matchReceiptLine, purchaseWithLines, savePurchase, scanReceipt, spendSummary, type ReceiptLine } from "@/receipts";
import { hasStore } from "@/stores/registry";
import { lookupBarcode, productsByIds } from "@/stores/search";
import { enabledStoreCodes } from "@/db/settings";
import { purchaseHistory, recordBought } from "@/orders";
import { addStock } from "@/stock";

export const purchasesRoute = new Hono();

/** Multipart upload (`files`, optional `store`) → parsed receipt with product matches; nothing is saved yet. */
purchasesRoute.post("/scan", async (c) => {
  const form = await c.req.formData().catch(() => null);
  if (!form) return c.json({ error: { code: "BAD_REQUEST", message: "multipart form expected" } }, 400);
  const files = form.getAll("files").filter((entry): entry is File => entry instanceof File);
  if (!files.length) return c.json({ error: { code: "BAD_REQUEST", message: "at least one image is required" } }, 400);
  const images = await Promise.all(
    files.map(async (file) => `data:${file.type || "image/jpeg"};base64,${Buffer.from(await file.arrayBuffer()).toString("base64")}`),
  );
  try {
    const scan = await scanReceipt(images);
    const hint = form.get("store");
    const store = typeof hint === "string" && hasStore(hint) ? hint : scan.store && hasStore(scan.store) ? scan.store : null;
    const lines = await Promise.all(
      scan.lines.map(async (line) => {
        const product = store ? await matchReceiptLine(store, line.name) : null;
        return { ...line, productId: product?.id ?? null, product };
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
purchasesRoute.post("/scan-item", async (c) => {
  {
    const body = (await c.req.json().catch(() => ({}))) as { store?: string; barcode?: string; purchasedAt?: number | null };
    const gtin = (body.barcode ?? "").replace(/\D/g, "");
    if (!body.store || !hasStore(body.store)) return c.json({ error: { code: "BAD_REQUEST", message: "store is required" } }, 400);
    if (gtin.length < 8 || gtin.length > 14) return c.json({ error: { code: "BAD_REQUEST", message: "barcode must be 8-14 digits" } }, 400);
    const store = body.store;
    const stores = [store, ...enabledStoreCodes().filter((code) => code !== store)];
    const results = await lookupBarcode(stores, gtin);
    const product = results.find((entry) => entry.store === store)?.product ?? results.find((entry) => entry.product)?.product ?? null;
    const at = typeof body.purchasedAt === "number" && body.purchasedAt > 0 ? body.purchasedAt : undefined;
    const { purchase, line } = recordBought({
      store,
      name: product?.title ?? gtin,
      quantity: 1,
      unitPriceCents: product?.priceCents ?? null,
      totalPriceCents: product?.priceCents ?? null,
      productId: product?.id ?? null,
      itemId: null,
      barcode: gtin,
      dealText: product?.dealText ?? null,
      purchasedAt: at,
    });
    addStock({ text: product?.title ?? gtin, quantityText: product?.quantityText ?? null, productId: product?.id ?? null, imageUrl: product?.imageUrl ?? null, barcode: gtin });
    return c.json({ purchase, line, product }, 201);
  }
});

purchasesRoute.get("/summary", (c) => c.json(spendSummary(Math.min(Number(c.req.query("months") ?? 6) || 6, 24))));

purchasesRoute.get("/:id", (c) => {
  const found = purchaseWithLines(c.req.param("id"));
  if (!found) return c.json({ error: { code: "NOT_FOUND", message: "purchase not found" } }, 404);
  const products = productsByIds(found.lines.map((line) => line.productId).filter((id): id is string => Boolean(id)));
  return c.json({ ...found, products: Object.fromEntries(products.map((product) => [product.id, product])) });
});

purchasesRoute.delete("/:id", (c) => (deletePurchase(c.req.param("id")) ? c.body(null, 204) : c.json({ error: { code: "NOT_FOUND", message: "purchase not found" } }, 404)));

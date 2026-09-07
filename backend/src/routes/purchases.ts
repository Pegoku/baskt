import { Hono } from "hono";
import { deletePurchase, listPurchases, matchReceiptLine, purchaseWithLines, savePurchase, scanReceipt, spendSummary, type ReceiptLine } from "@/receipts";
import { hasStore } from "@/stores/registry";
import { productsByIds } from "@/stores/search";

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

purchasesRoute.get("/summary", (c) => c.json(spendSummary(Math.min(Number(c.req.query("months") ?? 6) || 6, 24))));

purchasesRoute.get("/:id", (c) => {
  const found = purchaseWithLines(c.req.param("id"));
  if (!found) return c.json({ error: { code: "NOT_FOUND", message: "purchase not found" } }, 404);
  const products = productsByIds(found.lines.map((line) => line.productId).filter((id): id is string => Boolean(id)));
  return c.json({ ...found, products: Object.fromEntries(products.map((product) => [product.id, product])) });
});

purchasesRoute.delete("/:id", (c) => (deletePurchase(c.req.param("id")) ? c.body(null, 204) : c.json({ error: { code: "NOT_FOUND", message: "purchase not found" } }, 404)));

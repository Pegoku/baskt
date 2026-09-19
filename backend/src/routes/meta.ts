import { speechTranscript } from "@/routes/speech";
import { translateContent } from "@/matching/translate";
import { findSimilar, setSimilarFeedback, SIMILAR_MAX } from "@/matching/similar";
import { productDetails } from "@/stores/details";
import { asc, desc, eq, inArray, sql } from "drizzle-orm";
import { Hono } from "hono";
import { aiStats } from "@/ai/client";
import { db, now } from "@/db";
import { aiCache, basketItems, basketMatches, priceHistory, products, searchCache } from "@/db/schema";
import { appLanguage, defaultServings, enabledStoreCodes, rankBy, setSetting, skipInStock } from "@/db/settings";
import { addStock, listStock, removeStock } from "@/stock";
import { deleteChoice, listChoices } from "@/matching/memory";
import { deleteName, listNames } from "@/matching/naming";
import { allStores, hasStore, storeHealth } from "@/stores/registry";
import { lookupBarcode, searchStore } from "@/stores/search";
import { priceHistoryFor, recentPriceChanges, runPriceScan, scanStatus } from "@/prices";

export const meta = new Hono();

meta.get("/health", (c) => c.json({ ok: true, version: "0.1.0", serverTime: now(), ai: aiStats(), stores: storeHealth() }));

meta.get("/stores", (c) => {
  const enabled = enabledStoreCodes();
  return c.json({ stores: allStores().map((store) => ({ ...store, enabled: enabled.includes(store.code) })) });
});

function settingsView() {
  return { enabledStores: enabledStoreCodes(), language: appLanguage(), recipeSkipInStock: skipInStock(), defaultServings: defaultServings(), rankBy: rankBy() };
}

meta.get("/settings", (c) => c.json(settingsView()));

meta.patch("/settings", async (c) => {
  const body = (await c.req.json().catch(() => ({}))) as { enabledStores?: string[]; language?: string; recipeSkipInStock?: boolean; defaultServings?: number | null; rankBy?: string };
  if (body.rankBy === "price" || body.rankBy === "unitPrice") setSetting("rankBy", body.rankBy);
  if (typeof body.recipeSkipInStock === "boolean") setSetting("recipeSkipInStock", body.recipeSkipInStock);
  if (body.defaultServings === null || (typeof body.defaultServings === "number" && body.defaultServings > 0 && body.defaultServings <= 50)) {
    setSetting("defaultServings", body.defaultServings);
  }
  if (Array.isArray(body.enabledStores)) {
    const valid = body.enabledStores.filter((code) => typeof code === "string" && hasStore(code));
    if (!valid.length) return c.json({ error: { code: "BAD_REQUEST", message: "at least one known store must stay enabled" } }, 400);
    setSetting("enabledStores", valid);
  }
  if (typeof body.language === "string" && /^[a-zA-Z]{2,3}([-_][a-zA-Z0-9]+)?$/.test(body.language.trim())) {
    setSetting("language", body.language.trim().toLowerCase().split(/[-_]/)[0]);
  }
  return c.json(settingsView());
});

meta.get("/stock", (c) => c.json({ items: listStock() }));

meta.post("/stock", async (c) => {
  const body = (await c.req.json().catch(() => ({}))) as { text?: string; quantityText?: string | null; productId?: string | null; imageUrl?: string | null; barcode?: string | null };
  if (!body.text?.trim()) return c.json({ error: { code: "BAD_REQUEST", message: "text is required" } }, 400);
  return c.json(addStock({ text: body.text, quantityText: body.quantityText ?? null, productId: body.productId ?? null, imageUrl: body.imageUrl ?? null, barcode: body.barcode ?? null }), 201);
});

meta.delete("/stock/:id", (c) => (removeStock(c.req.param("id")) ? c.body(null, 204) : c.json({ error: { code: "NOT_FOUND", message: "not in stock" } }, 404)));

meta.get("/products/search", async (c) => {
  const query = c.req.query("q")?.trim();
  const store = c.req.query("store");
  if (!query) return c.json({ error: { code: "BAD_REQUEST", message: "q is required" } }, 400);
  const stores = store ? [store] : enabledStoreCodes();
  if (stores.some((code) => !hasStore(code))) return c.json({ error: { code: "BAD_REQUEST", message: "unknown store" } }, 400);
  const limit = Math.min(Number(c.req.query("limit") ?? 20) || 20, 40);
  const results = await Promise.all(
    stores.map(async (code) => {
      try {
        const result = await searchStore(code, query, { limit, force: c.req.query("force") === "true" });
        return { store: code, source: result.source, products: result.products, error: null };
      } catch (error) {
        return { store: code, source: "error", products: [], error: error instanceof Error ? error.message : String(error) };
      }
    }),
  );
  return c.json({ query, results });
});

/** Scan result: the product at every enabled store that recognises the barcode. */
meta.get("/products/barcode/:gtin", async (c) => {
  const gtin = c.req.param("gtin").replace(/\D/g, "");
  if (gtin.length < 8 || gtin.length > 14) return c.json({ error: { code: "BAD_REQUEST", message: "gtin must be 8-14 digits" } }, 400);
  const results = await lookupBarcode(enabledStoreCodes(), gtin);
  return c.json({ gtin, results });
});

meta.post("/transcribe", async (c) => {
  const transcript = await speechTranscript(c);
  return typeof transcript === "string" ? c.json({ transcript }) : transcript;
});

meta.post("/translate", async (c) => {
  const body = await c.req.json().catch(() => null);
  if (!body || typeof body.language !== "string" || !/^[a-z]{2,3}(-[a-zA-Z0-9]+)?$/.test(body.language) ||
      !Array.isArray(body.texts) || body.texts.length > 200 || body.texts.some((text: unknown) => typeof text !== "string") ||
      body.texts.join("").length > 24000 ||
      (body.descriptionIndices !== undefined && (!Array.isArray(body.descriptionIndices) || body.descriptionIndices.length > body.texts.length ||
        body.descriptionIndices.some((index: unknown) => !Number.isInteger(index) || Number(index) < 0 || Number(index) >= body.texts.length)))) return c.json({ error: { code: "BAD_REQUEST", message: "Invalid translation request" } }, 400);
  return c.json(await translateContent(body.texts, body.language, body.descriptionIndices));
});

/** "Find something like this": the same or the closest product at every enabled store, best first. */
meta.get("/products/:id/similar", async (c) => {
  const product = db().select().from(products).where(eq(products.id, c.req.param("id"))).get();
  if (!product) return c.json({ error: { code: "NOT_FOUND", message: "product not found" } }, 404);
  const requested = c.req.query("store");
  const stores = requested ? [requested] : enabledStoreCodes();
  if (stores.some((code) => !hasStore(code))) return c.json({ error: { code: "BAD_REQUEST", message: "unknown store" } }, 400);
  const limit = Math.min(Number(c.req.query("limit") ?? 8) || 8, SIMILAR_MAX);
  return c.json({ product, results: await findSimilar(product, stores, { limit }) });
});

/** Thumbs on a similar-product pairing: `{productId, up: true|false|null}` (null forgets it). */
meta.post("/products/:id/similar/feedback", async (c) => {
  const reference = db().select({ id: products.id }).from(products).where(eq(products.id, c.req.param("id"))).get();
  if (!reference) return c.json({ error: { code: "NOT_FOUND", message: "product not found" } }, 404);
  const body = (await c.req.json().catch(() => null)) as { productId?: unknown; up?: unknown } | null;
  if (!body || typeof body.productId !== "string" || !(typeof body.up === "boolean" || body.up === null))
    return c.json({ error: { code: "BAD_REQUEST", message: "productId and up (true, false or null) are required" } }, 400);
  if (body.productId === reference.id) return c.json({ error: { code: "BAD_REQUEST", message: "a product cannot be its own match" } }, 400);
  return c.json({ referenceProductId: reference.id, productId: body.productId, feedback: setSimilarFeedback(reference.id, body.productId, body.up) });
});

meta.get("/products/:id/details", async (c) => {
  const product = db().select().from(products).where(eq(products.id, c.req.param("id"))).get();
  if (!product) return c.json({ error: { code: "NOT_FOUND", message: "product not found" } }, 404);
  return c.json({ product, ...await productDetails(product) });
});

meta.get("/products/:id", (c) => {
  const product = db().select().from(products).where(eq(products.id, c.req.param("id"))).get();
  if (!product) return c.json({ error: { code: "NOT_FOUND", message: "product not found" } }, 404);
  return c.json(product);
});

meta.get("/products/:id/price-history", (c) => {
  const days = Math.min(Number(c.req.query("days") ?? 90) || 90, 365);
  return c.json({ points: priceHistoryFor(c.req.param("id"), days) });
});

/** Watched products whose price changed recently (drops first). */
meta.get("/prices/changes", (c) => c.json({ changes: recentPriceChanges(Math.min(Number(c.req.query("days") ?? 7) || 7, 90)), scan: scanStatus() }));

meta.get("/memory", (c) => c.json({ choices: listChoices(Number(c.req.query("limit") ?? 200) || 200), names: listNames() }));

meta.delete("/memory/names/:id", (c) => (deleteName(c.req.param("id")) ? c.body(null, 204) : c.json({ error: { code: "NOT_FOUND", message: "name not found" } }, 404)));

meta.delete("/memory/:id", (c) => (deleteChoice(c.req.param("id")) ? c.body(null, 204) : c.json({ error: { code: "NOT_FOUND", message: "choice not found" } }, 404)));

meta.get("/admin/stats", (c) => {
  const database = db();
  const count = (table: any) => database.select({ n: sql<number>`count(*)` }).from(table).get()?.n ?? 0;
  return c.json({
    products: count(products),
    searchCacheEntries: count(searchCache),
    aiCacheEntries: count(aiCache),
    basketItems: count(basketItems),
    stores: storeHealth(),
    ai: aiStats(),
  });
});

/** Re-prices every product referenced by the baskets now (same job as the nightly scan). */
meta.post("/admin/refresh", async (c) => c.json({ ...(await runPriceScan()), scan: scanStatus() }));

meta.get("/admin/scan", (c) => c.json(scanStatus()));

meta.post("/admin/clear-cache", (c) => {
  const database = db();
  database.delete(searchCache).run();
  const kind = c.req.query("ai") === "true";
  if (kind) database.delete(aiCache).run();
  return c.json({ ok: true });
});

meta.get("/admin/recent-products", (c) => c.json({ products: db().select().from(products).orderBy(desc(products.fetchedAt)).limit(50).all() }));

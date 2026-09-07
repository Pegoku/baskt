import { asc, desc, eq, inArray } from "drizzle-orm";
import { db, now } from "@/db";
import { basketItems, basketMatches, priceHistory, products, type ProductRow } from "@/db/schema";
import { getSetting, setSetting } from "@/db/settings";
import { env } from "@/env";
import { refreshQueriesFor } from "@/stores/search";

/** Products referenced by any basket item (chosen or provisional) plus everything ever chosen. */
export function watchedProductIds(): Set<string> {
  const ids = new Set<string>();
  const openItems = new Set(db().select({ id: basketItems.id }).from(basketItems).where(eq(basketItems.kind, "item")).all().map((row) => row.id));
  for (const match of db().select().from(basketMatches).all()) {
    if (!openItems.has(match.itemId)) continue;
    if (match.chosenProductId) ids.add(match.chosenProductId);
    else if (match.candidateIds[0]) ids.add(match.candidateIds[0]);
  }
  return ids;
}

export type ScanStatus = { lastRunAt: number | null; lastResult: { products: number; queries: number; refreshed: number } | null; nextRunAt: number | null; running: boolean };

let running = false;

export async function runPriceScan(): Promise<ScanStatus["lastResult"]> {
  if (running) return getSetting("priceScan.lastResult", null);
  running = true;
  try {
    const ids = watchedProductIds();
    const result = await refreshQueriesFor(ids);
    const summary = { products: ids.size, ...result };
    setSetting("priceScan.lastRunAt", now());
    setSetting("priceScan.lastResult", summary);
    return summary;
  } finally {
    running = false;
  }
}

function nextRunAt(): number {
  const [hour, minute] = env.priceScanTime.split(":").map(Number);
  const next = new Date();
  next.setHours(hour || 3, minute || 0, 0, 0);
  if (next.getTime() <= Date.now()) next.setDate(next.getDate() + 1);
  return next.getTime();
}

export function scanStatus(): ScanStatus {
  return {
    lastRunAt: getSetting<number | null>("priceScan.lastRunAt", null),
    lastResult: getSetting("priceScan.lastResult", null),
    nextRunAt: env.priceScanEnabled ? nextRunAt() : null,
    running,
  };
}

/** Schedules the nightly scan (checks every minute whether the configured time has passed). */
export function startPriceScheduler() {
  if (!env.priceScanEnabled) return;
  let scheduled = nextRunAt();
  setInterval(() => {
    if (Date.now() < scheduled) return;
    scheduled = nextRunAt();
    runPriceScan().catch((error) => console.error("[prices] nightly scan failed:", error));
  }, 60_000).unref();
  console.log(`[prices] nightly scan at ${env.priceScanTime}`);
}

export type PriceChange = { product: ProductRow; previousCents: number; currentCents: number; changedAt: number; diffCents: number };

/** Watched products whose price moved within the last `days`. */
export function recentPriceChanges(days = 7): PriceChange[] {
  const ids = Array.from(watchedProductIds());
  if (!ids.length) return [];
  const since = now() - days * 24 * 60 * 60 * 1000;
  const rows = db().select().from(products).where(inArray(products.id, ids)).all();
  const changes: PriceChange[] = [];
  for (const product of rows) {
    const history = db().select().from(priceHistory).where(eq(priceHistory.productId, product.id)).orderBy(desc(priceHistory.capturedAt)).limit(10).all();
    if (history.length < 2 || history[0].capturedAt < since) continue;
    const previous = history[1];
    if (previous.priceCents === history[0].priceCents) continue;
    changes.push({ product, previousCents: previous.priceCents, currentCents: history[0].priceCents, changedAt: history[0].capturedAt, diffCents: history[0].priceCents - previous.priceCents });
  }
  return changes.sort((a, b) => a.diffCents - b.diffCents);
}

export function priceHistoryFor(productId: string, days: number) {
  const since = now() - days * 24 * 60 * 60 * 1000;
  return db()
    .select({ capturedAt: priceHistory.capturedAt, priceCents: priceHistory.priceCents, isDeal: priceHistory.isDeal })
    .from(priceHistory)
    .where(eq(priceHistory.productId, productId))
    .orderBy(asc(priceHistory.capturedAt))
    .all()
    .filter((point) => point.capturedAt >= since);
}

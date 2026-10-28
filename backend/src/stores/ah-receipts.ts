import { and, eq, gt, lt } from "drizzle-orm";
import { db } from "@/db";
import { purchaseLines, purchases, type ProductRow } from "@/db/schema";
import { getSetting, setSetting } from "@/db/settings";
import { eurosToCents } from "@/lib/units";
import { deletePurchase, lineScore, openItemsAt, savePurchase, type ReceiptLine } from "@/receipts";
import { graphql } from "@/stores/ah-account";
import { getAdapter } from "@/stores/registry";
import { productsByIds, upsertProducts } from "@/stores/search";
import { productKey } from "@/stores/types";

/**
 * The AH account's digital receipts (kassabonnen) as purchases, so orders and spending fill themselves. Each
 * receipt is imported once; a shopping-mode trip at AH around the same time is replaced by it, since the
 * receipt is what was really paid.
 */
const IMPORTED_KEY = "ahImportedReceipts";
const FIRST_IMPORT = 30;
const TRIP_WINDOW_MS = 6 * 60 * 60 * 1000;

type Money = { amount?: number | null } | null;
type ReceiptSummary = { id: string; dateTime?: string | null; totalAmount?: Money };
type ReceiptProduct = { id?: number | null; quantity?: number | null; name?: string | null; price?: Money; amount?: Money };
type ReceiptDetail = {
  products?: ReceiptProduct[] | null;
  discounts?: Array<{ type?: string | null; name?: string | null; amount?: Money }> | null;
  total?: Money;
  transaction?: { dateTime?: string | null } | null;
};

const LIST_QUERY = `query($offset: Int!, $limit: Int!) { posReceiptsPage(pagination: {offset: $offset, limit: $limit}) { posReceipts { id dateTime totalAmount { amount } } } }`;
const DETAIL_QUERY = `query($id: String!) { posReceiptDetails(id: $id) {
  products { id quantity name price { amount } amount { amount } }
  discounts { type name amount { amount } }
  total { amount }
  transaction { dateTime }
} }`;

/** Till product ids to webshop ids (AH answers -1 or null for products not sold online). */
async function webshopIds(posIds: number[]): Promise<Map<number, number>> {
  const unique = [...new Set(posIds)];
  const result = new Map<number, number>();
  for (let start = 0; start < unique.length; start += 40) {
    const chunk = unique.slice(start, start + 40);
    const aliases = chunk.map((id, index) => `p${index}: productConvertId(sourceId: ${Math.trunc(id)})`).join(" ");
    try {
      const data = await graphql<Record<string, number | null>>(`query { ${aliases} }`);
      chunk.forEach((id, index) => {
        const webshop = data[`p${index}`];
        if (typeof webshop === "number" && webshop > 0) result.set(id, webshop);
      });
    } catch (error) {
      console.warn(`[ah-receipts] product id conversion failed: ${error instanceof Error ? error.message : error}`);
    }
  }
  return result;
}

async function catalogue(ids: string[]): Promise<Map<string, ProductRow>> {
  const known = new Map(productsByIds(ids).map((row) => [row.id, row]));
  const missing = ids.filter((id) => !known.has(id)).slice(0, 60);
  const adapter = getAdapter("AH");
  if (missing.length && adapter.refresh) {
    for (const row of upsertProducts(await adapter.refresh(missing.map((id) => id.slice(3))))) known.set(row.id, row);
  }
  return known;
}

/** Discounts are listed apart from the products; each goes onto the product line its name describes. */
export function receiptLines(detail: ReceiptDetail, products: Map<number, ProductRow>): Array<ReceiptLine & { productId: string | null }> {
  const lines = (detail.products ?? [])
    .filter((entry) => entry.name)
    .map((entry) => {
      const product = entry.id != null ? products.get(entry.id) ?? null : null;
      const quantity = entry.quantity && entry.quantity > 0 ? entry.quantity : 1;
      const total = eurosToCents(entry.amount?.amount) ?? 0;
      return {
        name: product?.title ?? entry.name!.trim(),
        quantity,
        unitPriceCents: eurosToCents(entry.price?.amount) ?? (quantity === 1 ? total : Math.round(total / quantity)),
        totalPriceCents: total,
        dealText: null as string | null,
        productId: product?.id ?? null,
        receiptName: entry.name!.trim(),
      };
    });
  // discountPersonal only sums the Bonus Box discounts already listed one by one, so it is left out.
  const discounts = (detail.discounts ?? []).map((entry) => ({ name: entry.name ?? "", type: entry.type ?? null, cents: eurosToCents(entry.amount?.amount) ?? 0 })).filter((entry) => entry.cents !== 0);
  const extra: Array<ReceiptLine & { productId: string | null }> = [];
  for (const discount of discounts) {
    const cents = discount.cents > 0 ? -discount.cents : discount.cents;
    let best: { index: number; score: number } | null = null;
    lines.forEach((line, index) => {
      const score = Math.max(lineScore(discount.name, line.name), lineScore(discount.name, line.receiptName));
      if (score > (best?.score ?? 0)) best = { index, score };
    });
    const label = discount.type === "BBOX" ? "Bonus Box" : "Bonus";
    if (best && (best as { score: number }).score >= 0.5) {
      const line = lines[(best as { index: number }).index];
      line.totalPriceCents += cents;
      line.dealText = line.dealText ? `${line.dealText}, ${label}` : label;
    } else {
      extra.push({ name: discount.name ? `${label}: ${discount.name}` : label, quantity: 1, unitPriceCents: cents, totalPriceCents: cents, dealText: label, productId: null });
    }
  }
  return [...lines.map(({ receiptName: _, ...line }) => line), ...extra];
}

export type ReceiptImport = { imported: number; replacedTrips: number; receipts: Array<{ id: string; purchaseId: string; at: number; totalCents: number }> };

let running: Promise<ReceiptImport> | null = null;

export function importAhReceipts(): Promise<ReceiptImport> {
  if (!running) running = runImport().finally(() => (running = null));
  return running;
}

async function runImport(): Promise<ReceiptImport> {
  const imported = getSetting<Record<string, string>>(IMPORTED_KEY, {});
  const firstRun = Object.keys(imported).length === 0;
  const page = await graphql<{ posReceiptsPage?: { posReceipts?: ReceiptSummary[] | null } | null }>(LIST_QUERY, { offset: 0, limit: firstRun ? FIRST_IMPORT : 15 });
  const fresh = (page.posReceiptsPage?.posReceipts ?? []).filter((receipt) => receipt.id && !imported[receipt.id]).reverse();
  const result: ReceiptImport = { imported: 0, replacedTrips: 0, receipts: [] };
  for (const summary of fresh) {
    const data = await graphql<{ posReceiptDetails?: ReceiptDetail | null }>(DETAIL_QUERY, { id: summary.id });
    const detail = data.posReceiptDetails;
    if (!detail) continue;
    const at = Date.parse(detail.transaction?.dateTime ?? summary.dateTime ?? "") || Date.now();
    const posIds = (detail.products ?? []).map((entry) => entry.id).filter((id): id is number => typeof id === "number");
    const ids = await webshopIds(posIds);
    const rows = await catalogue([...new Set(ids.values())].map((id) => productKey("AH", String(id))));
    const products = new Map<number, ProductRow>();
    for (const [pos, webshop] of ids) {
      const row = rows.get(productKey("AH", String(webshop)));
      if (row) products.set(pos, row);
    }
    const lines = receiptLines(detail, products);
    const totalCents = eurosToCents(detail.total?.amount ?? summary.totalAmount?.amount) ?? lines.reduce((sum, line) => sum + line.totalPriceCents, 0);

    // A trip ticked off in shopping mode around this time is the same shop: its ticks link the receipt lines to
    // list items, then the receipt takes its place.
    const trips = db().select().from(purchases).where(and(eq(purchases.store, "AH"), eq(purchases.source, "shop"), gt(purchases.purchasedAt, at - TRIP_WINDOW_MS), lt(purchases.purchasedAt, at + TRIP_WINDOW_MS))).all();
    const ticked = trips.flatMap((trip) => db().select().from(purchaseLines).where(eq(purchaseLines.purchaseId, trip.id)).all()).filter((line) => line.itemId);
    const open = openItemsAt("AH");
    for (const line of lines) {
      const tick = ticked.find((entry) => (line.productId && entry.productId === line.productId) || lineScore(entry.name, line.name) >= 0.6);
      if (tick) {
        line.itemId = tick.itemId;
        ticked.splice(ticked.indexOf(tick), 1);
        continue;
      }
      // Something still open on the list that is this very product: the receipt shows it was bought.
      // Only for a receipt from today's shop: an old one says nothing about what is on the list now.
      const item = line.productId && Date.now() - at < 12 * 60 * 60 * 1000 ? open.find((entry) => entry.product?.id === line.productId) : undefined;
      if (item) {
        line.itemId = item.itemId;
        open.splice(open.indexOf(item), 1);
      }
    }
    const row = savePurchase({ store: "AH", purchasedAt: null, totalCents, source: "ah", lines });
    db().update(purchases).set({ purchasedAt: at }).where(eq(purchases.id, row.id)).run();
    for (const trip of trips) deletePurchase(trip.id);
    result.replacedTrips += trips.length;
    imported[summary.id] = row.id;
    setSetting(IMPORTED_KEY, imported);
    result.imported += 1;
    result.receipts.push({ id: summary.id, purchaseId: row.id, at, totalCents });
  }
  if (result.imported) console.log(`[ah-receipts] imported ${result.imported} receipt(s), replaced ${result.replacedTrips} shopping trip(s)`);
  return result;
}

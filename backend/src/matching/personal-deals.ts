import { db } from "@/db";
import { baskets } from "@/db/schema";
import { findDeals } from "@/matching/deals";
import { activateOffer, AhAccountError, autoActivateEnabled, loadPersistedOffers, personalOffers } from "@/stores/ah-account";
import { importAhReceipts } from "@/stores/ah-receipts";
import type { PricedRow } from "@/stores/personal-overlay";

export type AutoActivation = { offerId: string; title: string; itemText: string; ok: boolean; error: string | null };

const MIN_GAP_MS = 5 * 60 * 1000;
let lastRun = 0;
let running: Promise<AutoActivation[]> | null = null;

/**
 * Activates the Bonus Box offers that match something on a basket, best saving first, until AH's weekly
 * maximum is used up. Activations cannot be undone, so only offers the deal judge accepted for an open
 * item count.
 */
export async function autoActivate(options: { force?: boolean } = {}): Promise<AutoActivation[]> {
  if (!autoActivateEnabled()) return [];
  if (running) return running;
  if (!options.force && Date.now() - lastRun < MIN_GAP_MS) return [];
  lastRun = Date.now();
  running = run().finally(() => (running = null));
  return running;
}

async function run(): Promise<AutoActivation[]> {
  const view = await personalOffers();
  if (!view.connected || view.maximumActivations == null) return [];
  let slots = view.maximumActivations - view.activatedCount;
  if (slots <= 0 || !view.offers.some((offer) => offer.status === "ACTIVATABLE" && offer.hqId != null)) return [];
  const wanted = new Map<string, { saving: number; itemText: string }>();
  for (const basket of db().select({ id: baskets.id }).from(baskets).all()) {
    for (const deal of await findDeals(basket.id)) {
      const tag = (deal.product as PricedRow).personal;
      if (tag?.status !== "ACTIVATABLE" || tag.source !== "BONUS_BOX") continue;
      const saving = deal.savingCents ?? 0;
      const previous = wanted.get(tag.offerId);
      if (!previous || saving > previous.saving) wanted.set(tag.offerId, { saving, itemText: deal.itemText });
    }
  }
  const results: AutoActivation[] = [];
  for (const [offerId, match] of [...wanted].sort((a, b) => b[1].saving - a[1].saving)) {
    if (slots <= 0) break;
    const title = view.offers.find((offer) => offer.id === offerId)?.title ?? offerId;
    try {
      await activateOffer(offerId, { auto: true });
      slots -= 1;
      results.push({ offerId, title, itemText: match.itemText, ok: true, error: null });
    } catch (error) {
      results.push({ offerId, title, itemText: match.itemText, ok: false, error: error instanceof Error ? error.message : String(error) });
      if (error instanceof AhAccountError && error.code !== "AH_ERROR") break;
    }
  }
  if (results.length) console.log(`[ah-account] auto-activated: ${results.map((entry) => `${entry.title} for "${entry.itemText}" ${entry.ok ? "ok" : entry.error}`).join("; ")}`);
  return results;
}

/** Keeps personal prices warm, receipts imported and auto-activation going without the app open. */
export function startPersonalOffersScheduler() {
  loadPersistedOffers();
  const tick = () =>
    personalOffers()
      .then(async (view) => {
        if (!view.connected) return;
        await importAhReceipts().catch((error) => console.warn(`[ah-receipts] scheduled import failed: ${error instanceof Error ? error.message : error}`));
        await autoActivate();
      })
      .catch((error) => console.warn(`[ah-account] scheduled refresh failed: ${error instanceof Error ? error.message : error}`));
  setTimeout(tick, 30_000).unref();
  setInterval(tick, 60 * 60 * 1000).unref();
}

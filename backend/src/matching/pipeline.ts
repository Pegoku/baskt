import { and, asc, eq } from "drizzle-orm";
import { aiConfigured } from "@/env";
import { db, newId, now } from "@/db";
import { basketItems, basketMatches, type BasketItemRow, type BasketMatchRow, type Equivalence, type ParsedIdea, type ProductRow } from "@/db/schema";
import { enabledStoreCodes } from "@/db/settings";
import { normalizeText } from "@/lib/text";
import { memoryContext, recordChoice, rememberedProductId } from "@/matching/memory";
import { alternativeQuery, parseIdea } from "@/matching/parse";
import { aiRankCandidates } from "@/matching/pick";
import { lexicalRank } from "@/matching/rank";
import { productsByIds, searchStore } from "@/stores/search";

export const OPTIONS_PER_PAGE = 3;
const MAX_CANDIDATES = 12;

const running = new Map<string, Promise<void>>();

export function getItem(itemId: string) {
  return db().select().from(basketItems).where(eq(basketItems.id, itemId)).get() ?? null;
}

export function getMatches(itemId: string) {
  return db().select().from(basketMatches).where(eq(basketMatches.itemId, itemId)).orderBy(asc(basketMatches.store)).all();
}

export function getMatch(itemId: string, store: string) {
  return db().select().from(basketMatches).where(and(eq(basketMatches.itemId, itemId), eq(basketMatches.store, store))).get() ?? null;
}

function updateItem(itemId: string, patch: Partial<BasketItemRow>) {
  db().update(basketItems).set({ ...patch, updatedAt: now() }).where(eq(basketItems.id, itemId)).run();
}

function saveMatch(match: BasketMatchRow) {
  db()
    .insert(basketMatches)
    .values(match)
    .onConflictDoUpdate({ target: [basketMatches.itemId, basketMatches.store], set: { ...match, id: undefined, itemId: undefined, store: undefined } })
    .run();
  return getMatch(match.itemId, match.store)!;
}

/** Runs the parse → search → rank pipeline for an item in the background. */
export function enqueue(itemId: string) {
  if (running.has(itemId)) return running.get(itemId)!;
  const task = processItem(itemId)
    .catch((error) => {
      console.error(`[pipeline] item ${itemId} failed:`, error);
      updateItem(itemId, { status: "ERROR", error: error instanceof Error ? error.message : String(error) });
    })
    .finally(() => running.delete(itemId));
  running.set(itemId, task);
  return task;
}

export function isRunning(itemId: string) {
  return running.has(itemId);
}

async function collectCandidates(item: BasketItemRow, parsed: ParsedIdea, store: string): Promise<{ candidates: ProductRow[]; queries: string[] }> {
  const queries = [parsed.queries[store] ?? item.text];
  if (normalizeText(item.text) !== normalizeText(queries[0])) queries.push(item.text);
  const seen = new Set<string>();
  const candidates: ProductRow[] = [];
  for (const query of queries) {
    if (candidates.length >= 6 && query !== queries[0]) break;
    const result = await searchStore(store, query, { limit: 20 });
    for (const product of result.products) {
      if (!seen.has(product.id)) {
        seen.add(product.id);
        candidates.push(product);
      }
    }
  }
  return { candidates, queries };
}

async function rankCandidates(item: BasketItemRow, parsed: ParsedIdea, store: string, candidates: ProductRow[]) {
  const lexical = lexicalRank(item.text, parsed, candidates).slice(0, MAX_CANDIDATES);
  const trimmed = lexical.map((entry) => entry.product);
  const fallback = {
    orderedIds: trimmed.map((product) => product.id),
    equivalences: Object.fromEntries(trimmed.map((product, index) => [product.id, (index === 0 ? "EQUIVALENT" : "SUBSTITUTE") as Equivalence])),
    confidence: lexical.length ? Math.min(0.9, Math.max(0.2, lexical[0].score / 6)) : 0,
    reason: aiConfigured() ? null : "Ranked by text similarity (AI not configured)",
  };
  if (!aiConfigured() || !trimmed.length) return fallback;
  const ai = await aiRankCandidates({ text: item.text, parsed, store, candidates: trimmed, memory: memoryContext(parsed.canonicalName, item.text, store) });
  return ai ?? fallback;
}

export async function processItem(itemId: string, options: { keepUserChoices?: boolean } = { keepUserChoices: true }) {
  const item = getItem(itemId);
  if (!item) return;
  const stores = enabledStoreCodes();
  updateItem(itemId, { status: "PARSING", error: null });
  const parsed = await parseIdea(item.text, stores);
  updateItem(itemId, { status: "MATCHING", parsedJson: parsed });

  const existing = new Map(getMatches(itemId).map((match) => [match.store, match]));
  await Promise.all(
    stores.map(async (store) => {
      const previous = existing.get(store);
      try {
        const { candidates } = await collectCandidates(item, parsed, store);
        const ranked = await rankCandidates(item, parsed, store, candidates);
        let orderedIds = ranked.orderedIds;
        let status: BasketMatchRow["status"] = orderedIds.length ? "PENDING" : "EXHAUSTED";
        let chosenProductId: string | null = null;
        let chosenBy: BasketMatchRow["chosenBy"] = orderedIds.length ? "AI" : null;

        if (options.keepUserChoices && previous?.status === "CHOSEN" && previous.chosenBy === "USER" && previous.chosenProductId) {
          chosenProductId = previous.chosenProductId;
          chosenBy = "USER";
          status = "CHOSEN";
          orderedIds = [chosenProductId, ...orderedIds.filter((id) => id !== chosenProductId)];
        } else if (options.keepUserChoices && previous?.status === "NONE") {
          status = "NONE";
          chosenBy = "USER";
        } else {
          const remembered = rememberedProductId(parsed.canonicalName, item.text, store);
          if (remembered && orderedIds.includes(remembered)) {
            chosenProductId = remembered;
            chosenBy = "MEMORY";
            status = "CHOSEN";
            orderedIds = [remembered, ...orderedIds.filter((id) => id !== remembered)];
          }
        }

        saveMatch({
          id: previous?.id ?? newId(),
          itemId,
          store,
          candidateIds: orderedIds,
          equivalences: ranked.equivalences,
          windowStart: 0,
          shownCount: Math.min(OPTIONS_PER_PAGE, Math.max(orderedIds.length, 1)),
          chosenProductId,
          status,
          chosenBy,
          confidence: ranked.confidence,
          reason: ranked.reason,
          updatedAt: now(),
        });
      } catch (error) {
        console.warn(`[pipeline] ${store} failed for "${item.text}": ${error instanceof Error ? error.message : error}`);
        saveMatch({
          id: previous?.id ?? newId(),
          itemId,
          store,
          candidateIds: previous?.candidateIds ?? [],
          equivalences: previous?.equivalences ?? {},
          windowStart: previous?.windowStart ?? 0,
          shownCount: previous?.shownCount ?? OPTIONS_PER_PAGE,
          chosenProductId: previous?.chosenProductId ?? null,
          status: previous?.status ?? "EXHAUSTED",
          chosenBy: previous?.chosenBy ?? null,
          confidence: previous?.confidence ?? null,
          reason: `Store lookup failed: ${error instanceof Error ? error.message : String(error)}`,
          updatedAt: now(),
        });
      }
    }),
  );
  // Drop matches for stores that are no longer enabled.
  for (const match of existing.values()) {
    if (!stores.includes(match.store)) db().delete(basketMatches).where(eq(basketMatches.id, match.id)).run();
  }
  updateItem(itemId, { status: "MATCHED", error: null });
}

export function shownWindow(match: BasketMatchRow) {
  const end = Math.min(match.shownCount, match.candidateIds.length);
  return match.candidateIds.slice(Math.min(match.windowStart, end), end);
}

/** User picked one of the shown options (or `null` = "not buying this at this store"). */
export function chooseMatch(itemId: string, store: string, productId: string | null) {
  const item = getItem(itemId);
  const match = getMatch(itemId, store);
  if (!item || !match) return null;
  const shown = shownWindow(match);
  const products = new Map(productsByIds([...shown, ...(productId ? [productId] : [])]).map((product) => [product.id, product]));
  if (productId && !products.has(productId)) return null;
  const canonical = item.parsedJson?.canonicalName ?? item.text;
  recordChoice({
    itemText: item.text,
    canonical,
    store,
    chosenProductId: productId,
    chosenTitle: productId ? products.get(productId)!.title : null,
    rejectedTitles: shown.filter((id) => id !== productId).map((id) => products.get(id)?.title ?? "").filter(Boolean),
  });
  const candidateIds = productId ? [productId, ...match.candidateIds.filter((id) => id !== productId)] : match.candidateIds;
  return saveMatch({
    ...match,
    candidateIds,
    chosenProductId: productId,
    status: productId ? "CHOSEN" : "NONE",
    chosenBy: "USER",
    updatedAt: now(),
  });
}

/** "None of them fit": remember the rejection and show the next options, searching wider when needed. */
export async function rejectShown(itemId: string, store: string) {
  const item = getItem(itemId);
  const match = getMatch(itemId, store);
  if (!item || !match) return null;
  const shown = shownWindow(match);
  const shownProducts = productsByIds(shown);
  const canonical = item.parsedJson?.canonicalName ?? item.text;
  if (shownProducts.length) {
    recordChoice({ itemText: item.text, canonical, store, chosenProductId: null, chosenTitle: null, rejectedTitles: shownProducts.map((product) => product.title) });
  }

  let candidateIds = match.candidateIds;
  const windowStart = Math.min(match.shownCount, candidateIds.length);
  if (windowStart >= candidateIds.length) {
    const more = await findMoreCandidates(item, store, candidateIds);
    if (!more.length) {
      return saveMatch({ ...match, windowStart, shownCount: windowStart, status: "EXHAUSTED", chosenProductId: null, chosenBy: null, updatedAt: now() });
    }
    candidateIds = [...candidateIds, ...more];
  }
  return saveMatch({
    ...match,
    candidateIds,
    windowStart,
    shownCount: Math.min(windowStart + OPTIONS_PER_PAGE, candidateIds.length),
    status: "PENDING",
    chosenProductId: null,
    chosenBy: "AI",
    updatedAt: now(),
  });
}

async function findMoreCandidates(item: BasketItemRow, store: string, known: string[]) {
  const parsed = item.parsedJson ?? { canonicalName: item.text, attributes: [], sizeHint: null, queries: {}, ambiguous: false };
  const tried = [parsed.queries[store] ?? item.text, item.text];
  const rejectedTitles = productsByIds(known).map((product) => product.title);
  const attempts: string[] = [];
  if (normalizeText(parsed.canonicalName) !== normalizeText(item.text)) attempts.push(parsed.canonicalName);
  if (aiConfigured()) {
    const alternative = await alternativeQuery(item.text, parsed, store, rejectedTitles.slice(-9), [...tried, ...attempts]);
    if (alternative) attempts.unshift(alternative);
  }
  for (const query of attempts) {
    const result = await searchStore(store, query, { limit: 20 });
    const fresh = result.products.filter((product) => !known.includes(product.id));
    if (fresh.length) return lexicalRank(item.text, parsed, fresh).map((entry) => entry.product.id);
  }
  return [];
}

/** Manual search from the app: adds the results as the next options. */
export async function searchMoreCandidates(itemId: string, store: string, query: string) {
  const item = getItem(itemId);
  const match = getMatch(itemId, store);
  if (!item || !match) return null;
  const parsed = item.parsedJson ?? { canonicalName: item.text, attributes: [], sizeHint: null, queries: {}, ambiguous: false };
  const result = await searchStore(store, query, { limit: 20 });
  const fresh = lexicalRank(query, { ...parsed, canonicalName: query }, result.products.filter((product) => !match.candidateIds.includes(product.id))).map(
    (entry) => entry.product.id,
  );
  if (!fresh.length) return match;
  const candidateIds = [...match.candidateIds, ...fresh];
  return saveMatch({
    ...match,
    candidateIds,
    windowStart: match.candidateIds.length,
    shownCount: Math.min(candidateIds.length, match.candidateIds.length + OPTIONS_PER_PAGE),
    status: "PENDING",
    chosenProductId: null,
    chosenBy: "AI",
    updatedAt: now(),
  });
}

export function rematchItem(itemId: string) {
  return processItem(itemId, { keepUserChoices: false });
}

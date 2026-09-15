import { and, asc, eq } from "drizzle-orm";
import { aiConfigured } from "@/env";
import { db, newId, now } from "@/db";
import {
  basketItems,
  basketMatches,
  type BasketItemRow,
  type BasketMatchRow,
  type Equivalence,
  type ParsedIdea,
  type ProductRow,
} from "@/db/schema";
import { enabledStoreCodes } from "@/db/settings";
import { normalizeText } from "@/lib/text";
import {
  clearRejections,
  memoryContext,
  recordChoice,
  rememberedProductId,
} from "@/matching/memory";
import { alternativeQuery, parseIdea } from "@/matching/parse";
import { aiRankCandidates } from "@/matching/pick";
import { lexicalRank, sortByReference } from "@/matching/rank";
import { productsByIds, searchStore } from "@/stores/search";

export const OPTIONS_PER_PAGE = 3;
const MAX_CANDIDATES = 12;

const running = new Map<string, Promise<void>>();

export function getItem(itemId: string) {
  return (
    db().select().from(basketItems).where(eq(basketItems.id, itemId)).get() ??
    null
  );
}

export function getMatches(itemId: string) {
  return db()
    .select()
    .from(basketMatches)
    .where(eq(basketMatches.itemId, itemId))
    .orderBy(asc(basketMatches.store))
    .all();
}

export function getMatch(itemId: string, store: string) {
  return (
    db()
      .select()
      .from(basketMatches)
      .where(
        and(eq(basketMatches.itemId, itemId), eq(basketMatches.store, store)),
      )
      .get() ?? null
  );
}

function updateItem(itemId: string, patch: Partial<BasketItemRow>) {
  db()
    .update(basketItems)
    .set({ ...patch, updatedAt: now() })
    .where(eq(basketItems.id, itemId))
    .run();
}

function saveMatch(match: BasketMatchRow) {
  // The item may have been deleted while its stores were being searched.
  if (!getItem(match.itemId)) return match;
  db()
    .insert(basketMatches)
    .values(match)
    .onConflictDoUpdate({
      target: [basketMatches.itemId, basketMatches.store],
      set: { ...match, id: undefined, itemId: undefined, store: undefined },
    })
    .run();
  return getMatch(match.itemId, match.store)!;
}

/** Runs the parse → search → rank pipeline for an item in the background. */
export function enqueue(itemId: string) {
  if (running.has(itemId)) return running.get(itemId)!;
  const task = processItem(itemId)
    .catch((error) => {
      console.error(`[pipeline] item ${itemId} failed:`, error);
      updateItem(itemId, {
        status: "ERROR",
        error: error instanceof Error ? error.message : String(error),
      });
    })
    .finally(() => running.delete(itemId));
  running.set(itemId, task);
  return task;
}

export function isRunning(itemId: string) {
  return running.has(itemId);
}

const MIN_CANDIDATES = 6;

async function collectCandidates(
  item: BasketItemRow,
  parsed: ParsedIdea,
  store: string,
): Promise<{ candidates: ProductRow[]; queries: string[] }> {
  const queries: string[] = [];
  for (const query of [
    parsed.queries[store] ?? item.text,
    parsed.fallbackQuery,
    item.text,
  ]) {
    if (
      query &&
      !queries.some((known) => normalizeText(known) === normalizeText(query))
    )
      queries.push(query);
  }
  const seen = new Set<string>();
  const candidates: ProductRow[] = [];
  for (const [index, query] of queries.entries()) {
    // The store query and the generic fallback always run (both cached); the raw text only when the pool is still thin.
    if (index >= 2 && candidates.length >= MIN_CANDIDATES) break;
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

async function rankCandidates(
  item: BasketItemRow,
  parsed: ParsedIdea,
  store: string,
  candidates: ProductRow[],
) {
  const lexical = lexicalRank(item.text, parsed, candidates).slice(
    0,
    MAX_CANDIDATES,
  );
  const trimmed = lexical.map((entry) => entry.product);
  const fallback = {
    orderedIds: trimmed.map((product) => product.id),
    equivalences: Object.fromEntries(
      trimmed.map((product, index) => [
        product.id,
        (index === 0 ? "EQUIVALENT" : "SUBSTITUTE") as Equivalence,
      ]),
    ),
    confidence: lexical.length
      ? Math.min(0.9, Math.max(0.2, lexical[0].score / 6))
      : 0,
    reason: aiConfigured()
      ? null
      : "Ranked by text similarity (AI not configured)",
  };
  if (!aiConfigured() || !trimmed.length) return fallback;
  const ai = await aiRankCandidates({
    text: item.text,
    parsed,
    store,
    candidates: trimmed,
    memory: memoryContext(parsed.canonicalName, item.text, store),
  });
  return ai ?? fallback;
}

export async function processItem(
  itemId: string,
  options: { keepUserChoices?: boolean } = { keepUserChoices: true },
) {
  const item = getItem(itemId);
  if (!item) return;
  if (item.kind === "group") {
    updateItem(itemId, { status: "MATCHED", error: null });
    return;
  }
  const stores = enabledStoreCodes();
  updateItem(itemId, { status: "PARSING", error: null });
  const parsed = await parseIdea(item.text, stores);
  const existing = new Map(
    getMatches(itemId).map((match) => [match.store, match]),
  );
  // A pinned product (scan / manual pick) lends its pack size as a preference for the other stores.
  if (!parsed.sizeHint) {
    const pinned = Array.from(existing.values()).find(
      (match) =>
        match.status === "CHOSEN" &&
        match.chosenBy === "USER" &&
        match.chosenProductId,
    );
    const product = pinned?.chosenProductId
      ? productsByIds([pinned.chosenProductId])[0]
      : undefined;
    if (product?.unitAmount && product.unit)
      parsed.sizeHint = { amount: product.unitAmount, unit: product.unit };
  }
  updateItem(itemId, { status: "MATCHING", parsedJson: parsed });
  await Promise.all(
    stores.map(async (store) => {
      const previous = existing.get(store);
      try {
        const { candidates } = await collectCandidates(item, parsed, store);
        const ranked = await rankCandidates(item, parsed, store, candidates);
        let orderedIds = ranked.orderedIds;
        let status: BasketMatchRow["status"] = orderedIds.length
          ? "PENDING"
          : "EXHAUSTED";
        let chosenProductId: string | null = null;
        let chosenBy: BasketMatchRow["chosenBy"] = orderedIds.length
          ? "AI"
          : null;

        if (
          options.keepUserChoices &&
          previous?.status === "CHOSEN" &&
          previous.chosenBy === "USER" &&
          previous.chosenProductId
        ) {
          chosenProductId = previous.chosenProductId;
          chosenBy = "USER";
          status = "CHOSEN";
          orderedIds = [
            chosenProductId,
            ...orderedIds.filter((id) => id !== chosenProductId),
          ];
        } else if (options.keepUserChoices && previous?.status === "NONE") {
          status = "NONE";
          chosenBy = "USER";
        } else {
          const remembered = rememberedProductId(
            parsed.canonicalName,
            item.text,
            store,
          );
          if (remembered && orderedIds.includes(remembered)) {
            chosenProductId = remembered;
            chosenBy = "MEMORY";
            status = "CHOSEN";
            orderedIds = [
              remembered,
              ...orderedIds.filter((id) => id !== remembered),
            ];
          }
        }

        saveMatch({
          hasRejectedSuggestions: previous?.hasRejectedSuggestions ?? false,
          id: previous?.id ?? newId(),
          itemId,
          store,
          candidateIds: orderedIds,
          equivalences: ranked.equivalences,
          windowStart: 0,
          shownCount: Math.min(
            OPTIONS_PER_PAGE,
            Math.max(orderedIds.length, 1),
          ),
          chosenProductId,
          status,
          chosenBy,
          confidence: ranked.confidence,
          reason: ranked.reason,
          updatedAt: now(),
        });
      } catch (error) {
        console.warn(
          `[pipeline] ${store} failed for "${item.text}": ${error instanceof Error ? error.message : error}`,
        );
        saveMatch({
          hasRejectedSuggestions: previous?.hasRejectedSuggestions ?? false,
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
    if (!stores.includes(match.store))
      db().delete(basketMatches).where(eq(basketMatches.id, match.id)).run();
  }
  updateItem(itemId, { status: "MATCHED", error: null });
}

export function shownWindow(match: BasketMatchRow) {
  const end = Math.min(match.shownCount, match.candidateIds.length);
  return match.candidateIds.slice(Math.min(match.windowStart, end), end);
}

/** User picked one of the shown options (or `null` = "not buying this at this store"). */
export function chooseMatch(
  itemId: string,
  store: string,
  productId: string | null,
) {
  const item = getItem(itemId);
  const match = getMatch(itemId, store);
  if (!item || !match) return null;
  const shown = shownWindow(match);
  const products = new Map(
    productsByIds([...shown, ...(productId ? [productId] : [])]).map(
      (product) => [product.id, product],
    ),
  );
  if (productId && !products.has(productId)) return null;
  const canonical = item.parsedJson?.canonicalName ?? item.text;
  recordChoice({
    itemText: item.text,
    canonical,
    store,
    chosenProductId: productId,
    chosenTitle: productId ? products.get(productId)!.title : null,
    rejectedTitles: shown
      .filter((id) => id !== productId)
      .map((id) => products.get(id)?.title ?? "")
      .filter(Boolean),
  });
  const candidateIds = productId
    ? [productId, ...match.candidateIds.filter((id) => id !== productId)]
    : match.candidateIds;
  return saveMatch({
    ...match,
    candidateIds,
    chosenProductId: productId,
    status: productId ? "CHOSEN" : "NONE",
    chosenBy: "USER",
    updatedAt: now(),
  });
}

/**
 * Thumbs up/down on a product. Up confirms it (and teaches memory); down records a rejection,
 * removes it from the options and, if it was the pick, falls back to the next best candidate.
 */
export function feedback(
  itemId: string,
  store: string,
  productId: string,
  up: boolean,
) {
  const item = getItem(itemId);
  const match = getMatch(itemId, store);
  if (!item || !match || !match.candidateIds.includes(productId)) return null;
  const product = productsByIds([productId])[0];
  if (!product) return null;
  const canonical = item.parsedJson?.canonicalName ?? item.text;
  if (up) {
    recordChoice({
      itemText: item.text,
      canonical,
      store,
      chosenProductId: productId,
      chosenTitle: product.title,
      rejectedTitles: [],
    });
    return saveMatch({
      ...match,
      candidateIds: [
        productId,
        ...match.candidateIds.filter((id) => id !== productId),
      ],
      chosenProductId: productId,
      status: "CHOSEN",
      chosenBy: "USER",
      windowStart: 0,
      shownCount: Math.min(OPTIONS_PER_PAGE, match.candidateIds.length),
      updatedAt: now(),
    });
  }
  recordChoice({
    itemText: item.text,
    canonical,
    store,
    chosenProductId: null,
    chosenTitle: null,
    rejectedTitles: [product.title],
  });
  const candidateIds = match.candidateIds.filter((id) => id !== productId);
  const wasChosen = match.chosenProductId === productId;
  return saveMatch({
    ...match,
    candidateIds,
    chosenProductId: wasChosen ? null : match.chosenProductId,
    status: candidateIds.length
      ? wasChosen
        ? "PENDING"
        : match.status
      : "EXHAUSTED",
    chosenBy: wasChosen ? "AI" : match.chosenBy,
    windowStart: wasChosen
      ? 0
      : Math.min(match.windowStart, Math.max(0, candidateIds.length - 1)),
    shownCount: wasChosen
      ? Math.min(OPTIONS_PER_PAGE, candidateIds.length)
      : Math.min(match.shownCount, candidateIds.length),
    updatedAt: now(),
  });
}

/** Re-enables a skipped store: back to the options it had (or "nothing found" so the smart search can be used). */
export function unskipMatch(itemId: string, store: string) {
  const match = getMatch(itemId, store);
  if (!match || match.status !== "NONE") return match;
  return saveMatch({
    ...match,
    status: match.candidateIds.length ? "PENDING" : "EXHAUSTED",
    chosenProductId: null,
    chosenBy: match.candidateIds.length ? "AI" : null,
    windowStart: 0,
    shownCount: Math.min(OPTIONS_PER_PAGE, match.candidateIds.length),
    updatedAt: now(),
  });
}

/** Restore all suggestion pages and forget this store's rejection-only memories. */
export function resetRejections(itemId: string, store: string) {
  const item = getItem(itemId);
  const match = getMatch(itemId, store);
  if (!item || !match) return null;
  clearRejections(item.parsedJson?.canonicalName ?? item.text, item.text, store);
  return saveMatch({
    ...match,
    hasRejectedSuggestions: false,
    windowStart: 0,
    shownCount: Math.min(OPTIONS_PER_PAGE, match.candidateIds.length),
    status: match.candidateIds.length ? "PENDING" : "EXHAUSTED",
    chosenProductId: null,
    chosenBy: null,
    confidence: null,
    reason: null,
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
    recordChoice({
      itemText: item.text,
      canonical,
      store,
      chosenProductId: null,
      chosenTitle: null,
      rejectedTitles: shownProducts.map((product) => product.title),
    });
  }

  let candidateIds = match.candidateIds;
  const windowStart = Math.min(match.shownCount, candidateIds.length);
  const reference = referenceProduct(itemId, store);
  if (reference && windowStart < candidateIds.length) {
    // The next options should resemble what the user picked at the other store, not just the next search hits.
    const remaining = sortByReference(
      reference,
      productsByIds(candidateIds.slice(windowStart)),
    );
    candidateIds = [
      ...candidateIds.slice(0, windowStart),
      ...remaining.map((product) => product.id),
    ];
  }
  if (windowStart >= candidateIds.length) {
    const { ids: more, tried } = await findMoreCandidates(
      item,
      store,
      candidateIds,
      reference,
    );
    if (!more.length) {
      // Tell the app what was tried so "Search alternatives" visibly did something.
      const reason = tried.length
        ? `Nothing new for: ${tried.join(", ")}`
        : "No alternative searches available";
      return saveMatch({
        ...match,
        hasRejectedSuggestions: match.hasRejectedSuggestions || shownProducts.length > 0,
        windowStart,
        shownCount: windowStart,
        status: "EXHAUSTED",
        chosenProductId: null,
        chosenBy: null,
        reason,
        updatedAt: now(),
      });
    }
    candidateIds = [...candidateIds, ...more];
    const equivalences = { ...match.equivalences };
    for (const id of more) equivalences[id] = equivalences[id] ?? "SUBSTITUTE";
    return saveMatch({
      ...match,
      hasRejectedSuggestions: match.hasRejectedSuggestions || shownProducts.length > 0,
      candidateIds,
      equivalences,
      windowStart,
      shownCount: Math.min(windowStart + OPTIONS_PER_PAGE, candidateIds.length),
      status: "PENDING",
      chosenProductId: null,
      chosenBy: "AI",
      reason: `Alternatives for: ${tried.slice(0, 3).join(", ")}`,
      updatedAt: now(),
    });
  }
  return saveMatch({
    ...match,
    hasRejectedSuggestions: match.hasRejectedSuggestions || shownProducts.length > 0,
    candidateIds,
    windowStart,
    shownCount: Math.min(windowStart + OPTIONS_PER_PAGE, candidateIds.length),
    status: "PENDING",
    chosenProductId: null,
    chosenBy: "AI",
    updatedAt: now(),
  });
}

/** The product chosen for this item at another store, if any: the best hint of what "fits". */
function referenceProduct(itemId: string, store: string): ProductRow | null {
  const chosen = getMatches(itemId)
    .filter((match) => match.store !== store && match.chosenProductId)
    .map((match) => match.chosenProductId as string);
  return chosen.length ? (productsByIds(chosen)[0] ?? null) : null;
}

/** "Biologische scharreleieren 10 stuks" for the AI, and "scharreleieren 10 stuks" as a query without the brand. */
function describeReference(reference: ProductRow) {
  const price = `€${(reference.priceCents / 100).toFixed(2)}`;
  const unitPrice = reference.unitPriceCents
    ? `, €${(reference.unitPriceCents / 100).toFixed(2)}/${reference.unitPriceUnit}`
    : "";
  return `${reference.title} (${reference.quantityText}, ${price}${unitPrice})`;
}

async function findMoreCandidates(
  item: BasketItemRow,
  store: string,
  known: string[],
  reference: ProductRow | null = null,
): Promise<{ ids: string[]; tried: string[] }> {
  const parsed = item.parsedJson ?? {
    canonicalName: item.text,
    attributes: [],
    sizeHint: null,
    queries: {},
    fallbackQuery: null,
    ambiguous: false,
  };
  const tried = [
    parsed.queries[store] ?? item.text,
    parsed.fallbackQuery,
    item.text,
  ].filter((query): query is string => Boolean(query));
  const rejectedTitles = productsByIds(known).map((product) => product.title);
  const attempts: string[] = [];
  if (normalizeText(parsed.canonicalName) !== normalizeText(item.text))
    attempts.push(parsed.canonicalName);
  if (reference) {
    // The other store's pick, minus its brand, is usually the best query for the equivalent here.
    const brandless = reference.brand
      ? reference.title
          .replace(
            new RegExp(
              reference.brand.replace(/[.*+?^${}()|[\]\\]/g, "\\$&"),
              "i",
            ),
            "",
          )
          .trim()
      : reference.title;
    if (
      brandless &&
      !tried.some((query) => normalizeText(query) === normalizeText(brandless))
    )
      attempts.push(brandless);
  }
  if (aiConfigured()) {
    const alternatives = await alternativeQuery(
      item.text,
      parsed,
      store,
      rejectedTitles.slice(-9),
      [...tried, ...attempts],
      reference ? describeReference(reference) : null,
    );
    attempts.unshift(...alternatives);
  }
  // Also retry the original query uncached: the first search may have been served from a stale cache.
  attempts.push(...tried.slice(0, 1));
  const fresh: ProductRow[] = [];
  const seen = new Set(known);
  for (const query of attempts) {
    try {
      const result = await searchStore(store, query, {
        limit: 20,
        force: query === tried[0],
      });
      for (const product of result.products) {
        if (!seen.has(product.id)) {
          seen.add(product.id);
          fresh.push(product);
        }
      }
    } catch (error) {
      console.warn(
        `[pipeline] alternative "${query}" at ${store} failed: ${error instanceof Error ? error.message : error}`,
      );
    }
    if (fresh.length >= 6) break;
  }
  return {
    ids: lexicalRank(item.text, parsed, fresh, reference).map(
      (entry) => entry.product.id,
    ),
    tried: attempts,
  };
}

/**
 * Manual search from the app: the typed text goes through the usual pipeline (understand it, search the
 * store with the right Dutch queries, rank with the AI and your preferences) and the best results become
 * the next options.
 */
export async function searchMoreCandidates(
  itemId: string,
  store: string,
  query: string,
) {
  const item = getItem(itemId);
  const match = getMatch(itemId, store);
  if (!item || !match) return null;
  const probe: BasketItemRow = { ...item, text: query };
  const parsed = await parseIdea(query, [store]);
  // Keep the original idea's size preference when the new text does not state one.
  if (!parsed.sizeHint && item.parsedJson?.sizeHint)
    parsed.sizeHint = item.parsedJson.sizeHint;
  const { candidates } = await collectCandidates(probe, parsed, store);
  const fresh = candidates.filter(
    (product) => !match.candidateIds.includes(product.id),
  );
  if (!fresh.length) {
    return saveMatch({
      ...match,
      reason: `Nothing new for "${query}" (tried ${parsed.queries[store] ?? query})`,
      updatedAt: now(),
    });
  }
  const ranked = await rankCandidates(probe, parsed, store, fresh);
  const candidateIds = [...match.candidateIds, ...ranked.orderedIds];
  return saveMatch({
    ...match,
    candidateIds,
    equivalences: { ...match.equivalences, ...ranked.equivalences },
    windowStart: match.candidateIds.length,
    shownCount: Math.min(
      candidateIds.length,
      match.candidateIds.length + OPTIONS_PER_PAGE,
    ),
    status: "PENDING",
    chosenProductId: null,
    chosenBy: "AI",
    reason: ranked.reason ?? `Results for "${query}"`,
    updatedAt: now(),
  });
}

export function rematchItem(itemId: string) {
  return processItem(itemId, { keepUserChoices: false });
}

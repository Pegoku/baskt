import type { BasketItemRow, BasketMatchRow, ProductRow } from "@/db/schema";
import { OPTIONS_PER_PAGE, shownWindow } from "@/matching/pipeline";

export type ProductView = ProductRow;

export type MatchView = {
  store: string;
  status: BasketMatchRow["status"];
  chosenBy: BasketMatchRow["chosenBy"];
  chosen: ProductView | null;
  /** Provisional product used by the comparison while the user has not chosen yet. */
  provisional: ProductView | null;
  options: ProductView[];
  equivalences: Record<string, string>;
  hasMore: boolean;
  hasRejectedSuggestions: boolean;
  totalCandidates: number;
  page: number;
  confidence: number | null;
  reason: string | null;
  updatedAt: number;
};

export type BasketItemView = {
  id: string;
  basketId: string;
  kind: BasketItemRow["kind"];
  parentId: string | null;
  recipe: BasketItemRow["recipeJson"];
  assignedStore: string | null;
  skippedReason: string | null;
  text: string;
  quantity: number;
  checked: boolean;
  sortOrder: number;
  status: BasketItemRow["status"];
  error: string | null;
  parsed: BasketItemRow["parsedJson"];
  needsChoice: boolean;
  matches: MatchView[];
  createdAt: number;
  updatedAt: number;
};

export function matchView(match: BasketMatchRow, products: Map<string, ProductRow>): MatchView {
  const optionIds = shownWindow(match);
  const chosen = match.chosenProductId ? products.get(match.chosenProductId) ?? null : null;
  const provisional = match.status === "PENDING" ? products.get(match.candidateIds[0] ?? "") ?? null : chosen;
  return {
    store: match.store,
    status: match.status,
    chosenBy: match.chosenBy,
    chosen,
    provisional,
    options: optionIds.map((id) => products.get(id)).filter((product): product is ProductRow => Boolean(product)),
    equivalences: Object.fromEntries(optionIds.map((id) => [id, match.equivalences[id] ?? "EQUIVALENT"])),
    hasRejectedSuggestions: match.hasRejectedSuggestions,
    hasMore: match.status !== "EXHAUSTED",
    totalCandidates: match.candidateIds.length,
    page: Math.max(1, Math.floor(match.windowStart / OPTIONS_PER_PAGE) + 1),
    confidence: match.confidence,
    reason: match.reason,
    updatedAt: match.updatedAt,
  };
}

export function itemView(item: BasketItemRow, matches: BasketMatchRow[], products: Map<string, ProductRow>): BasketItemView {
  const views = matches.map((match) => matchView(match, products));
  return {
    id: item.id,
    basketId: item.basketId,
    kind: item.kind,
    parentId: item.parentId ?? null,
    recipe: item.recipeJson ?? null,
    assignedStore: item.assignedStore ?? null,
    skippedReason: item.skippedReason ?? null,
    text: item.text,
    quantity: item.quantity,
    checked: item.checked,
    sortOrder: item.sortOrder,
    status: item.status,
    error: item.error,
    parsed: item.parsedJson ?? null,
    needsChoice: item.status === "MATCHED" && views.some((view) => view.status === "PENDING"),
    matches: views,
    createdAt: item.createdAt,
    updatedAt: item.updatedAt,
  };
}

export function collectProductIds(matches: BasketMatchRow[]) {
  const ids = new Set<string>();
  for (const match of matches) {
    for (const id of shownWindow(match)) ids.add(id);
    if (match.chosenProductId) ids.add(match.chosenProductId);
    if (match.candidateIds[0]) ids.add(match.candidateIds[0]);
  }
  return Array.from(ids);
}

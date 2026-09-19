import { beforeAll, describe, expect, test } from "bun:test";
import { eq } from "drizzle-orm";
import { db, newId, resetDbForTests } from "@/db";
import { basketMatches, type BasketItemRow, type BasketMatchRow } from "@/db/schema";
import { createApp } from "@/app";
import { pickDeals, planTidy, splitGroups, toCandidate, type TidyCandidate } from "@/matching/tidy";
import type { Deal } from "@/matching/deals";
import type { ProductRow } from "@/db/schema";
import type { DuplicateGroup } from "@/matching/dedupe";
import { setAdaptersForTests } from "@/stores/registry";
import { StoreThrottle } from "@/stores/throttle";
import type { StoreAdapter } from "@/stores/types";

const app = createApp({ token: "secret", log: false });
const headers = { authorization: "Bearer secret", "content-type": "application/json" };
const api = (path: string, init: RequestInit = {}) => app.request(`/api/v1${path}`, { ...init, headers: { ...headers, ...(init.headers ?? {}) } });

const throttle = new StoreThrottle("AH", 0);
const ah: StoreAdapter & { health(): ReturnType<StoreThrottle["health"]> } = {
  info: { code: "AH", name: "Albert Heijn", color: "#000", coverage: "full" },
  health: () => throttle.health(),
  async search() {
    return [];
  },
};

beforeAll(() => {
  resetDbForTests(":memory:");
  setAdaptersForTests([ah]);
});

function row(id: string, text: string, quantity = 1, createdAt = 0): BasketItemRow {
  return { id, basketId: "default", kind: "item", parentId: null, recipeJson: null, assignedStore: null, skippedReason: null, text, quantity, checked: false, boughtAt: null, sortOrder: 0, status: "MATCHED", error: null, parsedJson: null, createdAt, updatedAt: createdAt };
}

function match(itemId: string, store: string, productId: string | null, chosenBy: BasketMatchRow["chosenBy"]): BasketMatchRow {
  return { id: newId(), itemId, store, candidateIds: productId ? [productId] : [], equivalences: {}, hasRejectedSuggestions: false, windowStart: 0, shownCount: 3, chosenProductId: productId, status: productId ? "CHOSEN" : "PENDING", chosenBy, confidence: null, reason: null, updatedAt: 0 };
}

function candidate(id: string, text: string, matches: BasketMatchRow[] = [], quantity = 1, createdAt = 0): TidyCandidate {
  return toCandidate(row(id, text, quantity, createdAt), matches, new Map(), null);
}

const group = (ids: string[], mergedText: string): DuplicateGroup => ({ items: ids.map((id) => ({ id, text: id, quantity: 1, folder: null })), reason: "same thing", mergedText, mergedQuantity: ids.length, exact: true });

describe("tidy plan", () => {
  test("merges duplicates when nobody picked a product, keeping the oldest", () => {
    const byId = new Map([
      ["a", candidate("a", "melk", [], 1, 20)],
      ["b", candidate("b", "milk", [], 2, 10)],
    ]);
    const { merges, distinct } = splitGroups([group(["a", "b"], "milk")], byId);
    expect(distinct).toEqual([]);
    expect(merges).toHaveLength(1);
    expect(merges[0].keepId).toBe("b");
    expect(merges[0].quantity).toBe(3);
    expect(merges[0].keepQuantity).toBe(2);
    expect(merges[0].text).toBe("milk");
  });

  test("merges into the entry with the user's pick when only one has picks", () => {
    const byId = new Map([
      ["a", candidate("a", "koekjes", [match("a", "AH", "AH:1", "AI")], 1, 0)],
      ["b", candidate("b", "cookies", [match("b", "AH", "AH:2", "USER")], 1, 10)],
    ]);
    const { merges, distinct } = splitGroups([group(["a", "b"], "cookies")], byId);
    expect(distinct).toEqual([]);
    expect(merges[0].keepId).toBe("b");
  });

  test("keeps look-alikes apart when each has its own chosen product", () => {
    const byId = new Map([
      ["a", candidate("a", "chocolate cookies", [match("a", "AH", "AH:1", "USER")])],
      ["b", candidate("b", "choc cookies", [match("b", "AH", "AH:2", "MEMORY")])],
    ]);
    const { merges, distinct } = splitGroups([group(["a", "b"], "chocolate cookies")], byId);
    expect(merges).toEqual([]);
    expect(distinct).toHaveLength(1);
    expect(distinct[0].items.map((item) => item.product)).toEqual(["AH:1", "AH:2"]);
  });

  test("still merges when both entries picked the very same product", () => {
    const byId = new Map([
      ["a", candidate("a", "melk", [match("a", "AH", "AH:1", "USER")])],
      ["b", candidate("b", "milk", [match("b", "AH", "AH:1", "USER")])],
    ]);
    const { merges, distinct } = splitGroups([group(["a", "b"], "milk")], byId);
    expect(distinct).toEqual([]);
    expect(merges).toHaveLength(1);
  });

  test("without AI the plan only carries exact merges and no renames", async () => {
    const plan = await planTidy([candidate("a", "Melk"), candidate("b", "melk", [], 2), candidate("c", "brood")]);
    expect(plan.scanned).toBe(3);
    expect(plan.renames).toEqual([]);
    expect(plan.distinct).toEqual([]);
    expect(plan.merges).toHaveLength(1);
    expect(plan.merges[0].items.map((item) => item.id)).toEqual(["a", "b"]);
    expect(plan.merges[0].quantity).toBe(3);
  });

  test("deals keep one per entry and store, skipping current picks and merged-away entries", () => {
    const product = (id: string) => ({ id, priceCents: 100 }) as ProductRow;
    const deal = (itemId: string, store: string, productId: string, currentProductId: string | null, savingCents: number): Deal => ({ itemId, itemText: itemId, store, product: product(productId), currentProductId, currentPriceCents: null, savingCents, equivalence: "EQUIVALENT" });
    const picked = pickDeals(
      [deal("a", "AH", "AH:1", null, 50), deal("a", "AH", "AH:2", null, 20), deal("a", "JUMBO", "J:1", null, 10), deal("b", "AH", "AH:3", "AH:3", 90), deal("c", "AH", "AH:4", null, 30)],
      new Set(["c"]),
    );
    expect(picked.map((entry) => `${entry.itemId}:${entry.product.id}`)).toEqual(["a:AH:1", "a:J:1"]);
  });

  test("route scans open items and reads picks from the matches table", async () => {
    for (const text of ["melk", "Melk", "brood"]) await api("/basket/items", { method: "POST", body: JSON.stringify({ text }) });
    const before = (await (await api("/basket")).json()) as { items: Array<{ id: string; text: string }> };
    const [first, second] = before.items.filter((item) => item.text.toLowerCase() === "melk");
    const bread = before.items.find((item) => item.text === "brood")!;
    await api(`/basket/items/${bread.id}`, { method: "PATCH", body: JSON.stringify({ checked: true }) });
    db().insert(basketMatches).values(match(first.id, "AH", "AH:1", "USER")).run();
    db().insert(basketMatches).values(match(second.id, "AH", "AH:2", "USER")).run();
    const response = await api("/basket/tidy", { method: "POST", body: JSON.stringify({}) });
    expect(response.status).toBe(200);
    const plan = (await response.json()) as { scanned: number; merges: unknown[]; deals: unknown[]; distinct: Array<{ items: Array<{ id: string }> }> };
    expect(plan.scanned).toBe(2);
    expect(plan.deals).toEqual([]);
    expect(plan.merges).toEqual([]);
    expect(plan.distinct).toHaveLength(1);
    expect(plan.distinct[0].items.map((item) => item.id).sort()).toEqual([first.id, second.id].sort());
  });

  test("renaming with keepMatches keeps the picks, a plain rename drops them", async () => {
    const created = (await (await api("/basket/items", { method: "POST", body: JSON.stringify({ text: "yorkham" }) })).json()) as { id: string };
    db().insert(basketMatches).values(match(created.id, "AH", "AH:9", "USER")).run();
    const kept = await api(`/basket/items/${created.id}`, { method: "PATCH", body: JSON.stringify({ text: "jamón york", keepMatches: true }) });
    expect(kept.status).toBe(200);
    expect(db().select().from(basketMatches).where(eq(basketMatches.itemId, created.id)).all()).toHaveLength(1);
    expect(((await kept.json()) as { text: string }).text).toBe("jamón york");
    await api(`/basket/items/${created.id}`, { method: "PATCH", body: JSON.stringify({ text: "ham" }) });
    expect(db().select().from(basketMatches).where(eq(basketMatches.itemId, created.id)).all()).toHaveLength(0);
  });
});

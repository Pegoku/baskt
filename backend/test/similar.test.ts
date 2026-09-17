import { beforeAll, describe, expect, test } from "bun:test";
import { resetDbForTests } from "@/db";
import { createApp } from "@/app";
import { findSimilar, heuristicKind, heuristicQueries, setSimilarFeedback } from "@/matching/similar";
import { setAdaptersForTests } from "@/stores/registry";
import { toRow, upsertProducts } from "@/stores/search";
import { StoreThrottle } from "@/stores/throttle";
import type { StoreAdapter, StoreProduct } from "@/stores/types";

function fake(store: string, name: string, catalogue: Record<string, StoreProduct[]>): StoreAdapter & { health(): ReturnType<StoreThrottle["health"]>; calls: string[] } {
  const throttle = new StoreThrottle(store, 0);
  return {
    info: { code: store, name, color: "#000", coverage: "full" },
    calls: [],
    health: () => throttle.health(),
    async search(query) {
      this.calls.push(query);
      const key = Object.keys(catalogue).find((entry) => query.toLowerCase().includes(entry));
      return key ? catalogue[key] : [];
    },
  };
}

function sp(store: string, sourceId: string, title: string, priceCents: number, brand: string | null = null, unitAmount = 1, unit = "l"): StoreProduct {
  return {
    store,
    sourceId,
    title,
    brand,
    quantityText: `${unitAmount} ${unit}`,
    unitAmount,
    unit: unit as StoreProduct["unit"],
    priceCents,
    regularPriceCents: null,
    unitPriceCents: Math.round(priceCents / unitAmount),
    unitPriceUnit: unit as StoreProduct["unitPriceUnit"],
    dealText: null,
    isDeal: false,
    imageUrl: null,
    sourceUrl: null,
    category: "Zuivel",
    available: true,
  };
}

const jumboMilk = sp("JUMBO", "a", "Jumbo Verse Halfvolle Melk 1 L", 129, "Jumbo");
const campinaJumbo = sp("JUMBO", "b", "Campina Halfvolle Melk 1 L", 179, "Campina");
const ah = fake("AH", "Albert Heijn", {
  melk: [
    sp("AH", "1", "AH Halfvolle melk", 95, "AH"),
    sp("AH", "2", "Campina Halfvolle melk", 149, "Campina"),
    sp("AH", "3", "AH Halfvolle melk", 189, "AH", 2, "l"),
    sp("AH", "4", "AH Chocolademelk", 199, "AH"),
  ],
});
const jumbo = fake("JUMBO", "Jumbo", { melk: [jumboMilk, campinaJumbo, sp("JUMBO", "c", "Jumbo Volle Melk 1 L", 135, "Jumbo")] });

const app = createApp({ token: "secret", log: false });
const headers = { authorization: "Bearer secret", "content-type": "application/json" };
const api = (path: string, init: RequestInit = {}) => app.request(`/api/v1${path}`, { ...init, headers: { ...headers, ...(init.headers ?? {}) } });

beforeAll(() => {
  resetDbForTests(":memory:");
  setAdaptersForTests([ah, jumbo]);
  upsertProducts([jumboMilk, campinaJumbo]);
});

describe("similar products", () => {
  test("queries drop the store prefix and the brand", () => {
    expect(heuristicQueries(toRow(jumboMilk))).toEqual(["Verse Halfvolle Melk 1 L", "Verse Halfvolle Melk"]);
    expect(heuristicQueries(toRow(campinaJumbo))).toEqual(["Campina Halfvolle Melk 1 L", "Halfvolle Melk 1 L", "Halfvolle Melk"]);
  });

  test("same brand and pack is the same product, another brand the equivalent", () => {
    const reference = toRow(campinaJumbo);
    expect(heuristicKind(reference, toRow(sp("AH", "2", "Campina Halfvolle melk", 149, "Campina")))).toBe("SAME");
    expect(heuristicKind(reference, toRow(sp("AH", "1", "AH Halfvolle melk", 95, "AH")))).toBe("EQUIVALENT");
    expect(heuristicKind(reference, toRow(sp("AH", "9", "Pindakaas", 250, "Calvé", 350, "g")))).toBe("SUBSTITUTE");
  });

  test("finds the closest product at every store and never returns the product itself", async () => {
    const results = await findSimilar(toRow(campinaJumbo), ["AH", "JUMBO"], { limit: 3 });
    const atAh = results.find((result) => result.store === "AH")!;
    expect(atAh.error).toBeNull();
    expect(atAh.matches[0].product.id).toBe("AH:2");
    expect(atAh.matches[0].kind).toBe("SAME");
    expect(atAh.matches.map((match) => match.product.id)).not.toContain("AH:4");
    const atJumbo = results.find((result) => result.store === "JUMBO")!;
    expect(atJumbo.matches.map((match) => match.product.id)).not.toContain("JUMBO:b");
    expect(atJumbo.matches[0].product.id).toBe("JUMBO:a");
  });

  test("route returns per-store matches for a known product and 404 otherwise", async () => {
    const response = await api("/products/JUMBO:b/similar?limit=2");
    expect(response.status).toBe(200);
    const body = (await response.json()) as { product: { id: string }; results: Array<{ store: string; matches: Array<{ kind: string }>; queries: string[] }> };
    expect(body.product.id).toBe("JUMBO:b");
    expect(body.results.map((result) => result.store).sort()).toEqual(["AH", "JUMBO"]);
    expect(body.results.every((result) => result.matches.length <= 2)).toBe(true);
    expect(body.results[0].queries.length).toBeGreaterThan(0);
    expect((await api("/products/JUMBO:b/similar?limit=99")).status).toBe(200);
    expect((await api("/products/NOPE:1/similar")).status).toBe(404);
    expect((await api("/products/JUMBO:b/similar?store=LIDL")).status).toBe(400);
  });

  test("thumbs down hides a pairing, thumbs up pins it first, null forgets", async () => {
    const reference = toRow(campinaJumbo);
    setSimilarFeedback(reference.id, "AH:2", false);
    setSimilarFeedback(reference.id, "AH:3", true);
    const [atAh] = await findSimilar(reference, ["AH"], { limit: 5 });
    const ids = atAh.matches.map((match) => match.product.id);
    expect(ids).not.toContain("AH:2");
    expect(ids[0]).toBe("AH:3");
    expect(atAh.matches[0].feedback).toBe("UP");
    expect(atAh.vision).toBe(false);

    const response = await api("/products/JUMBO:b/similar/feedback", { method: "POST", body: JSON.stringify({ productId: "AH:2", up: null }) });
    expect(response.status).toBe(200);
    expect(((await response.json()) as { feedback: string | null }).feedback).toBeNull();
    const [again] = await findSimilar(reference, ["AH"], { limit: 5 });
    expect(again.matches.map((match) => match.product.id)).toContain("AH:2");

    expect((await api("/products/JUMBO:b/similar/feedback", { method: "POST", body: JSON.stringify({ productId: "JUMBO:b", up: true }) })).status).toBe(400);
    expect((await api("/products/JUMBO:b/similar/feedback", { method: "POST", body: JSON.stringify({ productId: "AH:1" }) })).status).toBe(400);
    expect((await api("/products/NOPE:1/similar/feedback", { method: "POST", body: JSON.stringify({ productId: "AH:1", up: true }) })).status).toBe(404);
  });
});

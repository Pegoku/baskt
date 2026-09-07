import { beforeAll, describe, expect, test } from "bun:test";
import { resetDbForTests } from "@/db";
import { createApp } from "@/app";
import { setAdaptersForTests } from "@/stores/registry";
import { StoreThrottle } from "@/stores/throttle";
import type { StoreAdapter, StoreProduct } from "@/stores/types";

process.env.APP_API_TOKEN = "secret";

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

function sp(store: string, sourceId: string, title: string, priceCents: number, quantityText = "1 l"): StoreProduct {
  return {
    store,
    sourceId,
    title,
    brand: null,
    quantityText,
    unitAmount: 1,
    unit: "l",
    priceCents,
    regularPriceCents: null,
    unitPriceCents: priceCents,
    unitPriceUnit: "l",
    dealText: null,
    isDeal: false,
    imageUrl: null,
    sourceUrl: null,
    category: "Zuivel",
    available: true,
  };
}

const ah = fake("AH", "Albert Heijn", {
  melk: [
    sp("AH", "1", "AH Halfvolle melk", 95),
    sp("AH", "2", "Campina Halfvolle melk", 149),
    sp("AH", "3", "AH Biologisch halfvolle melk", 139),
    sp("AH", "4", "Arla Halfvolle melk", 159),
  ],
  chocolade: [sp("AH", "9", "AH Chocolademelk", 199)],
});
const jumbo = fake("JUMBO", "Jumbo", {
  melk: [sp("JUMBO", "a", "Jumbo Verse Halfvolle Melk 1 L", 129), sp("JUMBO", "b", "Campina Langlekker Halfvolle Melk 1 L", 179)],
});

const app = createApp({ token: "secret", log: false });
const headers = { authorization: "Bearer secret", "content-type": "application/json" };
const api = (path: string, init: RequestInit = {}) => app.request(`/api/v1${path}`, { ...init, headers: { ...headers, ...(init.headers ?? {}) } });

async function waitForMatched(id: string) {
  for (let i = 0; i < 50; i += 1) {
    const body = (await (await api("/basket")).json()) as { items: Array<{ id: string; status: string }> };
    const item = body.items.find((entry) => entry.id === id);
    if (item?.status === "MATCHED" || item?.status === "ERROR") return body;
    await new Promise((resolve) => setTimeout(resolve, 20));
  }
  throw new Error("item never finished");
}

beforeAll(() => {
  resetDbForTests(":memory:");
  setAdaptersForTests([ah, jumbo]);
});

describe("api", () => {
  test("rejects missing token", async () => {
    const response = await app.request("/api/v1/health");
    expect(response.status).toBe(401);
  });

  test("health and stores", async () => {
    expect((await api("/health")).status).toBe(200);
    const stores = (await (await api("/stores")).json()) as { stores: Array<{ code: string; enabled: boolean }> };
    expect(stores.stores.map((store) => store.code)).toEqual(["AH", "JUMBO"]);
    expect(stores.stores.every((store) => store.enabled)).toBe(true);
  });

  let itemId = "";

  test("adding an idea produces 3 options per store", async () => {
    const created = await api("/basket/items", { method: "POST", body: JSON.stringify({ text: "halfvolle melk", quantity: 2 }) });
    expect(created.status).toBe(201);
    itemId = ((await created.json()) as { id: string }).id;
    const body = (await waitForMatched(itemId)) as any;
    const item = body.items[0];
    expect(item.status).toBe("MATCHED");
    expect(item.needsChoice).toBe(true);
    expect(item.parsed.queries.AH).toBe("halfvolle melk");
    const ahMatch = item.matches.find((match: any) => match.store === "AH");
    expect(ahMatch.status).toBe("PENDING");
    expect(ahMatch.options).toHaveLength(3);
    expect(ahMatch.options[0].title).toBe("AH Halfvolle melk");
    expect(ahMatch.provisional.id).toBe("AH:1");
    const jumboMatch = item.matches.find((match: any) => match.store === "JUMBO");
    expect(jumboMatch.options).toHaveLength(2);
    expect(ah.calls.length).toBeGreaterThan(0);
  });

  test("compare uses provisional picks while unconfirmed", async () => {
    const compare = (await (await api("/basket/compare")).json()) as any;
    const ahStore = compare.stores.find((store: any) => store.store === "AH");
    expect(ahStore.fullTotalCents).toBe(190);
    expect(ahStore.unconfirmedCount).toBe(1);
  });

  test("none of them fit shows the next options and remembers the rejection", async () => {
    const response = await api(`/basket/items/${itemId}/matches/AH/reject`, { method: "POST" });
    expect(response.status).toBe(200);
    const item = (await response.json()) as any;
    const match = item.matches.find((entry: any) => entry.store === "AH");
    expect(match.status).toBe("PENDING");
    expect(match.page).toBe(2);
    expect(match.options.map((option: any) => option.id)).toEqual(["AH:4"]);
    const memory = (await (await api("/memory")).json()) as any;
    expect(memory.choices).toHaveLength(1);
    expect(memory.choices[0].rejectedTitles).toHaveLength(3);
  });

  test("rejecting everything marks the store exhausted", async () => {
    const response = await api(`/basket/items/${itemId}/matches/AH/reject`, { method: "POST" });
    const item = (await response.json()) as any;
    const match = item.matches.find((entry: any) => entry.store === "AH");
    expect(match.status).toBe("EXHAUSTED");
    expect(match.options).toEqual([]);
  });

  test("manual search adds new options", async () => {
    const response = await api(`/basket/items/${itemId}/matches/AH/search`, { method: "POST", body: JSON.stringify({ query: "chocolade" }) });
    const item = (await response.json()) as any;
    const match = item.matches.find((entry: any) => entry.store === "AH");
    expect(match.status).toBe("PENDING");
    expect(match.options.map((option: any) => option.id)).toEqual(["AH:9"]);
  });

  test("choosing records the choice and confirms the match", async () => {
    const response = await api(`/basket/items/${itemId}/matches/JUMBO/choose`, { method: "POST", body: JSON.stringify({ productId: "JUMBO:b" }) });
    expect(response.status).toBe(200);
    const item = (await response.json()) as any;
    const match = item.matches.find((entry: any) => entry.store === "JUMBO");
    expect(match.status).toBe("CHOSEN");
    expect(match.chosenBy).toBe("USER");
    expect(match.chosen.id).toBe("JUMBO:b");
    const compare = (await (await api("/basket/compare")).json()) as any;
    const jumboStore = compare.stores.find((store: any) => store.store === "JUMBO");
    expect(jumboStore.fullTotalCents).toBe(358);
    expect(jumboStore.unconfirmedCount).toBe(0);
  });

  test("a new identical idea is auto-matched from memory", async () => {
    const created = await api("/basket/items", { method: "POST", body: JSON.stringify({ text: "halfvolle melk" }) });
    const id = ((await created.json()) as { id: string }).id;
    const body = (await waitForMatched(id)) as any;
    const item = body.items.find((entry: any) => entry.id === id);
    const match = item.matches.find((entry: any) => entry.store === "JUMBO");
    expect(match.status).toBe("CHOSEN");
    expect(match.chosenBy).toBe("MEMORY");
    expect(match.chosen.id).toBe("JUMBO:b");
  });

  test("suggest returns history matches without AI", async () => {
    const body = (await (await api("/basket/suggest?q=halfv")).json()) as { suggestions: string[]; source: string };
    expect(body.suggestions).toContain("halfvolle melk");
    expect(body.source).toBe("history");
    const short = (await (await api("/basket/suggest?q=h")).json()) as { suggestions: string[] };
    expect(short.suggestions).toEqual([]);
  });

  test("delete leaves a tombstone", async () => {
    expect((await api(`/basket/items/${itemId}`, { method: "DELETE" })).status).toBe(204);
    const body = (await (await api("/basket?since=1")).json()) as any;
    expect(body.deletedIds).toContain(itemId);
  });
});

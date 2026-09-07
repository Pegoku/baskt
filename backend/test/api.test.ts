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

  test("language setting is stored and normalized", async () => {
    const response = await api("/settings", { method: "PATCH", body: JSON.stringify({ language: "nl-NL" }) });
    expect(((await response.json()) as { language: string }).language).toBe("nl");
    const current = (await (await api("/settings")).json()) as { language: string; enabledStores: string[] };
    expect(current.language).toBe("nl");
    expect(current.enabledStores).toEqual(["AH", "JUMBO"]);
  });

  test("suggest returns history matches without AI", async () => {
    const body = (await (await api("/basket/suggest?q=halfv")).json()) as { suggestions: string[]; source: string };
    expect(body.suggestions).toContain("halfvolle melk");
    expect(body.source).toBe("history");
    const short = (await (await api("/basket/suggest?q=h")).json()) as { suggestions: string[] };
    expect(short.suggestions).toEqual([]);
  });

  test("folders hold child items, cascade on delete and toggle checked together", async () => {
    const created = await api("/basket/groups", { method: "POST", body: JSON.stringify({ text: "cookies", items: ["halfvolle melk", "eieren"] }) });
    expect(created.status).toBe(201);
    const body = (await created.json()) as any;
    expect(body.group.kind).toBe("group");
    expect(body.items).toHaveLength(2);
    expect(body.items.every((child: any) => child.parentId === body.group.id)).toBe(true);
    await waitForMatched(body.items[0].id);
    const basketBody = (await (await api("/basket")).json()) as any;
    const group = basketBody.items.find((entry: any) => entry.id === body.group.id);
    expect(group.status).toBe("MATCHED");
    expect(group.matches).toEqual([]);
    const compare = (await (await api("/basket/compare")).json()) as any;
    expect(compare.items.some((row: any) => row.itemId === body.group.id)).toBe(false);
    expect(compare.items.some((row: any) => row.itemId === body.items[0].id)).toBe(true);

    const checked = (await (await api(`/basket/items/${body.group.id}`, { method: "PATCH", body: JSON.stringify({ checked: true }) })).json()) as any;
    expect(checked.checked).toBe(true);
    const after = (await (await api("/basket")).json()) as any;
    expect(after.items.filter((entry: any) => entry.parentId === body.group.id).every((child: any) => child.checked)).toBe(true);

    const added = await api(`/basket/groups/${body.group.id}/items`, { method: "POST", body: JSON.stringify({ text: "boter" }) });
    expect(added.status).toBe(201);
    expect(((await added.json()) as any).parentId).toBe(body.group.id);

    expect((await api(`/basket/items/${body.group.id}`, { method: "DELETE" })).status).toBe(204);
    const gone = (await (await api("/basket?since=1")).json()) as any;
    expect(gone.deletedIds).toContain(body.group.id);
    expect(gone.deletedIds).toContain(body.items[0].id);
    expect(gone.items.some((entry: any) => entry.parentId === body.group.id)).toBe(false);
  });

  test("recipe-like text creates a folder even without AI", async () => {
    const created = await api("/basket/items", { method: "POST", body: JSON.stringify({ text: "ingredients for pancakes" }) });
    const group = (await created.json()) as any;
    expect(group.kind).toBe("group");
    for (let i = 0; i < 50 && ((await (await api("/basket")).json()) as any).items.find((e: any) => e.id === group.id)?.status === "PARSING"; i += 1) await new Promise((r) => setTimeout(r, 20));
    const final = ((await (await api("/basket")).json()) as any).items.find((e: any) => e.id === group.id);
    expect(final.status).toBe("ERROR");
    expect(final.error).toContain("No ingredients");
    const suggestion = (await (await api("/basket/suggest?q=ingredients%20for%20pancakes")).json()) as any;
    expect(suggestion.recipe).toBeNull();
    await api(`/basket/items/${group.id}`, { method: "DELETE" });
  });

  test("multiple baskets: create, add into, move and copy items, delete", async () => {
    const created = await api("/baskets", { method: "POST", body: JSON.stringify({ name: "Sweets", emoji: "🍫" }) });
    expect(created.status).toBe(201);
    const sweets = (await created.json()) as any;
    const list = (await (await api("/baskets")).json()) as any;
    expect(list.baskets.map((b: any) => b.name)).toEqual(["Personal", "Sweets"]);

    const added = (await (await api("/basket/items", { method: "POST", body: JSON.stringify({ text: "chocolade", basketId: sweets.id }) })).json()) as any;
    expect(added.basketId).toBe(sweets.id);
    const personal = (await (await api("/basket")).json()) as any;
    expect(personal.items.some((i: any) => i.id === added.id)).toBe(false);
    const sweetsBasket = (await (await api(`/basket?basketId=${sweets.id}`)).json()) as any;
    expect(sweetsBasket.items.some((i: any) => i.id === added.id)).toBe(true);

    const copied = (await (await api(`/basket/items/${added.id}/transfer`, { method: "POST", body: JSON.stringify({ basketId: "default", copy: true }) })).json()) as any;
    expect(copied.id).not.toBe(added.id);
    expect(copied.basketId).toBe("default");
    const moved = (await (await api(`/basket/items/${added.id}/transfer`, { method: "POST", body: JSON.stringify({ basketId: "default" }) })).json()) as any;
    expect(moved.id).toBe(added.id);
    expect(moved.basketId).toBe("default");
    expect(((await (await api(`/basket?basketId=${sweets.id}`)).json()) as any).items).toHaveLength(0);

    expect((await api(`/baskets/${sweets.id}`, { method: "DELETE" })).status).toBe(204);
    expect((await api("/baskets/default", { method: "DELETE" })).status).toBe(400);
    await api(`/basket/items/${added.id}`, { method: "DELETE" });
    await api(`/basket/items/${copied.id}`, { method: "DELETE" });
  });

  test("stock entries are matched against ingredient texts", async () => {
    const added = await api("/stock", { method: "POST", body: JSON.stringify({ text: "salt" }) });
    expect(added.status).toBe(201);
    const list = (await (await api("/stock")).json()) as any;
    expect(list.items.map((row: any) => row.text)).toEqual(["salt"]);
    const { inStock } = await import("@/stock");
    expect(inStock("salt 1 pinch")?.text).toBe("salt");
    expect(inStock("sea salt flakes")?.text).toBe("salt");
    expect(inStock("sugar 100 g")).toBeNull();
    expect((await api(`/stock/${list.items[0].id}`, { method: "DELETE" })).status).toBe(204);
    const settings = (await (await api("/settings", { method: "PATCH", body: JSON.stringify({ recipeSkipInStock: false }) })).json()) as any;
    expect(settings.recipeSkipInStock).toBe(false);
    await api("/settings", { method: "PATCH", body: JSON.stringify({ recipeSkipInStock: true }) });
  });

  test("delete leaves a tombstone", async () => {
    expect((await api(`/basket/items/${itemId}`, { method: "DELETE" })).status).toBe(204);
    const body = (await (await api("/basket?since=1")).json()) as any;
    expect(body.deletedIds).toContain(itemId);
  });
});

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
    { ...sp("AH", "3", "AH Biologisch halfvolle melk", 139), isDeal: true, dealText: "2 voor 2.50", regularPriceCents: 159 },
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
    expect(match.hasRejectedSuggestions).toBe(true);
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

  test("a skipped store can be re-enabled", async () => {
    let item = (await (await api(`/basket/items/${itemId}/matches/JUMBO/choose`, { method: "POST", body: JSON.stringify({ productId: null }) })).json()) as any;
    expect(item.matches.find((m: any) => m.store === "JUMBO").status).toBe("NONE");
    item = (await (await api(`/basket/items/${itemId}/matches/JUMBO/unskip`, { method: "POST" })).json()) as any;
    const jumbo = item.matches.find((m: any) => m.store === "JUMBO");
    expect(jumbo.status).toBe("PENDING");
    expect(jumbo.options.length).toBeGreaterThan(0);
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

  test("thumbs down drops a product from the options, thumbs up confirms it", async () => {
    const created = await api("/basket/items", { method: "POST", body: JSON.stringify({ text: "melk" }) });
    const id = ((await created.json()) as { id: string }).id;
    await waitForMatched(id);
    let item = ((await (await api("/basket")).json()) as any).items.find((entry: any) => entry.id === id);
    let ah = item.matches.find((match: any) => match.store === "AH");
    const first = ah.provisional.id;
    const before = ah.totalCandidates;
    item = (await (await api(`/basket/items/${id}/matches/AH/feedback`, { method: "POST", body: JSON.stringify({ productId: first, up: false }) })).json()) as any;
    ah = item.matches.find((match: any) => match.store === "AH");
    expect(ah.totalCandidates).toBe(before - 1);
    expect(ah.options.map((option: any) => option.id)).not.toContain(first);
    expect(ah.status).toBe("PENDING");
    const memory = (await (await api("/memory")).json()) as any;
    expect(memory.choices.some((choice: any) => choice.rejectedTitles.length === 1 && choice.chosenProductId === null)).toBe(true);

    const liked = ah.options[0].id;
    item = (await (await api(`/basket/items/${id}/matches/AH/feedback`, { method: "POST", body: JSON.stringify({ productId: liked, up: true }) })).json()) as any;
    ah = item.matches.find((match: any) => match.store === "AH");
    expect(ah.status).toBe("CHOSEN");
    expect(ah.chosen.id).toBe(liked);
    await api(`/basket/items/${id}`, { method: "DELETE" });
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

  test("rankBy setting is stored and echoed by compare", async () => {
    const updated = (await (await api("/settings", { method: "PATCH", body: JSON.stringify({ rankBy: "unitPrice" }) })).json()) as any;
    expect(updated.rankBy).toBe("unitPrice");
    expect(((await (await api("/basket/compare")).json()) as any).rankBy).toBe("unitPrice");
    await api("/settings", { method: "PATCH", body: JSON.stringify({ rankBy: "price" }) });
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

  test("barcode lookup and adding a concrete product pins that store", async () => {
    const lookup = (await (await api("/products/barcode/8712800147008")).json()) as any;
    expect(lookup.results.map((r: any) => r.store)).toEqual(["AH", "JUMBO"]);
    expect(lookup.results.every((r: any) => r.product === null)).toBe(true); // fakes know no barcodes
    await api("/products/search?q=melk&store=JUMBO");
    const created = await api("/basket/items/from-product", { method: "POST", body: JSON.stringify({ productId: "JUMBO:a" }) });
    expect(created.status).toBe(201);
    const item = (await created.json()) as any;
    expect(item.text).toBe("Verse Halfvolle Melk");
    const jumbo = item.matches.find((m: any) => m.store === "JUMBO");
    expect(jumbo.status).toBe("CHOSEN");
    expect(jumbo.chosenBy).toBe("USER");
    await waitForMatched(item.id);
    const after = ((await (await api("/basket")).json()) as any).items.find((e: any) => e.id === item.id);
    expect(after.matches.find((m: any) => m.store === "JUMBO").chosen.id).toBe("JUMBO:a");
    expect(after.matches.find((m: any) => m.store === "AH").options.length).toBeGreaterThan(0);
    await api(`/basket/items/${item.id}`, { method: "DELETE" });
  });

  test("own recipes: create, list, update, folder from it, delete", async () => {
    const created = await api("/recipes/mine", { method: "POST", body: JSON.stringify({ title: "Arroz cubano", servings: "2", ingredientLines: ["200 g rice", "2 eggs", "1 can tomato sauce"], steps: ["Cook the rice", "Fry the eggs", "Warm the sauce and serve"] }) });
    expect(created.status).toBe(201);
    const recipe = (await created.json()) as any;
    expect(recipe.origin).toBe("manual");
    expect(recipe.steps).toHaveLength(3);
    const mine = (await (await api("/recipes/mine")).json()) as any;
    expect(mine.recipes.map((r: any) => r.id)).toContain(recipe.id);
    const fetched = (await (await api(`/recipes/fetch?url=${encodeURIComponent(`baskt://recipe/${recipe.id}`)}`)).json()) as any;
    expect(fetched.ingredientLines).toHaveLength(3);
    const updated = (await (await api(`/recipes/mine/${recipe.id}`, { method: "PATCH", body: JSON.stringify({ title: "Arroz a la cubana" }) })).json()) as any;
    expect(updated.title).toBe("Arroz a la cubana");
    const folder = await api("/basket/groups", { method: "POST", body: JSON.stringify({ url: `baskt://recipe/${recipe.id}` }) });
    expect(folder.status).toBe(201);
    const groupId = ((await folder.json()) as any).group.id;
    for (let i = 0; i < 100 && ((await (await api("/basket")).json()) as any).items.find((e: any) => e.id === groupId)?.status === "PARSING"; i += 1) await new Promise((r) => setTimeout(r, 20));
    const basketBody = (await (await api("/basket")).json()) as any;
    const group = basketBody.items.find((e: any) => e.id === groupId);
    expect(group.status).toBe("MATCHED");
    expect(basketBody.items.filter((e: any) => e.parentId === groupId)).toHaveLength(3);
    await api(`/basket/items/${groupId}`, { method: "DELETE" });
    expect((await api(`/recipes/mine/${recipe.id}`, { method: "DELETE" })).status).toBe(204);
  });

  test("recipe favourites can be saved and removed", async () => {
    const saved = await api("/recipes/favourites", { method: "POST", body: JSON.stringify({ title: "Pannenkoeken", url: "https://www.ah.nl/allerhande/recept/R-R1/pannenkoeken" }) });
    expect(saved.status).toBe(201);
    const id = ((await saved.json()) as any).id;
    const again = await api("/recipes/favourites", { method: "POST", body: JSON.stringify({ title: "Pannenkoeken", url: "https://www.ah.nl/allerhande/recept/R-R1/pannenkoeken" }) });
    expect(((await again.json()) as any).id).toBe(id);
    expect(((await (await api("/recipes/favourites")).json()) as any).favourites).toHaveLength(1);
    expect((await api(`/recipes/favourites/${id}`, { method: "DELETE" })).status).toBe(204);
  });

  test("price changes endpoint reports watched products and scan status", async () => {
    const body = (await (await api("/prices/changes")).json()) as any;
    expect(Array.isArray(body.changes)).toBe(true);
    expect(body.scan.nextRunAt).toBeNull(); // PRICE_SCAN=off in tests
    const refreshed = (await (await api("/admin/refresh", { method: "POST" })).json()) as any;
    expect(typeof refreshed.products).toBe("number");
  });

  test("voice interpretation proposes items and confirm adds them", async () => {
    const proposal = (await (await api("/basket/interpret", { method: "POST", body: JSON.stringify({ text: "melk, rijst en suiker" }) })).json()) as any;
    expect(proposal.items.map((item: any) => item.text)).toEqual(["melk", "rijst", "suiker"]);
    expect(proposal.items.every((item: any) => item.wanted)).toBe(true);
    const confirmed = await api("/basket/confirm", { method: "POST", body: JSON.stringify({ items: [{ text: "melk" }, { text: "ingredients for soup", kind: "recipe" }] }) });
    expect(confirmed.status).toBe(201);
    const items = ((await confirmed.json()) as any).items;
    expect(items).toHaveLength(2);
    expect(items[1].kind).toBe("group");
    for (const item of items) await api(`/basket/items/${item.id}`, { method: "DELETE" });
  });

  test("purchases can be saved, listed, summarised and deleted", async () => {
    const created = await api("/purchases", {
      method: "POST",
      body: JSON.stringify({ store: "AH", purchasedAt: "2026-09-01", lines: [{ name: "AH HALFVOLLE MELK", quantity: 2, unitPriceCents: 129, totalPriceCents: 258, dealText: null, productId: "AH:1" }, { name: "BROOD", quantity: 1, totalPriceCents: 199 }] }),
    });
    expect(created.status).toBe(201);
    const saved = (await created.json()) as any;
    expect(saved.purchase.totalCents).toBe(457);
    expect(saved.lines).toHaveLength(2);
    const list = (await (await api("/purchases")).json()) as any;
    expect(list.purchases[0].lineCount).toBe(2);
    const summary = (await (await api("/purchases/summary")).json()) as any;
    expect(summary.totalCents).toBe(457);
    expect(summary.months[0].perStore.AH).toBe(457);
    expect(summary.topProducts[0].name).toBe("AH HALFVOLLE MELK");
    expect((await api(`/purchases/${saved.purchase.id}`, { method: "DELETE" })).status).toBe(204);
  });

  test("deals lists promoted candidates of the same kind", async () => {
    const created = await api("/basket/items", { method: "POST", body: JSON.stringify({ text: "bio melk" }) });
    const id = ((await created.json()) as { id: string }).id;
    await waitForMatched(id);
    const body = (await (await api("/basket/deals")).json()) as any;
    const forItem = body.deals.filter((deal: any) => deal.itemId === id);
    expect(forItem.length).toBeGreaterThan(0);
    expect(forItem.every((deal: any) => deal.product.isDeal)).toBe(true);
    expect(forItem[0].store).toBe("AH");
    await api(`/basket/items/${id}`, { method: "DELETE" });
  });

  test("order step assigns stores per item and compare reports the totals", async () => {
    const a = (await (await api("/basket/items", { method: "POST", body: JSON.stringify({ text: "halfvolle melk" }) })).json()) as any;
    await waitForMatched(a.id);
    const all = (await (await api("/basket/assign", { method: "POST", body: JSON.stringify({ mode: "store:AH" }) })).json()) as any;
    expect(all.items.find((i: any) => i.id === a.id).assignedStore).toBe("AH");
    const mix = (await (await api("/basket/assign", { method: "POST", body: JSON.stringify({ mode: "mix" }) })).json()) as any;
    expect(mix.items.find((i: any) => i.id === a.id).assignedStore).toBe("AH"); // AH:1 at 95 is cheaper than JUMBO:a at 129
    const manual = (await (await api(`/basket/items/${a.id}`, { method: "PATCH", body: JSON.stringify({ assignedStore: "JUMBO" }) })).json()) as any;
    expect(manual.assignedStore).toBe("JUMBO");
    const compare = (await (await api("/basket/compare")).json()) as any;
    expect(compare.order.perStore.JUMBO.count).toBeGreaterThanOrEqual(1);
    const cleared = (await (await api("/basket/assign", { method: "POST", body: JSON.stringify({ mode: "clear" }) })).json()) as any;
    expect(cleared.items.find((i: any) => i.id === a.id).assignedStore).toBeNull();
    await api(`/basket/items/${a.id}`, { method: "DELETE" });
  });

  test("existing items can be grouped into a folder and bulk deleted", async () => {
    const a = (await (await api("/basket/items", { method: "POST", body: JSON.stringify({ text: "melk" }) })).json()) as any;
    const b = (await (await api("/basket/items", { method: "POST", body: JSON.stringify({ text: "eieren" }) })).json()) as any;
    const grouped = await api("/basket/groups/from-items", { method: "POST", body: JSON.stringify({ text: "Breakfast", itemIds: [a.id, b.id] }) });
    expect(grouped.status).toBe(201);
    const body = (await grouped.json()) as any;
    expect(body.group.kind).toBe("group");
    expect(body.items.map((i: any) => i.id).sort()).toEqual([a.id, b.id].sort());
    expect(body.items.every((i: any) => i.parentId === body.group.id)).toBe(true);
    const removed = (await (await api("/basket/items/delete", { method: "POST", body: JSON.stringify({ itemIds: [body.group.id] }) })).json()) as any;
    expect(removed.deleted).toBe(1);
    const after = (await (await api("/basket?since=1")).json()) as any;
    expect(after.deletedIds).toContain(a.id);
  });

  test("assistant: history, offline reply, and applying a validated proposal", async () => {
    const reply = (await (await api("/chat", { method: "POST", body: JSON.stringify({ text: "swap the milk" }) })).json()) as any;
    expect(reply.messages).toHaveLength(2);
    expect(reply.messages[1].role).toBe("assistant");
    expect(reply.messages[1].content).toContain("AI provider");
    const hist = (await (await api("/chat")).json()) as any;
    expect(hist.messages.length).toBe(2);

    // Build a proposal directly through the module to exercise validation and apply.
    const item = (await (await api("/basket/items", { method: "POST", body: JSON.stringify({ text: "melk" }) })).json()) as any;
    await waitForMatched(item.id);
    await api("/products/search?q=melk&store=JUMBO");
    const { applyProposal } = await import("@/assistant");
    const { db } = await import("@/db");
    const { chatMessages } = await import("@/db/schema");
    const proposal = { summary: "Test", applied: null, changes: [
      { type: "replace", itemId: item.id, text: "melk", store: "JUMBO", productId: "JUMBO:b", from: null, to: "Campina" },
      { type: "quantity", itemId: item.id, text: "melk", quantity: 3 },
      { type: "add", text: "eieren", quantity: 1 },
    ] };
    db().insert(chatMessages).values({ id: "msg-test", basketId: "default", role: "assistant", content: "x", proposalJson: proposal as any, recipesJson: null, createdAt: Date.now() }).run();
    const applied = (await (await api("/chat/proposals/msg-test/apply", { method: "POST", body: JSON.stringify({ indices: [0, 1, 2] }) })).json()) as any;
    expect(applied.results.every((r: any) => r.ok)).toBe(true);
    expect(applied.message.proposalJson.applied).toEqual([0, 1, 2]);
    const basketBody = (await (await api("/basket")).json()) as any;
    const updated = basketBody.items.find((i: any) => i.id === item.id);
    expect(updated.quantity).toBe(3);
    expect(updated.matches.find((m: any) => m.store === "JUMBO").chosen.id).toBe("JUMBO:b");
    expect(basketBody.items.some((i: any) => i.text === "eieren")).toBe(true);
    for (const i of basketBody.items.filter((i: any) => i.id === item.id || i.text === "eieren")) await api(`/basket/items/${i.id}`, { method: "DELETE" });
    expect((await api("/chat", { method: "DELETE" })).status).toBe(204);
  });

  test("whatsapp routes report disabled without a bridge", async () => {
    const status = (await (await api("/whatsapp/status")).json()) as any;
    expect(status.enabled).toBe(false);
    expect((await api("/whatsapp/send", { method: "POST", body: JSON.stringify({}) })).status).toBe(400);
  });

  test("share links expose a basket without the bearer and allow checking items", async () => {
    const created = (await (await api("/baskets/default/share", { method: "POST", body: JSON.stringify({ baseUrl: "http://example" }) })).json()) as any;
    expect(created.url).toBe(`http://example/share/default?t=${created.token}`);
    const item = (await (await api("/basket/items", { method: "POST", body: JSON.stringify({ text: "kaas" }) })).json()) as any;
    const page = await app.request(`/share/default?t=${created.token}`);
    expect(page.status).toBe(200);
    expect(await page.text()).toContain("Personal");
    expect((await app.request("/share/default?t=wrong")).status).toBe(401);
    const data = (await (await app.request(`/share/default/api?t=${created.token}`)).json()) as any;
    expect(data.items.some((i: any) => i.id === item.id)).toBe(true);
    const toggled = await app.request(`/share/default/api/items/${item.id}/checked?t=${created.token}`, { method: "POST", headers: { "content-type": "application/json" }, body: JSON.stringify({ checked: true }) });
    expect(toggled.status).toBe(200);
    expect(((await (await api("/basket")).json()) as any).items.find((i: any) => i.id === item.id).checked).toBe(true);
    expect((await api("/baskets/default/share", { method: "DELETE" })).status).toBe(204);
    expect((await app.request(`/share/default/api?t=${created.token}`)).status).toBe(401);
    await api(`/basket/items/${item.id}`, { method: "DELETE" });
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

  test("an idea already in stock arrives checked with the reason", async () => {
    const zout = (await (await api("/stock", { method: "POST", body: JSON.stringify({ text: "zout" }) })).json()) as any;
    const item = (await (await api("/basket/items", { method: "POST", body: JSON.stringify({ text: "zout" }) })).json()) as any;
    expect(item.checked).toBe(true);
    expect(item.skippedReason).toBe("in stock: zout");
    const unchecked = (await (await api(`/basket/items/${item.id}`, { method: "PATCH", body: JSON.stringify({ checked: false }) })).json()) as any;
    expect(unchecked.skippedReason).toBeNull();
    await api(`/basket/items/${item.id}`, { method: "DELETE" });
    await api(`/stock/${zout.id}`, { method: "DELETE" });
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
    const scanned = (await (await api("/stock", { method: "POST", body: JSON.stringify({ text: "Verse Halfvolle Melk", productId: "JUMBO:a", imageUrl: "https://img/x.png", barcode: "8712800147008" }) })).json()) as any;
    expect(scanned.productId).toBe("JUMBO:a");
    const again = (await (await api("/stock", { method: "POST", body: JSON.stringify({ text: "melk", barcode: "8712800147008" }) })).json()) as any;
    expect(again.id).toBe(scanned.id);
    expect((await api(`/stock/${scanned.id}`, { method: "DELETE" })).status).toBe(204);
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


test("refresh restores the first suggestions and only clears this store's item rejections", async () => {
  const { recordChoice, listChoices } = await import("@/matching/memory");
  const created = await (await api("/basket/items", { method: "POST", body: JSON.stringify({ text: "halfvolle melk" }) })).json() as any;
  const id = created.id;
  await waitForMatched(id);
  await api(`/basket/items/${id}/matches/AH/reject`, { method: "POST" });
  await api(`/basket/items/${id}/matches/AH/reject`, { method: "POST" });
  const rejected = await (await api(`/basket/items/${id}/matches/AH/reject`, { method: "POST" })).json() as any;
  expect(rejected.matches.find((entry: any) => entry.store === "AH").hasRejectedSuggestions).toBe(true);
  const { chooseMatch, getMatches } = await import("@/matching/pipeline");
  chooseMatch(id, "AH", "AH:1");
  expect(getMatches(id).find((entry) => entry.store === "AH")?.hasRejectedSuggestions).toBe(true);
  const otherStore = recordChoice({ itemText: "halfvolle melk", canonical: "halfvolle melk", store: "JUMBO", chosenProductId: null, chosenTitle: null, rejectedTitles: ["Milk"] });
  const otherItem = recordChoice({ itemText: "bread", canonical: "bread", store: "AH", chosenProductId: null, chosenTitle: null, rejectedTitles: ["Bread"] });
  const pick = recordChoice({ itemText: "halfvolle melk", canonical: "halfvolle melk", store: "AH", chosenProductId: "AH:1", chosenTitle: "Milk", rejectedTitles: ["Other milk"] });
  const response = await api(`/basket/items/${id}/matches/AH/reset`, { method: "POST" });
  expect(response.status).toBe(200);
  const item = await response.json() as any;
  const match = item.matches.find((entry: any) => entry.store === "AH");
  expect(match.status).toBe("PENDING");
  expect(match.page).toBe(1);
  expect(match.options).toHaveLength(3);
  expect(match.chosen).toBeNull();
  expect(match.hasRejectedSuggestions).toBe(false);
  const remaining = listChoices();
  expect(remaining.map((row) => row.id)).toContain(otherStore.id);
  expect(remaining.map((row) => row.id)).toContain(otherItem.id);
  expect(remaining.map((row) => row.id)).toContain(pick.id);
  expect(remaining.filter((row) => row.store === "AH" && row.itemText === "halfvolle melk" && !row.chosenProductId)).toHaveLength(0);
  expect((await api(`/basket/items/missing/matches/AH/reset`, { method: "POST" })).status).toBe(404);
});

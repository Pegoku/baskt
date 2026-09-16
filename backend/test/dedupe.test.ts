import { beforeAll, describe, expect, test } from "bun:test";
import { resetDbForTests } from "@/db";
import { createApp } from "@/app";
import { findDuplicates } from "@/matching/dedupe";
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

describe("dedupe", () => {
  test("groups literal repeats and canonical twins without AI", async () => {
    const groups = await findDuplicates([
      { id: "a", text: "Melk", quantity: 1, canonical: null, folder: null },
      { id: "b", text: "melk ", quantity: 2, canonical: null, folder: null },
      { id: "c", text: "eieren", quantity: 1, canonical: "eggs", folder: null },
      { id: "d", text: "eggs", quantity: 1, canonical: "eggs", folder: "Pancakes" },
      { id: "e", text: "boter", quantity: 1, canonical: "butter", folder: null },
    ]);
    expect(groups).toHaveLength(2);
    const milk = groups.find((group) => group.items.some((item) => item.id === "a"))!;
    expect(milk.items.map((item) => item.id)).toEqual(["a", "b"]);
    expect(milk.mergedQuantity).toBe(3);
    expect(milk.exact).toBe(true);
    const eggs = groups.find((group) => group.items.some((item) => item.id === "c"))!;
    expect(eggs.items.map((item) => item.id)).toEqual(["c", "d"]);
    expect(eggs.items[1].folder).toBe("Pancakes");
    expect(eggs.mergedText).toBe("eieren");
  });

  test("a single item is never a duplicate", async () => {
    expect(await findDuplicates([{ id: "a", text: "melk", quantity: 1, canonical: null, folder: null }])).toEqual([]);
  });

  test("route scans only open items of the basket", async () => {
    for (const text of ["melk", "Melk", "brood"]) await api("/basket/items", { method: "POST", body: JSON.stringify({ text }) });
    const before = (await (await api("/basket")).json()) as { items: Array<{ id: string; text: string }> };
    const bread = before.items.find((item) => item.text === "brood")!;
    await api(`/basket/items/${bread.id}`, { method: "PATCH", body: JSON.stringify({ checked: true }) });
    const response = await api("/basket/dedupe", { method: "POST", body: JSON.stringify({}) });
    expect(response.status).toBe(200);
    const body = (await response.json()) as { groups: Array<{ items: Array<{ text: string }> }>; scanned: number };
    expect(body.scanned).toBe(2);
    expect(body.groups).toHaveLength(1);
    expect(body.groups[0].items.map((item) => item.text).sort()).toEqual(["Melk", "melk"]);
    expect((await api("/basket/dedupe", { method: "POST", body: JSON.stringify({ basketId: "nope" }) })).status).toBe(404);
  });
});

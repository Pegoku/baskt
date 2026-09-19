import { beforeAll, describe, expect, test } from "bun:test";
import { resetDbForTests } from "@/db";
import { createApp } from "@/app";
import { listNames, preferredName, rememberName } from "@/matching/naming";
import { planTidy, reviseTidy, toCandidate } from "@/matching/tidy";
import type { BasketItemRow } from "@/db/schema";
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

function row(id: string, text: string, canonical: string | null = null): BasketItemRow {
  return { id, basketId: "default", kind: "item", parentId: null, recipeJson: null, assignedStore: null, skippedReason: null, text, quantity: 1, checked: false, boughtAt: null, sortOrder: 0, status: "MATCHED", error: null, parsedJson: canonical ? { canonicalName: canonical, attributes: [], sizeHint: null, queries: {}, fallbackQuery: null, ambiguous: false } : null, createdAt: 0, updatedAt: 0 };
}

describe("naming memory", () => {
  test("remembers a wording and finds it by text, generic name or the preferred wording itself", () => {
    const saved = rememberName({ source: "krulsla melange", canonical: "mixed lettuce", preferred: "bolsa de lechugas" });
    expect(saved?.keys).toEqual(["krulsla melange", "mixed lettuce"]);
    expect(preferredName("Krulsla Melange", null)).toBe("bolsa de lechugas");
    expect(preferredName("lettuce mix", "mixed lettuce")).toBe("bolsa de lechugas");
    expect(preferredName("bolsa de lechugas", null)).toBe("bolsa de lechugas");
    expect(preferredName("melk", null)).toBeNull();
  });

  test("a newer correction for the same source replaces the old one", () => {
    rememberName({ source: "krulsla melange", canonical: null, preferred: "lechuga en bolsa" });
    expect(listNames().filter((entry) => entry.keys.includes("krulsla melange"))).toHaveLength(1);
    expect(preferredName("krulsla melange", null)).toBe("lechuga en bolsa");
  });

  test("tidy applies remembered wordings without the model", async () => {
    const plan = await planTidy([toCandidate(row("a", "krulsla melange"), [], new Map(), null), toCandidate(row("b", "lechuga en bolsa"), [], new Map(), null), toCandidate(row("c", "melk"), [], new Map(), null)]);
    expect(plan.renames).toEqual([{ id: "a", from: "krulsla melange", to: "lechuga en bolsa", reason: null }]);
  });

  test("memory route lists names and forgets them", async () => {
    const listed = (await (await api("/memory")).json()) as { names: Array<{ id: string; preferred: string }> };
    expect(listed.names.map((entry) => entry.preferred)).toEqual(["lechuga en bolsa"]);
    expect((await api(`/memory/names/${listed.names[0].id}`, { method: "DELETE" })).status).toBe(204);
    expect((await api(`/memory/names/${listed.names[0].id}`, { method: "DELETE" })).status).toBe(404);
    expect(listNames()).toEqual([]);
  });

  test("revise route validates input and keeps the plan without AI", async () => {
    const plan = { scanned: 1, language: "Spanish", renames: [{ id: "x", from: "krulsla melange", to: "mezcla de lechugas", reason: null }], merges: [], distinct: [], deals: [] };
    expect((await api("/basket/tidy/revise", { method: "POST", body: JSON.stringify({ plan, comment: "" }) })).status).toBe(400);
    expect((await api("/basket/tidy/revise", { method: "POST", body: JSON.stringify({ comment: "x" }) })).status).toBe(400);
    const response = await api("/basket/tidy/revise", { method: "POST", body: JSON.stringify({ plan, selected: { renames: ["x"] }, comment: "it should be bolsa de lechugas" }) });
    expect(response.status).toBe(200);
    const revised = (await response.json()) as typeof plan & { reply: string | null };
    expect(revised.renames).toEqual(plan.renames);
    expect(revised.reply).toContain("not configured");
  });

  test("a comment about nothing selected leaves the plan alone", async () => {
    const plan = { scanned: 0, language: "Spanish", renames: [], merges: [], distinct: [], deals: [] };
    expect(await reviseTidy(plan, { renames: [], merges: [], deals: [] }, "double it", new Map())).toEqual({ ...plan, reply: null });
  });
});

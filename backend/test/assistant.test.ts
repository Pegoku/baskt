import { beforeAll, describe, expect, test } from "bun:test";
import { db, now, resetDbForTests } from "@/db";
import { sanitizeProposal, toolResultText } from "@/assistant";
import { basketItems, DEFAULT_BASKET_ID, type ItemKind } from "@/db/schema";

function row(id: string, text: string, kind: ItemKind = "item", parentId: string | null = null) {
  db().insert(basketItems).values({
    id, basketId: DEFAULT_BASKET_ID, kind, parentId, text,
    quantity: 1, checked: false, sortOrder: 0, status: "NEW",
    recipeJson: null, assignedStore: null, skippedReason: null, error: null, parsedJson: null,
    createdAt: now(), updatedAt: now(),
  }).run();
}

describe("assistant", () => {
  beforeAll(() => {
    resetDbForTests();
    for (let i = 0; i < 20; i += 1) row(`item-${i}`, `product ${i}`);
    row("folder", "Pancakes", "group");
    row("child", "flour", "item", "folder");
  });

  test("delete_all covers the whole basket, not the handful of items that fit a tool result", () => {
    const proposal = sanitizeProposal({ summary: "Empty the basket", changes: [{ type: "delete_all" }] }, DEFAULT_BASKET_ID);
    // 20 loose items plus the recipe folder; the folder's child goes with it.
    expect(proposal?.changes).toHaveLength(21);
    expect(proposal?.changes.every((change) => change.type === "delete")).toBe(true);
    expect(proposal?.changes.map((change) => (change.type === "delete" ? change.itemId : ""))).not.toContain("child");
  });

  test("delete_all does not duplicate an item the model already listed", () => {
    const proposal = sanitizeProposal(
      { summary: "Empty", changes: [{ type: "delete", itemId: "item-3" }, { type: "delete_all" }] },
      DEFAULT_BASKET_ID,
    );
    expect(proposal?.changes).toHaveLength(21);
  });

  test("an oversized tool result drops whole entries and says how many are missing", () => {
    const entries = Array.from({ length: 400 }, (_, i) => ({ itemId: `id-${i}`, text: `product ${i}` }));
    const text = toolResultText(entries, "list_basket");
    expect(text).toContain("of 400 entries are shown");
    // Still parseable JSON: the cut never lands in the middle of an entry.
    const shown = JSON.parse(text.slice(0, text.lastIndexOf("]") + 1)) as unknown[];
    expect(shown.length).toBeGreaterThan(100);
    expect(shown).toEqual(entries.slice(0, shown.length));
  });

  test("a basket that fits is passed through whole", () => {
    const entries = Array.from({ length: 30 }, (_, i) => ({ itemId: `id-${i}`, text: `product ${i}` }));
    expect(JSON.parse(toolResultText(entries, "list_basket"))).toEqual(entries);
  });
});

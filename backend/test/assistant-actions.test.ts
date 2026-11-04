import { beforeAll, describe, expect, test } from "bun:test";
import { applyProposal, runTool, sanitizeProposal } from "@/assistant";
import { db, now, resetDbForTests } from "@/db";
import { basketItems, chatMessages, DEFAULT_BASKET_ID, type Proposal } from "@/db/schema";
import { getItem } from "@/matching/pipeline";
import { addStock, listStock } from "@/stock";

function insert(id: string, text: string, kind: "item" | "group", parentId: string | null, recipeJson: unknown = null) {
  db().insert(basketItems).values({
    id, basketId: DEFAULT_BASKET_ID, kind, parentId, text,
    quantity: 1, checked: false, sortOrder: 0, status: "NEW",
    recipeJson: recipeJson as never, assignedStore: null, skippedReason: null, error: null, parsedJson: null,
    createdAt: now(), updatedAt: now(),
  }).run();
}

function proposalMessage(id: string, proposal: Proposal) {
  db().insert(chatMessages).values({
    id, basketId: DEFAULT_BASKET_ID, role: "assistant", content: "", proposalJson: proposal,
    recipesJson: null, productIdsJson: null, createdAt: now(),
  }).run();
}

describe("assistant stock and recipe-folder actions", () => {
  let riceId = "";
  beforeAll(() => {
    resetDbForTests();
    insert("lasagne", "Lasagne", "group", null, {
      title: "Lasagne", sourceUrl: null, servings: "4", ingredientLines: ["pasta", "olive oil"],
      skipped: [{ text: "olive oil", quantity: 1, reason: "in stock" }], baseServings: 4, currentServings: 4,
    });
    insert("plain", "Snacks", "group", null);
    insert("pasta", "pasta", "item", "lasagne");
    riceId = addStock("rice", "1 kg").id;
  });

  test("list_basket shows recipe folders with servings and skipped ingredients", async () => {
    const result = (await runTool({ name: "list_basket", args: {} }, DEFAULT_BASKET_ID)) as Array<Record<string, unknown>>;
    expect(result[0]).toEqual({ folderId: "lasagne", recipe: "Lasagne", servings: 4, skippedInStock: ["olive oil"] });
    // Plain folders have no recipe to rescale.
    expect(result.some((entry) => entry.folderId === "plain")).toBe(false);
    expect(result.some((entry) => entry.itemId === "pasta")).toBe(true);
  });

  test("list_stock gives the ids stock_remove needs", async () => {
    const result = (await runTool({ name: "list_stock", args: {} }, DEFAULT_BASKET_ID)) as Array<Record<string, unknown>>;
    expect(result).toEqual([{ stockId: riceId, text: "rice", quantity: "1 kg", productId: null }]);
  });

  test("new change types are checked against real folders and stock", () => {
    const proposal = sanitizeProposal({
      summary: "Changes",
      changes: [
        { type: "stock_add", text: "eggs", quantityText: "10" },
        { type: "stock_remove", stockId: "made-up", text: "rice" },
        { type: "stock_remove", stockId: "made-up", text: "caviar" },
        { type: "servings", folderId: "lasagne", servings: 6 },
        { type: "servings", folderId: "lasagne", servings: 500 },
        { type: "servings", folderId: "plain", servings: 2 },
        { type: "add_skipped", folderId: "lasagne" },
      ],
    }, DEFAULT_BASKET_ID);
    expect(proposal?.changes).toEqual([
      { type: "stock_add", text: "eggs", quantityText: "10" },
      // A wrong id falls back to the entry the text names; unknown stock is dropped.
      { type: "stock_remove", stockId: riceId, text: "rice" },
      { type: "servings", itemId: "lasagne", title: "Lasagne", from: 4, servings: 6 },
      { type: "add_skipped", itemId: "lasagne", title: "Lasagne", items: ["olive oil"] },
    ]);
  });

  test("applying stock and add_skipped changes edits stock and the folder", async () => {
    proposalMessage("m1", {
      summary: "Stock",
      changes: [
        { type: "stock_add", text: "eggs", quantityText: "10" },
        { type: "stock_remove", stockId: riceId, text: "rice" },
        { type: "add_skipped", itemId: "lasagne", title: "Lasagne", items: ["olive oil"] },
      ],
      applied: null,
    });
    const result = await applyProposal("m1", [0, 1, 2]);
    expect(result?.results.every((entry) => entry.ok)).toBe(true);
    expect(listStock().map((row) => [row.text, row.quantityText])).toEqual([["eggs", "10"]]);
    expect(getItem("lasagne")?.recipeJson?.skipped).toEqual([]);
    const children = db().select().from(basketItems).all().filter((row) => row.parentId === "lasagne").map((row) => row.text);
    expect(children.sort()).toEqual(["olive oil", "pasta"]);
  });
});

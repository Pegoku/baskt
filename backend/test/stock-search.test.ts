import { afterEach, beforeEach, describe, expect, test } from "bun:test";
import { resetPoolStats } from "@/ai/pool";
import { db, resetDbForTests } from "@/db";
import { products, stock } from "@/db/schema";
import { env } from "@/env";
import { addStock, listStock, stockView, tagStockForSearch } from "@/stock";

const realFetch = globalThis.fetch;
const realChat = env.ai.chat;

beforeEach(() => resetDbForTests());
afterEach(() => {
  globalThis.fetch = realFetch;
  env.ai.chat = realChat;
  resetPoolStats();
});

/** Fakes the AI: answers each numbered item line in the prompt with the words given for that item's text. */
function answerWith(byText: Record<string, string>, seen: string[] = []) {
  env.ai.chat = [{ id: "AI", priority: 1, baseUrl: "https://api.groq.com/openai/v1", apiKey: "k", model: "openai/gpt-oss-20b", reasoning: "low", providerOrder: [], providerQuantizations: [], vendor: "" }];
  globalThis.fetch = (async (_url: string, init: RequestInit) => {
    seen.push(String(init.body));
    const { messages } = JSON.parse(String(init.body)) as { messages: Array<{ role: string; content: string }> };
    const terms: Record<string, string> = {};
    for (const line of messages.find((message) => message.role === "user")!.content.split("\n")) {
      const [, number, text] = /^(\d+)\. (.*)$/.exec(line)!;
      if (byText[text]) terms[number] = byText[text];
    }
    return Response.json({ choices: [{ message: { content: JSON.stringify({ terms }) }, finish_reason: "stop" }] });
  }) as unknown as typeof fetch;
}

describe("stock search words", () => {
  test("rice finds arroz redondo before tagging and after an old empty result", () => {
    const row = addStock("arroz redondo");
    expect(stockView()[0].searchTerms.split(" ")).toContain("rice");
    db().update(stock).set({ searchTerms: "" }).run();
    expect(stockView()[0].searchTerms.split(" ")).toContain("rice");
    expect(listStock()[0].id).toBe(row.id);
  });

  test("existing generic stock displays its linked purchased product and retains its old search name", () => {
    db().insert(products).values({ id: "AH:2", store: "AH", sourceId: "2", title: "AH Ronde rijst", quantityText: "1 kg", priceCents: 199, imageUrl: "https://example.com/rice.jpg", fetchedAt: 0 }).run();
    const row = addStock("arroz redondo");
    db().update(stock).set({ productId: "AH:2" }).run();
    const view = stockView()[0];
    expect(view.text).toBe("AH Ronde rijst");
    expect(view.imageUrl).toBe("https://example.com/rice.jpg");
    expect(view.searchTerms.split(" ")).toContain("rice");
    expect(view.product?.id).toBe("AH:2");
    addStock({ text: "rice", productId: "AH:2" });
    expect(listStock()).toHaveLength(1);
    expect(listStock()[0].id).toBe(row.id);
    expect(listStock()[0].text).toBe("AH Ronde rijst");
  });

  test("the AI's translations and kinds become searchable, once per entry", async () => {
    addStock("atún en aceite de oliva");
    addStock("sal");
    const seen: string[] = [];
    answerWith({ "atún en aceite de oliva": "Tuna, tonijn, fish, Pescado, canned", sal: "salt zout" }, seen);
    await tagStockForSearch();
    const view = stockView();
    expect(view.find((row) => row.text === "atún en aceite de oliva")?.searchTerms).toBe("tuna tonijn fish pescado canned");
    expect(view.find((row) => row.text === "sal")?.searchTerms).toBe("salt zout");
    expect(seen.length).toBe(1);

    await tagStockForSearch();
    expect(seen.length).toBe(1); // nothing left to tag
  });

  test("an entry the AI skipped remains eligible for a later retry", async () => {
    addStock("mystery");
    answerWith({});
    await tagStockForSearch();
    expect(listStock()[0].searchTerms).toBeNull();
    answerWith({ mystery: "food eten comida" });
    await tagStockForSearch();
    expect(listStock()[0].searchTerms).toBe("food eten comida");
  });

  test("a failed call leaves entries untagged for a later retry", async () => {
    addStock("melk");
    env.ai.chat = [{ id: "AI", priority: 1, baseUrl: "https://api.groq.com/openai/v1", apiKey: "k", model: "m", reasoning: "low", providerOrder: [], providerQuantizations: [], vendor: "" }];
    globalThis.fetch = (async () => new Response("boom", { status: 500 })) as unknown as typeof fetch;
    await tagStockForSearch();
    expect(listStock()[0].searchTerms).toBeNull();
  });

  test("a linked product's own title is searchable even without the AI", () => {
    db().insert(products).values({ id: "AH:1", store: "AH", sourceId: "1", title: "Statesman Tonijnstukken in olijfolie", brand: "Statesman", quantityText: "160 g", priceCents: 199, fetchedAt: 0 }).run();
    addStock({ text: "atún en aceite de oliva", productId: "AH:1" });
    expect(stockView()[0].text).toBe("Statesman Tonijnstukken in olijfolie");
    expect(stockView()[0].product?.id).toBe("AH:1");
  });
});

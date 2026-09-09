import { asc, eq } from "drizzle-orm";
import { chatJson, type ChatMessage } from "@/ai/client";
import { db, newId, now } from "@/db";
import {
  basketItems,
  basketMatches,
  chatMessages,
  products,
  type ChatMessageRow,
  type Proposal,
  type ProposedChange,
  type RecipeCard,
} from "@/db/schema";
import { appLanguageName, enabledStoreCodes } from "@/db/settings";
import { aiConfigured } from "@/env";
import { dishQueries } from "@/matching/cook";
import { localizeRecipe } from "@/matching/localize";
import { chooseMatch, getItem, getMatch } from "@/matching/pipeline";
import { searchAllSources } from "@/matching/sources";
import {
  createGroupFromUrl,
  createItem,
  deleteItemWithChildren,
  updateItemFields,
} from "@/routes/basket";
import { fetchRecipe } from "@/routes/recipes";
import { collectProductIds, itemView } from "@/serialize";
import { inStock, listStock, matchByText } from "@/stock";
import { allStores } from "@/stores/registry";
import { productsByIds, searchStore } from "@/stores/search";

const HISTORY = 16;
const MAX_STEPS = 8;

export function history(basketId: string): ChatMessageRow[] {
  return db()
    .select()
    .from(chatMessages)
    .where(eq(chatMessages.basketId, basketId))
    .orderBy(asc(chatMessages.createdAt))
    .all();
}

export function clearHistory(basketId: string) {
  db().delete(chatMessages).where(eq(chatMessages.basketId, basketId)).run();
}

function save(row: Omit<ChatMessageRow, "id" | "createdAt">): ChatMessageRow {
  const full: ChatMessageRow = { id: newId(), createdAt: now(), ...row };
  db().insert(chatMessages).values(full).run();
  return full;
}

/** Compact view of the basket for the model: ids, texts, quantities, per-store picks with sizes and prices. */
function basketSnapshot(basketId: string) {
  const items = db()
    .select()
    .from(basketItems)
    .where(eq(basketItems.basketId, basketId))
    .orderBy(asc(basketItems.sortOrder))
    .all()
    .filter((item) => item.kind === "item");
  const ids = items.map((item) => item.id);
  const matches = ids.length
    ? db()
        .select()
        .from(basketMatches)
        .all()
        .filter((match) => ids.includes(match.itemId))
    : [];
  const prods = new Map(
    productsByIds(collectProductIds(matches)).map((product) => [
      product.id,
      product,
    ]),
  );
  return items.map((item) => {
    const view = itemView(
      item,
      matches.filter((match) => match.itemId === item.id),
      prods,
    );
    return {
      itemId: item.id,
      text: item.text,
      quantity: item.quantity,
      checked: item.checked,
      stores: Object.fromEntries(
        view.matches.map((match) => {
          const product = match.chosen ?? match.provisional;
          return [
            match.store,
            product
              ? {
                  productId: product.id,
                  title: product.title,
                  size: product.quantityText,
                  priceCents: product.priceCents,
                  status: match.status,
                }
              : { status: match.status },
          ];
        }),
      ),
    };
  });
}

type ToolCall = { name: string; args: Record<string, unknown> };

/** What the assistant is doing right now, per basket, for the app to show while it waits. */
const progress = new Map<string, string[]>();
export function progressFor(basketId: string) {
  return progress.get(basketId) ?? [];
}
function report(basketId: string, step: string) {
  const steps = progress.get(basketId) ?? [];
  // "Thinking" is transient: replace it with the next real step instead of stacking it in the trace.
  if (steps.length && steps[steps.length - 1] === "Thinking") steps.pop();
  steps.push(step);
  progress.set(basketId, steps.slice(-12));
}

function describeTool(call: ToolCall): string {
  const args = call.args ?? {};
  const storeName = (code: unknown) =>
    allStores().find((store) => store.code === code)?.name ??
    String(code ?? "");
  switch (call.name) {
    case "list_basket":
      return "Reading your basket";
    case "list_stock":
      return "Checking what you have in stock";
    case "search_products":
      return `Searching ${storeName(args.store)} for “${String(args.query ?? "")}”`;
    case "search_recipes":
      return `Looking for recipes: “${String(args.query ?? "")}”`;
    case "read_recipe":
      return `Reading a recipe (${
        String(args.url ?? "")
          .replace(/^https?:\/\/(www\.)?/, "")
          .split("/")[0]
      })`;
    default:
      return `Using ${call.name}`;
  }
}

/** Native function-calling specs; gpt-oss prefers this channel over describing tools in JSON text. */
const TOOL_SPECS = [
  {
    name: "list_basket",
    description: "Items in the basket with the chosen product per store.",
    parameters: { type: "object", properties: {} },
  },
  {
    name: "list_stock",
    description: "What the household already has at home.",
    parameters: { type: "object", properties: {} },
  },
  {
    name: "search_products",
    description: "Search a supermarket's catalogue.",
    parameters: {
      type: "object",
      properties: {
        store: { type: "string", description: "store code, e.g. AH or JUMBO" },
        query: { type: "string" },
      },
      required: ["store", "query"],
    },
  },
  {
    name: "search_recipes",
    description: "Find recipe cards from several recipe sites.",
    parameters: {
      type: "object",
      properties: { query: { type: "string" } },
      required: ["query"],
    },
  },
  {
    name: "read_recipe",
    description: "Read a recipe's full ingredients and steps.",
    parameters: {
      type: "object",
      properties: { url: { type: "string" } },
      required: ["url"],
    },
  },
].map((fn) => ({ type: "function", function: fn }));

async function runTool(call: ToolCall, basketId: string): Promise<unknown> {
  const args = call.args ?? {};
  switch (call.name) {
    case "list_basket":
      return basketSnapshot(basketId);
    case "list_stock": {
      const { listStock } = await import("@/stock");
      return listStock().map((row) => ({
        text: row.text,
        quantity: row.quantityText,
      }));
    }
    case "search_products": {
      const store = String(args.store ?? "");
      const query = String(args.query ?? "").trim();
      if (!enabledStoreCodes().includes(store) || !query)
        return {
          error: `store must be one of ${enabledStoreCodes().join(", ")} and query non-empty`,
        };
      const result = await searchStore(store, query, { limit: 12 });
      return result.products.map((product) => ({
        productId: product.id,
        title: product.title,
        size: product.quantityText,
        priceCents: product.priceCents,
        unitPrice: product.unitPriceCents
          ? `${product.unitPriceCents}c/${product.unitPriceUnit}`
          : null,
        deal: product.dealText,
      }));
    }
    case "search_recipes": {
      const query = String(args.query ?? "").trim();
      if (!query) return { error: "query required" };
      const understood = await dishQueries(query);
      const { hits } = await searchAllSources(understood.queries);
      return hits.slice(0, 8).map((hit) => ({
        title: hit.title,
        url: hit.url,
        source: hit.source,
        imageUrl: hit.imageUrl,
      }));
    }
    case "read_recipe": {
      const url = String(args.url ?? "");
      const recipe = await fetchRecipe(url).catch(() => null);
      if (!recipe) return { error: "could not read that recipe" };
      const localized = await localizeRecipe(recipe);
      return {
        title: localized.title,
        servings: localized.servings,
        totalTime: localized.totalTime,
        ingredients: localized.ingredientLines,
        steps: (localized.steps ?? []).slice(0, 12).map((step) => step.text),
      };
    }
    default:
      return { error: `unknown tool ${call.name}` };
  }
}

const SYSTEM = (
  language: string,
) => `You are baskt's shopping assistant for a household in the Netherlands. You help with the shopping list (called the basket), suggest recipes, and make changes — but you NEVER change anything yourself: you propose changes and the user confirms them in the app.
Answer in ${language}. Be brief and concrete.

You work in steps. Each turn return ONLY a JSON object:
{
  "answer": plain text ONLY for answering a question or an important note; "" otherwise (see rules),
  "tool": {"name": "...", "args": {...}} or null,
  "proposal": {"summary": string, "changes": [...]} or null,
  "recipes": [{"title","url","source","imageUrl"}] or null
}
Tools (use them to look before you propose):
- list_basket: items with itemId, quantity, and per store the current pick (productId, title, size, price).
- list_stock: what the user already has at home.
- search_products {store, query}: products at one store (codes: STORES) with productId, size, price.
- search_recipes {query}: recipe cards from several sites.
- read_recipe {url}: full ingredients and steps, to check a recipe really fits what the user wants.
Change types for proposals (use real itemIds/productIds from tool results only):
- {"type":"add","text":..,"quantity":n}
- {"type":"delete","itemId":..,"text":..}
- {"type":"rename","itemId":..,"from":..,"to":..}
- {"type":"quantity","itemId":..,"text":..,"quantity":n}
- {"type":"replace","itemId":..,"text":..,"store":..,"productId":..,"from":current title or null,"to":new title}
- {"type":"skip","itemId":..,"text":..,"store":..}
- {"type":"add_recipe_folder","url":..,"title":..}
Rules: when asked to change products (e.g. "swap 1 kg bags for 500 g"), first list_basket, then search_products per store for each item that matches; propose "replace" ONLY for items where you actually found a fitting product, and say which ones you could not find.
The app itself asks the user to confirm every change: NEVER ask for permission in text ("would you like…?"). Whenever you have found concrete changes, put ALL of them in "proposal" in the same turn; the app shows them as a card titled with "summary" (e.g. "What you still need to buy for pancakes") and the user ticks what they want. "answer" is ONLY for answering a question the user asked (e.g. cooking time) or an important warning; it must be an empty string when the card says it all, and it must NEVER name the items that are in "changes" (that would duplicate the card). When the user wants a recipe, ALWAYS call search_recipes first and put real results in "recipes" (max 5) so the app can show cards; never invent a recipe in the text. Whenever the user needs or lacks items, put an "add" change for each of them instead of listing them. Before proposing adds for missing ingredients ALWAYS call list_stock and list_basket first, and skip what is already there. When the user explicitly names items to add, include EVERY one of them as "add" changes even if they are already in the basket or in stock: the app labels duplicates itself ("already in your list as …") and lets the user decide. Never silently drop a named item. An add's "text" is a short generic idea like "penne 500 g" or "eggs 10", never a brand's product title. Previous turns show which proposed changes the user APPLIED: treat applied adds as already in the basket and "not applied" ones as still missing. Use read_recipe when the user is specific about what they want. "answer" is plain text without markdown. Stop using tools once you have what you need and give the final answer.`;

/** Short text for a change, used to tell the model what it proposed earlier and what the user accepted. */
function describeChange(change: ProposedChange): string {
  switch (change.type) {
    case "add":
      return (
        `add "${change.text}"${change.quantity > 1 ? ` x${change.quantity}` : ""}` +
        (change.inList
          ? ` (already in the list as "${change.inList}")`
          : change.stockName
            ? ` (already in stock: "${change.stockName}")`
            : "")
      );
    case "delete":
      return `delete "${change.text}"`;
    case "rename":
      return `rename "${change.from}" to "${change.to}"`;
    case "quantity":
      return `set "${change.text}" to x${change.quantity}`;
    case "replace":
      return `replace product for "${change.text}" at ${change.store} with "${change.to}"`;
    case "skip":
      return `skip "${change.text}" at ${change.store}`;
    case "add_recipe_folder":
      return `add recipe folder "${change.title}"`;
  }
}

/**
 * The model tends to drop items the user named when they are already in the list. Put them back as adds
 * flagged "already in your list" so the user sees them and decides (only on turns that propose adds).
 */
function withNamedDuplicates(
  proposal: Proposal,
  userText: string,
  basketId: string,
): Proposal {
  if (!proposal.changes.some((change) => change.type === "add"))
    return proposal;
  const listRows = db()
    .select()
    .from(basketItems)
    .where(eq(basketItems.basketId, basketId))
    .all()
    .filter((item) => item.kind === "item");
  const stockRows = listStock();
  const changes = [...proposal.changes];
  for (const row of listRows) {
    if (!matchByText(userText, [row])) continue; // the user did not name this item
    const covered = changes.some(
      (change) =>
        change.type === "add" &&
        (change.inList === row.text || matchByText(change.text, [row])),
    );
    if (covered) continue;
    const have = inStock(row.text, stockRows);
    changes.push({
      type: "add",
      text: row.text,
      quantity: 1,
      inStock: Boolean(have),
      stockName: have?.text ?? null,
      inList: row.text,
    });
  }
  return changes.length === proposal.changes.length
    ? proposal
    : { ...proposal, changes };
}

function sanitizeProposal(raw: unknown, basketId: string): Proposal | null {
  if (!raw || typeof raw !== "object") return null;
  const record = raw as { summary?: unknown; changes?: unknown };
  const changes: ProposedChange[] = [];
  const stockRows = listStock();
  const listRows = db()
    .select()
    .from(basketItems)
    .where(eq(basketItems.basketId, basketId))
    .all()
    .filter((item) => item.kind === "item");
  for (const entry of Array.isArray(record.changes) ? record.changes : []) {
    if (!entry || typeof entry !== "object") continue;
    const change = entry as Record<string, unknown>;
    const itemId = typeof change.itemId === "string" ? change.itemId : null;
    const item = itemId ? getItem(itemId) : null;
    switch (change.type) {
      case "add": {
        if (typeof change.text !== "string" || !change.text.trim()) break;
        const have = inStock(change.text.trim(), stockRows);
        changes.push({
          type: "add",
          text: change.text.trim(),
          quantity:
            typeof change.quantity === "number" && change.quantity > 0
              ? Math.floor(change.quantity)
              : 1,
          // Flagged so the app can show "already in stock" / "already in your list" and leave it unticked.
          inStock: change.inStock === true || Boolean(have),
          stockName: have?.text ?? null,
          inList: matchByText(change.text.trim(), listRows)?.text ?? null,
        });
        break;
      }
      case "delete":
        if (item)
          changes.push({ type: "delete", itemId: item.id, text: item.text });
        break;
      case "rename":
        if (item && typeof change.to === "string" && change.to.trim())
          changes.push({
            type: "rename",
            itemId: item.id,
            from: item.text,
            to: change.to.trim(),
          });
        break;
      case "quantity":
        if (item && typeof change.quantity === "number" && change.quantity > 0)
          changes.push({
            type: "quantity",
            itemId: item.id,
            text: item.text,
            quantity: Math.floor(change.quantity),
          });
        break;
      case "replace": {
        const store = typeof change.store === "string" ? change.store : "";
        const productId =
          typeof change.productId === "string" ? change.productId : "";
        const product = productId ? productsByIds([productId])[0] : undefined;
        // Only real products at a real store: the model cannot invent a replacement.
        if (
          item &&
          product &&
          product.store === store &&
          enabledStoreCodes().includes(store)
        ) {
          const current = getMatch(item.id, store);
          const currentProduct = current?.chosenProductId
            ? productsByIds([current.chosenProductId])[0]
            : current?.candidateIds[0]
              ? productsByIds([current.candidateIds[0]])[0]
              : undefined;
          changes.push({
            type: "replace",
            itemId: item.id,
            text: item.text,
            store,
            productId,
            from: currentProduct?.title ?? null,
            to: product.title,
          });
        }
        break;
      }
      case "skip":
        if (
          item &&
          typeof change.store === "string" &&
          enabledStoreCodes().includes(change.store)
        )
          changes.push({
            type: "skip",
            itemId: item.id,
            text: item.text,
            store: change.store,
          });
        break;
      case "add_recipe_folder":
        if (
          typeof change.url === "string" &&
          /^(https?:\/\/|baskt:\/\/recipe\/)/.test(change.url)
        )
          changes.push({
            type: "add_recipe_folder",
            url: change.url,
            title: typeof change.title === "string" ? change.title : change.url,
          });
        break;
    }
  }
  if (!changes.length) return null;
  return {
    summary:
      typeof record.summary === "string" ? record.summary : "Proposed changes",
    changes,
    applied: null,
  };
}

function sanitizeRecipes(raw: unknown): RecipeCard[] | null {
  if (!Array.isArray(raw)) return null;
  const cards = raw
    .filter(
      (entry): entry is Record<string, unknown> =>
        Boolean(entry) && typeof entry === "object",
    )
    .filter(
      (entry) =>
        typeof entry.title === "string" &&
        typeof entry.url === "string" &&
        /^https?:\/\//.test(entry.url as string),
    )
    .slice(0, 5)
    .map((entry) => ({
      title: entry.title as string,
      url: entry.url as string,
      source: typeof entry.source === "string" ? entry.source : null,
      imageUrl: typeof entry.imageUrl === "string" ? entry.imageUrl : null,
    }));
  return cards.length ? cards : null;
}

/** One user turn: stores the message, runs the tool loop, stores and returns the assistant's message. */
export async function chat(
  basketId: string,
  text: string,
): Promise<ChatMessageRow[]> {
  const userRow = save({
    basketId,
    role: "user",
    content: text,
    proposalJson: null,
    recipesJson: null,
  });
  if (!aiConfigured()) {
    return [
      userRow,
      save({
        basketId,
        role: "assistant",
        content:
          "The assistant needs an AI provider configured on the server (AI_API_KEY / AI_MODEL).",
        proposalJson: null,
        recipesJson: null,
      }),
    ];
  }
  const stores = allStores()
    .filter((store) => enabledStoreCodes().includes(store.code))
    .map((store) => `${store.code}=${store.name}`)
    .join(", ");
  const messages: ChatMessage[] = [
    {
      role: "system",
      content: SYSTEM(appLanguageName()).replace("STORES", stores),
    },
  ];
  for (const row of history(basketId).slice(-HISTORY)) {
    // Prior proposals are summarised so the model knows what was already suggested/applied.
    const extra = row.proposalJson
      ? `\n[proposal "${row.proposalJson.summary}": ${row.proposalJson.changes
          .map(
            (change, index) =>
              `${describeChange(change)} — ${row.proposalJson?.applied?.includes(index) ? "APPLIED by the user" : "not applied"}`,
          )
          .join("; ")}]`
      : "";
    messages.push({
      role: row.role === "assistant" ? "system" : "user",
      content: `${row.role === "assistant" ? "ASSISTANT (previous turn): " : ""}${row.content}${extra}`,
    });
  }

  let reply = "";
  let proposal: Proposal | null = null;
  let recipes: RecipeCard[] | null = null;
  let lastRecipeHits: RecipeCard[] = [];
  progress.set(basketId, []);
  report(basketId, "Thinking about your request");
  try {
    for (let step = 0; step < MAX_STEPS; step += 1) {
      const raw = await chatJson<{
        answer?: unknown;
        reply?: unknown;
        tool?: unknown;
        proposal?: unknown;
        recipes?: unknown;
      }>(messages, { maxTokens: 4000, tools: TOOL_SPECS });
      if (!raw) {
        reply =
          reply || "I could not reach the AI right now. Please try again.";
        break;
      }
      const answer = typeof raw.answer === "string" ? raw.answer : raw.reply;
      if (typeof answer === "string" && answer.trim()) reply = answer.trim();
      proposal = sanitizeProposal(raw.proposal, basketId) ?? proposal;
      recipes = sanitizeRecipes(raw.recipes) ?? recipes;
      const tool =
        raw.tool &&
        typeof raw.tool === "object" &&
        typeof (raw.tool as ToolCall).name === "string"
          ? (raw.tool as ToolCall)
          : null;
      if (!tool) break;
      report(basketId, describeTool(tool));
      const result = await runTool(tool, basketId);
      console.log(
        `[assistant] ${tool.name}(${JSON.stringify(tool.args ?? {})}) -> ${Array.isArray(result) ? `${result.length} results` : JSON.stringify(result).slice(0, 120)}`,
      );
      // Make sure recipe results reach the user as cards even if the model only describes them in text.
      if (
        tool.name === "search_recipes" &&
        Array.isArray(result) &&
        result.length
      )
        lastRecipeHits = result as RecipeCard[];
      report(basketId, "Thinking");
      messages.push({
        role: "system",
        content: `ASSISTANT called ${tool.name}(${JSON.stringify(tool.args ?? {})})`,
      });
      messages.push({
        role: "user",
        content: `TOOL RESULT ${tool.name}: ${JSON.stringify(result).slice(0, 6000)}${
          tool.name === "list_basket" || tool.name === "list_stock"
            ? "\n(Reminder: items the user explicitly named to add must still appear as add changes even if they are listed here; the app marks duplicates.)"
            : ""
        }`,
      });
    }
  } catch (error) {
    console.warn(
      `[assistant] turn failed: ${error instanceof Error ? error.message : error}`,
    );
    reply =
      reply ||
      "Something went wrong while I was working on that. Please try again.";
  } finally {
    progress.delete(basketId);
  }
  if (proposal) proposal = withNamedDuplicates(proposal, text, basketId);
  if (!recipes && lastRecipeHits.length)
    recipes = sanitizeRecipes(lastRecipeHits);
  // A proposal card (with its summary as title) is a complete message on its own; only fill text when there is nothing else.
  if (!reply && !proposal) reply = recipes ? "Here are some recipes." : "Done.";
  return [
    userRow,
    save({
      basketId,
      role: "assistant",
      content: reply,
      proposalJson: proposal,
      recipesJson: recipes,
    }),
  ];
}

/** Applies the confirmed subset of a proposal; each change goes through the normal basket functions. */
export async function applyProposal(
  messageId: string,
  indices: number[],
): Promise<{
  message: ChatMessageRow;
  results: Array<{ index: number; ok: boolean; error?: string }>;
} | null> {
  const row = db()
    .select()
    .from(chatMessages)
    .where(eq(chatMessages.id, messageId))
    .get();
  if (!row?.proposalJson) return null;
  const results: Array<{ index: number; ok: boolean; error?: string }> = [];
  const applied = new Set(row.proposalJson.applied ?? []);
  for (const index of indices) {
    const change = row.proposalJson.changes[index];
    if (!change || applied.has(index)) continue;
    try {
      switch (change.type) {
        case "add":
          createItem(change.text, change.quantity, null, "item", row.basketId);
          break;
        case "delete":
          deleteItemWithChildren(change.itemId);
          break;
        case "rename":
          updateItemFields(change.itemId, { text: change.to });
          break;
        case "quantity":
          updateItemFields(change.itemId, { quantity: change.quantity });
          break;
        case "replace":
          if (!chooseMatch(change.itemId, change.store, change.productId))
            throw new Error("product no longer available");
          break;
        case "skip":
          chooseMatch(change.itemId, change.store, null);
          break;
        case "add_recipe_folder":
          createGroupFromUrl(change.url, row.basketId);
          break;
      }
      applied.add(index);
      results.push({ index, ok: true });
    } catch (error) {
      results.push({
        index,
        ok: false,
        error: error instanceof Error ? error.message : String(error),
      });
    }
  }
  const updated: Proposal = {
    ...row.proposalJson,
    applied: Array.from(applied).sort((a, b) => a - b),
  };
  db()
    .update(chatMessages)
    .set({ proposalJson: updated })
    .where(eq(chatMessages.id, messageId))
    .run();
  return { message: { ...row, proposalJson: updated }, results };
}

export { products };

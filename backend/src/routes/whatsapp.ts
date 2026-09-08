import { and, asc, eq, inArray } from "drizzle-orm";
import { Hono } from "hono";
import { db } from "@/db";
import { basketItems, basketMatches, DEFAULT_BASKET_ID } from "@/db/schema";
import { enabledStoreCodes, getSetting, setSetting } from "@/db/settings";
import { env } from "@/env";
import { getBasket } from "@/routes/baskets";
import { collectProductIds, itemView } from "@/serialize";
import { allStores } from "@/stores/registry";
import { productsByIds } from "@/stores/search";

/** Thin proxy to the Node WhatsApp bridge plus the "send basket as item messages" composer. */
export const whatsapp = new Hono();

async function bridge(path: string, init: RequestInit = {}) {
  if (!env.whatsappUrl) throw new Error("WHATSAPP_URL is not configured");
  const response = await fetch(`${env.whatsappUrl}${path}`, { ...init, signal: AbortSignal.timeout(60_000) });
  const body = (await response.json().catch(() => ({}))) as Record<string, unknown>;
  if (!response.ok) throw new Error(typeof body.error === "string" ? body.error : `bridge HTTP ${response.status}`);
  return body;
}

whatsapp.get("/status", async (c) => {
  if (!env.whatsappUrl) return c.json({ enabled: false, status: "disabled", chatId: null });
  try {
    const status = await bridge("/status");
    return c.json({ enabled: true, ...status, chatId: getSetting<string | null>("whatsapp.chatId", null), chatName: getSetting<string | null>("whatsapp.chatName", null) });
  } catch (error) {
    return c.json({ enabled: true, status: "unreachable", lastError: error instanceof Error ? error.message : String(error), chatId: getSetting<string | null>("whatsapp.chatId", null) });
  }
});

whatsapp.get("/qr", async (c) => {
  try {
    return c.json(await bridge("/qr"));
  } catch (error) {
    return c.json({ error: { code: "BRIDGE", message: error instanceof Error ? error.message : String(error) } }, 502);
  }
});

whatsapp.get("/chats", async (c) => {
  try {
    return c.json(await bridge("/chats"));
  } catch (error) {
    return c.json({ error: { code: "BRIDGE", message: error instanceof Error ? error.message : String(error) } }, 502);
  }
});

whatsapp.patch("/settings", async (c) => {
  const body = (await c.req.json().catch(() => ({}))) as { chatId?: string; chatName?: string };
  if (typeof body.chatId === "string") setSetting("whatsapp.chatId", body.chatId);
  if (typeof body.chatName === "string") setSetting("whatsapp.chatName", body.chatName);
  return c.json({ chatId: getSetting<string | null>("whatsapp.chatId", null), chatName: getSetting<string | null>("whatsapp.chatName", null) });
});

/** Sends the open items of a basket as one message each (picture + text) to the configured chat. */
whatsapp.post("/send", async (c) => {
  const body = (await c.req.json().catch(() => ({}))) as { basketId?: string; chatId?: string; store?: string };
  const basketId = body.basketId ?? DEFAULT_BASKET_ID;
  const basket = getBasket(basketId);
  if (!basket) return c.json({ error: { code: "NOT_FOUND", message: "basket not found" } }, 404);
  const chatId = body.chatId ?? getSetting<string | null>("whatsapp.chatId", null);
  if (!chatId) return c.json({ error: { code: "BAD_REQUEST", message: "pick a WhatsApp chat first" } }, 400);
  const items = db().select().from(basketItems).where(and(eq(basketItems.basketId, basketId), eq(basketItems.kind, "item"), eq(basketItems.checked, false))).orderBy(asc(basketItems.sortOrder)).all();
  const matches = items.length ? db().select().from(basketMatches).where(inArray(basketMatches.itemId, items.map((item) => item.id))).all() : [];
  const products = new Map(productsByIds(collectProductIds(matches)).map((product) => [product.id, product]));
  const storeNames = Object.fromEntries(allStores().map((store) => [store.code, store.name]));
  const stores = enabledStoreCodes();
  const payload = items
    .map((item) => itemView(item, matches.filter((match) => match.itemId === item.id), products))
    .filter((view) => !body.store || view.assignedStore === body.store)
    .map((view) => {
      const store = view.assignedStore ?? stores.find((code) => view.matches.some((match) => match.store === code && (match.chosen ?? match.provisional)));
      const match = store ? view.matches.find((entry) => entry.store === store) : undefined;
      const product = match ? match.chosen ?? match.provisional : null;
      const price = product ? `€${((product.priceCents * view.quantity) / 100).toFixed(2).replace(".", ",")}` : "";
      const text = product
        ? `${view.quantity > 1 ? `${view.quantity}× ` : ""}${product.title} (${product.quantityText}) — ${price} @ ${storeNames[store!] ?? store}\n_for: ${view.text}_\nReact ✅ when bought`
        : `${view.quantity > 1 ? `${view.quantity}× ` : ""}${view.text}\nReact ✅ when bought`;
      return { id: view.id, basketId, title: view.text, imageUrl: product?.imageUrl ?? null, text };
    });
  if (!payload.length) return c.json({ error: { code: "BAD_REQUEST", message: "nothing to send" } }, 400);
  try {
    const result = await bridge("/send", {
      method: "POST",
      headers: { "content-type": "application/json" },
      body: JSON.stringify({ chatId, items: payload, header: `🧺 *${basket.name}* — ${payload.length} items${body.store ? ` at ${storeNames[body.store] ?? body.store}` : ""}` }),
    });
    return c.json(result);
  } catch (error) {
    return c.json({ error: { code: "BRIDGE", message: error instanceof Error ? error.message : String(error) } }, 502);
  }
});

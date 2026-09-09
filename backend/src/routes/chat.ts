import { Hono } from "hono";
import { DEFAULT_BASKET_ID } from "@/db/schema";
import { applyProposal, chat, clearHistory, history, progressFor } from "@/assistant";
import { basketExists } from "@/routes/baskets";

/** Assistant chat: persistent per-basket history, tool-using turns, confirm-before-apply proposals. */
export const chatRoute = new Hono();

chatRoute.get("/", (c) => {
  const basketId = c.req.query("basketId") ?? DEFAULT_BASKET_ID;
  if (!basketExists(basketId)) return c.json({ error: { code: "NOT_FOUND", message: "basket not found" } }, 404);
  return c.json({ basketId, messages: history(basketId) });
});

/** Live steps of the turn in progress (empty when idle); the app polls this while waiting. */
chatRoute.get("/progress", (c) => {
  const basketId = c.req.query("basketId") ?? DEFAULT_BASKET_ID;
  const steps = progressFor(basketId);
  return c.json({ busy: steps.length > 0, steps });
});

chatRoute.post("/", async (c) => {
  const body = (await c.req.json().catch(() => ({}))) as { basketId?: string; text?: string };
  const basketId = body.basketId ?? DEFAULT_BASKET_ID;
  if (!basketExists(basketId)) return c.json({ error: { code: "NOT_FOUND", message: "basket not found" } }, 404);
  if (!body.text?.trim()) return c.json({ error: { code: "BAD_REQUEST", message: "text is required" } }, 400);
  return c.json({ messages: await chat(basketId, body.text.trim()) });
});

chatRoute.delete("/", (c) => {
  const basketId = c.req.query("basketId") ?? DEFAULT_BASKET_ID;
  clearHistory(basketId);
  return c.body(null, 204);
});

chatRoute.post("/proposals/:messageId/apply", async (c) => {
  const body = (await c.req.json().catch(() => ({}))) as { indices?: number[] };
  const indices = Array.isArray(body.indices) ? body.indices.filter((value): value is number => Number.isInteger(value)) : [];
  const result = await applyProposal(c.req.param("messageId"), indices);
  if (!result) return c.json({ error: { code: "NOT_FOUND", message: "proposal not found" } }, 404);
  return c.json(result);
});

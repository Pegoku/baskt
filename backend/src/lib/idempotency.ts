import { createMiddleware } from "hono/factory";
import { eq } from "drizzle-orm";
import { db } from "@/db";
import { mutationReceipts } from "@/db/schema";
import { sha256 } from "@/lib/text";

/** Serializes retries of one client operation; successful responses are retained across restarts. */
export function idempotency() {
  const inFlight = new Map<string, Promise<void>>();
  return createMiddleware(async (c, next) => {
    const key = c.req.header("Idempotency-Key");
    if (!key || !["POST", "PATCH", "DELETE"].includes(c.req.method)) return next();
    if (!/^[a-zA-Z0-9-]{16,128}$/.test(key)) return c.json({ error: { code: "BAD_REQUEST", message: "invalid operation key" } }, 400);
    const fingerprint = await sha256(`${c.req.method}|${c.req.url}|${await c.req.raw.clone().text()}`);
    // Authentication middleware runs first. Receipts are scoped to the current credential.
    const scopedKey = await sha256(`${c.req.header("authorization") ?? ""}|${key}`);
    while (inFlight.has(scopedKey)) await inFlight.get(scopedKey);
    let release!: () => void;
    inFlight.set(scopedKey, new Promise<void>((resolve) => { release = resolve; }));
    try {
      const database = db();
      const receipt = database.select().from(mutationReceipts).where(eq(mutationReceipts.key, scopedKey)).get();
      if (receipt) {
        if (receipt.fingerprint !== fingerprint) return c.json({ error: { code: "CONFLICT", message: "operation key was used for a different request" } }, 409);
        c.res = new Response(receipt.status === 204 ? null : receipt.body, { status: receipt.status, headers: { "Content-Type": "application/json" } });
        return;
      }
      await next();
      if (c.res.ok) database.insert(mutationReceipts).values({
        key: scopedKey, fingerprint, status: c.res.status, body: await c.res.clone().text(), createdAt: Date.now(),
      }).run();
    } finally { inFlight.delete(scopedKey); release(); }
  });
}

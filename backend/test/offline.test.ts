import { beforeEach, expect, test } from "bun:test";
import { eq } from "drizzle-orm";
import { createApp } from "@/app";
import { db, resetDbForTests } from "@/db";
import { upstreamCache } from "@/db/schema";
import { cachedUpstream } from "@/lib/cache";

beforeEach(() => { resetDbForTests(); });
const headers = { authorization: "Bearer secret", "content-type": "application/json", "Idempotency-Key": "offline-operation-1234" };
const createBasket = (app: ReturnType<typeof createApp>, name = "Offline") => app.request("/api/v1/baskets", { method: "POST", headers, body: JSON.stringify({ name }) });

test("concurrent and restarted client retries return the original creation", async () => {
  const app = createApp({ token: "secret", log: false });
  const responses = await Promise.all([createBasket(app), createBasket(app)]);
  expect(responses.map((r) => r.status)).toEqual([201, 201]);
  const [a, b] = await Promise.all(responses.map((r) => r.json()));
  expect(a.id).toBe(b.id);
  const restarted = createApp({ token: "secret", log: false });
  expect((await (await createBasket(restarted)).json()).id).toBe(a.id);
  const list = await (await app.request("/api/v1/baskets", { headers })).json();
  expect(list.baskets).toHaveLength(2);
});

test("operation keys cannot be reused for different payloads or bypass authentication", async () => {
  const app = createApp({ token: "secret", log: false });
  await createBasket(app);
  expect((await createBasket(app, "Different")).status).toBe(409);
  expect((await app.request("/api/v1/baskets", { method: "POST", headers: { ...headers, authorization: "Bearer wrong" }, body: '{"name":"Offline"}' })).status).toBe(401);
});

test("failed writes are retryable and successful deletes replay as 204", async () => {
  const app = createApp({ token: "secret", log: false });
  expect((await createBasket(app, "")).status).toBe(400);
  const created = await (await createBasket(app)).json();
  const remove = () => app.request(`/api/v1/baskets/${created.id}`, { method: "DELETE", headers: { ...headers, "Idempotency-Key": "delete-operation-1234" } });
  expect((await remove()).status).toBe(204);
  const again = await remove();
  expect(again.status).toBe(204);
  expect(await again.text()).toBe("");
});

test("upstream cache coalesces requests and reuses persisted values", async () => {
  let calls = 0;
  const load = async () => { calls++; await Bun.sleep(5); return { title: "Recipe" }; };
  const results = await Promise.all([cachedUpstream("recipe", 1000, load), cachedUpstream("recipe", 1000, load)]);
  expect(calls).toBe(1);
  expect(results[0]).toEqual(results[1]);
  expect(await cachedUpstream("recipe", 1000, load)).toEqual({ title: "Recipe" });
  expect(calls).toBe(1);
});

test("expired entries refresh, serve bounded stale data on errors and never cache failures", async () => {
  await cachedUpstream("recipe", 1000, async () => "saved");
  db().update(upstreamCache).set({ expiresAt: 0, staleUntil: Date.now() + 10000 }).where(eq(upstreamCache.key, "recipe")).run();
  const fail = async (): Promise<string> => { throw new Error("unavailable"); };
  expect(await cachedUpstream("recipe", 1000, fail)).toBe("saved");
  db().update(upstreamCache).set({ staleUntil: 0 }).where(eq(upstreamCache.key, "recipe")).run();
  await expect(cachedUpstream("recipe", 1000, fail)).rejects.toThrow("unavailable");
  expect(await cachedUpstream("recipe", 1000, async () => "new")).toBe("new");
  let calls = 0;
  const missing = () => cachedUpstream("missing", 1000, async () => { calls++; return null; }, { cacheable: value => value !== null });
  await missing(); await missing();
  expect(calls).toBe(2);
});

import { eq, lt } from "drizzle-orm";
import { db } from "@/db";
import { upstreamCache } from "@/db/schema";

// Scope in-flight work to the database as well as the key (tests can replace the database).
const flights = new WeakMap<object, Map<string, Promise<unknown>>>();

/** Persistent TTL cache with coalesced misses, bounded retention and stale-on-error fallback. */
export async function cachedUpstream<T>(key: string, ttlMs: number, load: () => Promise<T>, options: {
  staleMs?: number; cacheable?: (value: T) => boolean; force?: boolean;
} = {}): Promise<T> {
  const database = db();
  const at = Date.now();
  const hit = database.select().from(upstreamCache).where(eq(upstreamCache.key, key)).get();
  if (!options.force && hit && hit.expiresAt > at) return hit.value as T;
  let pending = flights.get(database);
  if (!pending) { pending = new Map(); flights.set(database, pending); }
  const existing = pending.get(key);
  if (existing) return existing as Promise<T>;
  const task = (async () => {
    try {
      const value = await load();
      if (options.cacheable?.(value) !== false) {
        const expiresAt = Date.now() + ttlMs;
        const row = { key, value, expiresAt, staleUntil: expiresAt + (options.staleMs ?? ttlMs) };
        database.delete(upstreamCache).where(lt(upstreamCache.staleUntil, Date.now())).run();
        database.insert(upstreamCache).values(row).onConflictDoUpdate({ target: upstreamCache.key, set: row }).run();
      }
      return value;
    } catch (error) {
      if (hit && hit.staleUntil > Date.now()) return hit.value as T;
      throw error;
    }
  })().finally(() => pending!.delete(key));
  pending.set(key, task);
  return task;
}

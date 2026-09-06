import { eq } from "drizzle-orm";
import { db } from "@/db";
import { settings } from "@/db/schema";
import { allStores } from "@/stores/registry";

export function getSetting<T>(key: string, fallback: T): T {
  const row = db().select().from(settings).where(eq(settings.key, key)).get();
  if (!row) return fallback;
  try {
    return JSON.parse(row.valueJson) as T;
  } catch {
    return fallback;
  }
}

export function setSetting<T>(key: string, value: T) {
  db()
    .insert(settings)
    .values({ key, valueJson: JSON.stringify(value) })
    .onConflictDoUpdate({ target: settings.key, set: { valueJson: JSON.stringify(value) } })
    .run();
}

export function enabledStoreCodes(): string[] {
  const known = allStores().map((store) => store.code);
  const enabled = getSetting<string[] | null>("enabledStores", null);
  if (!enabled) return known;
  const filtered = enabled.filter((code) => known.includes(code));
  return filtered.length ? filtered : known;
}

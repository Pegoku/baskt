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

const LANGUAGE_NAMES: Record<string, string> = { en: "English", nl: "Dutch", es: "Spanish", ca: "Catalan", de: "German", fr: "French", it: "Italian", pt: "Portuguese", pl: "Polish", tr: "Turkish" };

/** BCP-47 language code the app asked for (default English). */
export function appLanguage(): string {
  const code = getSetting<string>("language", "en").toLowerCase().split(/[-_]/)[0];
  return /^[a-z]{2,3}$/.test(code) ? code : "en";
}

export function appLanguageName(): string {
  const code = appLanguage();
  return LANGUAGE_NAMES[code] ?? code;
}

export function enabledStoreCodes(): string[] {
  const known = allStores().map((store) => store.code);
  const enabled = getSetting<string[] | null>("enabledStores", null);
  if (!enabled) return known;
  const filtered = enabled.filter((code) => known.includes(code));
  return filtered.length ? filtered : known;
}

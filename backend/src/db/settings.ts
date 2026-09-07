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

export type RankBy = "price" | "unitPrice";

/** Whether options and cheapest-store hints favour the pack price or the price per kg/l/piece. */
export function rankBy(): RankBy {
  return getSetting<string>("rankBy", "price") === "unitPrice" ? "unitPrice" : "price";
}

/** Default number of servings recipe folders are scaled to (null = as written). */
export function defaultServings(): number | null {
  const value = getSetting<number | null>("defaultServings", null);
  return typeof value === "number" && value > 0 ? value : null;
}

/** Recipe folders leave out ingredients that are in stock (default on). */
export function skipInStock(): boolean {
  return getSetting<boolean>("recipeSkipInStock", true);
}

export function enabledStoreCodes(): string[] {
  const known = allStores().map((store) => store.code);
  const enabled = getSetting<string[] | null>("enabledStores", null);
  if (!enabled) return known;
  const filtered = enabled.filter((code) => known.includes(code));
  return filtered.length ? filtered : known;
}

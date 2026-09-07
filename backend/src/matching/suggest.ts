import { desc } from "drizzle-orm";
import { cachedChatJson } from "@/ai/client";
import { db } from "@/db";
import { basketItems, choices } from "@/db/schema";
import { appLanguage, appLanguageName } from "@/db/settings";
import { aiConfigured } from "@/env";
import { normalizeText, sha256, tokenize } from "@/lib/text";

export const SUGGEST_PROMPT_VERSION = "v4";

const SYSTEM = `The user is typing a grocery item into a shopping list app for Dutch supermarkets. The text may be incomplete, misspelled, in Dutch/English/Spanish, or a description instead of a name (e.g. "the olive liquid used to fry things" = olive oil).
Return ONLY {"suggestions": [up to 3 short concrete item names, best first]}.
Rules: return 3 suggestions when the text is about something sold in a supermarket, including household goods like toilet paper or detergent (fewer only if nothing else fits); each is 1-4 words naming a real grocery product type (e.g. "olive oil", "semi-skimmed milk", "free-range eggs"); ALWAYS write suggestions in LANGUAGE, whatever language the user typed in; fix typos (never repeat a misspelling); no brands unless typed; no explanations; return [] only when the text is clearly not about shopping.
Dutch dairy terms: halfvol/halfvolle = semi-skimmed, vol/volle = whole, mager/magere = skimmed, houdbaar = long-life, karnemelk = buttermilk.`;

/** Items typed or chosen before that start with / contain the same tokens: instant, no AI. */
export function historySuggestions(text: string, limit = 3): string[] {
  const normalized = normalizeText(text);
  if (normalized.length < 2) return [];
  const tokens = tokenize(text);
  const database = db();
  const seen = new Set<string>();
  const results: string[] = [];
  const candidates = [
    ...database.select({ text: basketItems.text }).from(basketItems).orderBy(desc(basketItems.updatedAt)).limit(200).all().map((row) => row.text),
    ...database.select({ text: choices.itemText }).from(choices).orderBy(desc(choices.createdAt)).limit(300).all().map((row) => row.text),
  ];
  for (const candidate of candidates) {
    const key = normalizeText(candidate);
    if (!key || key === normalized || seen.has(key)) continue;
    const candidateTokens = tokenize(candidate);
    const matches = key.startsWith(normalized) || tokens.every((token) => candidateTokens.some((other) => other.startsWith(token)));
    if (!matches) continue;
    seen.add(key);
    results.push(candidate);
    if (results.length >= limit) break;
  }
  return results;
}

export async function aiSuggestions(text: string): Promise<string[]> {
  if (!aiConfigured() || normalizeText(text).length < 3) return [];
  const key = await sha256(`${SUGGEST_PROMPT_VERSION}|${appLanguage()}|${normalizeText(text)}`);
  const raw = await cachedChatJson<{ suggestions?: unknown }>(
    "suggest",
    key,
    [
      { role: "system", content: SYSTEM.replaceAll("LANGUAGE", appLanguageName()) },
      { role: "user", content: text },
    ],
    { maxTokens: 200 },
  );
  if (!raw || !Array.isArray(raw.suggestions)) return [];
  return raw.suggestions.filter((value): value is string => typeof value === "string" && value.trim().length > 0).map((value) => value.trim()).slice(0, 3);
}

/** History first (instant, personal), then AI interpretations; duplicates and the typed text itself removed. */
export async function suggest(text: string): Promise<{ suggestions: string[]; source: "history" | "ai" | "both" | "none" }> {
  const history = historySuggestions(text);
  const ai = await aiSuggestions(text);
  const typed = normalizeText(text);
  const seen = new Set<string>([typed]);
  const merged: string[] = [];
  for (const suggestion of [...history, ...ai]) {
    const key = normalizeText(suggestion);
    if (!key || seen.has(key)) continue;
    seen.add(key);
    merged.push(suggestion);
  }
  const source = history.length && ai.length ? "both" : history.length ? "history" : ai.length ? "ai" : "none";
  return { suggestions: merged.slice(0, 4), source };
}

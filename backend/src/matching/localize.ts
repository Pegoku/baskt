import { cachedChatJson } from "@/ai/client";
import { appLanguage, appLanguageName } from "@/db/settings";
import { aiConfigured } from "@/env";
import { normalizeText, sha256 } from "@/lib/text";
import type { Recipe } from "@/matching/recipes";

export const L10N_PROMPT_VERSION = "v3";

export type LocalizedRecipe = Recipe & { originalTitle: string; language: string };

/** Translates title, ingredient lines and steps of a (Dutch) recipe into the app language; cached per recipe. */
export async function localizeRecipe(recipe: Recipe): Promise<LocalizedRecipe> {
  const language = appLanguage();
  const untouched: LocalizedRecipe = { ...recipe, originalTitle: recipe.title, language: "nl" };
  // Even for Dutch the steps are rewritten: sites pad them with links and cross-references.
  if (!aiConfigured()) return untouched;
  const steps = recipe.steps ?? [];
  const key = await sha256([L10N_PROMPT_VERSION, language, recipe.sourceUrl ?? recipe.title, recipe.ingredientLines.join("|"), steps.map((step) => step.text).join("|")].join("#"));
  const raw = await cachedChatJson<{ title?: unknown; ingredientLines?: unknown; steps?: unknown }>(
    "recipe-l10n",
    key,
    [
      {
        role: "system",
        content: `Translate this recipe into ${appLanguageName()}. The title and EVERY ingredient line must be in ${appLanguageName()} (e.g. "250 g tarwebloem" becomes "250 g wheat flour" in English): keep amounts and units, same count and order, translate the words. Steps: keep the same count and order, but turn each into a clean, self-contained cooking instruction in ${appLanguageName()}: remove links, "see this article", tips about other recipes, ads, and chatty filler; keep times, temperatures and techniques. Return ONLY {"title": string, "ingredientLines": string[], "steps": string[]}.`,
      },
      { role: "user", content: JSON.stringify({ title: recipe.title, ingredientLines: recipe.ingredientLines, steps: steps.map((step) => step.text) }) },
    ],
    { maxTokens: 3000 },
  );
  const lines = Array.isArray(raw?.ingredientLines) ? raw!.ingredientLines.filter((line): line is string => typeof line === "string") : [];
  const stepTexts = Array.isArray(raw?.steps) ? raw!.steps.filter((line): line is string => typeof line === "string") : [];
  return {
    ...recipe,
    title: typeof raw?.title === "string" && raw.title.trim() ? raw.title.trim() : recipe.title,
    ingredientLines: lines.length === recipe.ingredientLines.length ? lines : recipe.ingredientLines,
    steps: stepTexts.length === steps.length ? steps.map((step, index) => ({ ...step, text: stepTexts[index] })) : steps,
    originalTitle: recipe.title,
    language,
  };
}

/** Translates a batch of recipe titles (search results) into the app language; cached per batch. */
export async function localizeTitles(titles: string[]): Promise<string[]> {
  const language = appLanguage();
  if (language === "nl" || !aiConfigured() || !titles.length) return titles;
  const key = await sha256([L10N_PROMPT_VERSION, language, ...titles.map(normalizeText)].join("#"));
  const raw = await cachedChatJson<{ titles?: unknown }>(
    "title-l10n",
    key,
    [
      { role: "system", content: `Translate these Dutch recipe names into ${appLanguageName()}. Keep dish names that are proper names (e.g. "Karaage", "Lasagne"). Return ONLY {"titles": string[]} with the same count and order.` },
      { role: "user", content: JSON.stringify(titles) },
    ],
    { maxTokens: 1500 },
  );
  const out = Array.isArray(raw?.titles) ? raw!.titles.filter((title): title is string => typeof title === "string") : [];
  return out.length === titles.length ? out : titles;
}

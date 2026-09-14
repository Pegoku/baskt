import { translateContent } from "@/matching/translate";
import { cachedChatJson } from "@/ai/client";
import { appLanguage, appLanguageName } from "@/db/settings";
import { aiConfigured } from "@/env";
import { normalizeText, sha256 } from "@/lib/text";
import type { Recipe } from "@/matching/recipes";

export const L10N_PROMPT_VERSION = "v4";

export type LocalizedRecipe = Recipe & { originalTitle: string; language: string | null; original: Recipe; translationAvailable: boolean };

/** Preserve the complete source alongside a faithful translation, including images and quantities. */
export async function localizeRecipe(recipe: Recipe): Promise<LocalizedRecipe> {
  const language = appLanguage();
  const steps = recipe.steps ?? [];
  const texts = [recipe.title, ...recipe.ingredientLines, ...steps.map((step) => step.text), recipe.servings ?? "", recipe.totalTime ?? "", recipe.description ?? ""];
  const result = await translateContent(texts, language);
  let index = 0;
  return {
    ...recipe,
    title: result.texts[index++],
    ingredientLines: recipe.ingredientLines.map(() => result.texts[index++]),
    steps: steps.map((step) => ({ ...step, text: result.texts[index++] })),
    servings: result.texts[index++] || null,
    totalTime: result.texts[index++] || undefined,
    description: result.texts[index++] || null,
    originalTitle: recipe.title,
    original: recipe,
    language: result.translated ? language : null,
    translationAvailable: result.translated,
  };
}

/** Translates a batch of recipe titles (search results) into the app language; cached per batch. */
export async function localizeTitles(titles: string[]): Promise<string[]> {
  const language = appLanguage();
  if (!aiConfigured() || !titles.length) return titles;
  const key = await sha256([L10N_PROMPT_VERSION, language, ...titles.map(normalizeText)].join("#"));
  const raw = await cachedChatJson<{ titles?: unknown }>(
    "title-l10n",
    key,
    [
      { role: "system", content: `Translate these recipe names (which may be in any language) into ${appLanguageName()}. Keep dish names that are proper names (e.g. "Karaage", "Lasagne"). Return ONLY {"titles": string[]} with the same count and order.` },
      { role: "user", content: JSON.stringify(titles) },
    ],
    { maxTokens: 1500 },
  );
  const out = Array.isArray(raw?.titles) ? raw!.titles.filter((title): title is string => typeof title === "string") : [];
  return out.length === titles.length ? out : titles;
}

import { chatJson } from "@/ai/client";
import { appLanguageName } from "@/db/settings";
import { aiConfigured } from "@/env";
import { looksLikeRecipe } from "@/matching/recipes";

export type VoiceItem = { text: string; quantity: number; wanted: boolean; kind: "item" | "recipe"; note: string | null };

const SYSTEM = `You turn a spoken, possibly rambling grocery request into a shopping list. The speaker may correct themselves ("...wait, never mind, I don't want rice, I already have it").
Return ONLY {"items":[{"text": item as a short shopping idea in LANGUAGE (keep sizes/brands the speaker said), "quantity": integer packs (default 1), "wanted": false when the speaker retracted it or said they already have it, else true, "kind": "recipe" when they ask for the ingredients of a dish (then text is the dish), else "item", "note": short reason when wanted is false, else null}]}.
Keep every mentioned product, including retracted ones (with wanted=false). Do not invent items.`;

function fallback(text: string): VoiceItem[] {
  return text
    .split(/[\n,;]+|\s+\b(?:and|en|y)\b\s+/gi)
    .map((part) => part.trim())
    .filter(Boolean)
    .slice(0, 40)
    .map((part) => ({ text: part, quantity: 1, wanted: true, kind: looksLikeRecipe(part) ? ("recipe" as const) : ("item" as const), note: null }));
}

/** Interprets a transcript into a proposal; nothing is added until the user confirms. */
export async function interpretVoice(transcript: string): Promise<VoiceItem[]> {
  const text = transcript.trim();
  if (!text) return [];
  if (!aiConfigured()) return fallback(text);
  const raw = await chatJson<{ items?: Array<Partial<VoiceItem>> }>(
    [
      { role: "system", content: SYSTEM.replaceAll("LANGUAGE", appLanguageName()) },
      { role: "user", content: text },
    ],
    { maxTokens: 1200 },
  );
  const items = (raw?.items ?? [])
    .filter((item) => typeof item.text === "string" && item.text.trim())
    .map((item) => ({
      text: item.text!.trim(),
      quantity: Number.isInteger(item.quantity) && item.quantity! > 0 ? item.quantity! : 1,
      wanted: item.wanted !== false,
      kind: item.kind === "recipe" || looksLikeRecipe(item.text!) ? ("recipe" as const) : ("item" as const),
      note: typeof item.note === "string" && item.note.trim() ? item.note.trim() : null,
    }));
  return items.length ? items : fallback(text);
}

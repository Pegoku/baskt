import { chatJson } from "@/ai/client";
import { appLanguageName } from "@/db/settings";
import type { BasketItemRow } from "@/db/schema";
import { aiConfigured } from "@/env";
import { rememberName } from "@/matching/naming";

export type InstructChange = {
  id: string;
  from: string;
  text: string | null;
  /** True when a new text is only a different wording for the same product, so its matches can stay. */
  wordingOnly: boolean;
  quantity: number | null;
  checked: boolean | null;
  remove: boolean;
};

export type InstructResult = {
  changes: InstructChange[];
  /** Wordings stored for later, as "source → preferred". */
  remembered: string[];
  /** What the model wants to say back when it could not (fully) do it. */
  reply: string | null;
};

const SYSTEM = `You edit selected entries of a grocery list on the user's instruction. The list is written in LANGUAGE. The user may write in any language.
Each entry has a number, its current wording, packs wanted, whether it is ticked off, and sometimes its generic meaning.
Return ONLY {"changes":[{"i": entry number, "text": new wording or null, "wordingOnly": true when the new text names the same product with different words (a translation, a spelling, a shorter name), false when it is a different product, "quantity": new integer packs or null, "checked": true/false or null, "remove": true to delete the entry}], "reply": short sentence in LANGUAGE when the instruction cannot be carried out or needs a remark, else null}.
Only change what the instruction asks for; leave other fields null. "double" means twice the current packs. Apply the instruction to every selected entry it applies to. Keep brand names, sizes and units the entry already has unless told otherwise. Never invent entries.`;

type AiChange = { i?: unknown; text?: unknown; wordingOnly?: unknown; quantity?: unknown; checked?: unknown; remove?: unknown };

/**
 * Turns a free-text comment about the selected entries into concrete edits. Wording corrections are
 * remembered so future tidy passes and translations use the user's words. Nothing is written to the
 * items here; the app applies the edits through the normal item operations.
 */
export async function interpretInstruction(items: BasketItemRow[], instruction: string): Promise<InstructResult> {
  const text = instruction.trim();
  if (!items.length || !text) return { changes: [], remembered: [], reply: null };
  if (!aiConfigured()) return { changes: [], remembered: [], reply: "The assistant is not configured on this server." };
  const listing = items
    .map((item, index) => {
      const canonical = item.parsedJson?.canonicalName;
      return `${index + 1}. "${item.text}" x${item.quantity}${item.checked ? " (ticked off)" : ""}${canonical && canonical.toLowerCase() !== item.text.toLowerCase() ? ` (means: ${canonical})` : ""}`;
    })
    .join("\n");
  const raw = await chatJson<{ changes?: AiChange[]; reply?: unknown }>(
    [
      { role: "system", content: SYSTEM.replaceAll("LANGUAGE", appLanguageName()) },
      { role: "user", content: `Entries:\n${listing}\n\nInstruction: ${text}` },
    ],
    { maxTokens: 1500 },
  );
  const changes: InstructChange[] = [];
  const remembered: string[] = [];
  for (const change of Array.isArray(raw?.changes) ? raw!.changes : []) {
    const index = typeof change.i === "number" && Number.isInteger(change.i) ? change.i - 1 : -1;
    const item = items[index];
    if (!item) continue;
    const newText = typeof change.text === "string" && change.text.trim() && change.text.trim() !== item.text ? change.text.replace(/\s+/g, " ").trim() : null;
    const quantity = typeof change.quantity === "number" && Number.isInteger(change.quantity) && change.quantity >= 1 && change.quantity !== item.quantity ? change.quantity : null;
    const checked = typeof change.checked === "boolean" && change.checked !== item.checked ? change.checked : null;
    const remove = change.remove === true;
    if (!newText && quantity === null && checked === null && !remove) continue;
    const wordingOnly = !!newText && change.wordingOnly === true;
    if (wordingOnly && rememberName({ source: item.text, canonical: item.parsedJson?.canonicalName ?? null, preferred: newText! })) remembered.push(`${item.text} → ${newText}`);
    changes.push({ id: item.id, from: item.text, text: newText, wordingOnly, quantity, checked, remove });
  }
  const reply = typeof raw?.reply === "string" && raw.reply.trim() ? raw.reply.trim() : null;
  return { changes, remembered, reply: reply ?? (changes.length ? null : "Nothing to change for that instruction.") };
}

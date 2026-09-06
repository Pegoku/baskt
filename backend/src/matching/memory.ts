import { desc, eq } from "drizzle-orm";
import { db, newId, now } from "@/db";
import { choices, type ChoiceRow } from "@/db/schema";
import { normalizeText, tokenize } from "@/lib/text";

export type ChoiceInput = {
  itemText: string;
  canonical: string;
  store: string;
  chosenProductId: string | null;
  chosenTitle: string | null;
  rejectedTitles: string[];
};

/** Stores what the user picked (or refused) so later matching can learn their preferences. */
export function recordChoice(input: ChoiceInput) {
  const row: ChoiceRow = {
    id: newId(),
    itemText: input.itemText,
    canonical: normalizeText(input.canonical) || normalizeText(input.itemText),
    store: input.store,
    chosenProductId: input.chosenProductId,
    chosenTitle: input.chosenTitle,
    rejectedTitles: input.rejectedTitles.filter(Boolean),
    createdAt: now(),
  };
  db().insert(choices).values(row).run();
  return row;
}

export function listChoices(limit = 200) {
  return db().select().from(choices).orderBy(desc(choices.createdAt)).limit(limit).all();
}

export function deleteChoice(id: string) {
  return db().delete(choices).where(eq(choices.id, id)).returning({ id: choices.id }).all().length > 0;
}

function overlap(a: string[], b: string[]) {
  if (!a.length || !b.length) return 0;
  const setB = new Set(b);
  const hits = a.filter((token) => setB.has(token)).length;
  return hits / Math.min(a.length, b.length);
}

/** Recent choices about the same kind of item (token overlap on canonical name or raw text). */
export function similarChoices(canonical: string, itemText: string, store?: string, limit = 6): ChoiceRow[] {
  const target = Array.from(new Set([...tokenize(canonical), ...tokenize(itemText)]));
  return listChoices(500)
    .filter((choice) => !store || choice.store === store)
    .filter((choice) => overlap(target, Array.from(new Set([...tokenize(choice.canonical), ...tokenize(choice.itemText)]))) >= 0.5)
    .slice(0, limit);
}

/** The product the user last picked for the very same item at this store, if any. */
export function rememberedProductId(canonical: string, itemText: string, store: string): string | null {
  const wantedCanonical = normalizeText(canonical);
  const wantedText = normalizeText(itemText);
  const hit = listChoices(500).find(
    (choice) =>
      choice.store === store &&
      choice.chosenProductId &&
      (choice.canonical === wantedCanonical || normalizeText(choice.itemText) === wantedText),
  );
  return hit?.chosenProductId ?? null;
}

const STORE_BRAND_PREFIX: Record<string, RegExp> = {
  AH: /^ah\b/i,
  JUMBO: /^jumbo\b/i,
};

/** Human-readable preference summary injected into ranking prompts. */
export function memoryContext(canonical: string, itemText: string, store: string): string {
  const lines: string[] = [];
  const similar = similarChoices(canonical, itemText, undefined, 8);
  for (const choice of similar) {
    const chosen = choice.chosenTitle ? `chose "${choice.chosenTitle}"` : "chose nothing";
    const rejected = choice.rejectedTitles.length ? `; rejected ${choice.rejectedTitles.map((title) => `"${title}"`).join(", ")}` : "";
    lines.push(`- For "${choice.itemText}" at ${choice.store}: ${chosen}${rejected}`);
  }

  const all = listChoices(500).filter((choice) => choice.chosenTitle);
  if (all.length >= 3) {
    const storeBrand = all.filter((choice) => STORE_BRAND_PREFIX[choice.store]?.test(choice.chosenTitle ?? "")).length;
    lines.push(`- Overall the user picked the supermarket's own brand in ${Math.round((storeBrand / all.length) * 100)}% of ${all.length} choices.`);
    const brandCounts = new Map<string, number>();
    for (const choice of all) {
      const brand = (choice.chosenTitle ?? "").split(" ")[0];
      if (brand && !STORE_BRAND_PREFIX[choice.store]?.test(brand)) brandCounts.set(brand, (brandCounts.get(brand) ?? 0) + 1);
    }
    const favourite = Array.from(brandCounts.entries())
      .filter(([, count]) => count >= 2)
      .sort((a, b) => b[1] - a[1])
      .slice(0, 5)
      .map(([brand, count]) => `${brand} (${count}x)`);
    if (favourite.length) lines.push(`- Brands picked repeatedly: ${favourite.join(", ")}.`);
  }

  const atStore = similar.filter((choice) => choice.store === store);
  if (!lines.length) return "";
  return `${atStore.length ? "" : ""}${lines.join("\n")}`;
}

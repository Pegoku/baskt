import { chatJson } from "@/ai/client";
import { appLanguageName } from "@/db/settings";
import { aiConfigured } from "@/env";
import { normalizeText } from "@/lib/text";

export type DedupeCandidate = {
  id: string;
  text: string;
  quantity: number;
  /** AI's generic name from parsing, when the item has been parsed already. */
  canonical: string | null;
  /** Folder name when the item is a recipe ingredient. */
  folder: string | null;
};

export type DuplicateGroup = {
  items: Array<{ id: string; text: string; quantity: number; folder: string | null }>;
  /** Why these count as the same thing, in the app language. */
  reason: string;
  /** Suggested wording for a merged item. */
  mergedText: string;
  /** Packs wanted in total when the entries are merged. */
  mergedQuantity: number;
  /** True when the texts (or their canonical names) are literally the same; no AI judgement involved. */
  exact: boolean;
};

const SYSTEM = `You tidy a grocery list written by someone living in the Netherlands. Entries may be Dutch, English, Spanish or a mix.
Find entries that mean the SAME product idea and would be bought as the same thing: same product in another language ("melk" / "milk"), plural vs singular, spelling variants, or a generic entry plus a more specific one of the same kind when they clearly refer to one purchase ("eggs" / "10 eggs").
Do NOT group real variants a shopper would buy separately: whole vs semi-skimmed milk, red vs white onions, chicken breast vs chicken thighs, butter vs margarine. When in doubt, do not group.
Return ONLY {"groups":[{"indices":[entry numbers, at least 2], "reason": one short sentence in LANGUAGE saying why they are the same, "mergedText": the best single wording for the merged entry in the user's own words (keep any size or brand mentioned)}]}. Return {"groups":[]} when there are no duplicates. Do not invent entries.`;

type AiGroup = { indices?: unknown; reason?: unknown; mergedText?: unknown };

/** Union-find over candidate indices so exact and AI groupings can overlap safely. */
function unite(parent: number[], a: number, b: number) {
  const root = (x: number): number => (parent[x] === x ? x : (parent[x] = root(parent[x])));
  const ra = root(a);
  const rb = root(b);
  if (ra !== rb) parent[Math.max(ra, rb)] = Math.min(ra, rb);
}

/** Groups entries that spell out the same idea, then asks the AI about the rest. */
export async function findDuplicates(candidates: DedupeCandidate[]): Promise<DuplicateGroup[]> {
  if (candidates.length < 2) return [];
  const parent = candidates.map((_, index) => index);
  const exactPairs = new Set<string>();
  const byKey = new Map<string, number>();
  candidates.forEach((candidate, index) => {
    const keys = [normalizeText(candidate.text), candidate.canonical ? normalizeText(candidate.canonical) : ""].filter(Boolean);
    for (const key of keys) {
      const seen = byKey.get(key);
      if (seen === undefined) byKey.set(key, index);
      else {
        unite(parent, seen, index);
        exactPairs.add(`${Math.min(seen, index)}:${Math.max(seen, index)}`);
      }
    }
  });

  const aiInfo = new Map<number, { reason: string; mergedText: string | null }>();
  if (aiConfigured() && candidates.length <= 120) {
    const listing = candidates
      .map((candidate, index) => `${index + 1}. "${candidate.text}"${candidate.quantity > 1 ? ` x${candidate.quantity}` : ""}${candidate.canonical && normalizeText(candidate.canonical) !== normalizeText(candidate.text) ? ` (means: ${candidate.canonical})` : ""}${candidate.folder ? ` [folder: ${candidate.folder}]` : ""}`)
      .join("\n");
    const raw = await chatJson<{ groups?: AiGroup[] }>(
      [
        { role: "system", content: SYSTEM.replaceAll("LANGUAGE", appLanguageName()) },
        { role: "user", content: listing },
      ],
      { maxTokens: 1200 },
    );
    for (const group of Array.isArray(raw?.groups) ? raw!.groups : []) {
      const indices = Array.isArray(group.indices)
        ? Array.from(new Set(group.indices.filter((value): value is number => Number.isInteger(value) && (value as number) >= 1 && (value as number) <= candidates.length).map((value) => value - 1)))
        : [];
      if (indices.length < 2) continue;
      for (const index of indices.slice(1)) unite(parent, indices[0], index);
      const info = {
        reason: typeof group.reason === "string" && group.reason.trim() ? group.reason.trim() : "",
        mergedText: typeof group.mergedText === "string" && group.mergedText.trim() ? group.mergedText.trim() : null,
      };
      for (const index of indices) aiInfo.set(index, info);
    }
  }

  const root = (x: number): number => (parent[x] === x ? x : (parent[x] = root(parent[x])));
  const clusters = new Map<number, number[]>();
  candidates.forEach((_, index) => {
    const key = root(index);
    clusters.set(key, [...(clusters.get(key) ?? []), index]);
  });
  const groups: DuplicateGroup[] = [];
  for (const indices of clusters.values()) {
    if (indices.length < 2) continue;
    const members = indices.map((index) => candidates[index]);
    // Exact when every member is tied to the cluster by a literal match, not only by the AI's opinion.
    const exact = indices.every((index) => indices.some((other) => other !== index && exactPairs.has(`${Math.min(index, other)}:${Math.max(index, other)}`)));
    const info = indices.map((index) => aiInfo.get(index)).find((entry) => entry?.reason);
    // Prefer the most specific wording (longest) as the merged text when the AI did not suggest one.
    const longest = [...members].sort((a, b) => b.text.length - a.text.length)[0].text;
    groups.push({
      items: members.map((member) => ({ id: member.id, text: member.text, quantity: member.quantity, folder: member.folder })),
      reason: info?.reason || (exact ? "Same entry written twice" : "These look like the same product"),
      mergedText: info?.mergedText ?? longest,
      mergedQuantity: members.reduce((sum, member) => sum + Math.max(1, member.quantity), 0),
      exact,
    });
  }
  return groups;
}

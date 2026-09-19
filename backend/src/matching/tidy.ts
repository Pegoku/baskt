import { chatJson } from "@/ai/client";
import { appLanguageName } from "@/db/settings";
import type { BasketItemRow, BasketMatchRow, ProductRow } from "@/db/schema";
import { aiConfigured } from "@/env";
import { normalizeText } from "@/lib/text";
import { findDuplicates, type DuplicateGroup } from "@/matching/dedupe";
import { listNames, preferredName } from "@/matching/naming";
import type { Deal } from "@/matching/deals";

/** An open item with what the user has already decided about it. */
export type TidyCandidate = {
  row: BasketItemRow;
  /** Folder name when the item is a recipe ingredient. */
  folder: string | null;
  /** Products the user (or their memory) picked, per store. Empty when nothing is pinned yet. */
  picks: Array<{ store: string; productId: string; title: string }>;
  /** How far the matching got, for choosing which duplicate survives a merge. */
  chosenCount: number;
};

export type TidyItemRef = { id: string; text: string; quantity: number; folder: string | null };

export type TidyRename = {
  id: string;
  from: string;
  to: string;
  /** Set when the new wording exists to tell the item apart from a look-alike that stays. */
  reason: string | null;
};

export type TidyMerge = {
  items: TidyItemRef[];
  /** The entry that survives: the one with the user's picks, else the furthest matched, else the oldest. */
  keepId: string;
  /** Final wording for the surviving entry, already in the app language. */
  text: string;
  /** Packs wanted when the quantities are added up ("merge the amounts"). */
  quantity: number;
  /** Packs the surviving entry asks for on its own ("only keep one"). */
  keepQuantity: number;
  reason: string;
};

export type TidyDistinct = {
  items: Array<TidyItemRef & { product: string | null }>;
  reason: string;
};

export type TidyPlan = {
  scanned: number;
  language: string;
  renames: TidyRename[];
  merges: TidyMerge[];
  /** Look-alikes left alone because each has its own chosen product. */
  distinct: TidyDistinct[];
  /** Promotions for entries that stay on the list, best saving first; one per entry and store. */
  deals: Deal[];
};

const RENAME_SYSTEM = `You tidy the wording of a grocery list for someone living in the Netherlands who wants every entry written in LANGUAGE.
For each numbered entry return the clean name it should have in LANGUAGE:
- Translate entries written in another language ("melk" -> the LANGUAGE word for milk).
- Fix misspellings and phonetic spellings, using the meaning given after "means:" when present ("Yorkham" -> the LANGUAGE name for York ham).
- Keep brand names, product names that are proper names, sizes, amounts and units exactly as written. Do not add or drop quantities.
- Keep the user's level of detail: do not make an entry more specific or more generic.
- Entries marked "apart from" name a different product than the entries listed there, and each has its chosen product named. Give them names in LANGUAGE that tell them apart (use the chosen product's brand, flavour or size), while staying short.
- Leave entries that are already correct LANGUAGE unchanged: do not return them.
- When "preferred wordings" are given, they are corrections the user made before: follow them exactly for the same product and use their style for similar ones.
Return ONLY {"entries":[{"i": entry number, "text": new wording}]} with one object per entry that changes. Return {"entries":[]} when nothing changes. Never invent entries.`;

type AiEntry = { i?: unknown; text?: unknown };

function ref(candidate: TidyCandidate): TidyItemRef {
  return { id: candidate.row.id, text: candidate.row.text, quantity: candidate.row.quantity, folder: candidate.folder };
}

/** Which user picks an item carries, as one comparable key; equal keys mean the same purchase. */
function pickKey(candidate: TidyCandidate) {
  return candidate.picks.map((pick) => `${pick.store}:${pick.productId}`).sort().join("|");
}

/** The user's picks per store, ignoring choices the AI made on its own. */
export function toCandidate(row: BasketItemRow, matches: BasketMatchRow[], products: Map<string, ProductRow>, folder: string | null): TidyCandidate {
  const picks = matches
    .filter((match) => match.status === "CHOSEN" && match.chosenProductId && (match.chosenBy === "USER" || match.chosenBy === "MEMORY"))
    .map((match) => ({ store: match.store, productId: match.chosenProductId!, title: products.get(match.chosenProductId!)?.title ?? match.chosenProductId! }));
  return { row, folder, picks, chosenCount: matches.filter((match) => match.status === "CHOSEN").length };
}

function survivor(members: TidyCandidate[]) {
  return [...members].sort((a, b) => b.picks.length - a.picks.length || b.chosenCount - a.chosenCount || a.row.createdAt - b.row.createdAt)[0];
}

/**
 * Splits duplicate groups into merges and look-alikes to keep apart. Two entries are only merged when at
 * most one of them carries the user's own product picks (or they all picked the very same products), so no
 * decision is ever thrown away. Exported for tests; it needs no AI.
 */
export function splitGroups(groups: DuplicateGroup[], byId: Map<string, TidyCandidate>) {
  const merges: Array<TidyMerge & { members: TidyCandidate[] }> = [];
  const distinct: Array<TidyDistinct & { members: TidyCandidate[] }> = [];
  for (const group of groups) {
    const members = group.items.map((item) => byId.get(item.id)).filter((member): member is TidyCandidate => Boolean(member));
    if (members.length < 2) continue;
    const pinned = members.filter((member) => member.picks.length);
    const purchases = new Set(pinned.map(pickKey));
    if (purchases.size > 1) {
      distinct.push({ members, reason: group.reason, items: members.map((member) => ({ ...ref(member), product: member.picks.map((pick) => pick.title).join(" / ") || null })) });
      continue;
    }
    const keep = survivor(members);
    merges.push({
      members,
      items: members.map(ref),
      keepId: keep.row.id,
      text: group.mergedText,
      quantity: members.reduce((sum, member) => sum + Math.max(1, member.row.quantity), 0),
      keepQuantity: Math.max(1, keep.row.quantity),
      reason: group.reason,
    });
  }
  return { merges, distinct };
}

/**
 * The "magic wand": one pass that merges duplicates nobody has decided about, keeps look-alikes with their
 * own picks apart, and rewrites every surviving entry in the app language. Nothing is changed here; the app
 * shows the plan and applies what the user confirms.
 */
export async function planTidy(candidates: TidyCandidate[], deals: Deal[] = []): Promise<TidyPlan> {
  const language = appLanguageName();
  const byId = new Map(candidates.map((candidate) => [candidate.row.id, candidate]));
  const groups = await findDuplicates(candidates.map((candidate) => ({ id: candidate.row.id, text: candidate.row.text, quantity: candidate.row.quantity, canonical: candidate.row.parsedJson?.canonicalName ?? null, folder: candidate.folder })));
  const { merges, distinct } = splitGroups(groups, byId);
  const removed = new Set(merges.flatMap((merge) => merge.members.map((member) => member.row.id)).filter((id) => !merges.some((merge) => merge.keepId === id)));

  // Everything that will still be on the list, with the wording it will have before renaming.
  const mergedText = new Map(merges.map((merge) => [merge.keepId, merge.text]));
  const apart = new Map<string, TidyDistinct["items"]>();
  for (const entry of distinct) for (const item of entry.items) apart.set(item.id, entry.items.filter((other) => other.id !== item.id));
  const survivors = candidates.filter((candidate) => !removed.has(candidate.row.id));
  const renamed = await unifyNames(
    survivors.map((candidate) => ({
      id: candidate.row.id,
      text: mergedText.get(candidate.row.id) ?? candidate.row.text,
      canonical: candidate.row.parsedJson?.canonicalName ?? null,
      product: apart.has(candidate.row.id) ? candidate.picks.map((pick) => pick.title).join(" / ") || null : null,
      apartFrom: apart.get(candidate.row.id)?.map((other) => other.text) ?? null,
    })),
    language,
  );

  const renames: TidyRename[] = [];
  for (const candidate of survivors) {
    const to = renamed.get(candidate.row.id);
    if (!to) continue;
    if (mergedText.has(candidate.row.id)) {
      mergedText.set(candidate.row.id, to);
      continue;
    }
    renames.push({ id: candidate.row.id, from: candidate.row.text, to, reason: apart.has(candidate.row.id) ? `Kept apart from ${apart.get(candidate.row.id)!.map((other) => `“${other.text}”`).join(", ")}` : null });
  }
  return {
    scanned: candidates.length,
    language,
    renames,
    merges: merges.map(({ members: _members, ...merge }) => ({ ...merge, text: mergedText.get(merge.keepId) ?? merge.text })),
    distinct: distinct.map(({ members: _members, ...entry }) => entry),
    deals: pickDeals(deals, removed),
  };
}

/** One promotion per surviving entry and store, skipping products that are already the pick. Exported for tests. */
export function pickDeals(deals: Deal[], removed: Set<string>) {
  const seen = new Set<string>();
  const out: Deal[] = [];
  for (const deal of deals) {
    const key = `${deal.itemId}:${deal.store}`;
    if (removed.has(deal.itemId) || deal.currentProductId === deal.product.id || seen.has(key)) continue;
    seen.add(key);
    out.push(deal);
  }
  return out;
}

type NameInput = { id: string; text: string; canonical: string | null; product: string | null; apartFrom: string[] | null };

/**
 * The app-language wording of each entry; returns only the ones that change. Wordings the user corrected
 * before are applied directly, and shown to the model as examples for the rest.
 */
async function unifyNames(entries: NameInput[], language: string): Promise<Map<string, string>> {
  const out = new Map<string, string>();
  const remembered = listNames(500);
  const pending: NameInput[] = [];
  for (const entry of entries) {
    const preferred = preferredName(entry.text, entry.canonical, remembered);
    if (preferred && !entry.apartFrom?.length) {
      if (preferred !== entry.text) out.set(entry.id, preferred);
    } else pending.push(entry);
  }
  entries = pending;
  if (!aiConfigured() || !entries.length || entries.length > 150) return out;
  const examples = remembered.slice(0, 30).map((row) => `"${row.source}" -> "${row.preferred}"`);
  const listing = entries
    .map((entry, index) => {
      const notes: string[] = [];
      if (entry.canonical && normalizeText(entry.canonical) !== normalizeText(entry.text)) notes.push(`means: ${entry.canonical}`);
      if (entry.apartFrom?.length) notes.push(`apart from ${entry.apartFrom.map((text) => `"${text}"`).join(", ")}${entry.product ? `; chosen product: "${entry.product}"` : ""}`);
      return `${index + 1}. "${entry.text}"${notes.length ? ` (${notes.join("; ")})` : ""}`;
    })
    .join("\n");
  const raw = await chatJson<{ entries?: AiEntry[] }>(
    [
      { role: "system", content: RENAME_SYSTEM.replaceAll("LANGUAGE", language) },
      { role: "user", content: examples.length ? `Preferred wordings:\n${examples.join("\n")}\n\nEntries:\n${listing}` : listing },
    ],
    { maxTokens: 2000 },
  );
  for (const entry of Array.isArray(raw?.entries) ? raw!.entries : []) {
    const index = typeof entry.i === "number" && Number.isInteger(entry.i) ? entry.i - 1 : -1;
    const text = typeof entry.text === "string" ? entry.text.replace(/\s+/g, " ").trim() : "";
    const source = entries[index];
    if (!source || !text || text === source.text) continue;
    // A rename should read like a list entry, not an essay: reject runaway rewrites.
    if (text.length > Math.max(40, source.text.length * 3)) continue;
    out.set(source.id, text);
  }
  return out;
}

import { desc, eq } from "drizzle-orm";
import { db, newId, now } from "@/db";
import { namingMemory, type NamingMemoryRow } from "@/db/schema";
import { appLanguage } from "@/db/settings";
import { normalizeText } from "@/lib/text";

/**
 * Remembers how the user wants an entry worded. Later tidy passes and translations use the preferred
 * wording instead of asking the model again. A new preference for the same source replaces the old one.
 */
export function rememberName(input: { source: string; canonical: string | null; preferred: string }) {
  const keys = Array.from(new Set([normalizeText(input.source), input.canonical ? normalizeText(input.canonical) : ""].filter(Boolean)));
  const preferred = input.preferred.replace(/\s+/g, " ").trim();
  if (!keys.length || !preferred) return null;
  const database = db();
  for (const row of listNames(500)) {
    if (row.keys.some((key) => keys.includes(key)) || normalizeText(row.preferred) === keys[0]) database.delete(namingMemory).where(eq(namingMemory.id, row.id)).run();
  }
  const row: NamingMemoryRow = { id: newId(), source: input.source.trim(), keys, preferred, language: appLanguage(), createdAt: now() };
  database.insert(namingMemory).values(row).run();
  return row;
}

export function listNames(limit = 200) {
  return db().select().from(namingMemory).orderBy(desc(namingMemory.createdAt)).limit(limit).all();
}

export function deleteName(id: string) {
  return db().delete(namingMemory).where(eq(namingMemory.id, id)).returning({ id: namingMemory.id }).all().length > 0;
}

/** The wording the user asked for, when this text (or its generic name) was corrected before. */
export function preferredName(text: string, canonical: string | null, rows: NamingMemoryRow[] = listNames(500)): string | null {
  const wanted = [normalizeText(text), canonical ? normalizeText(canonical) : ""].filter(Boolean);
  const hit = rows.find((row) => row.keys.some((key) => wanted.includes(key)) || wanted.includes(normalizeText(row.preferred)));
  return hit ? hit.preferred : null;
}

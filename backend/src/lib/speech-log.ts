import { appendFileSync, mkdirSync, renameSync, existsSync, statSync } from "node:fs";
import { dirname, join } from "node:path";
import { env } from "@/env";

export const speechLogPath = process.env.SPEECH_LOG_PATH ?? join(dirname(env.databasePath), "logs", "speech.jsonl");
/** Metadata only: never log credentials, spoken text, or signed audio URLs. */
export function speechLog(event: string, fields: Record<string, string | number | boolean | null | undefined>) {
  if (env.databasePath === ":memory:") return;
  try {
    mkdirSync(dirname(speechLogPath), { recursive: true, mode: 0o700 });
    if (existsSync(speechLogPath) && statSync(speechLogPath).size > 5 * 1024 * 1024) renameSync(speechLogPath, speechLogPath + ".1");
    appendFileSync(speechLogPath, JSON.stringify({ time: new Date().toISOString(), event, ...fields }) + "\n", { mode: 0o600 });
  } catch { console.warn("[speech] Could not write diagnostic log"); }
}

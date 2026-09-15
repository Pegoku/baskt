import { existsSync, readFileSync, watchFile, unwatchFile } from "node:fs";
import { speechLogPath } from "@/lib/speech-log";

const args = process.argv.slice(2);
if (args.includes("--help")) {
  console.log("bun run logs [--follow] [--lines 50] [--remote]\nSpeech metadata: model, voice, language, language boost, prediction ID, cache and timing. No text or secrets.");
  process.exit(0);
}
const countAt = args.indexOf("--lines");
const count = countAt < 0 ? 50 : Number(args[countAt + 1]);
if (!Number.isInteger(count) || count < 1 || count > 10000) throw new Error("--lines must be between 1 and 10000");
if (args.includes("--remote")) {
  if (!process.env.REPLICATE_API_TOKEN) throw new Error("REPLICATE_API_TOKEN is not configured");
  const response = await fetch("https://ai.hackclub.com/proxy/v1/replicate/predictions", {
    headers: { Authorization: `Bearer ${process.env.REPLICATE_API_TOKEN}` }, signal: AbortSignal.timeout(30000),
  });
  if (!response.ok) throw new Error("Could not read provider history: HTTP " + response.status);
  const data = await response.json() as { results?: Array<{ id: string; model: string; status: string; created_at: string; input?: Record<string, unknown> }> };
  for (const item of (data.results ?? []).filter((item) => ["minimax/speech-2.8-turbo", "qwen/qwen3-tts", "inworld/realtime-tts-1.5-mini", "inworld/realtime-tts-1.5-max"].includes(item.model)).slice(0, count)) {
    console.log(JSON.stringify({ time: item.created_at, model: item.model, predictionId: item.id, status: item.status,
      voice: item.input?.voice_id ?? item.input?.speaker, languageBoost: item.input?.language_boost, language: item.input?.language }));
  }
  process.exit(0);
}
let previous = "";
function read(initial = false) {
  const current = existsSync(speechLogPath) ? readFileSync(speechLogPath, "utf8") : "";
  const added = initial ? current.trimEnd().split("\n").slice(-count).join("\n") : current.startsWith(previous) ? current.slice(previous.length) : current;
  if (added.trim()) console.log(added.trimEnd());
  previous = current;
}
console.log("Speech log: " + speechLogPath);
read(true);
if (args.includes("--follow")) {
  watchFile(speechLogPath, { interval: 500 }, () => read());
  process.on("SIGINT", () => { unwatchFile(speechLogPath); process.exit(0); });
}

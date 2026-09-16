import { Database } from "bun:sqlite";
import { mkdir } from "node:fs/promises";
import { resolve, join } from "node:path";
import { env } from "@/env";
import { minimaxLanguageBoost, speechCatalogue, speechSamples } from "@/ai/speak";
import { sha256 } from "@/lib/text";

const args = process.argv.slice(2);
if (args.includes("--help")) {
  console.log("bun run tts:export [language=es] [directory=data/tts-export/<language>]\nExports cached MiniMax and Qwen3 previews as MP3. Qwen WAV is converted locally with ffmpeg. No generation/API calls.");
  process.exit(0);
}
const language = args[0] ?? "es";
const text = speechSamples[language];
if (!text) throw new Error("No preview sample for " + language);
const directory = resolve(args[1] ?? join("data", "tts-export", language));
const database = new Database(env.databasePath, { readonly: true });
const query = database.query("SELECT value FROM upstream_cache WHERE key = ?");
let exported = 0;
let existing = 0;
const missing: string[] = [];
const failed: string[] = [];
try {
  for (const model of speechCatalogue(language).filter((item) => item.id === "qwen/qwen3-tts" || item.id.startsWith("minimax/"))) {
    for (const voice of model.voices) {
      // Match synthesize's cache identity; read-only, including retained expired samples.
      const boost = model.id.startsWith("minimax/") ? minimaxLanguageBoost[language] : undefined;
      const key = "speech-audio:v1:" + await sha256(JSON.stringify([model.id, voice, language, text, ...(boost ? [boost] : [])]));
      const row = query.get(key) as { value: string } | null;
      if (!row) { missing.push(model.id + "/" + voice); continue; }
      const value = JSON.parse(row.value);
      if (typeof value !== "string") { failed.push(model.id + "/" + voice); continue; }
      const bytes = Buffer.from(value, "base64");
      const folder = join(directory, model.id.replaceAll("/", "--"));
      await mkdir(folder, { recursive: true });
      const file = join(folder, voice.replace(/[^a-zA-Z0-9_-]/g, "_") + ".mp3");
      if (await Bun.file(file).exists()) { existing++; continue; }
      if (bytes.subarray(0, 4).toString() === "RIFF") {
        if (!Bun.which("ffmpeg")) throw new Error("Install ffmpeg to convert Qwen WAV previews to MP3");
        const process = Bun.spawn(["ffmpeg", "-v", "error", "-n", "-i", "pipe:0", "-codec:a", "libmp3lame", "-q:a", "2", file],
          { stdin: new Blob([bytes]), stdout: "ignore", stderr: "pipe" });
        const error = await new Response(process.stderr).text();
        if (await process.exited !== 0) { failed.push(voice + ": " + error.trim()); continue; }
      } else {
        await Bun.write(file, bytes);
      }
      exported++;
      console.log(file);
    }
  }
} finally { database.close(); }
console.log(JSON.stringify({ directory, exported, existing, missing: missing.length, failed }, null, 2));
if (missing.length) console.log("Uncached voices:\n" + missing.join("\n"));
if (failed.length) process.exitCode = 1;

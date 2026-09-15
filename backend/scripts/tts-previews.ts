import { speechCatalogue, speechSamples, synthesize } from "@/ai/speak";

const language = process.argv[2] ?? "es";
const sample = speechSamples[language];
if (!sample) throw new Error("No preview sample for " + language);
const jobs = speechCatalogue(language).filter((model) => model.id === "qwen/qwen3-tts" || model.id.startsWith("minimax/"))
  .flatMap((model) => model.voices.map((voice) => ({ model: model.id, voice })));
const failures: string[] = [];
let stopped = false;
let next = 0;
let completed = 0;
console.log(`Preparing ${jobs.length} ${language} previews (cached clips are reused).`);
await Promise.all(Array.from({ length: 2 }, async () => {
  while (!stopped && next < jobs.length) {
    const job = jobs[next++];
    let ok = false;
    for (let attempt = 1; attempt <= 6; attempt++) {
      try {
        const bytes = await synthesize(sample, job.model, job.voice, language);
        console.log(`[${++completed}/${jobs.length}] ${job.model} ${job.voice}: ${bytes.length} bytes cached`);
        ok = true;
        break;
      } catch (error) {
        if (error instanceof Error && error.message === "Speech provider daily spending limit reached") {
          console.warn("Daily spending limit reached. Rerun after the limit resets or is increased; cached previews will be reused.");
          stopped = true;
          break;
        }
        console.warn(`${job.voice}: attempt ${attempt} failed: ${error instanceof Error ? error.message : "unknown error"}`);
        if (attempt < 6) await new Promise((resolve) => setTimeout(resolve, error instanceof Error && error.message.includes("(429)") ? 30000 * attempt : 3000 * attempt));
      }
    }
    if (!ok) failures.push(job.model + " " + job.voice);
  }
}));
console.log(JSON.stringify({ language, total: jobs.length, completed, remaining: jobs.length - completed, stopped, failures }));
if (failures.length || stopped) process.exitCode = 1;

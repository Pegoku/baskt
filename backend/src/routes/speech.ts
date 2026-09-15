import type { Context } from "hono";
import { sttConfigured, transcribeAudio } from "@/ai/transcribe";
import { appLanguage } from "@/db/settings";

/** Shared upload validation and STT for any voice entry point. */
export async function speechTranscript(c: Context): Promise<string | Response> {
  if (!sttConfigured()) return c.json({ error: { code: "STT_UNAVAILABLE", message: "no speech-to-text provider is configured" } }, 503);
  const form = await c.req.formData().catch(() => null);
  const file = form?.get("audio");
  if (!(file instanceof File)) return c.json({ error: { code: "BAD_REQUEST", message: "an audio file is required" } }, 400);
  if (file.size > 25 * 1024 * 1024) return c.json({ error: { code: "BAD_REQUEST", message: "audio is larger than 25 MB" } }, 413);
  const asked = form?.get("language");
  const language = typeof asked === "string" && /^[a-z]{2,3}$/i.test(asked.trim()) ? asked.trim().toLowerCase() : appLanguage();
  const transcript = await transcribeAudio(
    { bytes: new Uint8Array(await file.arrayBuffer()), filename: file.name || "dictation.m4a", mime: file.type || "audio/m4a" },
    language,
  );
  if (transcript === null) return c.json({ error: { code: "STT_FAILED", message: "no speech-to-text provider answered" } }, 502);
  return transcript;
}

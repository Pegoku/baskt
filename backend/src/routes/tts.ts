import { Hono } from "hono";
import { speechCatalogue, speechConfigured, speechSamples, speechChoice, synthesize } from "@/ai/speak";

export const tts = new Hono();
tts.get("/voices", (c) => c.json({ configured: speechConfigured(), models: speechCatalogue(c.req.query("language")) }));
tts.post("/", async (c) => {
  const body = await c.req.json().catch(() => null);
  if (!body || typeof body.model !== "string" || typeof body.voice !== "string" || typeof body.language !== "string" ||
      !speechChoice(body.model, body.voice, body.language) ||
      (body.preview !== true && (typeof body.text !== "string" || !body.text.trim() || body.text.length > 1800)))
    return c.json({ error: { code: "BAD_REQUEST", message: "Invalid speech request or unsupported language" } }, 400);
  const text = body.preview === true ? speechSamples[body.language] ?? speechSamples.en : body.text.trim();
  try {
    const bytes = await synthesize(text, body.model, body.voice, body.language);
    return new Response(new Uint8Array(bytes).buffer, { headers: { "Content-Type": body.model === "qwen/qwen3-tts" ? "audio/wav" : "audio/mpeg", "Cache-Control": "private, max-age=86400" } });
  } catch {
    return c.json({ error: { code: "SPEECH_UNAVAILABLE", message: "Speech is unavailable. Please try again." } }, 503);
  }
});

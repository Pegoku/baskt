import { attemptOrder, cooldownFor, noteCall, noteFailure, noteUsage } from "@/ai/pool";
import { env } from "@/env";

export type Audio = { bytes: Uint8Array; filename: string; mime: string };

/** Whether the server can transcribe (otherwise the app falls back to on-device dictation). */
export function sttConfigured() {
  return env.ai.stt.length > 0;
}

/**
 * Sends recorded audio to an OpenAI-compatible /audio/transcriptions endpoint (Groq's whisper-large-v3 is
 * far more accurate than on-device dictation for mixed-language grocery talk). Returns null when every
 * provider in the pool fails, so the caller can ask the phone to do it instead.
 */
export async function transcribeAudio(audio: Audio, language?: string): Promise<string | null> {
  const order = attemptOrder("stt");
  for (const target of order) {
    noteCall(target.id);
    const started = performance.now();
    try {
      const form = new FormData();
      form.append("file", new Blob([audio.bytes as BlobPart], { type: audio.mime }), audio.filename);
      form.append("model", target.model);
      form.append("response_format", "json");
      form.append("temperature", "0");
      // Whisper guesses the language per utterance; telling it the app language stops Dutch turning into German.
      if (language) form.append("language", language);
      const response = await fetch(`${target.baseUrl}/audio/transcriptions`, {
        method: "POST",
        headers: { authorization: `Bearer ${target.apiKey}` },
        body: form,
        signal: AbortSignal.timeout(60_000),
      });
      if (!response.ok) {
        const text = await response.text();
        noteFailure(target.id, `HTTP ${response.status}: ${text.slice(0, 120)}`, cooldownFor(response.status, response.headers.get("retry-after")));
        console.warn(`[stt] ${target.id} HTTP ${response.status}: ${text.slice(0, 200)}`);
        continue;
      }
      const payload = (await response.json()) as { text?: string };
      const text = payload.text?.trim() ?? "";
      noteUsage(target.id, 0, 0);
      console.log(`[stt] ${target.id} ${target.model} ${audio.bytes.length} bytes, ${Math.round(performance.now() - started)} ms`);
      return text;
    } catch (error) {
      const message = error instanceof Error ? error.message : String(error);
      noteFailure(target.id, message, 15_000);
      console.warn(`[stt] ${target.id} failed: ${message}`);
    }
  }
  return null;
}

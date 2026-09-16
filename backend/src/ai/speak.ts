import { attemptOrder, cooldownFor, noteCall, noteFailure, noteUsage } from "@/ai/pool";
import { aiPool, type AiProvider } from "@/env";
import { speechLog } from "@/lib/speech-log";
import { minimaxVoices } from "@/ai/minimax-voices";
import { cachedUpstream } from "@/lib/cache";
import { sha256 } from "@/lib/text";

export const speechModels = [
  { id: "inworld/realtime-tts-1.5-mini", name: "Inworld Mini", description: "Fast · lowest cost", voices: ["Ashley", "Dennis", "Alex"], languages: ["en", "es", "fr", "de", "nl", "it", "pt", "zh", "ja", "ko", "ru", "ar", "pl", "he", "hi"] },
  { id: "inworld/realtime-tts-1.5-max", name: "Inworld Max", description: "More expressive", voices: ["Ashley", "Dennis", "Alex"], languages: ["en", "es", "fr", "de", "nl", "it", "pt", "zh", "ja", "ko", "ru", "ar", "pl", "he", "hi"] },
  { id: "minimax/speech-2.8-turbo", name: "MiniMax Turbo", description: "Natural multilingual voices", voices: minimaxVoices.map((voice) => voice.id), languages: [...new Set(minimaxVoices.map((voice) => voice.language))] },
  { id: "qwen/qwen3-tts", name: "Qwen3 TTS", description: "Expressive multilingual preset voices", voices: ["Aiden", "Dylan", "Eric", "Ono_anna", "Ryan", "Serena", "Sohee", "Uncle_fu", "Vivian"], languages: ["zh", "en", "ja", "ko", "fr", "de", "it", "es", "pt", "ru"] },
];
export function speechCatalogue(language?: string) {
  return speechModels.map((model) => {
    const voices = model.id.startsWith("minimax/")
      ? minimaxVoices.filter((voice) => !language || voice.language === language).map((voice) => voice.id)
      : model.voices;
    return { ...model, voices, voiceNames: Object.fromEntries(minimaxVoices.filter((voice) => voices.includes(voice.id)).map((voice) => [voice.id, voice.name])) };
  }).filter((model) => model.voices.length > 0 && (!language || model.languages.includes(language)));
}
// Replicate's MiniMax schema expects language names, not ISO codes.
export const minimaxLanguageBoost: Record<string, string> = {
  en: "English", zh: "Chinese", yue: "Cantonese", ja: "Japanese", ko: "Korean",
  es: "Spanish", pt: "Portuguese", id: "Indonesian", ru: "Russian", fr: "French",
  it: "Italian", th: "Thai", pl: "Polish", ro: "Romanian", de: "German",
  el: "Greek", cs: "Czech", fi: "Finnish", hi: "Hindi", nl: "Dutch",
  ar: "Arabic", tr: "Turkish", uk: "Ukrainian", vi: "Vietnamese",
};
/** Whether any speech provider is configured (REPLICATE_API_TOKEN, REPLICATE_2, ...). */
export function speechConfigured() {
  return aiPool("replicate").length > 0;
}

/** A failure of this account — the same request is worth trying on the next token in the pool. */
class ProviderError extends Error {
  constructor(message: string, readonly cooldownMs: number) {
    super(message);
  }
}
export function speechChoice(modelId: string, voice: string, language: string) {
  const model = speechModels.find((item) => item.id === modelId);
  if (!model || !model.voices.includes(voice) || !model.languages.includes(language)) return null;
  if (modelId.startsWith("minimax/") && !minimaxVoices.some((item) => item.id === voice && item.language === language)) return null;
  return model;
}
export const speechSamples: Record<string, string> = {
  en: "Hello! I'm your shopping assistant. What would you like to cook today?",
  es: "¡Hola! Soy tu asistente de compras. ¿Qué te gustaría cocinar hoy?",
  ca: "Hola! Soc el teu assistent de compres. Què t'agradaria cuinar avui?",
  nl: "Hallo! Ik ben je boodschappenassistent. Wat wil je vandaag koken?",
  de: "Hallo! Ich bin dein Einkaufsassistent. Was möchtest du heute kochen?",
  fr: "Bonjour ! Je suis votre assistant de courses. Que souhaitez-vous cuisiner aujourd'hui ?",
};
type Prediction = { id?: string; status?: string; output?: unknown; input?: { language_boost?: string; language?: string } };
async function prediction(target: AiProvider, path: string, body?: unknown): Promise<Prediction> {
  let response: Response;
  try {
    response = await fetch(target.baseUrl + path, {
      method: body ? "POST" : "GET",
      headers: { Authorization: `Bearer ${target.apiKey}`, "Content-Type": "application/json", Prefer: "wait=20" },
      ...(body ? { body: JSON.stringify(body) } : {}),
      signal: AbortSignal.timeout(30000),
    });
  } catch (error) {
    throw new ProviderError(`Speech provider unreachable (${error instanceof Error ? error.message : error})`, 15_000);
  }
  if (!response.ok) {
    // Classify the known spending cap without exposing arbitrary provider response content.
    const detail = response.status === 429 ? await response.text() : "";
    // The cap resets daily, so park this token for a while instead of asking it again every minute.
    if (/daily spending limit/i.test(detail)) throw new ProviderError("Speech provider daily spending limit reached", 30 * 60_000);
    throw new ProviderError(`Speech provider unavailable (${response.status})`, cooldownFor(response.status, response.headers.get("retry-after")));
  }
  return response.json() as Promise<Prediction>;
}
export function speechOutput(value: unknown): string | null {
  const raw = typeof value === "string" ? value : Array.isArray(value) ? value[0] :
    value && typeof value === "object" ? (value as Record<string, unknown>).audio : null;
  if (typeof raw !== "string") return null;
  try {
    const url = new URL(raw);
    return url.protocol === "https:" && !url.username && !url.password && !url.port &&
      (url.hostname === "replicate.delivery" || url.hostname.endsWith(".replicate.delivery") ||
       (url.hostname === "ai.hackclub.com" && url.pathname.startsWith("/proxy/"))) ? raw : null;
  } catch { return null; }
}
export function speechInput(text: string, model: string, voice: string, language: string) {
  if (!speechChoice(model, voice, language)) throw new Error("Unsupported speech voice or language");
  if (model === "qwen/qwen3-tts") return { text, mode: "custom_voice", speaker: voice, language: minimaxLanguageBoost[language] };
  return { text, voice_id: voice, audio_format: "mp3",
    ...(model.startsWith("minimax/") ? { language_boost: minimaxLanguageBoost[language] } : {}) };
}
/** Persist audio bytes, since Replicate output links expire. Coalesce simultaneous cache misses. */
export async function synthesize(text: string, model: string, voice: string, language: string): Promise<Uint8Array> {
  if (!speechConfigured()) throw new Error("Speech is not configured");
  const minimax = model.startsWith("minimax/");
  const boost = minimax ? minimaxLanguageBoost[language] : undefined;
  if (minimax && !boost) throw new Error("Unsupported MiniMax speech language");
  // Keep other models' cached audio; regenerate MiniMax clips made with automatic detection.
  const key = "speech-audio:v1:" + await sha256(JSON.stringify([model, voice, language, text, ...(boost ? [boost] : [])]));
  const input = speechInput(text, model, voice, language);
  const metadata = { model, voice, language, languageBoost: boost, providerLanguage: input.language, characters: text.length };
  const started = Date.now();
  let generated = false;
  speechLog("request", metadata);
  try {
    const audio = await cachedUpstream(key, 90 * 86400000, async () => {
      generated = true;
      speechLog("generation", metadata);
      // A prediction id only exists on the account that created it, so one token runs the whole
      // generation; only a provider-level failure (cap reached, endpoint down) moves to the next.
      return await onAnyProvider(async (target) => {
        let result = await prediction(target, `/models/${model}/predictions`, { input });
        speechLog("prediction", { ...metadata, provider: target.id, predictionId: result.id, status: result.status,
          confirmedLanguageBoost: result.input?.language_boost, confirmedLanguage: result.input?.language });
        const deadline = Date.now() + 90000;
        while (result.status !== "succeeded") {
          if (["failed", "canceled"].includes(result.status ?? "") || !result.id || !/^[a-zA-Z0-9_-]+$/.test(result.id)) throw new Error("Speech generation failed");
          if (Date.now() > deadline) throw new Error("Speech generation timed out");
          await new Promise((resolve) => setTimeout(resolve, 700));
          result = await prediction(target, "/predictions/" + result.id);
        }
        speechLog("generated", { ...metadata, provider: target.id, predictionId: result.id, status: result.status,
          confirmedLanguageBoost: result.input?.language_boost, confirmedLanguage: result.input?.language });
        const url = speechOutput(result.output);
        if (!url) throw new Error("Speech provider returned no audio");
        const response = await fetch(url, { redirect: "error", signal: AbortSignal.timeout(30000),
          headers: new URL(url).hostname === "ai.hackclub.com" ? { Authorization: `Bearer ${target.apiKey}` } : {} });
        if (!response.ok) throw new Error("Could not download speech");
        const bytes = new Uint8Array(await response.arrayBuffer());
        if (!bytes.length || bytes.length > 10 * 1024 * 1024) throw new Error("Invalid speech audio");
        noteUsage(target.id, 0, 0);
        return Buffer.from(bytes).toString("base64");
      });
    });
    const bytes = Buffer.from(audio, "base64");
    speechLog("ready", { ...metadata, source: generated ? "generated" : "cache-or-shared", bytes: bytes.length, elapsedMs: Date.now() - started });
    return bytes;
  } catch (error) {
    speechLog("failed", { ...metadata, reason: error instanceof Error && error.message === "Speech provider daily spending limit reached" ? "daily-spending-limit" : "provider-or-audio-error", elapsedMs: Date.now() - started });
    throw error;
  }
}

/**
 * Runs one generation on the speech pool: best priority first, round-robin between equal ones. Only a
 * ProviderError moves on — a rejected voice or a failed prediction would fail the same way everywhere and
 * generating it twice costs money.
 */
async function onAnyProvider<T>(run: (target: AiProvider) => Promise<T>): Promise<T> {
  const order = attemptOrder("replicate");
  let last: unknown = new Error("Speech is not configured");
  for (const target of order) {
    noteCall(target.id);
    try {
      return await run(target);
    } catch (error) {
      if (!(error instanceof ProviderError)) {
        noteFailure(target.id, error instanceof Error ? error.message : String(error));
        throw error;
      }
      noteFailure(target.id, error.message, error.cooldownMs);
      last = error;
    }
  }
  throw last;
}

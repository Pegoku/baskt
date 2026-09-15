import { cachedUpstream } from "@/lib/cache";
import { sha256 } from "@/lib/text";

export const speechModels = [
  { id: "inworld/realtime-tts-1.5-mini", name: "Inworld Mini", description: "Fast · lowest cost", voices: ["Ashley", "Dennis", "Alex"], languages: ["en", "es", "fr", "de", "nl", "it", "pt", "zh", "ja", "ko", "ru", "ar", "pl", "he", "hi"] },
  { id: "inworld/realtime-tts-1.5-max", name: "Inworld Max", description: "More expressive", voices: ["Ashley", "Dennis", "Alex"], languages: ["en", "es", "fr", "de", "nl", "it", "pt", "zh", "ja", "ko", "ru", "ar", "pl", "he", "hi"] },
  { id: "minimax/speech-2.8-turbo", name: "MiniMax Turbo", description: "Widest language support · includes Catalan", voices: ["English_Wiselady", "English_Deep-VoicedGentleman"], languages: ["en", "es", "ca", "fr", "de", "nl", "it", "pt", "zh", "ja", "ko", "ru", "ar", "pl", "he", "hi", "tr", "uk", "vi", "id", "th", "ro", "el", "cs", "fi", "bg", "da", "ms", "fa", "sk", "sv", "hr", "tl", "hu", "no", "sl", "nn", "ta", "af"] },
];
const base = "https://ai.hackclub.com/proxy/v1/replicate";
export function speechChoice(modelId: string, voice: string, language: string) {
  const model = speechModels.find((item) => item.id === modelId);
  if (!model || !model.voices.includes(voice) || !model.languages.includes(language)) return null;
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
type Prediction = { id?: string; status?: string; output?: unknown };
async function prediction(path: string, body?: unknown): Promise<Prediction> {
  const response = await fetch(base + path, {
    method: body ? "POST" : "GET",
    headers: { Authorization: `Bearer ${process.env.REPLICATE_API_TOKEN}`, "Content-Type": "application/json", Prefer: "wait=20" },
    ...(body ? { body: JSON.stringify(body) } : {}),
    signal: AbortSignal.timeout(30000),
  });
  if (!response.ok) throw new Error(`Speech provider unavailable (${response.status})`);
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
/** Persist audio bytes, since Replicate output links expire. Coalesce simultaneous cache misses. */
export async function synthesize(text: string, model: string, voice: string, language: string): Promise<Uint8Array> {
  if (!process.env.REPLICATE_API_TOKEN) throw new Error("Speech is not configured");
  const key = "speech-audio:v1:" + await sha256(JSON.stringify([model, voice, language, text]));
  const audio = await cachedUpstream(key, 90 * 86400000, async () => {
    let result = await prediction(`/models/${model}/predictions`, { input: {
      text, voice_id: voice, audio_format: "mp3",
      ...(model.startsWith("minimax/") ? { language_boost: "Automatic" } : {}),
    } });
    const deadline = Date.now() + 90000;
    while (result.status !== "succeeded") {
      if (["failed", "canceled"].includes(result.status ?? "") || !result.id || !/^[a-zA-Z0-9_-]+$/.test(result.id)) throw new Error("Speech generation failed");
      if (Date.now() > deadline) throw new Error("Speech generation timed out");
      await new Promise((resolve) => setTimeout(resolve, 700));
      result = await prediction("/predictions/" + result.id);
    }
    const url = speechOutput(result.output);
    if (!url) throw new Error("Speech provider returned no audio");
    const response = await fetch(url, { redirect: "error", signal: AbortSignal.timeout(30000),
      headers: new URL(url).hostname === "ai.hackclub.com" ? { Authorization: `Bearer ${process.env.REPLICATE_API_TOKEN}` } : {} });
    if (!response.ok) throw new Error("Could not download speech");
    const bytes = new Uint8Array(await response.arrayBuffer());
    if (!bytes.length || bytes.length > 10 * 1024 * 1024) throw new Error("Invalid speech audio");
    return Buffer.from(bytes).toString("base64");
  });
  return Buffer.from(audio, "base64");
}

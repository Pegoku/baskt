function num(value: string | undefined, fallback: number) {
  const parsed = Number(value);
  return Number.isFinite(parsed) && parsed > 0 ? parsed : fallback;
}

function list(value: string | undefined) {
  return (value ?? "")
    .split(",")
    .map((entry) => entry.trim())
    .filter(Boolean);
}

/** One OpenAI-compatible endpoint + model. */
export type AiTarget = {
  baseUrl: string;
  apiKey: string;
  model: string;
  reasoning: "off" | "low" | "medium" | "high" | "none";
  providerOrder: string[];
  providerQuantizations: string[];
};

/** A target inside a pool: `priority` 1 is tried first, equal priorities are round-robined. */
export type AiProvider = AiTarget & { id: string; priority: number };

/** Which purpose a pool serves; each has its own env prefix and its own round-robin cursor. */
export type AiProfile = "default" | "assistant" | "vision" | "stt";

const FIELDS = [
  "BASE_URL",
  "API_KEY",
  "MODEL",
  "REASONING",
  "PRIORITY",
  "PROVIDER_ORDER",
  "PROVIDER_QUANTIZATIONS",
] as const;

/** Slot 1 is the unnumbered `PREFIX_FIELD`, slot 2+ are `PREFIX_2_FIELD`, `PREFIX_3_FIELD`, ... */
function slotVar(prefix: string, slot: number, field: string) {
  const value =
    process.env[slot === 1 ? `${prefix}_${field}` : `${prefix}_${slot}_${field}`];
  return value?.trim() || undefined;
}

const MAX_SLOTS = 9;

/**
 * Reads a numbered family of provider variables into a pool (exported for the tests). Every slot inherits what it does not set
 * from slot 1 (and slot 1 from `base`), so a second API key for the same provider is one line of .env:
 * `AI_2_API_KEY=...`. Providers without a key or model are dropped.
 */
export function readPool(
  prefix: string,
  base: Omit<AiTarget, "providerOrder" | "providerQuantizations">,
  options: { requireOwnVars?: boolean } = {},
): AiProvider[] {
  const owns = (slot: number) => FIELDS.some((field) => slotVar(prefix, slot, field));
  const read = (slot: number, inherit: AiTarget): AiTarget => ({
    baseUrl: (slotVar(prefix, slot, "BASE_URL") ?? inherit.baseUrl).replace(/\/$/, ""),
    apiKey: slotVar(prefix, slot, "API_KEY") ?? inherit.apiKey,
    model: slotVar(prefix, slot, "MODEL") ?? inherit.model,
    reasoning: (slotVar(prefix, slot, "REASONING") ?? inherit.reasoning) as AiTarget["reasoning"],
    providerOrder: slotVar(prefix, slot, "PROVIDER_ORDER")
      ? list(slotVar(prefix, slot, "PROVIDER_ORDER"))
      : inherit.providerOrder,
    providerQuantizations: slotVar(prefix, slot, "PROVIDER_QUANTIZATIONS")
      ? list(slotVar(prefix, slot, "PROVIDER_QUANTIZATIONS"))
      : inherit.providerQuantizations,
  });
  const first = read(1, { ...base, providerOrder: [], providerQuantizations: [] });
  const pool: AiProvider[] = [];
  for (let slot = 1; slot <= MAX_SLOTS; slot += 1) {
    if (slot > 1 && !owns(slot)) continue;
    if (slot === 1 && options.requireOwnVars && !owns(1)) continue;
    const target = slot === 1 ? first : read(slot, first);
    if (!target.apiKey || !target.model) continue;
    pool.push({
      ...target,
      id: slot === 1 ? prefix : `${prefix}_${slot}`,
      priority: num(slotVar(prefix, slot, "PRIORITY"), 1),
    });
  }
  return pool;
}

const chatBase = {
  baseUrl:
    process.env.AI_BASE_URL ??
    process.env.HACKCLUB_AI_BASE_URL ??
    "https://ai.hackclub.com/proxy/v1",
  apiKey: process.env.AI_API_KEY ?? process.env.HACKCLUB_AI_API_KEY ?? "",
  model: process.env.AI_MODEL ?? process.env.HACKCLUB_AI_MODEL ?? "",
  /**
   * How to steer thinking models (OpenRouter-style `reasoning` field):
   * "off" sends {enabled:false} (Qwen3), "low"/"medium"/"high" sends {effort} (gpt-oss), "none" sends nothing.
   */
  reasoning: (process.env.AI_REASONING ?? "off") as AiTarget["reasoning"],
};

const chat = readPool("AI", chatBase);
const firstChat = chat[0];

export const env = {
  port: num(process.env.PORT, 3000),
  databasePath: process.env.DATABASE_PATH ?? "./data/baskt.db",
  apiToken: process.env.APP_API_TOKEN ?? "",
  ai: {
    /**
     * Pools of interchangeable providers. Within a pool the lowest `priority` wins and equal priorities
     * are used round-robin, so two keys for the same free tier double the quota; a provider that fails or
     * hits its rate limit is skipped until it cools down (see ai/pool.ts).
     */
    chat,
    /**
     * Optional separate pool for the assistant's long, tool-using turns (set any AI_ASSISTANT_* variable
     * to enable): e.g. Groq's free tier for the many small calls and OpenRouter for the assistant, whose
     * 2k-token steps would exhaust Groq's 8k tokens/minute. Empty means "use the chat pool".
     */
    assistant: readPool("AI_ASSISTANT", { ...chatBase, reasoning: (process.env.AI_REASONING ?? "low") as AiTarget["reasoning"] }, { requireOwnVars: true }),
    /** Image-capable models for receipt photos; may live at another provider (Groq has no vision models). */
    vision: readPool("AI_VISION", { ...chatBase, model: process.env.AI_VISION_MODEL ?? "google/gemini-2.5-flash-lite" }),
    /**
     * Speech-to-text (OpenAI-compatible /audio/transcriptions). Defaults to the Groq providers of the chat
     * pool, which serve whisper-large-v3 on the same key; when nothing speaks it the app dictates on-device.
     */
    stt: sttPool(),
  },
  storeProxyUrl: process.env.STORE_PROXY_URL?.trim() || undefined,
  /** Set RECIPE_LOOKUP=off to skip fetching recipe pages (tests, offline). */
  recipeLookup: process.env.RECIPE_LOOKUP !== "off",
  /** WhatsApp bridge (backend/whatsapp) base URL, e.g. http://localhost:3001; empty disables the feature. */
  whatsappUrl: process.env.WHATSAPP_URL?.trim().replace(/\/$/, "") || "",
  /** Nightly re-pricing of products referenced by baskets. */
  priceScanEnabled: process.env.PRICE_SCAN !== "off",
  priceScanTime: process.env.PRICE_SCAN_TIME ?? "03:30",
  storeMinGapMs: num(process.env.STORE_MIN_GAP_MS, 400),
  searchCacheTtlMs:
    num(process.env.SEARCH_CACHE_TTL_HOURS, 24) * 60 * 60 * 1000,
};

function sttPool(): AiProvider[] {
  const model = process.env.AI_STT_MODEL?.trim() || "whisper-large-v3";
  const configured = readPool("AI_STT", { ...chatBase, model }, { requireOwnVars: true });
  if (configured.length) return configured;
  // No dedicated keys: Groq serves Whisper on the same key as the chat models, so reuse those providers.
  return chat
    .filter((provider) => aiVendor(provider.baseUrl) === "groq")
    .map((provider) => ({ ...provider, id: `${provider.id}_STT`, model }));
}

export function aiConfigured() {
  return env.ai.chat.length > 0;
}

/** The providers to try for a purpose, best first; the assistant falls back to the chat pool. */
export function aiPool(profile: AiProfile = "default"): AiProvider[] {
  if (profile === "assistant") return env.ai.assistant.length ? env.ai.assistant : env.ai.chat;
  if (profile === "vision") return env.ai.vision;
  if (profile === "stt") return env.ai.stt;
  return env.ai.chat;
}

/** Which dialect of the OpenAI-compatible API a provider speaks (they differ in reasoning/provider fields). */
export function aiVendor(
  baseUrl: string = firstChat?.baseUrl ?? chatBase.baseUrl,
): "groq" | "openrouter" | "other" {
  if (/groq\.com/.test(baseUrl)) return "groq";
  if (/openrouter\.ai|hackclub\.com/.test(baseUrl)) return "openrouter";
  return "other";
}

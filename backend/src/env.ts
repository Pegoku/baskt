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

const MAX_SLOTS = 9;

/** The settings one provider line can carry; `id` is added once it lands in a pool. */
type AiSlot = Omit<AiProvider, "id">;

const NAMED_FIELDS: Record<string, keyof AiSlot> = {
  url: "baseUrl",
  base_url: "baseUrl",
  key: "apiKey",
  api_key: "apiKey",
  model: "model",
  priority: "priority",
  reasoning: "reasoning",
  order: "providerOrder",
  quant: "providerQuantizations",
};

/**
 * Reads one provider line: `https://host/v1, sk-key, some/model, 2`. Fields are recognised by shape and
 * may come in any order — the endpoint is the one with "://", a bare number is the priority, and the
 * remaining bare values are the API key and then the model — so a second key for the provider above is
 * just `AI_2=sk-other`. Anything left out is inherited from `inherit`. Fields can also be named
 * (`key=…`, `model=…`, `priority=2`, `reasoning=low`, `order=coreweave|together`, `quant=fp4`), which is
 * the only way to set the OpenRouter-only routing hints; those lists are separated by "|", not commas.
 * Returns null for a line that said nothing this side understood, so a typo drops the provider (with a
 * warning) instead of quietly adding a second copy of the one above it.
 */
export function parseProvider(entry: string, inherit: AiSlot, label = "AI"): AiSlot | null {
  const slot: AiSlot = { ...inherit };
  const bare: string[] = [];
  const named = new Set<keyof AiSlot>();
  let understood = 0;
  for (const field of entry.split(",").map((part) => part.trim()).filter(Boolean)) {
    const match = /^([a-zA-Z_]+)\s*=\s*(.*)$/.exec(field);
    if (match) {
      const name = NAMED_FIELDS[match[1].toLowerCase()];
      if (!name) {
        console.warn(`[ai] ${label}: ignoring unknown field "${match[1]}"`);
        continue;
      }
      assign(slot, name, match[2].trim());
      named.add(name);
      understood += 1;
    } else if (field.includes("://")) {
      slot.baseUrl = field.replace(/\/$/, "");
      understood += 1;
    } else if (/^\d+$/.test(field)) {
      slot.priority = Number(field);
      understood += 1;
    } else {
      bare.push(field);
      understood += 1;
    }
  }
  if (!understood) {
    console.warn(`[ai] ${label}: no usable fields, skipping this provider`);
    return null;
  }
  // Whatever was not named, in the order people write it: the key first, the model second.
  const positional = (["apiKey", "model"] as const).filter((name) => !named.has(name));
  bare.forEach((value, index) => {
    if (positional[index]) assign(slot, positional[index], value);
  });
  return slot;
}

function assign(slot: AiSlot, name: keyof AiSlot, value: string) {
  if (name === "priority") slot.priority = num(value, 1);
  else if (name === "providerOrder" || name === "providerQuantizations") slot[name] = value.split("|").map((entry) => entry.trim()).filter(Boolean);
  else if (name === "reasoning") slot.reasoning = value as AiTarget["reasoning"];
  else if (name === "baseUrl") slot.baseUrl = value.replace(/\/$/, "");
  else slot[name] = value;
}

/**
 * Reads a pool: the first provider comes from the plain `PREFIX_BASE_URL`/`PREFIX_API_KEY`/`PREFIX_MODEL`
 * variables (or a `PREFIX_1` line), the rest from one line each in `PREFIX_2` … `PREFIX_9`. Providers
 * without a key or a model are dropped. Exported for the tests.
 */
export function readPool(
  prefix: string,
  base: Omit<AiTarget, "providerOrder" | "providerQuantizations">,
  options: { requireOwnVars?: boolean } = {},
): AiProvider[] {
  const own = (name: string) => process.env[`${prefix}_${name}`]?.trim() || undefined;
  const classic: AiSlot = {
    baseUrl: (own("BASE_URL") ?? base.baseUrl).replace(/\/$/, ""),
    apiKey: own("API_KEY") ?? base.apiKey,
    model: own("MODEL") ?? base.model,
    reasoning: (own("REASONING") ?? base.reasoning) as AiTarget["reasoning"],
    providerOrder: list(own("PROVIDER_ORDER")),
    providerQuantizations: list(own("PROVIDER_QUANTIZATIONS")),
    priority: num(own("PRIORITY"), 1),
  };
  const line = own("1");
  const first = (line && parseProvider(line, classic, `${prefix}_1`)) || classic;
  const configured = Boolean(line) || ["BASE_URL", "API_KEY", "MODEL", "REASONING", "PRIORITY", "PROVIDER_ORDER", "PROVIDER_QUANTIZATIONS"].some(own);
  const pool: AiProvider[] = [];
  if ((configured || !options.requireOwnVars) && first.apiKey && first.model) pool.push({ ...first, id: `${prefix}_1` });
  for (let slot = 2; slot <= MAX_SLOTS; slot += 1) {
    const entry = own(String(slot));
    if (!entry) continue;
    const target = parseProvider(entry, first, `${prefix}_${slot}`);
    if (target?.apiKey && target.model) pool.push({ ...target, id: `${prefix}_${slot}` });
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

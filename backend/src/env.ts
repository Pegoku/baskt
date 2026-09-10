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

export const env = {
  port: num(process.env.PORT, 3000),
  databasePath: process.env.DATABASE_PATH ?? "./data/baskt.db",
  apiToken: process.env.APP_API_TOKEN ?? "",
  ai: {
    baseUrl: (
      process.env.AI_BASE_URL ??
      process.env.HACKCLUB_AI_BASE_URL ??
      "https://ai.hackclub.com/proxy/v1"
    ).replace(/\/$/, ""),
    apiKey: process.env.AI_API_KEY ?? process.env.HACKCLUB_AI_API_KEY ?? "",
    model: process.env.AI_MODEL ?? process.env.HACKCLUB_AI_MODEL ?? "",
    /** Image-capable model for receipt photos; may live at another provider (Groq has no vision models). */
    visionModel: process.env.AI_VISION_MODEL ?? "google/gemini-2.5-flash-lite",
    visionBaseUrl: (
      process.env.AI_VISION_BASE_URL ??
      process.env.AI_BASE_URL ??
      process.env.HACKCLUB_AI_BASE_URL ??
      "https://ai.hackclub.com/proxy/v1"
    ).replace(/\/$/, ""),
    visionApiKey:
      process.env.AI_VISION_API_KEY ??
      process.env.AI_API_KEY ??
      process.env.HACKCLUB_AI_API_KEY ??
      "",
    /**
     * How to steer thinking models (OpenRouter-style `reasoning` field):
     * "off" sends {enabled:false} (Qwen3), "low"/"medium"/"high" sends {effort} (gpt-oss), "none" sends nothing.
     */
    reasoning: (process.env.AI_REASONING ?? "off") as
      "off" | "low" | "medium" | "high" | "none",
    /** Optional OpenRouter provider routing, e.g. AI_PROVIDER_ORDER=coreweave AI_PROVIDER_QUANTIZATIONS=fp4. */
    providerOrder: list(process.env.AI_PROVIDER_ORDER),
    providerQuantizations: list(process.env.AI_PROVIDER_QUANTIZATIONS),
    /**
     * Optional separate provider for the assistant's long, tool-using turns (set AI_ASSISTANT_MODEL to enable):
     * e.g. Groq's free tier for the many small calls and OpenRouter for the assistant, whose 2k-token steps
     * would exhaust Groq's 8k tokens/minute.
     */
    assistant: process.env.AI_ASSISTANT_MODEL
      ? ({
          baseUrl: (
            process.env.AI_ASSISTANT_BASE_URL ??
            process.env.AI_BASE_URL ??
            "https://ai.hackclub.com/proxy/v1"
          ).replace(/\/$/, ""),
          apiKey:
            process.env.AI_ASSISTANT_API_KEY ?? process.env.AI_API_KEY ?? "",
          model: process.env.AI_ASSISTANT_MODEL,
          reasoning: (process.env.AI_ASSISTANT_REASONING ??
            process.env.AI_REASONING ??
            "low") as AiTarget["reasoning"],
          providerOrder: list(process.env.AI_ASSISTANT_PROVIDER_ORDER),
          providerQuantizations: list(
            process.env.AI_ASSISTANT_PROVIDER_QUANTIZATIONS,
          ),
        } satisfies AiTarget)
      : (null as AiTarget | null),
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

export function aiConfigured() {
  return Boolean(env.ai.apiKey && env.ai.model);
}

/** The endpoint/model to use for a purpose: the assistant may have its own, everything else uses the main one. */
export function aiTarget(
  profile: "default" | "assistant" = "default",
): AiTarget {
  return (profile === "assistant" && env.ai.assistant) || env.ai;
}

/** Which dialect of the OpenAI-compatible API a provider speaks (they differ in reasoning/provider fields). */
export function aiVendor(
  baseUrl: string = env.ai.baseUrl,
): "groq" | "openrouter" | "other" {
  if (/groq\.com/.test(baseUrl)) return "groq";
  if (/openrouter\.ai|hackclub\.com/.test(baseUrl)) return "openrouter";
  return "other";
}

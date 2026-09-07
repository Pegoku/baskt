function num(value: string | undefined, fallback: number) {
  const parsed = Number(value);
  return Number.isFinite(parsed) && parsed > 0 ? parsed : fallback;
}

export const env = {
  port: num(process.env.PORT, 3000),
  databasePath: process.env.DATABASE_PATH ?? "./data/baskt.db",
  apiToken: process.env.APP_API_TOKEN ?? "",
  ai: {
    baseUrl: (process.env.AI_BASE_URL ?? process.env.HACKCLUB_AI_BASE_URL ?? "https://ai.hackclub.com/proxy/v1").replace(/\/$/, ""),
    apiKey: process.env.AI_API_KEY ?? process.env.HACKCLUB_AI_API_KEY ?? "",
    model: process.env.AI_MODEL ?? process.env.HACKCLUB_AI_MODEL ?? "",
    /**
     * How to steer thinking models (OpenRouter-style `reasoning` field):
     * "off" sends {enabled:false} (Qwen3), "low"/"medium"/"high" sends {effort} (gpt-oss), "none" sends nothing.
     */
    reasoning: (process.env.AI_REASONING ?? "off") as "off" | "low" | "medium" | "high" | "none",
    /** Optional OpenRouter provider routing, e.g. AI_PROVIDER_ORDER=coreweave AI_PROVIDER_QUANTIZATIONS=fp4. */
    providerOrder: (process.env.AI_PROVIDER_ORDER ?? "").split(",").map((value) => value.trim()).filter(Boolean),
    providerQuantizations: (process.env.AI_PROVIDER_QUANTIZATIONS ?? "").split(",").map((value) => value.trim()).filter(Boolean),
  },
  storeProxyUrl: process.env.STORE_PROXY_URL?.trim() || undefined,
  /** Set RECIPE_LOOKUP=off to skip fetching recipe pages (tests, offline). */
  recipeLookup: process.env.RECIPE_LOOKUP !== "off",
  storeMinGapMs: num(process.env.STORE_MIN_GAP_MS, 400),
  searchCacheTtlMs: num(process.env.SEARCH_CACHE_TTL_HOURS, 24) * 60 * 60 * 1000,
};

export function aiConfigured() {
  return Boolean(env.ai.apiKey && env.ai.model);
}

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
    /** Reasoning models (Qwen3, DeepSeek R1) burn the token budget on hidden thinking; OpenRouter-style proxies accept this switch. */
    disableReasoning: (process.env.AI_DISABLE_REASONING ?? "true") !== "false",
  },
  storeProxyUrl: process.env.STORE_PROXY_URL?.trim() || undefined,
  storeMinGapMs: num(process.env.STORE_MIN_GAP_MS, 400),
  searchCacheTtlMs: num(process.env.SEARCH_CACHE_TTL_HOURS, 24) * 60 * 60 * 1000,
};

export function aiConfigured() {
  return Boolean(env.ai.apiKey && env.ai.model);
}

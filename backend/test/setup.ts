// Tests must never hit the real AI provider or depend on a developer's .env (Bun loads it automatically),
// including the numbered load-balancer slots (AI_2_API_KEY, AI_VISION_..., AI_STT_...).
for (const key of Object.keys(process.env)) {
  if (/^(AI|HACKCLUB_AI)_/.test(key)) delete process.env[key];
}
process.env.APP_API_TOKEN = "secret";
process.env.DATABASE_PATH = ":memory:";
process.env.RECIPE_LOOKUP = "off";
process.env.PRICE_SCAN = "off";

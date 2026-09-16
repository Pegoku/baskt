// Tests must never hit a real provider or depend on a developer's .env (Bun loads it automatically),
// including the numbered load-balancer slots (AI_2, AI_VISION_..., AI_STT_..., REPLICATE_2).
for (const key of Object.keys(process.env)) {
  if (/^(AI|HACKCLUB_AI|REPLICATE)_/.test(key)) delete process.env[key];
}
process.env.APP_API_TOKEN = "secret";
process.env.DATABASE_PATH = ":memory:";
process.env.RECIPE_LOOKUP = "off";
process.env.PRICE_SCAN = "off";

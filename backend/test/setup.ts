// Tests must never hit the real AI provider or depend on a developer's .env.
process.env.AI_API_KEY = "";
process.env.AI_MODEL = "";
process.env.HACKCLUB_AI_API_KEY = "";
process.env.HACKCLUB_AI_MODEL = "";
process.env.APP_API_TOKEN = "secret";
process.env.DATABASE_PATH = ":memory:";
process.env.RECIPE_LOOKUP = "off";
process.env.PRICE_SCAN = "off";

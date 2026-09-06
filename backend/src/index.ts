import { createApp } from "@/app";
import { db } from "@/db";
import { aiConfigured, env } from "@/env";

db();
if (!env.apiToken) console.warn("[baskt] APP_API_TOKEN is empty: the API is unauthenticated");
if (!aiConfigured()) console.warn("[baskt] AI_API_KEY/AI_MODEL not set: matching falls back to text similarity");

const app = createApp();

export default {
  port: env.port,
  hostname: "0.0.0.0",
  fetch: app.fetch,
  idleTimeout: 120,
};

console.log(`[baskt] listening on http://0.0.0.0:${env.port}`);

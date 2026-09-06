import { and, eq } from "drizzle-orm";
import { db, now } from "@/db";
import { aiCache } from "@/db/schema";
import { aiConfigured, env } from "@/env";

export type ChatMessage = { role: "system" | "user"; content: string };

let calls = 0;
let failures = 0;

export function aiStats() {
  return { configured: aiConfigured(), model: env.ai.model || null, calls, failures };
}

/** Calls an OpenAI-compatible chat-completions endpoint and parses the JSON object it returns. */
export async function chatJson<T>(messages: ChatMessage[], options: { maxTokens?: number; retries?: number } = {}): Promise<T | null> {
  if (!aiConfigured()) return null;
  const retries = options.retries ?? 2;
  for (let attempt = 1; attempt <= retries; attempt += 1) {
    calls += 1;
    try {
      const response = await fetch(`${env.ai.baseUrl}/chat/completions`, {
        method: "POST",
        headers: { authorization: `Bearer ${env.ai.apiKey}`, "content-type": "application/json" },
        body: JSON.stringify({
          model: env.ai.model,
          messages,
          temperature: 0,
          max_tokens: options.maxTokens ?? 1500,
          response_format: { type: "json_object" },
        }),
        signal: AbortSignal.timeout(60_000),
      });
      if (!response.ok) {
        failures += 1;
        console.warn(`[ai] HTTP ${response.status}: ${(await response.text()).slice(0, 200)}`);
        continue;
      }
      const payload = (await response.json()) as { choices?: Array<{ message?: { content?: string } }> };
      const content = payload.choices?.[0]?.message?.content;
      if (!content) {
        failures += 1;
        continue;
      }
      return JSON.parse(stripFences(content)) as T;
    } catch (error) {
      failures += 1;
      console.warn(`[ai] attempt ${attempt}/${retries} failed: ${error instanceof Error ? error.message : error}`);
    }
  }
  return null;
}

function stripFences(content: string) {
  const trimmed = content.trim();
  const fence = trimmed.match(/^```(?:json)?\s*([\s\S]*?)```$/i);
  return fence ? fence[1] : trimmed;
}

/** Wraps chatJson with the ai_cache table. */
export async function cachedChatJson<T>(kind: string, key: string, messages: ChatMessage[], options: { maxTokens?: number } = {}): Promise<T | null> {
  const database = db();
  const hit = database.select().from(aiCache).where(and(eq(aiCache.kind, kind), eq(aiCache.key, key))).get();
  if (hit) return JSON.parse(hit.responseJson) as T;
  const result = await chatJson<T>(messages, options);
  if (result !== null) {
    database
      .insert(aiCache)
      .values({ kind, key, responseJson: JSON.stringify(result), createdAt: now() })
      .onConflictDoUpdate({ target: [aiCache.kind, aiCache.key], set: { responseJson: JSON.stringify(result), createdAt: now() } })
      .run();
  }
  return result;
}

import { and, eq } from "drizzle-orm";
import { db, now } from "@/db";
import { aiCache } from "@/db/schema";
import { aiConfigured, env } from "@/env";

export type ChatMessage = { role: "system" | "user"; content: string };

let calls = 0;
let failures = 0;

export function aiStats() {
  return {
    configured: aiConfigured(),
    model: env.ai.model || null,
    calls,
    failures,
  };
}

/** Calls an OpenAI-compatible chat-completions endpoint and parses the JSON object it returns. */
export async function chatJson<T>(
  messages: ChatMessage[],
  options: { maxTokens?: number; retries?: number; tools?: unknown[] } = {},
): Promise<T | null> {
  if (!aiConfigured()) return null;
  const retries = options.retries ?? 2;
  for (let attempt = 1; attempt <= retries; attempt += 1) {
    calls += 1;
    try {
      const response = await fetch(`${env.ai.baseUrl}/chat/completions`, {
        method: "POST",
        headers: {
          authorization: `Bearer ${env.ai.apiKey}`,
          "content-type": "application/json",
        },
        body: JSON.stringify({
          model: env.ai.model,
          messages,
          temperature: 0,
          max_tokens: options.maxTokens ?? 1500,
          response_format: { type: "json_object" },
          ...(options.tools?.length
            ? { tools: options.tools, tool_choice: "auto" }
            : {}),
          ...reasoningField(),
          ...providerField(),
        }),
        signal: AbortSignal.timeout(60_000),
      });
      if (!response.ok) {
        failures += 1;
        console.warn(
          `[ai] HTTP ${response.status}: ${(await response.text()).slice(0, 200)}`,
        );
        continue;
      }
      const payload = (await response.json()) as {
        choices?: Array<{
          message?: {
            content?: string;
            reasoning?: string;
            reasoning_content?: string;
            tool_calls?: Array<{
              function?: { name?: string; arguments?: string };
            }>;
          };
          finish_reason?: string;
        }>;
      };
      const choice = payload.choices?.[0];
      const content = choice?.message?.content;
      // Some models (gpt-oss) emit a native function call when the prompt describes tools, even though
      // none are declared. Translate it into the JSON "tool" field the assistant protocol expects.
      const nativeCall = choice?.message?.tool_calls?.[0]?.function;
      if (!content && nativeCall?.name) {
        let args: unknown = {};
        try {
          args = nativeCall.arguments ? JSON.parse(nativeCall.arguments) : {};
        } catch {
          args = {};
        }
        return { tool: { name: nativeCall.name, args } } as T;
      }
      // Last resort: the model sometimes leaves the tool JSON in its reasoning and returns no content.
      const reasoningText =
        choice?.message?.reasoning ?? choice?.message?.reasoning_content ?? "";
      const buried =
        !content && options.tools?.length
          ? /\{\s*"name"\s*:\s*"([a-z_]+)"[^]*\}/.exec(reasoningText)
          : null;
      if (buried) {
        try {
          const parsed = JSON.parse(buried[0]) as {
            name: string;
            args?: unknown;
          };
          return { tool: { name: parsed.name, args: parsed.args ?? {} } } as T;
        } catch {
          // fall through to the retry below
        }
      }
      if (!content) {
        failures += 1;
        // gpt-oss sometimes spends the whole turn in reasoning and returns no content; log the tail so we can see why.
        const reasoning =
          choice?.message?.reasoning ??
          choice?.message?.reasoning_content ??
          "";
        console.warn(
          `[ai] empty content (finish_reason=${choice?.finish_reason ?? "?"}), attempt ${attempt}/${retries}${reasoning ? `; reasoning tail: ${reasoning.slice(-300).replace(/\s+/g, " ")}` : ""}`,
        );
        continue;
      }
      return JSON.parse(stripFences(content)) as T;
    } catch (error) {
      failures += 1;
      console.warn(
        `[ai] attempt ${attempt}/${retries} failed: ${error instanceof Error ? error.message : error}`,
      );
    }
  }
  return null;
}

function reasoningField() {
  if (env.ai.reasoning === "off") return { reasoning: { enabled: false } };
  if (env.ai.reasoning === "none") return {};
  return { reasoning: { effort: env.ai.reasoning } };
}

function providerField() {
  if (!env.ai.providerOrder.length && !env.ai.providerQuantizations.length)
    return {};
  return {
    provider: {
      ...(env.ai.providerOrder.length ? { order: env.ai.providerOrder } : {}),
      ...(env.ai.providerQuantizations.length
        ? { quantizations: env.ai.providerQuantizations }
        : {}),
      allow_fallbacks: true,
    },
  };
}

function stripFences(content: string) {
  const trimmed = content.trim();
  const fence = trimmed.match(/^```(?:json)?\s*([\s\S]*?)```$/i);
  return fence ? fence[1] : trimmed;
}

/** Wraps chatJson with the ai_cache table. */
export async function cachedChatJson<T>(
  kind: string,
  key: string,
  messages: ChatMessage[],
  options: { maxTokens?: number } = {},
): Promise<T | null> {
  const database = db();
  const hit = database
    .select()
    .from(aiCache)
    .where(and(eq(aiCache.kind, kind), eq(aiCache.key, key)))
    .get();
  if (hit) return JSON.parse(hit.responseJson) as T;
  const result = await chatJson<T>(messages, options);
  if (result !== null) {
    database
      .insert(aiCache)
      .values({
        kind,
        key,
        responseJson: JSON.stringify(result),
        createdAt: now(),
      })
      .onConflictDoUpdate({
        target: [aiCache.kind, aiCache.key],
        set: { responseJson: JSON.stringify(result), createdAt: now() },
      })
      .run();
  }
  return result;
}

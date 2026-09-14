import { afterEach, describe, expect, test } from "bun:test";
import { attemptOrder, cooldownFor, noteFailure, noteUsage, resetPoolStats } from "@/ai/pool";
import { transcribeAudio } from "@/ai/transcribe";
import { env, readPool, type AiProvider } from "@/env";

function provider(id: string, priority: number, extra: Partial<AiProvider> = {}): AiProvider {
  return {
    id,
    priority,
    baseUrl: "https://api.groq.com/openai/v1",
    apiKey: `key-${id}`,
    model: "openai/gpt-oss-20b",
    reasoning: "low",
    providerOrder: [],
    providerQuantizations: [],
    ...extra,
  };
}

const realFetch = globalThis.fetch;
const realChat = env.ai.chat;
const realStt = env.ai.stt;

afterEach(() => {
  globalThis.fetch = realFetch;
  env.ai.chat = realChat;
  env.ai.stt = realStt;
  resetPoolStats();
});

describe("provider env parsing", () => {
  const base = { baseUrl: "https://api.groq.com/openai/v1", apiKey: "", model: "", reasoning: "off" as const };

  test("numbered slots inherit everything they do not set", () => {
    process.env.TESTAI_API_KEY = "one";
    process.env.TESTAI_MODEL = "openai/gpt-oss-20b";
    process.env.TESTAI_2_API_KEY = "two";
    const pool = readPool("TESTAI", base);
    expect(pool.map((entry) => [entry.id, entry.apiKey, entry.model, entry.priority])).toEqual([
      ["TESTAI", "one", "openai/gpt-oss-20b", 1],
      ["TESTAI_2", "two", "openai/gpt-oss-20b", 1],
    ]);
    delete process.env.TESTAI_API_KEY;
    delete process.env.TESTAI_MODEL;
    delete process.env.TESTAI_2_API_KEY;
  });

  test("a slot without a key or model is dropped, priorities are kept", () => {
    process.env.TESTAI_API_KEY = "one";
    process.env.TESTAI_MODEL = "a";
    process.env.TESTAI_2_BASE_URL = "https://openrouter.ai/api/v1"; // no key of its own → inherits, so it counts
    process.env.TESTAI_2_PRIORITY = "2";
    process.env.TESTAI_3_MODEL = ""; // empty value is not a slot
    const pool = readPool("TESTAI", base);
    expect(pool.map((entry) => `${entry.id}:${entry.priority}`)).toEqual(["TESTAI:1", "TESTAI_2:2"]);
    expect(pool[1].baseUrl).toBe("https://openrouter.ai/api/v1");
    for (const key of ["TESTAI_API_KEY", "TESTAI_MODEL", "TESTAI_2_BASE_URL", "TESTAI_2_PRIORITY", "TESTAI_3_MODEL"]) delete process.env[key];
  });

  test("requireOwnVars keeps an unconfigured pool empty", () => {
    expect(readPool("TESTAI_ASSISTANT", { ...base, apiKey: "inherited", model: "a" }, { requireOwnVars: true })).toEqual([]);
  });
});

describe("attempt order", () => {
  test("equal priorities take turns", () => {
    env.ai.chat = [provider("A", 1), provider("B", 1)];
    const first = attemptOrder("default")[0].id;
    const second = attemptOrder("default")[0].id;
    expect(first).not.toBe(second);
    expect(attemptOrder("default")[0].id).toBe(first);
  });

  test("a lower priority number always goes first", () => {
    env.ai.chat = [provider("BACKUP", 2), provider("MAIN", 1)];
    for (let round = 0; round < 4; round += 1) {
      expect(attemptOrder("default").map((entry) => entry.id)).toEqual(["MAIN", "BACKUP"]);
    }
  });

  test("a rate-limited provider drops behind the healthy ones and returns after a success", () => {
    env.ai.chat = [provider("MAIN", 1), provider("BACKUP", 2)];
    noteFailure("MAIN", "HTTP 429", 30_000);
    expect(attemptOrder("default").map((entry) => entry.id)).toEqual(["BACKUP", "MAIN"]);
    noteUsage("MAIN", 10, 10);
    expect(attemptOrder("default").map((entry) => entry.id)).toEqual(["MAIN", "BACKUP"]);
  });
});

describe("transcription", () => {
  const clip = { bytes: new Uint8Array([1, 2, 3]), filename: "dictation.m4a", mime: "audio/m4a" };

  test("falls over to the next key when one is metered and keeps using the healthy one", async () => {
    env.ai.stt = [provider("STT_A", 1, { model: "whisper-large-v3" }), provider("STT_B", 1, { model: "whisper-large-v3" })];
    const used: string[] = [];
    globalThis.fetch = (async (_url: string, init: RequestInit) => {
      const key = String((init.headers as Record<string, string>).authorization).replace("Bearer key-", "");
      used.push(key);
      if (key === "STT_A") return new Response("rate limited", { status: 429, headers: { "retry-after": "30" } });
      return Response.json({ text: " two litres of milk " });
    }) as unknown as typeof fetch;

    // Two calls, so the round-robin has offered the clip to both keys and A has hit its 429 once.
    expect(await transcribeAudio(clip, "en")).toBe("two litres of milk");
    expect(await transcribeAudio(clip, "en")).toBe("two litres of milk");
    expect(used).toContain("STT_A");
    used.length = 0;
    expect(await transcribeAudio(clip, "en")).toBe("two litres of milk");
    expect(used).toEqual(["STT_B"]); // A stays out until its cooldown expires
  });

  test("returns null when the whole pool fails so the app can dictate on-device", async () => {
    env.ai.stt = [provider("STT_A", 1, { model: "whisper-large-v3" })];
    globalThis.fetch = (async () => new Response("boom", { status: 500 })) as unknown as typeof fetch;
    expect(await transcribeAudio(clip)).toBeNull();
  });

  test("sends the clip as multipart with the model and language", async () => {
    env.ai.stt = [provider("STT_A", 1, { model: "whisper-large-v3" })];
    let form: FormData | null = null;
    let url = "";
    globalThis.fetch = (async (target: string, init: RequestInit) => {
      url = target;
      form = init.body as FormData;
      return Response.json({ text: "eggs" });
    }) as unknown as typeof fetch;

    await transcribeAudio(clip, "nl");
    expect(url).toBe("https://api.groq.com/openai/v1/audio/transcriptions");
    expect(form!.get("model")).toBe("whisper-large-v3");
    expect(form!.get("language")).toBe("nl");
    expect((form!.get("file") as File).name).toBe("dictation.m4a");
  });
});

describe("cooldowns", () => {
  test("a metered key honours Retry-After, a rejected key sits out, a bad request does not count", () => {
    expect(cooldownFor(429, "12")).toBe(12_000);
    expect(cooldownFor(429, null)).toBe(5_000);
    expect(cooldownFor(429, "600")).toBe(60_000); // bounded: the next call must not wait ten minutes
    expect(cooldownFor(401)).toBe(60_000);
    expect(cooldownFor(503)).toBe(15_000);
    expect(cooldownFor(400)).toBe(0);
  });
});

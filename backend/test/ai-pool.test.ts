import { Hono } from "hono";
import { speechTranscript } from "@/routes/speech";
import { afterEach, describe, expect, test } from "bun:test";
import { attemptOrder, cooldownFor, noteFailure, noteUsage, resetPoolStats } from "@/ai/pool";
import { transcribeAudio } from "@/ai/transcribe";
import { env, parseProvider, readPool, type AiProvider } from "@/env";

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

describe("provider lines", () => {
  const base = { baseUrl: "https://api.groq.com/openai/v1", apiKey: "", model: "", reasoning: "off" as const };
  const first = { ...base, apiKey: "one", model: "openai/gpt-oss-20b", priority: 1, providerOrder: [], providerQuantizations: [] };

  test("recognises endpoint, key, model and priority by shape", () => {
    expect(parseProvider("https://openrouter.ai/api/v1, sk-or-x, z-ai/glm-4.7-flash, 2", first)).toMatchObject({
      baseUrl: "https://openrouter.ai/api/v1",
      apiKey: "sk-or-x",
      model: "z-ai/glm-4.7-flash",
      priority: 2,
    });
    // The endpoint and the priority are found wherever they are; the key and the model are told apart
    // by the order they are written in, so writing them the other way round needs a name.
    expect(parseProvider("3, sk-key, some/model, https://host/v1", first)).toMatchObject({
      baseUrl: "https://host/v1",
      apiKey: "sk-key",
      model: "some/model",
      priority: 3,
    });
    expect(parseProvider("some/model, key=sk-key", first)).toMatchObject({ apiKey: "sk-key", model: "some/model" });
  });

  test("a lone key is a second account for the provider above", () => {
    expect(parseProvider("gsk_two", first)).toEqual({ ...first, apiKey: "gsk_two" });
  });

  test("named fields win and carry what shape cannot express", () => {
    const slot = parseProvider("model=qwen/qwen3.8-27b, reasoning=high, order=coreweave|together, quant=fp4", first);
    expect(slot).toMatchObject({
      apiKey: "one", // inherited
      model: "qwen/qwen3.8-27b",
      reasoning: "high",
      providerOrder: ["coreweave", "together"],
      providerQuantizations: ["fp4"],
    });
    // A named key leaves the first bare value to the model, not the other way round.
    expect(parseProvider("key=gsk_two, some/model", first)).toMatchObject({ apiKey: "gsk_two", model: "some/model" });
  });

  test("a typo is reported and ignored instead of silently changing the provider", () => {
    const warnings: string[] = [];
    const warn = console.warn;
    console.warn = (message: string) => warnings.push(message);
    expect(parseProvider("ky=gsk_two", first, "AI_2")).toBeNull(); // not a second copy of the first
    console.warn = warn;
    expect(warnings[0]).toContain('AI_2: ignoring unknown field "ky"');
    expect(warnings[1]).toContain("AI_2: no usable fields");
  });

  test("the pool starts at the plain variables and extends one line at a time", () => {
    process.env.TESTAI_API_KEY = "one";
    process.env.TESTAI_MODEL = "openai/gpt-oss-20b";
    process.env.TESTAI_2 = "gsk_two";
    process.env.TESTAI_3 = "https://openrouter.ai/api/v1, sk-or, openai/gpt-oss-20b, 2";
    process.env.TESTAI_5 = "priority=4"; // no key of its own: inherits the first, gaps are allowed
    const pool = readPool("TESTAI", base);
    expect(pool.map((entry) => [entry.id, entry.apiKey, entry.model, entry.priority])).toEqual([
      ["TESTAI_1", "one", "openai/gpt-oss-20b", 1],
      ["TESTAI_2", "gsk_two", "openai/gpt-oss-20b", 1],
      ["TESTAI_3", "sk-or", "openai/gpt-oss-20b", 2],
      ["TESTAI_5", "one", "openai/gpt-oss-20b", 4],
    ]);
    expect(pool[1].baseUrl).toBe("https://api.groq.com/openai/v1");
    for (const key of ["TESTAI_API_KEY", "TESTAI_MODEL", "TESTAI_2", "TESTAI_3", "TESTAI_5"]) delete process.env[key];
  });

  test("the whole pool can be written as lines, and one without a key is dropped", () => {
    process.env.TESTAI_1 = "https://api.groq.com/openai/v1, gsk_one, openai/gpt-oss-20b";
    process.env.TESTAI_2 = "model=only/a-model"; // nothing to authenticate with
    const pool = readPool("TESTAI", { ...base, apiKey: "", model: "" });
    expect(pool.map((entry) => entry.id)).toEqual(["TESTAI_1", "TESTAI_2"]);
    expect(pool[1].apiKey).toBe("gsk_one"); // inherited from the line above, so it stays usable
    delete process.env.TESTAI_1;
    delete process.env.TESTAI_2;
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

describe("the dictation pool", () => {
  const groq = { AI_BASE_URL: "https://api.groq.com/openai/v1", AI_API_KEY: "gsk_one", AI_MODEL: "openai/gpt-oss-20b" };

  /** env.ts reads process.env once, so each case re-imports it with its own environment. */
  async function pools(vars: Record<string, string>) {
    const previous = { ...process.env };
    Object.assign(process.env, groq, vars);
    const fresh = await import(`@/env?stt=${JSON.stringify(vars)}`);
    for (const key of Object.keys(process.env)) if (!(key in previous)) delete process.env[key];
    Object.assign(process.env, previous);
    return fresh.env.ai as { chat: AiProvider[]; stt: AiProvider[] };
  }

  test("reuses the Groq chat keys, because the same key serves Whisper", async () => {
    const ai = await pools({ AI_2: "gsk_two" });
    expect(ai.stt.map((entry) => [entry.id, entry.apiKey, entry.model])).toEqual([
      ["AI_1_STT", "gsk_one", "whisper-large-v3"],
      ["AI_2_STT", "gsk_two", "whisper-large-v3"],
    ]);
  });

  test("AI_STT_MODEL picks the model without inventing a provider", async () => {
    const ai = await pools({ AI_STT_MODEL: "whisper-large-v3-turbo" });
    expect(ai.stt.map((entry) => [entry.id, entry.model])).toEqual([["AI_1_STT", "whisper-large-v3-turbo"]]);
  });

  test("a dedicated key takes over from the chat providers", async () => {
    const ai = await pools({ AI_STT_1: "https://api.groq.com/openai/v1, gsk_whisper", AI_STT_2: "gsk_whisper_two" });
    expect(ai.stt.map((entry) => [entry.id, entry.apiKey, entry.model])).toEqual([
      ["AI_STT_1", "gsk_whisper", "whisper-large-v3"],
      ["AI_STT_2", "gsk_whisper_two", "whisper-large-v3"],
    ]);
  });

  test("AI_STT=off leaves dictation on the phone", async () => {
    const ai = await pools({ AI_STT: "off", AI_STT_1: "https://api.groq.com/openai/v1, gsk_whisper" });
    expect(ai.stt).toEqual([]);
    expect(ai.chat.length).toBe(1); // the chat pool is untouched
  });

  test("a non-Groq chat provider gets no dictation unless one is configured", async () => {
    const ai = await pools({ AI_BASE_URL: "https://openrouter.ai/api/v1", AI_API_KEY: "sk-or", AI_STT_MODEL: "whisper-large-v3" });
    expect(ai.stt).toEqual([]);
  });
});


describe("shared speech upload", () => {
  const app = new Hono().post("/transcribe", async (c) => {
    const result = await speechTranscript(c);
    return typeof result === "string" ? c.json({ transcript: result }) : result;
  });
  test("returns transcript without interpreting it as shopping items", async () => {
    env.ai.stt = [provider("speech-route", 1, { model: "whisper-large-v3" })];
    globalThis.fetch = (async () => Response.json({ text: "Tell me a joke" })) as typeof fetch;
    const form = new FormData();
    form.append("audio", new File(["audio"], "voice.m4a", { type: "audio/m4a" }));
    form.append("language", "en");
    const result = await app.request("/transcribe", { method: "POST", body: form });
    expect(result.status).toBe(200);
    expect(await result.json()).toEqual({ transcript: "Tell me a joke" });
  });
  test("signals unavailable STT and validates uploads", async () => {
    env.ai.stt = [];
    expect((await app.request("/transcribe", { method: "POST" })).status).toBe(503);
    env.ai.stt = [provider("speech-route", 1, { model: "whisper-large-v3" })];
    expect((await app.request("/transcribe", { method: "POST", body: new FormData() })).status).toBe(400);
  });
});

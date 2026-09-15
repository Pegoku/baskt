import { afterEach, beforeEach, describe, expect, test } from "bun:test";
import { resetDbForTests } from "@/db";
import { speechInput, minimaxLanguageBoost, speechCatalogue, speechChoice, speechOutput, synthesize } from "@/ai/speak";
import { tts } from "@/routes/tts";

const originalFetch = globalThis.fetch;
const originalToken = process.env.REPLICATE_API_TOKEN;
beforeEach(() => { resetDbForTests(":memory:"); process.env.REPLICATE_API_TOKEN = "test-token"; });
afterEach(() => {
  globalThis.fetch = originalFetch;
  if (originalToken === undefined) delete process.env.REPLICATE_API_TOKEN;
  else process.env.REPLICATE_API_TOKEN = originalToken;
});
describe("multilingual speech", () => {
  test("only allows known voices and supported languages", () => {
    expect(speechChoice("inworld/realtime-tts-1.5-mini", "Ashley", "es")).not.toBeNull();
    expect(speechChoice("inworld/realtime-tts-1.5-mini", "Ashley", "ca")).toBeNull();
    expect(speechChoice("minimax/speech-2.8-turbo", "English_Wiselady", "ca")).toBeNull();
    expect(speechChoice("unknown", "Ashley", "en")).toBeNull();
    expect(speechChoice("inworld/realtime-tts-1.5-mini", "unknown", "en")).toBeNull();
  });
  test("catalogue only exposes MiniMax voices for the requested language", () => {
    const spanish = speechCatalogue("es").find((model) => model.id.startsWith("minimax/"))!;
    expect(spanish.voices.length).toBe(47);
    expect(spanish.voices.every((voice) => voice.startsWith("Spanish_"))).toBe(true);
    expect(spanish.voiceNames.Spanish_SereneWoman).toBe("Serene Woman");
    expect(speechCatalogue("nl").find((model) => model.id.startsWith("minimax/"))!.voices.length).toBe(2);
    expect(speechCatalogue("ca").some((model) => model.id.startsWith("minimax/"))).toBe(false);
    expect(speechChoice("minimax/speech-2.8-turbo", "English_Wiselady", "es")).toBeNull();
    expect(speechChoice("minimax/speech-2.8-turbo", "Spanish_SereneWoman", "es")).not.toBeNull();
  });
  test("sends explicit language boosts for every MiniMax catalog language", async () => {
    const model = speechCatalogue().find((item) => item.id.startsWith("minimax/"))!;
    let expected = "";
    globalThis.fetch = (async (url: string | URL | Request, init?: RequestInit) => {
      if (String(url).includes("/models/")) {
        expect(JSON.parse(String(init?.body)).input.language_boost).toBe(expected);
        return Response.json({ status: "succeeded", output: "https://replicate.delivery/test.mp3" });
      }
      return new Response(new Uint8Array([1, 2, 3]));
    }) as unknown as typeof fetch;
    for (const language of model.languages) {
      expected = minimaxLanguageBoost[language];
      expect(expected).toBeTruthy();
      expect(expected).not.toBe("Automatic");
      const voice = speechCatalogue(language).find((item) => item.id === model.id)!.voices[0];
      await synthesize("Test", model.id, voice, language);
    }
    expect(minimaxLanguageBoost.es).toBe("Spanish");
    expect(minimaxLanguageBoost.nl).toBe("Dutch");
    expect(minimaxLanguageBoost.zh).toBe("Chinese");
    expect(minimaxLanguageBoost.yue).toBe("Cantonese");
  });
  test("Qwen exposes all nine multilingual speakers and sends its own schema", () => {
    const qwen = speechCatalogue("es").find((model) => model.id === "qwen/qwen3-tts")!;
    expect(qwen.voices.length).toBe(9);
    expect(speechInput("Hola", qwen.id, "Serena", "es")).toEqual({
      text: "Hola", mode: "custom_voice", speaker: "Serena", language: "Spanish",
    });
    expect(speechCatalogue("nl").some((model) => model.id === qwen.id)).toBe(false);
    expect(() => speechInput("Hola", qwen.id, "unknown", "es")).toThrow();
  });
  test("restricts audio downloads to provider output hosts", () => {
    expect(speechOutput("https://replicate.delivery/audio.mp3")).not.toBeNull();
    expect(speechOutput({ audio: "https://replicate.delivery/audio.mp3" })).not.toBeNull();
    for (const url of ["http://127.0.0.1/a", "https://example.com/a", "https://replicate.delivery.evil.test/a", "https://u:p@replicate.delivery/a"]) {
      expect(speechOutput(url)).toBeNull();
    }
  });
  test("voice listing generates no audio", async () => {
    globalThis.fetch = (async () => { throw new Error("Unexpected generation"); }) as unknown as typeof fetch;
    const result = await tts.request("/voices");
    expect(result.status).toBe(200);
    expect((await result.json()).models.length).toBe(4);
  });
  test("caches audio bytes and coalesces previews without leaking credentials to the file host", async () => {
    let calls = 0;
    globalThis.fetch = (async (url: string | URL | Request, init?: RequestInit) => {
      calls++;
      if (String(url).includes("/models/")) {
        expect(new Headers(init?.headers).get("authorization")).toBe("Bearer test-token");
        return Response.json({ status: "succeeded", output: "https://replicate.delivery/test.mp3" });
      }
      expect(new Headers(init?.headers).get("authorization")).toBeNull();
      return new Response(new Uint8Array([1, 2, 3]), { headers: { "content-type": "audio/mpeg" } });
    }) as unknown as typeof fetch;
    const request = () => synthesize("Hola.", "inworld/realtime-tts-1.5-mini", "Ashley", "es");
    const values = await Promise.all([request(), request()]);
    expect([...values[0]]).toEqual([1, 2, 3]);
    expect([...await request()]).toEqual([1, 2, 3]);
    expect(calls).toBe(2);
  });
  test("failed generation is not cached and can be retried", async () => {
    let calls = 0;
    globalThis.fetch = (async () => { calls++; return Response.json({ status: "failed" }); }) as unknown as typeof fetch;
    for (let i = 0; i < 2; i++) {
      await expect(synthesize("Hello", "inworld/realtime-tts-1.5-mini", "Ashley", "en")).rejects.toThrow();
    }
    expect(calls).toBe(2);
  });
  test("rejects empty or oversized narration before generation", async () => {
    for (const text of ["", "a".repeat(1801)]) {
      const response = await tts.request("/", { method: "POST", headers: { "content-type": "application/json" },
        body: JSON.stringify({ text, model: "inworld/realtime-tts-1.5-mini", voice: "Ashley", language: "en" }) });
      expect(response.status).toBe(400);
    }
  });
});

import { existsSync } from "node:fs";
import { mkdtemp, rm, writeFile } from "node:fs/promises";
import { homedir, tmpdir } from "node:os";
import { join } from "node:path";
import { env } from "@/env";
import type { ChatMessage } from "@/ai/client";

/**
 * Last-resort provider: the Codex CLI (`codex exec`) on the server's own ChatGPT login. It is slow (a
 * process per call, a few seconds each) and meant for when every HTTP provider in the pool is out of
 * quota or down, so the app keeps working instead of losing its AI features.
 */

let calls = 0;
let failures = 0;
let lastError: string | null = null;
/** Codex runs one process per call; a burst (a basket of 30 items) must not fork 30 agents at once. */
const MAX_PARALLEL = 2;
let running = 0;
const waiting: Array<() => void> = [];

export function codexStats() {
  if (!env.ai.codex.enabled) return null;
  return { model: env.ai.codex.model, calls, failures, lastError };
}

export function resetCodexStats() {
  calls = 0;
  failures = 0;
  lastError = null;
}

let resolvedBin: string | null | undefined;

/** Finds the codex launcher: AI_CODEX_BIN, then PATH, then Bun's global bin (systemd's PATH lacks it). */
function codexBin(): string | null {
  if (resolvedBin !== undefined) return resolvedBin;
  const candidates = [
    env.ai.codex.bin,
    Bun.which("codex"),
    join(homedir(), ".cache/.bun/bin/codex"),
    join(homedir(), ".bun/bin/codex"),
  ].filter((path): path is string => Boolean(path));
  resolvedBin = candidates.find((path) => (path.includes("/") ? existsSync(path) : Bun.which(path))) ?? null;
  if (resolvedBin && !resolvedBin.includes("/")) resolvedBin = Bun.which(resolvedBin);
  if (!resolvedBin) console.warn("[ai] codex fallback enabled but no codex binary found (set AI_CODEX_BIN)");
  return resolvedBin;
}

async function slot() {
  if (running < MAX_PARALLEL) {
    running += 1;
    return;
  }
  await new Promise<void>((resolve) => waiting.push(resolve));
  running += 1;
}

function release() {
  running -= 1;
  waiting.shift()?.();
}

/** Writes the call's images to files, since `codex exec -i` takes paths, not URLs. */
async function saveImages(messages: ChatMessage[], dir: string) {
  const urls = messages.flatMap((message) =>
    typeof message.content === "string" ? [] : message.content.flatMap((part) => (part.type === "image_url" ? [part.image_url.url] : [])),
  );
  const paths: string[] = [];
  for (const [index, url] of urls.entries()) {
    try {
      let bytes: Uint8Array;
      let type: string;
      const data = /^data:([^;,]+)(;base64)?,(.*)$/s.exec(url);
      if (data) {
        type = data[1];
        bytes = data[2] ? Buffer.from(data[3], "base64") : new TextEncoder().encode(decodeURIComponent(data[3]));
      } else {
        const response = await fetch(url, { signal: AbortSignal.timeout(15_000) });
        if (!response.ok) continue;
        type = response.headers.get("content-type") ?? "image/jpeg";
        bytes = new Uint8Array(await response.arrayBuffer());
      }
      const extension = /png/.test(type) ? "png" : /webp/.test(type) ? "webp" : /gif/.test(type) ? "gif" : "jpg";
      const path = join(dir, `image-${index + 1}.${extension}`);
      await writeFile(path, bytes);
      paths.push(path);
    } catch (error) {
      console.warn(`[ai] codex: could not fetch image ${index + 1}: ${error instanceof Error ? error.message : String(error)}`);
    }
  }
  return paths;
}

/** Flattens the chat into one prompt; images are attached in the order they appear. */
function promptFor(messages: ChatMessage[]) {
  let image = 0;
  const body = messages
    .map((message) => {
      const text =
        typeof message.content === "string"
          ? message.content
          : message.content.map((part) => (part.type === "text" ? part.text : `[attached image ${(image += 1)}]`)).join("\n");
      return `### ${message.role === "system" ? "Instructions" : "Input"}\n${text}`;
    })
    .join("\n\n");
  return `You are answering an API call, not doing a coding task. Do not run commands, read files or edit anything: reply only with the single JSON object the instructions below ask for, with no prose and no code fences.\n\n${body}`;
}

/** Runs one chat call through `codex exec` and returns its raw final message, or null on failure. */
export async function codexChat(messages: ChatMessage[]): Promise<string | null> {
  if (!env.ai.codex.enabled) return null;
  const bin = codexBin();
  if (!bin) return null;
  await slot();
  calls += 1;
  const started = performance.now();
  const dir = await mkdtemp(join(tmpdir(), "baskt-codex-"));
  try {
    const images = await saveImages(messages, dir);
    const output = join(dir, "answer.txt");
    const args = [
      "exec",
      "--model", env.ai.codex.model,
      "--skip-git-repo-check",
      "--ephemeral",
      "--sandbox", "read-only",
      "--color", "never",
      "--cd", dir,
      "-c", `model_reasoning_effort="${env.ai.codex.reasoning}"`,
      "--output-last-message", output,
      ...images.flatMap((path) => ["--image", path]),
      "-",
    ];
    const child = Bun.spawn([bin, ...args], {
      cwd: dir,
      stdin: new TextEncoder().encode(promptFor(messages)),
      stdout: "ignore",
      stderr: "pipe",
      timeout: env.ai.codex.timeoutMs,
      killSignal: "SIGKILL",
    });
    const [code, stderr] = await Promise.all([child.exited, new Response(child.stderr).text()]);
    const answer = (await Bun.file(output).exists()) ? (await Bun.file(output).text()).trim() : "";
    if (code !== 0 || !answer) {
      failures += 1;
      lastError = (child.signalCode ? `killed (${child.signalCode}, timeout?)` : `exit ${code}`) + `: ${stderr.trim().slice(-200)}`;
      console.warn(`[ai] codex ${env.ai.codex.model} failed, ${lastError}`);
      return null;
    }
    lastError = null;
    console.log(`[ai] codex ${env.ai.codex.model} answered${images.length ? ` (${images.length} image${images.length > 1 ? "s" : ""})` : ""}, ${Math.round(performance.now() - started)} ms`);
    return answer;
  } catch (error) {
    failures += 1;
    lastError = error instanceof Error ? error.message : String(error);
    console.warn(`[ai] codex failed: ${lastError}`);
    return null;
  } finally {
    release();
    await rm(dir, { recursive: true, force: true }).catch(() => {});
  }
}

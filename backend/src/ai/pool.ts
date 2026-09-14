import { aiPool, env, type AiProfile, type AiProvider } from "@/env";

type Health = {
  calls: number;
  failures: number;
  promptTokens: number;
  completionTokens: number;
  /** Epoch ms until which this provider is skipped (rate limited or failing). */
  cooldownUntil: number;
  lastError: string | null;
};

const health = new Map<string, Health>();
/** Round-robin position per pool; one step per call, so equal-priority providers alternate. */
const cursors = new Map<string, number>();

function entry(id: string): Health {
  let found = health.get(id);
  if (!found) {
    found = { calls: 0, failures: 0, promptTokens: 0, completionTokens: 0, cooldownUntil: 0, lastError: null };
    health.set(id, found);
  }
  return found;
}

/** Milliseconds left before a cooled-down provider may be used again (0 when it is ready). */
export function cooldownLeft(id: string) {
  return Math.max(0, (health.get(id)?.cooldownUntil ?? 0) - Date.now());
}

/**
 * The order to try the providers of a pool in for one call: lowest priority number first, round-robin
 * between equal priorities, and providers that are cooling down moved to the back (a rate-limited key is
 * still better than no answer once everything else failed).
 */
export function attemptOrder(profile: AiProfile = "default"): AiProvider[] {
  const pool = aiPool(profile);
  if (pool.length < 2) return pool;
  const cursor = (cursors.get(profile) ?? 0) + 1;
  cursors.set(profile, cursor);
  const priorities = [...new Set(pool.map((provider) => provider.priority))].sort((a, b) => a - b);
  const ordered = priorities.flatMap((priority) => {
    const group = pool.filter((provider) => provider.priority === priority);
    const offset = cursor % group.length;
    return [...group.slice(offset), ...group.slice(0, offset)];
  });
  return [...ordered.filter((provider) => !cooldownLeft(provider.id)), ...ordered.filter((provider) => cooldownLeft(provider.id))];
}

/**
 * How long to stop using a provider after an HTTP error: a metered key honours Retry-After, a key the
 * provider rejects outright (bad token, no access to the model) sits out a minute rather than costing
 * every call a round trip, and a server or transport error gets a short pause. A 400 is about the
 * request, not the provider, so it does not count against it.
 */
export function cooldownFor(status: number, retryAfter: string | null = null): number {
  if (status === 429) {
    const seconds = Number(retryAfter);
    return Math.min(60_000, (Number.isFinite(seconds) && seconds > 0 ? seconds : 5) * 1000);
  }
  if (status === 401 || status === 402 || status === 403 || status === 404) return 60_000;
  if (status >= 500) return 15_000;
  return 0;
}

export function noteCall(id: string) {
  entry(id).calls += 1;
}

export function noteUsage(id: string, promptTokens: number, completionTokens: number) {
  const record = entry(id);
  record.promptTokens += promptTokens;
  record.completionTokens += completionTokens;
  record.cooldownUntil = 0; // a success proves the provider is healthy again
  record.lastError = null;
}

export function noteFailure(id: string, error: string, cooldownMs = 0) {
  const record = entry(id);
  record.failures += 1;
  record.lastError = error.slice(0, 200);
  if (cooldownMs > 0) record.cooldownUntil = Math.max(record.cooldownUntil, Date.now() + cooldownMs);
}

/** Per-provider counters for /health and the benchmark. */
export function poolStats() {
  const pools: Record<string, unknown[]> = {};
  for (const profile of ["default", "assistant", "vision", "stt"] as const) {
    if (profile === "assistant" && !env.ai.assistant.length) continue; // shares the chat pool
    const providers = aiPool(profile);
    if (!providers.length) continue;
    pools[profile] = providers.map((provider) => {
      const record = entry(provider.id);
      return {
        id: provider.id,
        model: provider.model,
        priority: provider.priority,
        calls: record.calls,
        failures: record.failures,
        promptTokens: record.promptTokens,
        completionTokens: record.completionTokens,
        cooldownMs: cooldownLeft(provider.id),
        lastError: record.lastError,
      };
    });
  }
  return pools;
}

export function resetPoolStats() {
  health.clear();
}

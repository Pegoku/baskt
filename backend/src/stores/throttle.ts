import { env } from "@/env";

export class StoreCooldownError extends Error {
  constructor(store: string, public readonly until: number) {
    super(`${store} is cooling down until ${new Date(until).toISOString()}`);
    this.name = "StoreCooldownError";
  }
}

export type StoreHealth = { store: string; cooldownUntil: number | null; lastRequestAt: number | null; requests: number; failures: number };

/** Serializes requests per store with a minimum gap and a cooldown after repeated rate limiting. */
export class StoreThrottle {
  private chain: Promise<unknown> = Promise.resolve();
  private lastRequestAt = 0;
  private cooldownUntil = 0;
  private rateLimitStrikes = 0;
  private requests = 0;
  private failures = 0;

  constructor(
    private readonly store: string,
    private readonly minGapMs = env.storeMinGapMs,
    private readonly cooldownMs = 10 * 60 * 1000,
  ) {}

  run<T>(task: () => Promise<T>): Promise<T> {
    const result = this.chain.then(async () => {
      if (Date.now() < this.cooldownUntil) throw new StoreCooldownError(this.store, this.cooldownUntil);
      const wait = this.lastRequestAt + this.minGapMs + Math.random() * this.minGapMs * 0.5 - Date.now();
      if (wait > 0) await new Promise((resolve) => setTimeout(resolve, wait));
      this.lastRequestAt = Date.now();
      this.requests += 1;
      try {
        const value = await task();
        this.rateLimitStrikes = 0;
        return value;
      } catch (error) {
        this.failures += 1;
        throw error;
      }
    });
    this.chain = result.catch(() => undefined);
    return result;
  }

  /** Called by adapters when the upstream answered 429/403 even after retries. */
  noteRateLimited() {
    this.rateLimitStrikes += 1;
    if (this.rateLimitStrikes >= 2) {
      this.cooldownUntil = Date.now() + this.cooldownMs;
      this.rateLimitStrikes = 0;
    }
  }

  health(): StoreHealth {
    return {
      store: this.store,
      cooldownUntil: this.cooldownUntil > Date.now() ? this.cooldownUntil : null,
      lastRequestAt: this.lastRequestAt || null,
      requests: this.requests,
      failures: this.failures,
    };
  }
}

import { env } from "@/env";

export class HttpError extends Error {
  constructor(
    public readonly status: number,
    public readonly url: string,
    public readonly body: string,
  ) {
    super(`HTTP ${status} for ${url}`);
    this.name = "HttpError";
  }
}

const RETRYABLE = new Set([403, 408, 425, 429, 500, 502, 503, 504]);

export type RetryOptions = {
  retries?: number;
  baseDelayMs?: number;
  maxDelayMs?: number;
  onRateLimited?: (status: number) => void;
};

function retryDelay(attempt: number, base: number, max: number, retryAfter: string | null) {
  const header = retryAfter ? Number(retryAfter) : NaN;
  if (Number.isFinite(header) && header > 0) return Math.min(header * 1000, max);
  const exponential = base * 2 ** (attempt - 1);
  return Math.min(max, exponential + Math.random() * base);
}

const sleep = (ms: number) => new Promise((resolve) => setTimeout(resolve, ms));

/** fetch() with exponential backoff on rate limiting / server errors. Supermarket hosts go through STORE_PROXY_URL when set. */
export async function fetchWithRetry(url: string, init: RequestInit = {}, options: RetryOptions = {}) {
  const retries = options.retries ?? 3;
  const base = options.baseDelayMs ?? 1500;
  const max = options.maxDelayMs ?? 20_000;
  let lastError: unknown;

  for (let attempt = 1; attempt <= retries + 1; attempt += 1) {
    try {
      const response = await fetch(url, {
        ...init,
        signal: init.signal ?? AbortSignal.timeout(25_000),
        // Bun supports a per-request proxy option.
        ...(env.storeProxyUrl ? { proxy: env.storeProxyUrl } : {}),
      } as RequestInit);

      if (response.ok) return response;

      const body = await response.text().catch(() => "");
      lastError = new HttpError(response.status, url, body.slice(0, 500));
      if (response.status === 429 || response.status === 403) options.onRateLimited?.(response.status);
      if (!RETRYABLE.has(response.status) || attempt > retries) throw lastError;
      await sleep(retryDelay(attempt, base, max, response.headers.get("retry-after")));
    } catch (error) {
      if (error instanceof HttpError) {
        if (attempt > retries || !RETRYABLE.has(error.status)) throw error;
        continue;
      }
      lastError = error;
      if (attempt > retries) throw error;
      await sleep(retryDelay(attempt, base, max, null));
    }
  }
  throw lastError;
}

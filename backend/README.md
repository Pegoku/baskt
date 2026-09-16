# baskt backend

Bun + Hono + Drizzle (SQLite) API that turns grocery *ideas* into concrete products at Dutch supermarkets and compares baskets between stores. The Android app in `../android` is a client of this API.

## Run

```bash
cp .env.example .env   # set APP_API_TOKEN, AI_API_KEY, AI_MODEL
bun install
bun run dev            # http://localhost:3000
bun test
```

Docker: `docker compose up -d --build` (data lives in the `baskt-data` volume).

Without `AI_API_KEY`/`AI_MODEL` the API still works: ideas are searched as typed and ranked by text similarity.

## How matching works

1. `POST /api/v1/basket/items {text}` stores the idea and starts a background job.
2. The idea is parsed by the AI into a canonical name, attributes, size hint and one Dutch search query per store (cached).
3. Each enabled store is searched through a 24 h cache with a per-store throttle (min gap + backoff + cooldown).
4. Candidates are ranked (AI with the user's remembered preferences, text similarity as fallback).
5. The app shows 3 options per store. `choose` confirms one, `reject` ("none of them fit") records the rejected titles and reveals the next 3, searching wider (AI-suggested alternative query) when the list runs out. Every choice/rejection is stored in `choices` and fed back into later rankings; an identical idea is auto-matched to what was chosen before.
6. `GET /api/v1/basket/compare` computes per-store totals (full and "comparable" over items present everywhere), cheapest store per item, unit-price-aware hints and a mix-and-match minimum.

## Choosing an AI model

`bun run bench` runs the app's real AI tasks (idea parsing, alternative queries, candidate ranking, deal
search expansion, recipe localisation and full assistant turns with tools) against several models and writes
a markdown report to `bench/` with pass rate, latency and cost per model. It uses an in-memory database, so
your data and caches are untouched; the API key comes from `.env`.

```bash
bun run bench                                             # default model list
bun run bench -- --models openai/gpt-oss-20b,openai/gpt-oss-120b --runs 2
bun run bench -- --only assistant                         # one group: parse, alternatives, rank, deals, localize, assistant
```

Add a check by appending to `cases` in `scripts/llm-bench.ts`; add a model by listing its OpenRouter id.

## Adding a supermarket

Implement `StoreAdapter` (`src/stores/types.ts`) in `src/stores/<store>.ts` and add it to the list in `src/stores/registry.ts`. Nothing else changes: the app reads the store list from `GET /api/v1/stores`.

## API

All routes are under `/api/v1` and require `Authorization: Bearer $APP_API_TOKEN` (unless the token is empty).

| Method | Path | Purpose |
|---|---|---|
| GET | `/health` | status, AI config, store throttle health |
| GET | `/stores` | store list with `enabled` flag |
| GET/PATCH | `/settings` | `{enabledStores: [...]}` |
| GET | `/basket?since=` | items with matches/options, `deletedIds`, `processing` |
| POST | `/basket/items` | `{text, quantity}` → item (matching runs in background) |
| POST | `/basket/from-text` | `{text}` → split a pasted list into items |
| PATCH/DELETE | `/basket/items/:id` | edit (`text`, `quantity`, `checked`, `sortOrder`) / delete |
| DELETE | `/basket?checked=true` | clear checked (or all) items |
| POST | `/basket/items/:id/rematch` | rerun matching, dropping user choices |
| POST | `/basket/items/:id/matches/:store/choose` | `{productId}` or `{productId: null}` (= not at this store) |
| POST | `/basket/items/:id/matches/:store/reject` | none of the shown options fit → next 3 |
| POST | `/basket/items/:id/matches/:store/search` | `{query}` manual search for more options |
| GET | `/basket/compare` | comparison across enabled stores |
| GET | `/products/search?q=&store=` | raw cached store search |
| GET | `/products/:id`, `/products/:id/price-history` | product and price points |
| GET/DELETE | `/memory`, `/memory/:id` | remembered choices |
| GET | `/admin/stats` | counters and store cooldowns |
| POST | `/admin/refresh` | re-price products referenced by the basket |
| POST | `/admin/clear-cache?ai=true` | drop the search (and AI) cache |

## Offline replay and upstream caching

Authenticated mutations accept an optional `Idempotency-Key` (16–128 alphanumeric/hyphen characters).
The Android outbox uses a fresh UUID per action and keeps it for retries. Successful JSON/204 responses
are stored in SQLite and replayed for identical requests; reusing the key with another method, path
or payload returns 409. Concurrent duplicates are serialized. Failed responses remain retryable.
Receipts are retained indefinitely so a device returning after a long absence cannot replay an old
successful creation. Back up these alongside the application database. This protects response-loss
retries; a process crash between a mutation and writing its receipt is not an atomic exactly-once
transaction.

The migration adds `upstream_cache` and `mutation_receipts`. Upstream cache entries persist across
restarts, merge simultaneous misses and serve bounded stale results if an upstream request fails:

| Content | Fresh for | Additional stale-on-error window |
| --- | --- | --- |
| Recipe page contents | 24 hours | 7 days |
| Per-source recipe searches | 1 hour | 1 hour |
| Barcode → product ID (including misses) | 6 hours | 24 hours |

Expired entries beyond their stale window are pruned on cache writes. Null recipe extraction results
are not cached; successful empty searches/barcode misses are. Barcode caches reference the current
product record, so cached lookups do not overwrite newer prices. Existing product-search, AI and
promotion caches remain in use. Mutable baskets, recipes owned by the user, stock, chat turns and
receipt recognition are not placed in this shared upstream cache.

`POST /basket/groups` accepts explicit empty `items` for an empty folder and optional `recipe` metadata
for downloaded cooking instructions. Transfer responses include `children` to map copied offline
IDs. Serving changes can include explicit `children` (`id`, `text`, `quantity`) plus
`recipe.ingredientLines`; the server preserves child IDs and rejects a changed ingredient set with
409 instead of deleting newer server ingredients.

### Speech previews and logs

TTS uses the Hack Club Replicate proxy. Set `REPLICATE_API_TOKEN` in the backend's ignored `.env`, and
`REPLICATE_2` … for further accounts: the pool round-robins between them, and a token that reports its
daily spending cap is parked for half an hour while the others keep answering.
The app selects models and voices from the server catalogue. Qwen3 TTS uses its nine preset
speakers in `custom_voice` mode with an explicit language; MiniMax sends `language_boost`
matching the selected language.

From `backend/`:

- `bun run tts:previews es` generates all Spanish MiniMax and Qwen3 samples, two at a time.
  Existing cached samples are reused. Failed samples are retried and reported with a nonzero exit status.
  A daily spending cap stops the batch immediately; rerun the same command after it resets or is increased.
- `bun run logs` shows the latest 50 speech log entries.
- `bun run logs --follow` follows new entries.
- `bun run logs --lines 10 --remote` reads recent predictions from the provider, including the
  language/boost actually stored on each request.

Local speech logs are in `data/logs/speech.jsonl` (beside the configured database), with one
rotated file at 5 MB. Override with `SPEECH_LOG_PATH`. They record metadata, cache use,
prediction IDs and provider-confirmed language settings, never keys, spoken text or audio URLs.
Provider history may expire; local logs retain confirmation for newly generated speech.
Audio previews are cached for 90 days and are generated only on demand unless the preview
command above is explicitly run.

Export cached Spanish previews to MP3 without contacting the provider:

```sh
bun run tts:export es
# Or choose a destination:
bun run tts:export es /path/to/voices
```

Default output: `backend/data/tts-export/es/`, grouped by model and named by voice ID.
MiniMax MP3s are copied directly. Qwen WAVs are converted locally with `ffmpeg`.
Existing files are skipped; missing cached previews are listed without generating them.

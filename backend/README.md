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

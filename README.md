# baskt

A basket of *ideas*, not products. Type "halfvolle milk" and baskt finds the matching products at Albert Heijn and Jumbo, lets you pick which one you mean (3 suggestions at a time, "none of these fit" shows the next 3), remembers your picks, and at checkout compares what the basket costs per supermarket.

```
backend/   Bun + Hono + SQLite API: store adapters, AI parsing/ranking, preference memory, comparison
android/   Kotlin + Jetpack Compose (Material 3 Expressive) client
```

## Quick start

1. Backend (on your home server or laptop):
   ```bash
   cd backend
   cp .env.example .env      # set APP_API_TOKEN, AI_API_KEY, AI_MODEL (Hack Club AI works)
   bun install && bun run dev
   ```
   or `docker compose up -d --build`.
2. App: install `android/app/build/outputs/apk/debug/app-debug.apk`, open Settings, enter the server URL (e.g. `http://192.168.1.10:3000`) and the token, tap **Save & test**.
3. Add ideas. Tap an item to choose between the suggested products per store. Tap **Compare**.

## Build the APK

```bash
cd android
JAVA_HOME=/opt/android-studio/jbr ./gradlew :app:assembleDebug   # any JDK 17+ works
```

`android/local.properties` must point `sdk.dir` at an Android SDK (compileSdk 37 is downloaded automatically when licences are accepted).

## Product details and translations

Tap a product row or thumbnail to open its images, price, size, category, and available store
description. Tap an image in the detail sheet to enlarge it. Descriptions come from the store;
products without published descriptions still show their existing facts and a source-page link.

Product text, recipe titles, descriptions, ingredient lines, steps, and matching explanations
are translated into the language selected in Settings (or the device language for System).
The top-corner document icon shows the original; it changes to a translate icon to switch back.
Hover or long-press for the action tooltip. The same image enlargement is available in recipes.
Editable source text stays intact; translations are for display and are cached per language.

Translations require the configured backend AI on first use. Saved translations remain available
offline; failures explicitly show the original. Older downloaded recipes are refreshed when online
to retain their complete original content. Install the updated backend with the Android app.

## Barcode scanning

The continuous scanner uses Google's bundled ML Kit. Aim near the frame: detection extends
65% of the frame’s height above and below it;
three matching, checksum-valid readings confirm a capture with haptic feedback. Tap the preview
to focus, use the light in dim rooms, or switch to 2× zoom for smaller labels.

Keep scanning distinct products, then tap **Review** to pause the camera and expand your batch.
Add products individually or add all recognised, unhandled products to your list or stock.
Stock actions count only eligible products: add missing items or remove items already in stock.
Move a barcode away from the camera and back to rescan a completed or dismissed item.
Holding the same label in view does not repeatedly capture it. Unknown products stay available
for **Retry lookup**. **Done** closes the session;
only products you explicitly added are saved.

## AI providers

The backend takes a list of OpenAI-compatible providers instead of one. `AI_BASE_URL`/`AI_API_KEY`/
`AI_MODEL` describe the first, and every other one is a single line — `AI_2` … `AI_9` — holding
`endpoint, key, model, priority`:

```bash
AI_2=gsk_...                                              # another key for the same endpoint and model
AI_3=https://openrouter.ai/api/v1, sk-or-..., openai/gpt-oss-20b, 2
AI_4=model=qwen/qwen3.8-27b, reasoning=high               # or name the fields you mean
```

Fields are recognised by shape: the endpoint is the one with `://`, a bare number is the priority, and
the remaining values are the key and then the model. Leave out whatever stays the same as the provider
above — a second account really is one word. `key=`, `model=`, `url=`, `priority=`, `reasoning=`,
`order=` and `quant=` name a field explicitly (the last two carry OpenRouter's routing and use `|`
between values, since commas separate fields).

Priority (default 1) decides the order: the lowest number is tried first and providers sharing a number
are used round-robin, which spreads the load over both keys and doubles a per-minute quota. A paid
fallback at priority 2 is only used when the free ones are exhausted. A provider that fails is skipped
until it cools down — `Retry-After` when it is metered, a minute when it rejects the key or the model,
15 s after a server or network error — and the next provider answers the same call instead of the user
waiting. Receipt vision (`AI_VISION_*`, `AI_VISION_2` …), the assistant (`AI_ASSISTANT_*`) and dictation
(`AI_STT_*`) are separate pools that balance the same way. `GET /api/v1/health` reports per-provider
calls, failures, tokens and cooldowns.

## Dictation

Tap the microphone and speak a whole list ("two milk, six eggs, and forget the rice, I already have
it"): the recording goes to `whisper-large-v3` on the server, which is faster and far more accurate
than the phone's recogniser, and the transcript is interpreted into a proposal you confirm. Without a
connection — or when no server provider answers — the app falls back to Android's on-device recogniser
and the same confirm screen.

Groq serves Whisper on the chat key, so every Groq provider in the pool transcribes too and nothing has
to be configured. `AI_STT_MODEL` switches model (`whisper-large-v3-turbo` is faster), `AI_STT_1` … give
dictation its own endpoint and keys when the chat models live somewhere without Whisper, and `AI_STT=off`
keeps every dictation on the phone.

## Adding a supermarket

Write one adapter in `backend/src/stores/` implementing `StoreAdapter` and register it in `backend/src/stores/registry.ts`. The app fetches the store list from the server, so it needs no change.

See `backend/README.md` for the API.

## Offline use

Open the updated app while connected once to download your data. Baskets, stock, your own recipes,
recipe favourites (including ingredients and cooking steps), purchases and chat history are saved on
the device. Favourite recipe images are downloaded on a best-effort basis; Android may evict image
caches. Previously viewed product searches, barcodes, deals and price histories can also be reused.
A recipe that has never been downloaded still needs a connection to open.

Everyday edits are applied locally and recorded in a durable outbox: basket/folder creation and
editing, moving/copying items, shopping checks, quantities, product picks and thumbs up/down, stock,
recipe creation/editing/deletion, favourites, settings, saving an already scanned receipt, purchase
removal, clearing chat history and forgetting preferences. Comparisons recalculate using saved
prices. New items wait for server-side product matching. Offline multiline input splits at newlines,
commas and semicolons; AI interpretation waits for a connection.

The app replays changes in order when the server returns. An Android background job also requests
sync after the app closes or the device restarts; Android controls when background jobs run. Opening
the app or tapping Retry requests an immediate check. Temporary IDs and successful operation
receipts survive restarts. Rejected edits remain in Settings → Offline changes for retry or discard;
discarding a creation also discards edits that depend on it. Local edits win for fields they change
when replayed; other server changes are fetched afterward. Server-side deletions or conflicting
recipe ingredient changes can require discarding the rejected edit and refreshing.

Chatbot turns, recipe discovery/AI generation, receipt recognition, new product searches/matching,
fresh prices, and sending to WhatsApp require the server. Cached prices, deals and spending summaries
are snapshots, not a guarantee of current store availability. Deploy the updated backend alongside
the APK to enable replay deduplication and offline recipe folder syncing. No data is uploaded to a
new server when changing settings: pending edits must first be synced or discarded, and each
server/credential has a separate local cache.

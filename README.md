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

Speech synthesis has the same pools under `REPLICATE_API_TOKEN` / `REPLICATE_2` … — one Replicate (or
Hack Club proxy) account per line, each with its own daily spending cap, so a cap that is reached moves
the next clip to the next token instead of failing.

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

## Duplicates

**Find duplicates** in the basket menu scans the open items. Entries that spell the same thing (or
whose parsed generic names match) are grouped without AI; the model then looks for the rest: the same
product in another language, singular/plural, or a generic entry next to a specific one. Real variants
(whole vs semi-skimmed milk) are left alone. Each group gets one decision: merge into a single entry
(keeps the one with the best product picks and sums the quantities), keep exactly one, or keep all.
Nothing changes until you tap **Apply**; the edits go through the normal item operations, so they queue
offline like any other change.

## Tidy up

The wand in the basket's top bar opens a full-screen review of the open items before anything changes. Duplicates that nobody has decided about are merged: one entry survives (the one with your
picks, else the furthest matched, else the oldest) and you choose per merge whether to keep just that
one or add the amounts up. Look-alikes that each
have their own chosen product (two different chocolate cookies, both picked at AH) are kept apart and
only renamed so you can tell them apart. Finally every surviving entry is rewritten in the app language:
translations ("melk" → "milk"), misspellings ("Yorkham" → "jamón york"), brands and sizes kept as
written. Promotions already known for an entry show up under that entry as "3 deals available", most
relevant first (the same product beats one that only shares a word); none is chosen until you pick one,
and picking makes it the pick at that store, like **Use this deal** on the deals screen. Ticks, choices
and the plan itself survive a rotation. Untick what you want to keep; renames go through `keepMatches`, so the picks survive the new
wording. Without an AI provider only literal repeats are merged and nothing is renamed.

## Comment mode

Inside **Tidy up**, hold a proposal to select it (or tap once comment mode is on; the top bar gets a
select all / unselect all button). The comment toggle in the top bar turns comment mode off or on; it
is on by default. Then write what should change about the selected proposals: "double the
cookies amount" sets the amount on that rename, "it should be bolsa de lechugas" replaces the proposed
wording, "no uses la oferta" drops a deal, "quiero 3 de leche" fixes a merge's amount. The assistant
revises only the selected proposals and answers on the screen when something cannot be done. Wording
corrections are remembered: the next tidy pass and later translations use your words straight away, and
the learned wordings can be forgotten from the memory screen.

## Find similar

Open any product (from a basket item, a search, a scan or a deal) and tap **Find similar at other
stores**. The server works out what the product is and searches every enabled store for it: first
the exact product with its brand, then the same type and size, then the store's own equivalent. Results
per store are labelled **Same product** (same brand, variant and pack), **Similar** (same thing from
another brand or in another pack size, with a short note on what differs) or **Substitute**. The
product's own store lists alternatives instead. Up to 12 results per store come back; the sheet shows
four and **Show more** unfolds the rest. The add button pins that product for its store and matches the
other stores as usual, exactly like adding from a scan. The last answer is kept for offline viewing.

When a vision pool is configured (`AI_VISION_*`, the same one that reads receipts; gpt-oss cannot see
images, so this is Gemini or another picture model), the packaging pictures are compared as well as the
titles: the reference picture is read first to work out brand, variant and flavour, then every candidate
picture is compared against it in groups of four. Without a vision provider the comparison is by name and
size only, and the sheet says so.

Every result has thumbs. A thumbs down hides that pairing for good and its place goes to the next
candidate; a thumbs up pins it first as **Confirmed**. Both are told to the model on later lookups so
that look-alikes of what you confirmed rank higher and look-alikes of what you rejected rank lower.
Tap a thumb again to take it back.

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

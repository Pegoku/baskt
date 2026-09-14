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

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

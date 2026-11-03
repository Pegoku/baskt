# AH: scan with the phone, pay at the till

Two parts: the **till relay** that ships now (experimental), and the notes for capturing the Appie app's own
zelfscan traffic so a real integration can follow.

## 1. Till relay (in the app now)

Turn on **Settings → Experimental → Scan with the phone, pay at the till**.

In shop mode:

- Every scan is kept on the phone exactly as the camera read it (text and barcode type).
- A scan that matches a list item ticks it and shows **Scanned for the till** on that row.
- A scan no list item claims (typically a weighed label) gets a **This is…** button: pick the item it belongs to.
  That ticks the item and tells the server what kind of code the item carries.
- Each open row says what to do: *Scan the barcode on the pack*, *Weighed · scan the label after weighing*,
  *No barcode · the till does this one*, or *Scan it when you pick it* (not learnt yet).
- The till button (top bar, with a count) opens the checkout screen.

Checkout screen: the loyalty card first, then each code in turn at full brightness. It runs by itself at the
chosen pace (0.8 / 1.2 / 2 / 3 s). Tap left to go back, right to go on, middle to pause. Volume up goes on,
volume down goes back. Two identical codes in a row get a short blank between them so the scanner does not
ignore the second. A pack item with quantity 3 scanned once is shown 3 times. The last page lists items ticked
off without a scan, with **No barcode** to record items that really have none. **Paid · clear codes** empties
the list for the store.

### What to check in the store

1. Does the till read EAN-13 from the screen at all? (The Bonuskaart already works, so it probably does.)
2. Weighed labels: EAN-13 starting with `2`, and the GS1 DataMatrix on meat and cheese. Does the till accept
   them re-drawn? The DataMatrix is re-encoded as GS1 (FNC1 kept), which unit tests check by decoding it again.
3. Pace: which interval is fastest without missed or double beeps.
4. Anything the till refuses: note the item and the raw code shown under the barcode.

### The map of which items need what

The server keeps it in the `barcodeKinds` setting (backend/data/baskt.db, `settings` table):

```sh
curl -H "Authorization: Bearer $APP_API_TOKEN" "$BASE/api/v1/purchases/barcode-kinds?store=AH"
```

Each entry has `kind` (`pack`, `label`, `none`), the product id or item text, and the last 5 raw codes.

## 2. Capturing Appie's zelfscan (for the real integration)

Self-scan is **not** on the public `api.ah.nl` and **not** in the GraphQL schema (checked against the
gwillem/appie-go 2026-01-18 dump). Decompiling Appie 9.47.3 (Flutter app, but the self-scan feature is native
Kotlin under `nl.ah.appie...selfscan`) gave the shape below.

### What the APK revealed

- **Host:** `https://selfscan.store.ah.nl:8881/api/selfscan/v2.0/` (class `qhs`, the `AppieSelfscanEnvironment`).
  A second `HandscannerSelfscanEnvironment` adds a websocket endpoint, for the store's physical handheld
  scanners, not the phone.
- **`selfscan.store.ah.nl` resolves to `172.16.8.3`** (a private, in-store-only address. AH's public DNS hands
  out a LAN IP), so this server is reachable **only from a device on the store's Wi-Fi**. The baskt backend (at
  home) can never reach it. A real self-scan client therefore has to live in the **Android app** and run only on
  store Wi-Fi; this is the one place baskt talks to a store directly.
- **Session = a "trip" keyed by UUID.** REST paths:
  - `trips/{uuid}` (the session; GET reads the basket)
  - `trips/{uuid}/items`, `trips/{uuid}/items/{barcode}` (add / adjust; fields `barcode`, `quantity`, `symbology`)
  - `trips/{uuid}/totals`
  - `trips/{uuid}/payment/status`
  - `trips/{uuid}/exitgate/status` (gate code after paying)
- **DTOs** under `nl.ah.appie.dto.selfscan` / `.zelfscan`: `Basket`, `Item` (+ `Item.Icons`), `UnitInfo`,
  `Subtotals`, `Discount`, `BonusInfo`, `Campaigns`, `Message`/`MessageArgs`, `Symbology`, `Redirect`,
  `PaymentStatusResult`, `PaymentTotalsResult`, `ExitgateStatusResult` (+ `.Status`), `ServiceResult`
  (`MemberType`, `StoreFormat`, `StoreType`), `TransactionInfoData` (+ `PaymentMethod`).
- **Headers seen near the client:** `X-Application`, `Appie-Current-Order-Id`, `AH-Payment-Method`,
  `X-Correlation-Id`, `Content-Type: application/json`, `User-Agent: Android`, app version `9.47.3`. The exact
  auth header is in an obfuscated OkHttp interceptor (`c07`); confirm it with the capture rather than guessing.

### Payment happens at the till, not in baskt

Confirmed from the app's own strings ("Go to a self-checkout and scan this code", "First, complete the payment at
the self-checkout", "You can pay with this at the cash registers on the Zelfscan square"): the standard zelfscan
flow scans items in the app, then shows a handoff code you scan at a Zelfscan self-checkout, where you pay with a
normal bank card / PIN. In-app "Online payment" (Adyen: iDEAL, card, AH Wallet) exists but is optional.

So a baskt self-scan client only needs to: (1) start a trip, (2) add each scanned item, (3) show the checkout
handoff code. It never touches payment (no Adyen, no money flow through baskt), which keeps it in the read-only
scope. Other features seen: weighed/loose produce (scan the scale label), Bonus / Bonus Box / coupons / Koopzegels
applied live, deposit (statiegeld) and gift cards, age-restricted items verified by staff, random checks
("controle"), and an exit-gate code after paying.

### Confirmed protocol (decrypted in-store capture, 2026-11-03, store 1379)

Captured a full real session (raw pcap + TLS keylog + decoded transcript in `backend/data/captures/`, git-ignored;
they hold the member's bonuskaart and a session token, so keep them local). All HTTP/1.1, JSON. No OAuth/bearer.

Auth is a set of `AH-*` headers, not `Authorization`:

- `AH-App-Name: Albert Heijn`, `AH-App-Version: 9.47.3`, `AH-Device-Platform: Android`,
  `AH-Device-Name: Pixel 8 Pro`, `AH-Device-Version: 17`, `User-Agent: Appie/9.47.3 (Android 17-API37; ...)`
- `AH-Device-Uuid: <uuid>`: a per-device UUID; it is also the trip/session id in the path.
- `AH-Uid: <bonuskaart>`: the member's bonuskaart number (e.g. `26230...`), empty on the very first `/service` call.
- `AH-Token: <token>`: a per-session token obtained from `GET /sessions/{uuid}` (below); sent on every call after.

Base `https://selfscan.store.ah.nl:8881/api/selfscan/v2.0/`. Flow:

1. `GET /service` -> `{"result":{"storeNumber":1379,"storeFormat":"Albert Heijn","storeType":"regular"}}`. Polled
   repeatedly as a heartbeat throughout.
2. `GET /sessions/{uuid}?terms=` -> `{"result":{"token":"<AH-Token>","eanRis":[{minRange,maxRange,eanType:"BOK"},…]}}`.
   This hands out the session token; `eanRis` are the in-store variable-barcode ranges (weighed/BOK items).
3. `GET /trips/{uuid}` -> the basket. It starts with the Bonuskaart as item 0
   (`{"description":"Bonuskaart","barcode":"<bonuskaart>","flags":["BOK"]}`).
4. `POST /trips/{uuid}/items`  body `{"barcode":"8719587149300","quantity":1}` -> returns the full updated basket:
   per item `description` (e.g. "PIZZADEEG"), `price` (cents), `quantity`, `nasaNr`, `flags` (ART/BONUS/AKTIE/…),
   `bonusInfo.promoLabel`; plus `discounts[]` (bonus text + `discount` cents) and `totals`
   (`subtotal`, `bonusDiscount`, `personalDiscount`, `discount`, `itemCount`, …) and a `transactionId`.
5. `PUT /trips/{uuid}/items/{barcode}`  body `{"quantity":N}` -> set quantity; **`{"quantity":0}` removes** the item.
6. `GET /trips/{uuid}/payment/totals` -> `{"message":{"code":"GOTO_PAYTOWER","message":"Ga naar een betaalpaal en
   scan onderstaande streepjescode"},"result":{"totals":{"total":1352,…},"barcode":"<bonuskaart>",
   "symbology":"EAN13","imageUrl":".../assets/barc/P<bonuskaart>.gif","showToPay":true}}`.
7. `GET /trips/{uuid}/payment/status?lastAttempt=false` -> `status: paying` ("Volg de instructies op de betaalpaal")
   then `status: paid` ("Je hebt betaald. Scan onderstaande code om het hekje te openen"), barcode = the bonuskaart.
8. `GET /trips/{uuid}/exitgate/status` -> gate state.

**Payment is 100% at the betaalpaal with your own card.** The "code to scan at checkout" and the gate code are
both just your **Bonuskaart EAN13** (the same barcode baskt already renders for loyalty cards). The app does not
mint a special checkout code and does not handle the payment.

### What a baskt (Android, store-Wi-Fi-only) read-only client needs

1. Persist a device UUID; set the `AH-*` headers above. Use the member's bonuskaart (baskt already has the AH
   account) as `AH-Uid`.
2. `GET /service`, then `GET /sessions/{uuid}?terms=` to get `AH-Token`.
3. For each scan: `POST /trips/{uuid}/items {barcode,quantity}`; show the returned basket (names, cents, bonus).
   Quantity edits via `PUT .../items/{barcode} {quantity}` (0 = remove).
4. At checkout: just show the Bonuskaart barcode (EAN13) for the betaalpaal. No payment code, no Adyen.

Open questions remaining: whether `GET /sessions` needs the `{uuid}` pre-registered with the member via an
`api.ah.nl` call (none was seen before it in the capture, so likely not), and the `eanRis`/`flags` semantics for
weighed items. Both can be read from the saved transcript.

### Non-rooted capture pipeline (built 2026-11-03, phone `42271FDJG0019N`)

Appie's `network_security_config` does not trust user CAs and there is no app-level pinning on this host, so the
app is repackaged to trust a user CA; then PCAPdroid does the on-device TLS interception on store Wi-Fi.

The patched build is already made on this machine:

- Pulled 3 split APKs with `adb`, bundled them, ran `apk-mitm --apktool apktool_3.0.3.jar` (the Play-store apktool
  was too old for target SDK 36 + sparse encoding). It adds `res/xml/nsc_mitm.xml` with a `<base-config>`
  trusting `user` + `system` certs, repoints the manifest, strips pinning, resigns with a debug key.
- Result: `/tmp/appie/patched/` (base + 2 splits, v3-signed). Install with `/tmp/appie/install-patched.sh`
  (uninstalls the Play build first, so it logs you out of Appie; re-login in the patched one).
- Caveat: a resigned payment app can trip Play Integrity. Read-only (start trip, read basket) is usually gated
  less than payment; if self-scan refuses to start, rooting (Magisk + system CA) is the fallback.

On the phone: install **PCAPdroid** + its **mitm addon**, install PCAPdroid's CA as a *user* certificate, then
add a **Decryption rule** for the Albert Heijn app (menu -> Decryption rules -> + -> App). The "Target apps"
toggle on the Status screen only scopes capture; TLS is decrypted only for apps/hosts in the Decryption rules.
Use **PCAPNG** dump format so decrypted payloads are saved to the file (a plain `.pcap` stores only the
encrypted packets; the decrypted view is otherwise only in the Connections UI).

### Dart vs native: why home decryption of `api.ah.nl` fails but self-scan should work (verified 2026-11-03)

Tested on the phone: with the CA installed (Trusted credentials -> User -> "PCAPdroid CA") and a decryption rule
for the app, `api.ah.nl` still came through as raw TLS and the app kept working. That is **not pinning**. The
content API is served by the app's **Dart/Flutter** layer (`package:http`, `ah_module_data/src/network/graphql/...`,
`SecurityContext_TrustBuiltinRoots`), and Dart ships its own CA roots and ignores Android's user CAs and
network-security-config. PCAPdroid passthrough'd those connections, so the app worked but nothing decrypted.

Self-scan is different: its endpoints, DTOs and the `:8881` host are **entirely in the native Kotlin dex, with
zero presence in the Dart image**. The self-scan client is native OkHttp/Ktor with no `CertificatePinner`,
`X509TrustManager` or `sslSocketFactory` of its own; it uses the app's shared custom trust manager (`ciw`), which
apk-mitm already neutralized. Native TLS honours the Android trust store + the patched `nsc_mitm.xml`. So the
**self-scan traffic should decrypt** with this exact setup even though the Dart content API does not.

Residual risk: if self-scan surprisingly does not decrypt in-store, the content path is Dart-style TLS and the
fix is **reFlutter** (patch `libflutter.so` to disable Dart cert validation and route through a proxy), not more
Android-CA work.

### In the store

The app gates self-scan on store Wi-Fi (the Zelfscan screen shows "Use your Albert Heijn store's Wi-Fi network to
start" and makes no call until then), so this can only be captured in-store, not at home.

On store Wi-Fi:

1. Start the PCAPdroid capture (decryption rule for Albert Heijn active).
2. Home -> store (winkel) mode -> the scan icon in the search bar -> **In-store Zelfscan**. The **trip-create
   request is the one we need**: it answers both open questions above.
3. Scan a couple of pack items, one weighed label, one DataMatrix item, and remove one again.
4. Read the decrypted requests: PCAPdroid -> Connections -> tap the `selfscan.store.ah.nl` (or
   `api.ah.nl/handscanner`) HTTPS connection -> **PAYLOAD -> SHOW DATA**, and screenshot the request line, the
   headers (especially `Authorization`), and the body. Even truncated, the request headers fit.

Getting a full file instead of screenshots: PCAPdroid's **PCAPNG** format (the only dump that saves decrypted
payloads) is a paid one-time unlock; a plain `.pcap` stores only the encrypted packets. Either buy the unlock for
a clean file to drop in `backend/data/captures/` (git-ignored; it holds your tokens), or just use the live
PAYLOAD -> SHOW DATA screenshots above, which are enough to model the read-only client with the DTOs here.

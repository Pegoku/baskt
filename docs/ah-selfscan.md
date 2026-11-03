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

### Still to confirm with one captured request

1. How a trip is created (does it need a store id from an entrance QR, or just `last_known_store_number` + the
   member token) and the create path/body.
2. The exact `Authorization` value on the port-8881 host (member bearer token, same as the rest of the app?).

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

On the phone: install **PCAPdroid** + its **mitm addon**, install PCAPdroid's CA as a *user* certificate, set the
decryption target to `com.icemobile.albertheijn`.

**Verify at home first** (don't waste the store trip): open the patched Appie, start a PCAPdroid mitm capture, do
any logged-in action, and confirm decrypted `api.ah.nl` JSON appears.

### In the store

On store Wi-Fi, start the PCAPdroid capture, then:

1. Start zelfscan (note how: QR at the entrance, choosing the store, Wi-Fi detection). The **trip-create request is
   the one we need**: it answers both open questions above.
2. Scan a couple of pack items, one weighed label, one DataMatrix item, and remove one again.
3. Stop the capture and export the PCAP/HAR. (For read-only you can stop before paying.)

Put the export in `backend/data/captures/` (git-ignored; it holds your tokens). Then the in-app self-scan client
can be modelled from it plus the DTOs above.

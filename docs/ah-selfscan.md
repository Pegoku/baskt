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
curl -H "Authorization: Bearer $APP_API_TOKEN" "$BASE/purchases/barcode-kinds?store=AH"
```

Each entry has `kind` (`pack`, `label`, `none`), the product id or item text, and the last 5 raw codes.

## 2. Capturing Appie's zelfscan (for the real integration)

The Appie app's own phone self-scan is not in the public GraphQL schema (checked against the
gwillem/appie-go 2026-01-18 dump: only delivery checkout, no in-store session). It is probably a separate REST
service, and it needs the store's Wi-Fi. To build it, baskt needs the requests from a real session.

### Setup (before going to the store)

Android does not trust user certificates for apps by default, so one of:

- **Rooted phone**: Magisk plus a module that moves user CAs into the system store, then any proxy works.
- **Not rooted**: patch the Appie APK with `npx apk-mitm appie.apk` (trusts user CAs and strips pinning), install
  the patched build. It may refuse to run if it checks its signature or Play Integrity; then root is the way.

Then capture **on the phone**, since it has to stay on store Wi-Fi: **PCAPdroid** with its mitm addon decrypts
TLS on-device and exports a pcap plus keylog (or HAR). Install its CA as a user certificate.

Check at home first: log in to the patched Appie, open a product, and confirm PCAPdroid shows decrypted
`api.ah.nl` requests.

### In the store

Start the capture just before opening zelfscan in Appie, and keep it running until you are past the gate.

1. Start zelfscan (note how: QR at the entrance, choosing the store, Wi-Fi detection).
2. Scan a few pack items, one weighed label, one DataMatrix item, and remove one item again.
3. Go to the betaalpaal and screenshot the code Appie shows (and note whether the till scans the phone or the
   phone scans the till).
4. Pay, and screenshot the gate code.
5. If there is a random check ("controle"), note when it is announced.

Stop the capture and put the export plus screenshots in `backend/data/captures/` (ignored by git, it will hold
your tokens). Then baskt can start a session, add each scanned code to it, and show Appie's own till code.

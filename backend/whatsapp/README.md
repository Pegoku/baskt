# baskt WhatsApp bridge

A small Node service (whatsapp-web.js) that turns a WhatsApp chat into a live shopping list:

- **Send**: baskt posts one message per open item (product photo + name, size, price, store). A header message names the basket.
- **Check off**: react with ✅ / 👍 / 🛒 on an item message, or reply to it with "done", "gekocht", "hecho"… and the item is checked in baskt (❌ / 👎 / "no" unchecks). The app sees it within seconds.

## Run

```bash
cd backend/whatsapp
npm install
APP_API_TOKEN=change-me BASKT_URL=http://localhost:3000 npm start
```

First start prints a QR code endpoint (`GET :3001/qr`); scan it from WhatsApp → Linked devices, or open baskt → Settings → WhatsApp. The session is stored in `data/session`.

Env: `WHATSAPP_PORT` (3001), `BASKT_URL`, `APP_API_TOKEN`, `WHATSAPP_DATA_DIR`, `WHATSAPP_CHROME_PATH` (Chromium binary; defaults to /usr/bin/chromium).

Then in the baskt backend `.env` set `WHATSAPP_URL=http://localhost:3001` and pick the target chat in the app (Settings → WhatsApp).

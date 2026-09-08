// baskt WhatsApp bridge (Node, whatsapp-web.js).
// Sends one message per basket item (picture + text) to a chat and watches for ✅/👍 reactions or
// replies on those messages; each one checks the item off through the baskt API.
import http from "node:http";
import fs from "node:fs";
import path from "node:path";
import QRCode from "qrcode";
import pkg from "whatsapp-web.js";

const { Client, LocalAuth, MessageMedia } = pkg;

const PORT = Number(process.env.WHATSAPP_PORT ?? 3001);
const BASKT_URL = (process.env.BASKT_URL ?? "http://localhost:3000").replace(/\/$/, "");
const BASKT_TOKEN = process.env.APP_API_TOKEN ?? "";
const DATA_DIR = process.env.WHATSAPP_DATA_DIR ?? "./data";
const CHROME = process.env.WHATSAPP_CHROME_PATH ?? ["/usr/bin/chromium", "/usr/bin/chromium-browser", "/usr/bin/google-chrome"].find((p) => fs.existsSync(p));
const DONE = new Set(["✅", "☑️", "✔️", "👍", "👌", "🛒"]);
const UNDO = new Set(["❌", "👎", "↩️"]);

fs.mkdirSync(DATA_DIR, { recursive: true });
const mapFile = path.join(DATA_DIR, "messages.json");
/** message id -> { itemId, basketId, title, chatId } */
let sent = fs.existsSync(mapFile) ? JSON.parse(fs.readFileSync(mapFile, "utf8")) : {};
const persist = () => fs.writeFileSync(mapFile, JSON.stringify(sent));

let state = { status: "starting", qr: null, me: null, lastError: null };

const client = new Client({
  authStrategy: new LocalAuth({ dataPath: path.join(DATA_DIR, "session") }),
  puppeteer: { headless: true, executablePath: CHROME, args: ["--no-sandbox", "--disable-setuid-sandbox", "--disable-dev-shm-usage"] },
});

client.on("qr", async (qr) => {
  state = { ...state, status: "qr", qr: await QRCode.toDataURL(qr) };
  console.log("[whatsapp] scan the QR code: open GET /qr or the baskt settings screen");
});
client.on("ready", () => {
  state = { ...state, status: "connected", qr: null, me: client.info?.wid?.user ?? null };
  console.log("[whatsapp] connected as", state.me);
});
client.on("authenticated", () => (state = { ...state, status: "authenticated", qr: null }));
client.on("auth_failure", (message) => (state = { ...state, status: "auth_failure", lastError: message }));
client.on("disconnected", (reason) => {
  state = { ...state, status: "disconnected", lastError: String(reason) };
  client.initialize().catch((error) => console.error("[whatsapp] re-init failed", error));
});

async function setChecked(entry, checked) {
  const response = await fetch(`${BASKT_URL}/api/v1/basket/items/${entry.itemId}`, {
    method: "PATCH",
    headers: { "content-type": "application/json", ...(BASKT_TOKEN ? { authorization: `Bearer ${BASKT_TOKEN}` } : {}) },
    body: JSON.stringify({ checked }),
  });
  if (!response.ok) throw new Error(`baskt HTTP ${response.status}`);
}

/** Emoji reaction on one of our item messages. */
client.on("message_reaction", async (reaction) => {
  const entry = sent[reaction.msgId?._serialized ?? reaction.msgId?.id];
  if (!entry) return;
  const emoji = reaction.reaction;
  try {
    if (DONE.has(emoji)) await setChecked(entry, true);
    else if (UNDO.has(emoji)) await setChecked(entry, false);
    else return;
    console.log(`[whatsapp] ${emoji} on "${entry.title}" -> ${DONE.has(emoji) ? "checked" : "unchecked"}`);
  } catch (error) {
    console.error("[whatsapp] could not update item:", error.message);
  }
});

/** A reply quoting one of our item messages ("done", "✅", "gekocht", "no") works too. */
client.on("message", async (message) => {
  if (!message.hasQuotedMsg) return;
  const quoted = await message.getQuotedMessage().catch(() => null);
  const entry = quoted && sent[quoted.id?._serialized];
  if (!entry) return;
  const text = (message.body ?? "").trim().toLowerCase();
  const done = DONE.has(text) || /^(done|ok|yes|got it|gekocht|gehaald|klaar|ja|hecho|sí|si)\b/.test(text);
  const undo = UNDO.has(text) || /^(no|nee|undo|niet|nope)\b/.test(text);
  if (!done && !undo) return;
  try {
    await setChecked(entry, done);
    await message.react(done ? "✅" : "↩️");
  } catch (error) {
    console.error("[whatsapp] could not update item:", error.message);
  }
});

async function sendItems(chatId, items, header) {
  if (state.status !== "connected") throw new Error(`WhatsApp not connected (${state.status})`);
  if (header) await client.sendMessage(chatId, header);
  const ids = [];
  for (const item of items) {
    let message;
    const caption = item.text;
    if (item.imageUrl) {
      try {
        const media = await MessageMedia.fromUrl(item.imageUrl, { unsafeMime: true, reqOptions: { headers: { "user-agent": "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/145.0.0.0 Mobile Safari/537.36" } } });
        message = await client.sendMessage(chatId, media, { caption });
      } catch {
        message = await client.sendMessage(chatId, caption);
      }
    } else {
      message = await client.sendMessage(chatId, caption);
    }
    sent[message.id._serialized] = { itemId: item.id, basketId: item.basketId, title: item.title, chatId };
    ids.push(message.id._serialized);
  }
  persist();
  return ids;
}

function json(res, status, body) {
  res.writeHead(status, { "content-type": "application/json" });
  res.end(JSON.stringify(body));
}

http
  .createServer(async (req, res) => {
    const url = new URL(req.url, `http://${req.headers.host}`);
    try {
      if (req.method === "GET" && url.pathname === "/status") return json(res, 200, { status: state.status, me: state.me, hasQr: Boolean(state.qr), lastError: state.lastError, tracked: Object.keys(sent).length });
      if (req.method === "GET" && url.pathname === "/qr") return json(res, 200, { qr: state.qr, status: state.status });
      if (req.method === "GET" && url.pathname === "/chats") {
        if (state.status !== "connected") return json(res, 503, { error: "not connected" });
        const chats = await client.getChats();
        return json(res, 200, { chats: chats.slice(0, 50).map((chat) => ({ id: chat.id._serialized, name: chat.name, isGroup: chat.isGroup })) });
      }
      if (req.method === "POST" && url.pathname === "/send") {
        let body = "";
        for await (const chunk of req) body += chunk;
        const { chatId, items, header } = JSON.parse(body || "{}");
        if (!chatId || !Array.isArray(items)) return json(res, 400, { error: "chatId and items are required" });
        const ids = await sendItems(chatId, items, header);
        return json(res, 200, { sent: ids.length });
      }
      json(res, 404, { error: "not found" });
    } catch (error) {
      json(res, 500, { error: error.message });
    }
  })
  .listen(PORT, () => console.log(`[whatsapp] bridge listening on :${PORT} (chrome: ${CHROME ?? "puppeteer default"})`));

client.initialize().catch((error) => {
  state = { ...state, status: "error", lastError: error.message };
  console.error("[whatsapp] init failed:", error);
});

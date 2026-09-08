import { and, asc, eq, inArray } from "drizzle-orm";
import { Hono } from "hono";
import { db, now } from "@/db";
import { basketItems, basketMatches, baskets } from "@/db/schema";
import { enabledStoreCodes } from "@/db/settings";
import { collectProductIds, itemView } from "@/serialize";
import { allStores } from "@/stores/registry";
import { productsByIds } from "@/stores/search";

/**
 * Browser view of a shared basket, protected by the basket's share token (no bearer needed).
 * Household members open the link from WhatsApp and check items off; the app sees the changes.
 */
export const share = new Hono();

function authorized(basketId: string, token: string | undefined) {
  if (!token) return null;
  const basket = db().select().from(baskets).where(eq(baskets.id, basketId)).get();
  return basket && basket.shareToken && basket.shareToken === token ? basket : null;
}

function payload(basketId: string) {
  const items = db().select().from(basketItems).where(eq(basketItems.basketId, basketId)).orderBy(asc(basketItems.sortOrder), asc(basketItems.createdAt)).all();
  const matches = items.length ? db().select().from(basketMatches).where(inArray(basketMatches.itemId, items.map((item) => item.id))).all() : [];
  const products = new Map(productsByIds(collectProductIds(matches)).map((product) => [product.id, product]));
  return {
    serverTime: now(),
    stores: allStores().filter((store) => enabledStoreCodes().includes(store.code)),
    items: items.map((item) => itemView(item, matches.filter((match) => match.itemId === item.id), products)),
  };
}

share.get("/:basketId/api", (c) => {
  const basket = authorized(c.req.param("basketId"), c.req.query("t"));
  if (!basket) return c.json({ error: { code: "UNAUTHORIZED", message: "invalid share link" } }, 401);
  return c.json({ basket: { id: basket.id, name: basket.name, emoji: basket.emoji }, ...payload(basket.id) });
});

share.post("/:basketId/api/items/:itemId/checked", async (c) => {
  const basket = authorized(c.req.param("basketId"), c.req.query("t"));
  if (!basket) return c.json({ error: { code: "UNAUTHORIZED", message: "invalid share link" } }, 401);
  const body = (await c.req.json().catch(() => ({}))) as { checked?: boolean };
  const item = db().select().from(basketItems).where(and(eq(basketItems.id, c.req.param("itemId")), eq(basketItems.basketId, basket.id))).get();
  if (!item) return c.json({ error: { code: "NOT_FOUND", message: "item not found" } }, 404);
  const checked = body.checked === true;
  db().update(basketItems).set({ checked, updatedAt: now() }).where(eq(basketItems.id, item.id)).run();
  if (item.kind === "group") db().update(basketItems).set({ checked, updatedAt: now() }).where(eq(basketItems.parentId, item.id)).run();
  return c.json({ ok: true });
});

share.post("/:basketId/api/items", async (c) => {
  const basket = authorized(c.req.param("basketId"), c.req.query("t"));
  if (!basket) return c.json({ error: { code: "UNAUTHORIZED", message: "invalid share link" } }, 401);
  const body = (await c.req.json().catch(() => ({}))) as { text?: string };
  if (!body.text?.trim()) return c.json({ error: { code: "BAD_REQUEST", message: "text is required" } }, 400);
  // Reuse the normal creation path so matching runs.
  const { createItemForShare } = await import("@/routes/basket");
  const item = createItemForShare(body.text.trim(), basket.id);
  return c.json({ id: item.id }, 201);
});

share.get("/:basketId", (c) => {
  const basket = authorized(c.req.param("basketId"), c.req.query("t"));
  if (!basket) return c.html("<h1>Invalid or expired share link</h1>", 401);
  return c.html(PAGE.replaceAll("__TITLE__", `${basket.emoji ?? "🧺"} ${basket.name}`));
});

const PAGE = `<!doctype html>
<html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>__TITLE__ · baskt</title>
<style>
:root{color-scheme:light dark;font-family:system-ui,sans-serif}
body{margin:0;background:#f4f7f4;color:#1b1b1b}
@media(prefers-color-scheme:dark){body{background:#111;color:#eee}.card{background:#1e1e1e!important}}
header{padding:16px 20px;display:flex;align-items:center;gap:12px}
h1{font-size:20px;margin:0;flex:1}
main{padding:0 12px 96px;max-width:640px;margin:0 auto}
.card{background:#fff;border-radius:14px;padding:10px 12px;margin:8px 0;display:flex;gap:10px;align-items:center;box-shadow:0 1px 2px rgba(0,0,0,.08)}
.card.done{opacity:.55}.card.done .t{text-decoration:line-through}
.card input{width:24px;height:24px;accent-color:#1b5e3f}
.t{flex:1}.sub{font-size:12px;opacity:.7}.folder{font-weight:600}
.badge{font-size:11px;padding:2px 6px;border-radius:6px;color:#fff;margin-right:4px}
form{position:fixed;bottom:0;left:0;right:0;display:flex;gap:8px;padding:12px;background:inherit;box-shadow:0 -2px 8px rgba(0,0,0,.1)}
form input{flex:1;font-size:16px;padding:12px;border-radius:12px;border:1px solid #bbb}
form button{padding:12px 16px;border-radius:12px;border:0;background:#1b5e3f;color:#fff;font-size:16px}
</style></head><body>
<header><h1>__TITLE__</h1><span id="count" class="sub"></span></header>
<main id="list"></main>
<form id="add"><input id="text" placeholder="Add an idea…" autocomplete="off"><button>Add</button></form>
<script>
const t=new URLSearchParams(location.search).get("t");const api=location.pathname+"/api";
const euro=c=>"€"+(c/100).toFixed(2).replace(".",",");
async function load(){const r=await fetch(api+"?t="+t);if(!r.ok){document.getElementById("list").innerHTML="<p>Link no longer valid</p>";return}
const d=await r.json();const stores=Object.fromEntries(d.stores.map(s=>[s.code,s]));const top=d.items.filter(i=>!i.parentId);
document.getElementById("count").textContent=d.items.filter(i=>i.kind==="item"&&!i.checked).length+" to buy";
const render=(i,indent)=>{const prices=i.kind==="group"?"":i.matches.map(m=>{const p=m.chosen||m.provisional;const s=stores[m.store];return p?'<span class="badge" style="background:'+(s?s.color:"#888")+'">'+(s?s.name:m.store)+'</span>'+euro(p.priceCents*i.quantity)+(m.status==="PENDING"?"?":""):""}).filter(Boolean).join(" &nbsp; ");
return '<label class="card'+(i.checked?" done":"")+'" style="margin-left:'+indent+'px"><input type="checkbox" '+(i.checked?"checked":"")+' data-id="'+i.id+'"><div class="t"><div class="'+(i.kind==="group"?"folder":"")+'">'+(i.kind==="group"?"📁 ":"")+(i.quantity>1?i.quantity+"× ":"")+i.text+'</div><div class="sub">'+prices+'</div></div></label>'};
document.getElementById("list").innerHTML=top.map(i=>render(i,0)+d.items.filter(c=>c.parentId===i.id).map(c=>render(c,24)).join("")).join("");
document.querySelectorAll("input[type=checkbox]").forEach(b=>b.onchange=async()=>{await fetch(api+"/items/"+b.dataset.id+"/checked?t="+t,{method:"POST",headers:{"content-type":"application/json"},body:JSON.stringify({checked:b.checked})});load()})}
document.getElementById("add").onsubmit=async e=>{e.preventDefault();const v=document.getElementById("text").value.trim();if(!v)return;document.getElementById("text").value="";await fetch(api+"/items?t="+t,{method:"POST",headers:{"content-type":"application/json"},body:JSON.stringify({text:v})});load()};
load();setInterval(load,5000);
</script></body></html>`;

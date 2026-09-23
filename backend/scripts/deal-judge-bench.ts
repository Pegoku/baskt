/**
 * Which way of asking the model "is this deal for this item?" is cheapest and fastest, and does it get the
 * answer right? Three shapes are compared on the same item/deal pairs from the real database:
 *
 *   pair    one call per (item, deal)                      → {"suitable": true|false}
 *   item    one call per item with all its deals            → {"suitable": [ids]}
 *   basket  one call for every item and every deal at once  → {"<item>": [ids]}
 *
 * The truth table below was labelled by hand; "either" pairs are debatable and are not scored.
 *
 *   bun scripts/deal-judge-bench.ts               # all strategies, 1 run
 *   bun scripts/deal-judge-bench.ts --runs 3 --only item,basket
 *
 * Reads the live database (no writes: chatJson is uncached) and needs the AI keys from backend/.env.
 */
import { aiStats, chatJson, resetAiStats } from "@/ai/client";
import { db } from "@/db";
import { basketItems, basketMatches, products, type ProductRow } from "@/db/schema";
import { env } from "@/env";
import { eq, inArray } from "drizzle-orm";
import { mkdirSync, writeFileSync } from "node:fs";
import { resolve } from "node:path";

const args = new Map<string, string>();
for (let i = 2; i < Bun.argv.length; i += 1) {
  const arg = Bun.argv[i];
  if (arg.startsWith("--")) args.set(arg.slice(2), Bun.argv[i + 1]?.startsWith("--") || Bun.argv[i + 1] === undefined ? "true" : Bun.argv[++i]);
}
const runs = Number(args.get("runs") ?? "1") || 1;
const only = (args.get("only") ?? "pair,item,basket").split(",");
// Groq meters tokens per minute per key, so one call in flight per key mirrors what the server would do.
const concurrency = Number(args.get("concurrency") ?? String(Math.max(1, env.ai.chat.length))) || 1;
// Let the tokens-per-minute window empty between strategies so one does not pay for the last one's tokens.
const cooldownMs = Number(args.get("cooldown") ?? (/groq\.com/.test(env.ai.chat[0]?.baseUrl ?? "") ? "65" : "0")) * 1000;
// Groq list prices (USD per million tokens); the free tier bills nothing, this is what it would cost elsewhere.
const PRICES: Record<string, { prompt: number; completion: number }> = {
  "openai/gpt-oss-20b": { prompt: 0.075, completion: 0.3 },
  "openai/gpt-oss-120b": { prompt: 0.15, completion: 0.6 },
};
const PRICE = PRICES[env.ai.chat[0]?.model ?? ""] ?? PRICES["openai/gpt-oss-20b"];

// ---------- truth ----------
type Label = "yes" | "no" | "either";
const TRUTH: Record<string, Label> = {
  // Gele uien
  "AH:567552": "no", "JUMBO:300829ZK": "no",
  // Houdbare halfvolle melk
  "AH:609672": "yes", "AH:609411": "yes",
  // Large tortilla volkoren
  "AH:602599": "yes", "AH:615177": "yes", "AH:615178": "yes", "AH:602600": "either",
  // NIVEA Men black & white original deo spray
  "AH:42587": "no", "JUMBO:198639STK": "yes", "JUMBO:505593BUS": "yes", "JUMBO:733472BUS": "either", "JUMBO:202907STK": "either",
  "JUMBO:198638STK": "either", "JUMBO:202535STK": "either", "JUMBO:733471BUS": "either", "JUMBO:433953BUS": "either",
  "JUMBO:544265BUS": "no", "JUMBO:544266BUS": "no", "JUMBO:588281BUS": "either",
  // Wasabi bollen
  "AH:559248": "yes", "AH:620179": "either", "AH:620180": "either", "AH:620132": "yes", "AH:620131": "yes",
  "JUMBO:575979ZK": "yes", "JUMBO:708589STK": "no", "JUMBO:708511STK": "no", "JUMBO:708583STK": "no", "JUMBO:576382STK": "no",
  // carrot veggie snacks
  "AH:589379": "no", "AH:601070": "no", "AH:601303": "yes", "AH:202208": "no", "JUMBO:608453BAK": "no", "JUMBO:735389PAK": "no",
  // nata para cocinar
  "AH:239019": "no", "JUMBO:507367CUP": "either",
  // olive oil tuna can
  "AH:147376": "yes", "AH:615106": "yes", "AH:615228": "yes", "AH:615217": "yes", "JUMBO:522936CUP": "no",
};

// ---------- data ----------
type Item = { id: string; text: string; canonical: string; current: string | null; deals: ProductRow[] };

function loadItems(): Item[] {
  const database = db();
  const items = database.select().from(basketItems).all();
  const matches = database.select().from(basketMatches).all();
  const out: Item[] = [];
  for (const item of items) {
    const own = matches.filter((match) => match.itemId === item.id);
    const ids = Array.from(new Set(own.flatMap((match) => match.candidateIds)));
    if (!ids.length) continue;
    const rows = database.select().from(products).where(inArray(products.id, ids)).all();
    const deals = rows.filter((row) => row.isDeal && row.available && row.id in TRUTH);
    if (!deals.length) continue;
    const currentId = own[0]?.chosenProductId ?? own[0]?.candidateIds[0] ?? null;
    const current = currentId ? database.select().from(products).where(eq(products.id, currentId)).get() : null;
    out.push({ id: item.id, text: item.text, canonical: item.parsedJson?.canonicalName ?? item.text, current: current?.title ?? null, deals });
  }
  return out;
}

const describe = (product: ProductRow) => `${product.title} (${product.quantityText ?? "?"}, ${product.dealText ?? "deal"})`;
const itemLine = (item: Item) => `${item.text}${item.canonical !== item.text ? ` (${item.canonical})` : ""}${item.current ? `; currently buying: ${item.current}` : ""}`;

const RULES =
  "You judge supermarket promotions for a shopping list. A deal suits an item when it is the same kind of product the shopper meant, " +
  "or a close alternative of the same type (another brand or flavour of the same product). It does not suit when it is a different product " +
  "that merely shares a word (e.g. bread rolls for a snack called 'bollen', canned carrots for fresh carrot snacks). Respond with JSON only.";

// ---------- strategies ----------
type Verdict = Map<string, boolean | null>; // product id → suitable, null when the call failed

async function pair(items: Item[]): Promise<Verdict> {
  const jobs = items.flatMap((item) => item.deals.map((deal) => ({ item, deal })));
  const verdict: Verdict = new Map();
  await pool(jobs, async ({ item, deal }) => {
    const result = await chatJson<{ suitable?: boolean }>([
      { role: "system", content: RULES + ' Return {"suitable": true|false}.' },
      { role: "user", content: `Item: ${itemLine(item)}\nDeal: ${describe(deal)}` },
    ], { maxTokens: 200 });
    verdict.set(deal.id, result ? result.suitable === true : null);
  });
  return verdict;
}

async function perItem(items: Item[]): Promise<Verdict> {
  const verdict: Verdict = new Map();
  await pool(items, async (item) => {
    const result = await chatJson<{ suitable?: unknown }>([
      { role: "system", content: RULES + ' Return {"suitable": [ids of the deals that suit the item]} (empty array when none do).' },
      { role: "user", content: `Item: ${itemLine(item)}\nDeals:\n${item.deals.map((deal, i) => `${i + 1}. ${describe(deal)}`).join("\n")}` },
    ], { maxTokens: 300 });
    const picked = new Set(Array.isArray(result?.suitable) ? result!.suitable.map(Number) : []);
    item.deals.forEach((deal, i) => verdict.set(deal.id, result ? picked.has(i + 1) : null));
  });
  return verdict;
}

async function basket(items: Item[]): Promise<Verdict> {
  const verdict: Verdict = new Map();
  const result = await chatJson<Record<string, unknown>>([
    { role: "system", content: RULES + ' Return {"<item number>": [numbers of the deals that suit that item]} for every item (empty array when none do).' },
    { role: "user", content: items.map((item, i) => `Item ${i + 1}: ${itemLine(item)}\n${item.deals.map((deal, j) => `  ${i + 1}.${j + 1} ${describe(deal)}`).join("\n")}`).join("\n\n") },
  ], { maxTokens: 1500 });
  items.forEach((item, i) => {
    const raw = result?.[String(i + 1)];
    const picked = new Set(Array.isArray(raw) ? raw.map((value) => String(value).replace(`${i + 1}.`, "")) : []);
    item.deals.forEach((deal, j) => verdict.set(deal.id, result ? picked.has(String(j + 1)) : null));
  });
  return verdict;
}

async function pool<T>(jobs: T[], work: (job: T) => Promise<void>) {
  let next = 0;
  await Promise.all(Array.from({ length: Math.min(concurrency, jobs.length) }, async () => {
    while (next < jobs.length) await work(jobs[next++]);
  }));
}

// ---------- run ----------
const items = loadItems();
const pairs = items.reduce((sum, item) => sum + item.deals.length, 0);
const scored = Object.values(TRUTH).filter((label) => label !== "either").length;
console.log(`${items.length} items, ${pairs} deal pairs (${scored} scored), model ${env.ai.chat[0]?.model}, concurrency ${concurrency}`);

type Row = { strategy: string; run: number; calls: number; failures: number; prompt: number; completion: number; ms: number; right: number; unanswered: number; wrongYes: string[]; wrongNo: string[] };
const rows: Row[] = [];
const strategies: Record<string, (items: Item[]) => Promise<Verdict>> = { pair, item: perItem, basket };
for (let run = 1; run <= runs; run += 1) {
  for (const name of only) {
    const strategy = strategies[name];
    if (!strategy) continue;
    if (rows.length && cooldownMs) await new Promise((resolve) => setTimeout(resolve, cooldownMs));
    resetAiStats();
    const started = performance.now();
    const verdict = await strategy(items);
    const ms = Math.round(performance.now() - started);
    const stats = aiStats();
    let right = 0;
    let unanswered = 0;
    const wrongYes: string[] = [];
    const wrongNo: string[] = [];
    for (const item of items) for (const deal of item.deals) {
      const label = TRUTH[deal.id];
      if (label === "either") continue;
      const answer = verdict.get(deal.id);
      if (answer == null) { unanswered += 1; continue; }
      const said = answer === true;
      if (said === (label === "yes")) right += 1;
      else (said ? wrongYes : wrongNo).push(`${item.text} ← ${deal.title}`);
    }
    rows.push({ strategy: name, run, calls: stats.calls, failures: stats.failures, prompt: stats.promptTokens, completion: stats.completionTokens, ms, right, unanswered, wrongYes, wrongNo });
    console.log(`${name} run ${run}: ${stats.calls} calls (${stats.failures} failed), ${stats.promptTokens}+${stats.completionTokens} tokens, ${ms} ms, ${right}/${scored} right, ${unanswered} unanswered`);
  }
}

// ---------- report ----------
const lines: string[] = [];
lines.push(`# Deal judge bench — ${new Date().toISOString()}`);
lines.push("");
lines.push(`Model ${env.ai.chat[0]?.model} via ${env.ai.chat[0]?.baseUrl}; ${items.length} items, ${pairs} deal pairs, ${scored} scored (the rest labelled "either"). ${runs} run(s), concurrency ${concurrency}.`);
lines.push(`Cost at Groq list price: $${PRICE.prompt}/M prompt, $${PRICE.completion}/M completion. Wall time is the whole basket, calls in flight as the server would run them.`);
lines.push("");
lines.push("| strategy | run | calls (failed) | prompt tokens | completion tokens | total tokens | wall ms | cost per basket | right | unanswered |");
lines.push("|---|---|---|---|---|---|---|---|---|---|");
for (const row of rows) {
  const cost = (row.prompt / 1e6) * PRICE.prompt + (row.completion / 1e6) * PRICE.completion;
  lines.push(`| ${row.strategy} | ${row.run} | ${row.calls} (${row.failures}) | ${row.prompt} | ${row.completion} | ${row.prompt + row.completion} | ${row.ms} | $${cost.toFixed(5)} | ${row.right}/${scored} | ${row.unanswered} |`);
}
lines.push("");
for (const row of rows) {
  if (!row.wrongYes.length && !row.wrongNo.length) continue;
  lines.push(`## ${row.strategy} run ${row.run} mistakes`);
  for (const line of row.wrongYes) lines.push(`- said yes, should be no: ${line}`);
  for (const line of row.wrongNo) lines.push(`- said no, should be yes: ${line}`);
  lines.push("");
}
const dir = resolve(import.meta.dir, "../bench");
mkdirSync(dir, { recursive: true });
const file = resolve(dir, `deal-judge-${new Date().toISOString().slice(0, 19).replace(/[T:]/g, "-")}.md`);
writeFileSync(file, lines.join("\n"));
console.log(`\n${lines.join("\n")}\n\nwritten to ${file}`);

/**
 * LLM benchmark: runs baskt's real AI-backed tasks (idea parsing, alternative queries, candidate ranking,
 * deal search expansion, recipe localisation and full assistant turns) against several models and reports
 * how many checks each model passes, how fast it is and roughly what it costs.
 *
 *   bun scripts/llm-bench.ts                              # default model list
 *   bun scripts/llm-bench.ts --models openai/gpt-oss-20b,openai/gpt-oss-120b
 *   bun scripts/llm-bench.ts --only assistant --runs 2    # a subset, repeated
 *
 * Uses an in-memory database so nothing is cached between models and your real data is untouched.
 * Needs AI_API_KEY (and AI_BASE_URL) in backend/.env like the server.
 */
import { aiStats, resetAiStats } from "@/ai/client";
import { db, resetDbForTests } from "@/db";
import { basketItems, DEFAULT_BASKET_ID, type ParsedIdea, type ProductRow } from "@/db/schema";
import { setSetting } from "@/db/settings";
import { env } from "@/env";
import { chat, clearHistory } from "@/assistant";
import { expandDealQuery } from "@/matching/deals";
import { localizeRecipe } from "@/matching/localize";
import { alternativeQuery, parseIdea } from "@/matching/parse";
import { aiRankCandidates } from "@/matching/pick";
import { addStock } from "@/stock";
import { mkdirSync, writeFileSync } from "node:fs";
import { resolve } from "node:path";

// ---------- CLI ----------
const args = new Map<string, string>();
for (let i = 2; i < Bun.argv.length; i += 1) {
  const arg = Bun.argv[i];
  if (arg.startsWith("--")) args.set(arg.slice(2), Bun.argv[i + 1]?.startsWith("--") || Bun.argv[i + 1] === undefined ? "true" : Bun.argv[++i]);
}
const DEFAULT_MODELS = [
  "openai/gpt-oss-20b",
  "openai/gpt-oss-120b",
  "google/gemini-2.5-flash-lite",
  "google/gemini-3.1-flash-lite",
  "qwen/qwen3.7-flash",
  "qwen/qwen3.5-flash-02-23",
  "nvidia/nemotron-3.5-lightning",
  "nvidia/nemotron-3-super-120b-a12b",
  "mistralai/mistral-small-3.2-24b-instruct",
  "z-ai/glm-4.7-flash",
  "openai/gpt-5-nano",
  "openai/gpt-4.1-nano",
  "deepseek/deepseek-v3.2",
  "meta-llama/llama-4-scout",
];
const models = (args.get("models") ?? DEFAULT_MODELS.join(",")).split(",").map((value) => value.trim()).filter(Boolean);
const runs = Number(args.get("runs") ?? "1") || 1;
const only = args.get("only") ?? null;
const concurrency = Number(args.get("concurrency") ?? "3") || 3;
const productionModel = process.env.AI_MODEL ?? "";
const productionProvider = { order: [...env.ai.providerOrder], quantizations: [...env.ai.providerQuantizations] };

if (!env.ai.apiKey) {
  console.error("AI_API_KEY is not set (put it in backend/.env)");
  process.exit(1);
}

// ---------- model metadata (pricing, reasoning support) ----------
type ModelMeta = { id: string; promptUsd: number; completionUsd: number; reasoning: boolean };
async function loadModelMeta(): Promise<Map<string, ModelMeta>> {
  const out = new Map<string, ModelMeta>();
  try {
    const response = await fetch(`${env.ai.baseUrl}/models`, { headers: { authorization: `Bearer ${env.ai.apiKey}` } });
    const payload = (await response.json()) as { data?: Array<{ id: string; pricing?: { prompt?: string; completion?: string }; supported_parameters?: string[] }> };
    for (const model of payload.data ?? []) {
      out.set(model.id, {
        id: model.id,
        promptUsd: Number(model.pricing?.prompt ?? 0) * 1e6,
        completionUsd: Number(model.pricing?.completion ?? 0) * 1e6,
        reasoning: (model.supported_parameters ?? []).includes("reasoning"),
      });
    }
  } catch (error) {
    console.warn(`could not load /models: ${error instanceof Error ? error.message : error}`);
  }
  return out;
}

// ---------- fixtures ----------
function product(store: string, id: string, title: string, quantityText: string, priceCents: number, unit: "kg" | "l" | "piece" | null, unitAmount: number | null, extra: Partial<ProductRow> = {}): ProductRow {
  const unitPriceCents = unit && unitAmount ? Math.round(priceCents / unitAmount) : null;
  return {
    id: `${store}:${id}`,
    store,
    sourceId: id,
    title,
    brand: extra.brand ?? null,
    quantityText,
    unitAmount,
    unit,
    priceCents,
    regularPriceCents: null,
    unitPriceCents,
    unitPriceUnit: unit,
    dealText: null,
    isDeal: false,
    imageUrl: null,
    sourceUrl: null,
    category: extra.category ?? null,
    available: true,
    fetchedAt: Date.now(),
    ...extra,
  };
}
const parsedOf = (canonicalName: string, sizeHint: ParsedIdea["sizeHint"] = null, attributes: string[] = []): ParsedIdea => ({
  canonicalName,
  attributes,
  sizeHint,
  queries: {},
  fallbackQuery: null,
  ambiguous: false,
});

function seedBasket() {
  resetDbForTests(":memory:");
  setSetting("language", "en");
  const stamp = Date.now();
  const rows = [
    { id: "item-eggs", text: "Eggs" },
    { id: "item-tomatoes", text: "Cherry tomatoes" },
    { id: "item-ham", text: "Ham" },
  ];
  rows.forEach((row, index) => {
    db().insert(basketItems).values({ id: row.id, basketId: DEFAULT_BASKET_ID, text: row.text, quantity: 1, kind: "item", sortOrder: index, status: "NEW", createdAt: stamp, updatedAt: stamp }).run();
  });
  addStock("Tarwebloem 1 kg");
  addStock("Penne Durum Tarwe 500 g");
  addStock("Olijfolie extra vierge");
  addStock("Keukenzout met jodium");
}

// ---------- cases ----------
type Case = { name: string; group: string; run: () => Promise<unknown>; check: (result: unknown) => string | null };
const has = (value: unknown, pattern: RegExp) => pattern.test(JSON.stringify(value ?? "").toLowerCase());
const fail = (message: string, value: unknown) => `${message} — got ${JSON.stringify(value).slice(0, 220)}`;

const cases: Case[] = [
  // ---- understanding ideas ----
  {
    name: "parse: halfvolle milk → melk query",
    group: "parse",
    run: () => parseIdea("halfvolle milk", ["AH", "JUMBO"]),
    check: (r) => (has((r as ParsedIdea).queries, /melk/) ? null : fail("expected a Dutch 'melk' query", r)),
  },
  {
    name: "parse: descriptive 'olive liquid for cooking' → olijfolie",
    group: "parse",
    run: () => parseIdea("olive liquid for cooking", ["AH", "JUMBO"]),
    check: (r) => (has((r as ParsedIdea).queries, /olijfolie/) ? null : fail("expected 'olijfolie'", r)),
  },
  {
    name: "parse: 'eieren 10' → piece size hint, no number in query",
    group: "parse",
    run: () => parseIdea("eieren 10", ["AH", "JUMBO"]),
    check: (r) => {
      const parsed = r as ParsedIdea;
      if (!has(parsed.queries, /eieren|ei\b/)) return fail("expected an egg query", r);
      if (parsed.sizeHint?.unit !== "piece" || parsed.sizeHint.amount !== 10) return fail("expected sizeHint 10 piece", r);
      if (has(parsed.queries, /10/)) return fail("numbers must not be in queries", r);
      return null;
    },
  },
  {
    name: "parse: Spanish 'leche semi uht' → houdbare halfvolle melk",
    group: "parse",
    run: () => parseIdea("leche semi uht", ["AH", "JUMBO"]),
    check: (r) => (has((r as ParsedIdea).queries, /melk/) && has(r, /halfvol|houdba|uht/) ? null : fail("expected halfvolle/houdbare melk", r)),
  },
  {
    name: "parse: '500g penne' → 0.5 kg hint",
    group: "parse",
    run: () => parseIdea("500g penne", ["AH", "JUMBO"]),
    check: (r) => {
      const parsed = r as ParsedIdea;
      if (!has(parsed.queries, /penne/)) return fail("expected penne query", r);
      const hint = parsed.sizeHint;
      if (!hint || hint.unit !== "kg" || Math.abs(hint.amount - 0.5) > 0.01) return fail("expected sizeHint 0.5 kg", r);
      return null;
    },
  },
  {
    name: "parse: 'kipfilet 1kg' → 1 kg hint",
    group: "parse",
    run: () => parseIdea("kipfilet 1kg", ["AH", "JUMBO"]),
    check: (r) => {
      const hint = (r as ParsedIdea).sizeHint;
      return hint && hint.unit === "kg" && Math.abs(hint.amount - 1) < 0.01 && has((r as ParsedIdea).queries, /kip/) ? null : fail("expected kip query with 1 kg hint", r);
    },
  },
  // ---- alternatives ----
  {
    name: "alternatives: krulsla melange → other lettuce",
    group: "alternatives",
    run: () => alternativeQuery("krulsla melange", parsedOf("curly lettuce mix"), "JUMBO", ["Jumbo Krulsla", "Jumbo Krulsla Melange"], ["krulsla melange", "krulsla"]),
    check: (r) => (Array.isArray(r) && r.length >= 2 && has(r, /sla|rucola|spinazie|andijvie/) ? null : fail("expected ≥2 lettuce-like queries", r)),
  },
  {
    name: "alternatives: eggs follow the other store's 6-pack",
    group: "alternatives",
    run: () => alternativeQuery("Eggs", parsedOf("eggs"), "JUMBO", ["Jumbo Gevulde Eieren Kerrie 4 Stuks"], ["eieren"], "AH Witte vrije uitloopeieren M (6 stuks, €2.19, €0.37/piece)"),
    check: (r) => (Array.isArray(r) && has(r, /eieren|ei\b/) && has(r, /6|zes/) ? null : fail("expected egg queries mentioning 6", r)),
  },
  // ---- ranking ----
  {
    name: "rank: halfvolle melk picks the half-fat milk",
    group: "rank",
    run: () =>
      aiRankCandidates({
        text: "halfvolle melk",
        parsed: parsedOf("half-fat milk"),
        store: "AH",
        memory: "",
        candidates: [
          product("AH", "1", "AH Volle melk", "1 l", 129, "l", 1),
          product("AH", "2", "AH Halfvolle melk", "1 l", 119, "l", 1),
          product("AH", "3", "AH Chocolademelk", "1 l", 199, "l", 1),
          product("AH", "4", "AH Halfvolle melk", "2 l", 219, "l", 2),
        ],
      }),
    check: (r) => {
      const top = (r as { orderedIds: string[] } | null)?.orderedIds?.[0];
      return top === "AH:2" || top === "AH:4" ? null : fail("expected AH:2 (or the 2 l half-fat) first", r);
    },
  },
  {
    name: "rank: 'eieren 6' picks the 6-pack, not filled eggs",
    group: "rank",
    run: () =>
      aiRankCandidates({
        text: "eieren 6",
        parsed: parsedOf("eggs", { amount: 6, unit: "piece" }),
        store: "JUMBO",
        memory: "",
        candidates: [
          product("JUMBO", "1", "Jumbo Gevulde Eieren Kerrie 4 Stuks", "105 g", 299, "kg", 0.105),
          product("JUMBO", "2", "Jumbo Kei Lekker Eieren 10 Stuks", "10 stuks", 429, "piece", 10),
          product("JUMBO", "3", "Jumbo Scharreleieren M/L 6 Stuks", "6 stuks", 255, "piece", 6),
          product("JUMBO", "4", "Jumbo Scharrel Kwarteleieren 12 Stuks", "12 stuks", 349, "piece", 12),
        ],
      }),
    check: (r) => ((r as { orderedIds: string[] } | null)?.orderedIds?.[0] === "JUMBO:3" ? null : fail("expected JUMBO:3 first", r)),
  },
  {
    name: "rank: olijfolie is not sunflower oil or olives",
    group: "rank",
    run: () =>
      aiRankCandidates({
        text: "olijfolie",
        parsed: parsedOf("olive oil"),
        store: "AH",
        memory: "",
        candidates: [
          product("AH", "1", "AH Zonnebloemolie", "1 l", 279, "l", 1),
          product("AH", "2", "AH Olijfolie extra vierge", "500 ml", 549, "l", 0.5),
          product("AH", "3", "AH Groene olijven", "200 g", 199, "kg", 0.2),
          product("AH", "4", "AH Olijfolie mild", "750 ml", 649, "l", 0.75),
        ],
      }),
    check: (r) => {
      const ranked = r as { orderedIds: string[]; equivalences: Record<string, string> } | null;
      const top = ranked?.orderedIds?.[0];
      if (top !== "AH:2" && top !== "AH:4") return fail("expected an olive oil first", r);
      if (ranked?.equivalences["AH:3"] === "EXACT") return fail("olives must not be EXACT", r);
      return null;
    },
  },
  // ---- deals ----
  {
    name: "deals: 'leche' expands to melk",
    group: "deals",
    run: () => expandDealQuery("leche"),
    check: (r) => (has(r, /melk/) ? null : fail("expected 'melk'", r)),
  },
  {
    name: "deals: 'bread' expands to brood",
    group: "deals",
    run: () => expandDealQuery("bread"),
    check: (r) => (has(r, /brood/) ? null : fail("expected 'brood'", r)),
  },
  // ---- recipe localisation ----
  {
    name: "localize: Dutch pancakes → English, same line counts",
    group: "localize",
    run: () =>
      localizeRecipe({
        title: "Pannenkoeken",
        sourceUrl: null,
        servings: "4 personen",
        ingredientLines: ["250 g tarwebloem", "2 eieren", "500 ml halfvolle melk", "snufje zout"],
        steps: [
          { text: "Meng de bloem met het zout in een kom. Bekijk ook ons artikel over pannenkoekenpannen.", imageUrl: null },
          { text: "Klop de eieren en de melk erdoor tot een glad beslag.", imageUrl: null },
          { text: "Bak de pannenkoeken in een hete pan met een beetje boter, ongeveer 2 minuten per kant.", imageUrl: null },
        ],
      }),
    check: (r) => {
      const localized = r as { title: string; ingredientLines: string[]; steps?: Array<{ text: string }> };
      if (localized.ingredientLines.length !== 4) return fail("expected 4 ingredient lines", r);
      if ((localized.steps ?? []).length !== 3) return fail("expected 3 steps", r);
      if (!has(localized.ingredientLines, /flour/) || !has(localized.ingredientLines, /egg/) || !has(localized.ingredientLines, /milk/)) return fail("expected flour/egg/milk in English", r);
      if (has(localized.steps, /article/)) return fail("filler about articles should be removed", r);
      return null;
    },
  },
  // ---- assistant turns (real tool loop, seeded basket + stock) ----
  {
    name: "assistant: missing for pancakes → proposal without flour/eggs, answer has the time",
    group: "assistant",
    run: async () => {
      clearHistory(DEFAULT_BASKET_ID);
      return chat(DEFAULT_BASKET_ID, "What am I missing for pancakes and how long do they take to make?");
    },
    check: (r) => {
      const reply = (r as Array<{ content: string; proposalJson: { changes: Array<{ type: string; text?: string }> } | null }>)[1];
      const changes = reply.proposalJson?.changes ?? [];
      const adds = changes.filter((change) => change.type === "add");
      if (!adds.length) return fail("expected add changes", { content: reply.content, changes });
      if (has(adds, /flour|bloem/)) return fail("flour is in stock and must not be proposed", { changes });
      if (has(adds, /\begg|eier/)) return fail("eggs are in the basket and must not be proposed", { changes });
      if (!/\d+\s*(min|hour|uur)/i.test(reply.content)) return fail("answer should give the cooking time", { content: reply.content });
      const named = adds.filter((add) => add.text && reply.content.toLowerCase().includes(add.text.toLowerCase().split(" ")[0]));
      if (named.length >= 2) return fail("answer repeats the proposed items", { content: reply.content, changes });
      return null;
    },
  },
  {
    name: "assistant: explicit adds keep named items, mark stock",
    group: "assistant",
    run: async () => {
      clearHistory(DEFAULT_BASKET_ID);
      return chat(DEFAULT_BASKET_ID, "Add penne and cherry tomatoes to my basket");
    },
    check: (r) => {
      const reply = (r as Array<{ content: string; proposalJson: { changes: Array<{ type: string; text?: string; inStock?: boolean; inList?: string | null }> } | null }>)[1];
      const adds = (reply.proposalJson?.changes ?? []).filter((change) => change.type === "add");
      const penne = adds.find((add) => /penne/i.test(add.text ?? ""));
      const tomatoes = adds.find((add) => /tomat/i.test(add.text ?? ""));
      if (!penne) return fail("expected a penne add", reply);
      if (!penne.inStock) return fail("penne should be flagged in stock", reply);
      if (!tomatoes) return fail("cherry tomatoes must be kept (flagged as in the list)", reply);
      if (has(adds, /brand|grand'?\s?italia|jumbo|ah /)) return fail("add text should be generic, not a product title", reply);
      return null;
    },
  },
  {
    name: "assistant: rename uses the real item id",
    group: "assistant",
    run: async () => {
      clearHistory(DEFAULT_BASKET_ID);
      return chat(DEFAULT_BASKET_ID, "Rename cherry tomatoes to tomatoes");
    },
    check: (r) => {
      const reply = (r as Array<{ content: string; proposalJson: { changes: Array<{ type: string; itemId?: string; to?: string }> } | null }>)[1];
      const rename = (reply.proposalJson?.changes ?? []).find((change) => change.type === "rename");
      if (!rename) return fail("expected a rename change", reply);
      if (rename.itemId !== "item-tomatoes") return fail("rename must target the cherry tomatoes item", reply);
      if (!/tomat/i.test(rename.to ?? "")) return fail("new name should be tomatoes", reply);
      if (/would you like|shall i|do you want/i.test(reply.content)) return fail("must not ask permission in text", reply);
      return null;
    },
  },
  {
    name: "assistant: delete the ham",
    group: "assistant",
    run: async () => {
      clearHistory(DEFAULT_BASKET_ID);
      return chat(DEFAULT_BASKET_ID, "Remove the ham from my list");
    },
    check: (r) => {
      const reply = (r as Array<{ content: string; proposalJson: { changes: Array<{ type: string; itemId?: string }> } | null }>)[1];
      const del = (reply.proposalJson?.changes ?? []).find((change) => change.type === "delete");
      return del?.itemId === "item-ham" ? null : fail("expected delete of item-ham", reply);
    },
  },
  {
    name: "assistant: no 1 kg bags → no invented replacements",
    group: "assistant",
    run: async () => {
      clearHistory(DEFAULT_BASKET_ID);
      return chat(DEFAULT_BASKET_ID, "Swap any 1 kg bags in my basket for 500 g ones");
    },
    check: (r) => {
      const reply = (r as Array<{ content: string; proposalJson: { changes: Array<{ type: string }> } | null }>)[1];
      const replaces = (reply.proposalJson?.changes ?? []).filter((change) => change.type === "replace" || change.type === "add");
      if (replaces.length) return fail("nothing matches, so no replace/add changes expected", reply);
      if (!reply.content.trim() || /could not reach|went wrong/i.test(reply.content)) return fail("expected a short explanation", reply);
      return null;
    },
  },
];

const selected = only ? cases.filter((c) => c.group === only || c.name.includes(only)) : cases;

// ---------- runner ----------
type CaseResult = { name: string; group: string; ok: boolean; ms: number; calls: number; detail: string | null };
type ModelResult = { model: string; results: CaseResult[]; promptTokens: number; completionTokens: number; failures: number; wallMs: number };

async function pool<T>(items: T[], limit: number, worker: (item: T) => Promise<void>) {
  let next = 0;
  await Promise.all(
    Array.from({ length: Math.min(limit, items.length) }, async () => {
      while (next < items.length) {
        const item = items[next++];
        await worker(item);
      }
    }),
  );
}

async function runModel(model: string, meta: ModelMeta | undefined): Promise<ModelResult> {
  env.ai.model = model;
  env.ai.reasoning = meta?.reasoning ? "low" : "none";
  const pinned = model === productionModel;
  env.ai.providerOrder = pinned ? productionProvider.order : [];
  env.ai.providerQuantizations = pinned ? productionProvider.quantizations : [];
  seedBasket();
  resetAiStats();
  const results: CaseResult[] = [];
  const started = performance.now();
  // Assistant cases share the basket history, so they run one at a time; the rest can overlap.
  const parallel = selected.filter((c) => c.group !== "assistant");
  const serial = selected.filter((c) => c.group === "assistant");
  const runCase = async (c: Case) => {
    for (let run = 0; run < runs; run += 1) {
      const before = aiStats().calls;
      const t0 = performance.now();
      let detail: string | null = null;
      try {
        const result = await c.run();
        detail = c.check(result);
      } catch (error) {
        detail = `threw: ${error instanceof Error ? error.message : String(error)}`;
      }
      const ms = performance.now() - t0;
      results.push({ name: c.name, group: c.group, ok: detail === null, ms, calls: aiStats().calls - before, detail });
      process.stdout.write(`  ${detail === null ? "✓" : "✗"} ${c.name} (${(ms / 1000).toFixed(1)}s)${detail ? `\n      ${detail}` : ""}\n`);
    }
  };
  await pool(parallel, concurrency, runCase);
  for (const c of serial) await runCase(c);
  const stats = aiStats();
  return { model, results, promptTokens: stats.promptTokens, completionTokens: stats.completionTokens, failures: stats.failures, wallMs: performance.now() - started };
}

function median(values: number[]) {
  if (!values.length) return 0;
  const sorted = [...values].sort((a, b) => a - b);
  return sorted[Math.floor(sorted.length / 2)];
}
function percentile(values: number[], p: number) {
  if (!values.length) return 0;
  const sorted = [...values].sort((a, b) => a - b);
  return sorted[Math.min(sorted.length - 1, Math.floor((sorted.length - 1) * p))];
}

function report(all: ModelResult[], meta: Map<string, ModelMeta>) {
  const lines: string[] = [];
  lines.push(`# baskt LLM benchmark — ${new Date().toISOString().slice(0, 16).replace("T", " ")}`);
  lines.push("");
  lines.push(`${selected.length} checks × ${runs} run(s) per model; latency is per check (an assistant check is a whole tool-using turn). Cost = tokens actually used in this run at the proxy's list price.`);
  lines.push("");
  lines.push("| model | pass | parse | alt | rank | deals | l10n | assistant | median s | p90 s | total s | tokens in/out | cost |");
  lines.push("|---|---|---|---|---|---|---|---|---|---|---|---|---|");
  const sorted = [...all].sort((a, b) => b.results.filter((r) => r.ok).length / b.results.length - a.results.filter((r) => r.ok).length / a.results.length || a.wallMs - b.wallMs);
  const groupScore = (m: ModelResult, group: string) => {
    const rows = m.results.filter((r) => r.group === group);
    return rows.length ? `${rows.filter((r) => r.ok).length}/${rows.length}` : "–";
  };
  for (const m of sorted) {
    const passed = m.results.filter((r) => r.ok).length;
    const pct = Math.round((passed / m.results.length) * 100);
    const times = m.results.map((r) => r.ms / 1000);
    const price = meta.get(m.model);
    const cost = price ? (m.promptTokens / 1e6) * price.promptUsd + (m.completionTokens / 1e6) * price.completionUsd : null;
    lines.push(
      `| ${m.model} | **${pct}%** (${passed}/${m.results.length}) | ${groupScore(m, "parse")} | ${groupScore(m, "alternatives")} | ${groupScore(m, "rank")} | ${groupScore(m, "deals")} | ${groupScore(m, "localize")} | ${groupScore(m, "assistant")} | ${median(times).toFixed(1)} | ${percentile(times, 0.9).toFixed(1)} | ${(m.wallMs / 1000).toFixed(0)} | ${m.promptTokens}/${m.completionTokens} | ${cost === null ? "?" : `$${cost.toFixed(4)}`} |`,
    );
  }
  lines.push("");
  lines.push("## Per check");
  lines.push("");
  lines.push(`| check | ${sorted.map((m) => m.model.split("/").pop()).join(" | ")} |`);
  lines.push(`|---|${sorted.map(() => "---").join("|")}|`);
  for (const c of selected) {
    const cells = sorted.map((m) => {
      const rows = m.results.filter((r) => r.name === c.name);
      const ok = rows.filter((r) => r.ok).length;
      return `${ok === rows.length ? "✓" : ok === 0 ? "✗" : `${ok}/${rows.length}`} ${(median(rows.map((r) => r.ms / 1000))).toFixed(1)}s`;
    });
    lines.push(`| ${c.name} | ${cells.join(" | ")} |`);
  }
  lines.push("");
  lines.push("## Failures");
  lines.push("");
  for (const m of sorted) {
    const failed = m.results.filter((r) => !r.ok);
    if (!failed.length) continue;
    lines.push(`### ${m.model}`);
    for (const r of failed) lines.push(`- ${r.name}: ${r.detail}`);
    lines.push("");
  }
  return lines.join("\n");
}

const meta = await loadModelMeta();
const all: ModelResult[] = [];
for (const model of models) {
  console.log(`\n=== ${model} ${meta.get(model)?.reasoning ? "(reasoning: low)" : ""}`);
  all.push(await runModel(model, meta.get(model)));
  const last = all[all.length - 1];
  console.log(`  → ${last.results.filter((r) => r.ok).length}/${last.results.length} passed in ${(last.wallMs / 1000).toFixed(0)}s, ${last.failures} failed AI calls`);
}
const markdown = report(all, meta);
const dir = resolve(import.meta.dir, "../bench");
mkdirSync(dir, { recursive: true });
const file = resolve(dir, `results-${new Date().toISOString().slice(0, 16).replace(/[:T]/g, "-")}.md`);
writeFileSync(file, markdown);
console.log(`\n${markdown.split("\n## Per check")[0]}\nfull report: ${file}`);

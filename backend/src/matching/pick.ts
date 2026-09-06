import { cachedChatJson } from "@/ai/client";
import type { Equivalence, ParsedIdea, ProductRow } from "@/db/schema";
import { normalizeText, sha256 } from "@/lib/text";

export const PICK_PROMPT_VERSION = "v1";

const EQUIVALENCES: Equivalence[] = ["EXACT", "EQUIVALENT", "SUBSTITUTE"];

export type RankedCandidates = {
  orderedIds: string[];
  equivalences: Record<string, Equivalence>;
  confidence: number;
  reason: string | null;
};

const SYSTEM = `You help a shopper in the Netherlands pick concrete supermarket products for a loosely described grocery idea.
Rank ALL candidate products from best to worst match for the idea. Consider: product type, the attributes the user asked for (e.g. halfvol, houdbaar, bio), pack size vs the size hint, price for money, and above all the user's remembered preferences when given (repeat what they chose before, avoid what they rejected).
Return ONLY a JSON object:
{"ranking":[{"idx": candidate index, "equivalence": "EXACT"|"EQUIVALENT"|"SUBSTITUTE"} ...all candidates...], "confidence": 0..1 for the top pick, "reason": max 12 words}
EXACT = exactly what was asked, EQUIVALENT = same product type with minor differences, SUBSTITUTE = different product that could stand in.`;

function formatCandidate(index: number, product: ProductRow) {
  const price = `€${(product.priceCents / 100).toFixed(2)}`;
  const unit = product.unitPriceCents && product.unitPriceUnit ? ` (€${(product.unitPriceCents / 100).toFixed(2)}/${product.unitPriceUnit})` : "";
  const deal = product.dealText ? ` deal: ${product.dealText}` : "";
  const availability = product.available ? "" : " UNAVAILABLE";
  return `${index}. ${product.title} | ${product.quantityText} | ${price}${unit}${product.brand ? ` | ${product.brand}` : ""}${deal}${availability}`;
}

export async function aiRankCandidates(input: {
  text: string;
  parsed: ParsedIdea;
  store: string;
  candidates: ProductRow[];
  memory: string;
}): Promise<RankedCandidates | null> {
  if (!input.candidates.length) return null;
  const key = await sha256(
    [PICK_PROMPT_VERSION, input.store, normalizeText(input.text), input.candidates.map((product) => `${product.id}@${product.priceCents}`).join(","), input.memory].join("|"),
  );
  const user = [
    `Idea: ${input.text}`,
    `Canonical: ${input.parsed.canonicalName}`,
    `Attributes: ${input.parsed.attributes.join(", ") || "none"}`,
    `Size hint: ${input.parsed.sizeHint ? `${input.parsed.sizeHint.amount} ${input.parsed.sizeHint.unit}` : "none"}`,
    `Store: ${input.store}`,
    input.memory ? `Remembered preferences:\n${input.memory}` : "Remembered preferences: none yet",
    "Candidates:",
    ...input.candidates.map((product, index) => formatCandidate(index, product)),
  ].join("\n");

  const raw = await cachedChatJson<{ ranking?: Array<{ idx?: number; equivalence?: string }>; confidence?: number; reason?: string }>(
    "pick",
    key,
    [
      { role: "system", content: SYSTEM },
      { role: "user", content: user },
    ],
    { maxTokens: 1200 },
  );
  if (!raw?.ranking || !Array.isArray(raw.ranking)) return null;

  const orderedIds: string[] = [];
  const equivalences: Record<string, Equivalence> = {};
  for (const entry of raw.ranking) {
    const product = typeof entry.idx === "number" ? input.candidates[entry.idx] : undefined;
    if (!product || orderedIds.includes(product.id)) continue;
    orderedIds.push(product.id);
    equivalences[product.id] = EQUIVALENCES.includes(entry.equivalence as Equivalence) ? (entry.equivalence as Equivalence) : "EQUIVALENT";
  }
  // Anything the model forgot goes to the end in the original order.
  for (const product of input.candidates) {
    if (!orderedIds.includes(product.id)) {
      orderedIds.push(product.id);
      equivalences[product.id] = "SUBSTITUTE";
    }
  }
  return {
    orderedIds,
    equivalences,
    confidence: typeof raw.confidence === "number" ? Math.min(1, Math.max(0, raw.confidence)) : 0.5,
    reason: typeof raw.reason === "string" ? raw.reason.slice(0, 120) : null,
  };
}

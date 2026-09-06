import type { ParsedIdea, ProductRow } from "@/db/schema";
import { expandTokens, tokenize, tokenSimilarity } from "@/lib/text";

const POSITION_BONUS = 0.3;

function sizeScore(parsed: ParsedIdea, product: ProductRow) {
  if (!parsed.sizeHint || !product.unitAmount || product.unit !== parsed.sizeHint.unit) return 0;
  const ratio = product.unitAmount / parsed.sizeHint.amount;
  // Perfect when the pack equals the hint; decays as the pack becomes much larger or smaller.
  return Math.max(0, 1 - Math.abs(Math.log(ratio)));
}

export function lexicalScore(text: string, parsed: ParsedIdea, product: ProductRow) {
  const queryTokens = Array.from(new Set([...tokenize(text), ...tokenize(parsed.canonicalName)]));
  const productTokens = tokenize([product.title, product.brand ?? "", product.category ?? ""].join(" "));
  if (!queryTokens.length || !productTokens.length) return 0;

  let score = 0;
  let matched = 0;
  for (const token of queryTokens) {
    // A token matches if it or any of its NL/EN synonyms resembles a product token.
    let best = 0;
    for (const alternative of expandTokens([token])) {
      for (const candidate of productTokens) best = Math.max(best, tokenSimilarity(alternative, candidate));
    }
    if (best >= 0.75) matched += 1;
    score += best;
  }
  score = (score / queryTokens.length) * 2 + matched;

  const titleTokens = tokenize(product.title);
  for (const attribute of parsed.attributes) {
    const attrTokens = tokenize(attribute);
    if (attrTokens.every((token) => titleTokens.some((candidate) => tokenSimilarity(token, candidate) >= 0.8))) score += 1;
  }

  score += sizeScore(parsed, product);
  // Without an explicit size hint, plain single packs beat multipacks / bulk packs.
  if (!parsed.sizeHint && /\b(\d+-pack|voordeel\w*|multipack|\d+\s*x\s*\d)/i.test(`${product.title} ${product.quantityText}`)) score -= 0.6;
  if (!product.available) score -= 2;
  if (product.isDeal) score += 0.1;
  // Slightly prefer plain, shorter titles (closer to a generic product) when everything else is equal.
  score -= Math.min(0.5, titleTokens.length * 0.03);
  return score;
}

/** Ranks candidates by lexical score; the store's own result order acts as a tie-breaker. */
export function lexicalRank(text: string, parsed: ParsedIdea, candidates: ProductRow[]) {
  const count = Math.max(candidates.length, 1);
  return candidates
    .map((product, index) => ({ product, score: lexicalScore(text, parsed, product) + ((count - index) / count) * POSITION_BONUS }))
    .sort((a, b) => b.score - a.score);
}

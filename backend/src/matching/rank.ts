import type { ParsedIdea, ProductRow } from "@/db/schema";
import { rankBy } from "@/db/settings";
import { expandTokens, tokenize, tokenSimilarity } from "@/lib/text";

const POSITION_BONUS = 0.3;

function sizeScore(parsed: ParsedIdea, product: ProductRow) {
  if (
    !parsed.sizeHint ||
    !product.unitAmount ||
    product.unit !== parsed.sizeHint.unit
  )
    return 0;
  const ratio = product.unitAmount / parsed.sizeHint.amount;
  // Perfect when the pack equals the hint; decays as the pack becomes much larger or smaller.
  return Math.max(0, 1 - Math.abs(Math.log(ratio)));
}

export function lexicalScore(
  text: string,
  parsed: ParsedIdea,
  product: ProductRow,
) {
  const queryTokens = Array.from(
    new Set([...tokenize(text), ...tokenize(parsed.canonicalName)]),
  );
  const productTokens = tokenize(
    [product.title, product.brand ?? "", product.category ?? ""].join(" "),
  );
  if (!queryTokens.length || !productTokens.length) return 0;

  let score = 0;
  let matched = 0;
  for (const token of queryTokens) {
    // A token matches if it or any of its NL/EN synonyms resembles a product token.
    let best = 0;
    for (const alternative of expandTokens([token])) {
      for (const candidate of productTokens)
        best = Math.max(best, tokenSimilarity(alternative, candidate));
    }
    if (best >= 0.75) matched += 1;
    score += best;
  }
  score = (score / queryTokens.length) * 2 + matched;

  const titleTokens = tokenize(product.title);
  for (const attribute of parsed.attributes) {
    const attrTokens = tokenize(attribute);
    if (
      attrTokens.every((token) =>
        titleTokens.some(
          (candidate) => tokenSimilarity(token, candidate) >= 0.8,
        ),
      )
    )
      score += 1;
  }

  score += sizeScore(parsed, product);
  // Without an explicit size hint, plain single packs beat multipacks / bulk packs.
  if (
    !parsed.sizeHint &&
    /\b(\d+-pack|voordeel\w*|multipack|\d+\s*x\s*\d)/i.test(
      `${product.title} ${product.quantityText}`,
    )
  )
    score -= 0.6;
  if (!product.available) score -= 2;
  if (product.isDeal) score += 0.1;
  // Slightly prefer plain, shorter titles (closer to a generic product) when everything else is equal.
  score -= Math.min(0.5, titleTokens.length * 0.03);
  return score;
}

/**
 * How much `product` resembles a product the user already picked elsewhere (0..1): same kind of pack
 * (pieces vs weight vs volume), similar pack size, similar unit price and overlapping title words.
 */
export function referenceSimilarity(
  reference: ProductRow,
  product: ProductRow,
) {
  let score = 0;
  if (reference.unit && product.unit) {
    if (reference.unit === product.unit) {
      score += 0.35;
      if (reference.unitAmount && product.unitAmount) {
        const ratio =
          Math.min(reference.unitAmount, product.unitAmount) /
          Math.max(reference.unitAmount, product.unitAmount);
        score += 0.25 * ratio;
      }
    } else score -= 0.3;
  }
  if (
    reference.unitPriceCents &&
    product.unitPriceCents &&
    reference.unitPriceUnit === product.unitPriceUnit
  ) {
    score +=
      0.15 *
      (Math.min(reference.unitPriceCents, product.unitPriceCents) /
        Math.max(reference.unitPriceCents, product.unitPriceCents));
  }
  const referenceTokens = tokenize(reference.title).filter(
    (token) => !/^\d+([.,]\d+)?$/.test(token),
  );
  const productTokens = tokenize(product.title);
  if (referenceTokens.length && productTokens.length) {
    let matched = 0;
    for (const token of referenceTokens) {
      if (
        expandTokens([token]).some((alternative) =>
          productTokens.some(
            (candidate) => tokenSimilarity(alternative, candidate) >= 0.8,
          ),
        )
      )
        matched += 1;
    }
    score += 0.25 * (matched / referenceTokens.length);
  }
  return Math.max(0, score);
}

/** Reorders products so the ones closest to `reference` come first (stable for equal similarity). */
export function sortByReference(reference: ProductRow, products: ProductRow[]) {
  return products
    .map((product, index) => ({
      product,
      index,
      similarity: referenceSimilarity(reference, product),
    }))
    .sort((a, b) => b.similarity - a.similarity || a.index - b.index)
    .map((entry) => entry.product);
}

/** Ranks candidates by lexical score; the store's own result order acts as a tie-breaker. */
export function lexicalRank(
  text: string,
  parsed: ParsedIdea,
  candidates: ProductRow[],
  reference: ProductRow | null = null,
) {
  const count = Math.max(candidates.length, 1);
  const byUnitPrice = rankBy() === "unitPrice";
  const unitPrices = candidates
    .map((product) => product.unitPriceCents ?? Number.POSITIVE_INFINITY)
    .filter(Number.isFinite);
  const cheapestUnit = unitPrices.length ? Math.min(...unitPrices) : null;
  return candidates
    .map((product, index) => {
      let score =
        lexicalScore(text, parsed, product) +
        ((count - index) / count) * POSITION_BONUS;
      // In unit-price mode the best €/kg or €/l among the candidates gets a nudge (never overriding a wrong product type).
      if (byUnitPrice && cheapestUnit && product.unitPriceCents)
        score += Math.max(0, 0.5 * (cheapestUnit / product.unitPriceCents));
      // With a pick at another store, favour the closest equivalent (same pack type and size) over random hits.
      if (reference) score += 1.5 * referenceSimilarity(reference, product);
      return { product, score };
    })
    .sort((a, b) => b.score - a.score);
}

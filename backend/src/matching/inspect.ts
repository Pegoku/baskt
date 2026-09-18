import { cachedChatJson } from "@/ai/client";
import type { ProductRow } from "@/db/schema";
import { appLanguageName } from "@/db/settings";
import { visionConfigured } from "@/env";
import { sha256 } from "@/lib/text";
import { productDetails } from "@/stores/details";

const INSPECT_PROMPT_VERSION = 1;
const MAX_IMAGES = 3;
const DESCRIPTION_CHARS = 1500;

export type Inspection = {
  /** Whether the pictures answer the question positively. */
  verdict: "yes" | "no" | "unsure";
  /** What the model actually saw on the packaging (label text, colours, badges). */
  seen: string;
  /** Short answer to the question, in the app language. */
  answer: string;
  imagesChecked: number;
};

const SYSTEM = (language: string) =>
  `You check supermarket product pictures for a shopping assistant. You get a question about a product, its listing text and one or more photos of the packaging. Look at the photos carefully: read the label, brand, claims (bio, vegan, sugar free, ...), variant, pack size and count. Base your verdict on what is visible, not on the listing text alone; say when the pictures do not show enough. Answer in ${language}.
Return ONLY a JSON object: {"verdict": "yes" | "no" | "unsure", "seen": string, "answer": string}. "seen" describes the packaging in one or two sentences, "answer" answers the question in one or two sentences.`;

/** Text summary of the listing, so the model can reconcile the picture with the shop's own words. */
export function describeProduct(product: ProductRow, description: string | null) {
  const lines = [
    `Store: ${product.store}`,
    `Title: ${product.title}`,
    product.brand ? `Brand: ${product.brand}` : null,
    `Size: ${product.quantityText}`,
    `Price: €${(product.priceCents / 100).toFixed(2)}${product.dealText ? ` (${product.dealText})` : ""}`,
    description ? `Description: ${description.slice(0, DESCRIPTION_CHARS)}` : null,
  ];
  return lines.filter(Boolean).join("\n");
}

/**
 * Asks the vision pool to look at a product's pictures and answer a question about it. Cached per
 * product, question and language; null when no vision provider answered.
 */
export async function inspectProduct(
  product: ProductRow,
  question: string,
): Promise<Inspection | { error: string }> {
  if (!visionConfigured()) return { error: "no vision provider is configured (AI_VISION_*), so pictures cannot be checked" };
  const details = await productDetails(product);
  const images = details.imageUrls.slice(0, MAX_IMAGES);
  if (!images.length) return { error: "this product has no picture" };
  const key = await sha256([INSPECT_PROMPT_VERSION, appLanguageName(), product.id, question.trim().toLowerCase(), images.join(",")].join("|"));
  const raw = await cachedChatJson<{ verdict?: unknown; seen?: unknown; answer?: unknown }>(
    "inspect-product",
    key,
    [
      { role: "system", content: SYSTEM(appLanguageName()) },
      {
        role: "user",
        content: [
          { type: "text", text: `Question: ${question.trim()}\n\n${describeProduct(product, details.description)}` },
          ...images.map((url) => ({ type: "image_url" as const, image_url: { url } })),
        ],
      },
    ],
    { maxTokens: 400, profile: "vision" },
  );
  if (!raw) return { error: "the vision model did not answer" };
  const verdict = raw.verdict === "yes" || raw.verdict === "no" ? raw.verdict : "unsure";
  return {
    verdict,
    seen: typeof raw.seen === "string" ? raw.seen : "",
    answer: typeof raw.answer === "string" ? raw.answer : "",
    imagesChecked: images.length,
  };
}

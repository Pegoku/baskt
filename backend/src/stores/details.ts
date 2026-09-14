import { getAdapter } from "@/stores/registry";
import { cachedUpstream } from "@/lib/cache";
import type { ProductRow } from "@/db/schema";

export function plainText(value: unknown): string | null {
  if (typeof value !== "string") return null;
  return value.replace(/<script\b[^>]*>[\s\S]*?<\/script>/gi, "").replace(/<\/(?:p|div|li)>|<br\s*\/?>/gi, "\n")
    .replace(/<[^>]*>/g, "").replace(/&nbsp;/g, " ").replace(/&amp;/g, "&").replace(/&quot;/g, '"').replace(/&#39;|&apos;/g, "'")
    .replace(/&#(\d+);/g, (_, n) => { const code = Number(n); return code > 0 && code <= 0x10ffff ? String.fromCodePoint(code) : ""; }).trim() || null;
}

export function parseProductDetails(html: string): { description: string | null; imageUrls: string[] } {
  let description: string | null = null;
  const images = new Set<string>();
  function visit(value: unknown) {
    if (Array.isArray(value)) { value.forEach(visit); return; }
    if (!value || typeof value !== "object") return;
    const node = value as Record<string, unknown>;
    const types = Array.isArray(node["@type"]) ? node["@type"] : [node["@type"]];
    if (types.includes("Product")) {
      description ??= plainText(node.description);
      const raw = Array.isArray(node.image) ? node.image : [node.image];
      for (const image of raw) {
        const url = typeof image === "string" ? image : image && typeof image === "object" ? (image as Record<string, unknown>).url : null;
        if (typeof url === "string" && /^https:\/\//.test(url)) images.add(url);
      }
    }
    if (node["@graph"]) visit(node["@graph"]);
    if (node.mainEntity) visit(node.mainEntity);
  }
  for (const match of html.matchAll(/<script\b[^>]*type=["']application\/ld\+json["'][^>]*>([\s\S]*?)<\/script>/gi)) {
    try { visit(JSON.parse(match[1])); } catch { /* A malformed unrelated block must not hide other data. */ }
  }
  return { description, imageUrls: [...images] };
}

/** Only fetch known product pages, never arbitrary user URLs or redirected internal resources. */
export async function productDetails(product: ProductRow) {
  const fallback = { description: null as string | null, imageUrls: product.imageUrl ? [product.imageUrl] : [] };
  try {
    const adapter = getAdapter(product.store);
    if (adapter.details) {
      const detail = await cachedUpstream(`adapter-product-details:v1:${product.id}`, 86400000,
        () => adapter.details!(product.sourceId), { cacheable: (value) => !!value.description, staleMs: 604800000 });
      if (detail.description) return { description: plainText(detail.description), imageUrls: detail.imageUrls.length ? [...new Set(detail.imageUrls)] : fallback.imageUrls };
    }
  } catch { /* Fall back to the public product page. */ }
  if (!product.sourceUrl) return fallback;
  const url = new URL(product.sourceUrl);
  if (url.protocol !== "https:" || url.port || url.username || url.password ||
      !["www.ah.nl", "www.jumbo.com"].includes(url.hostname) || !url.pathname.startsWith("/producten/")) return fallback;
  try {
    return await cachedUpstream(`product-details:v2:${product.id}`, 86400000, async () => {
      const response = await fetch(url, { redirect: "error", signal: AbortSignal.timeout(12000), headers: { "user-agent": "Mozilla/5.0", accept: "text/html" } });
      if (!response.ok) throw new Error("Product page unavailable");
      const detail = parseProductDetails(await response.text());
      return { ...detail, imageUrls: detail.imageUrls.length ? [...new Set(detail.imageUrls)] : fallback.imageUrls };
    }, { cacheable: (value) => !!value.description, staleMs: 604800000 });
  } catch { return fallback; }
}

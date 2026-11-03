import { getSetting, setSetting } from "@/db/settings";
import { parseScannedCode } from "@/lib/gs1";

/**
 * What kind of code a product carries at the till, learnt from scans in the store: a fixed barcode on the pack, a
 * label printed when it is weighed (price or weight inside the code), or nothing scannable at all. The phone uses it
 * to tell which items on the list still need a label scanned before the checkout can replay the codes.
 */
export type BarcodeKind = "pack" | "label" | "none";

export type BarcodeKindEntry = {
  store: string;
  productId: string | null;
  itemText: string | null;
  kind: BarcodeKind;
  /** The last few raw codes seen, newest first, so the map can be checked by hand. */
  codes: string[];
  count: number;
  lastAt: number;
};

const KEY = "barcodeKinds";
const MAX_CODES = 5;

/** Pack or label, read from the code itself; null when it is not a product code at all. */
export function kindOfCode(raw: string): Exclude<BarcodeKind, "none"> | null {
  const code = parseScannedCode(raw);
  if (!code) return null;
  return code.variable || code.priceCents != null || code.weightGrams != null ? "label" : "pack";
}

function entryKey(store: string, productId: string | null, itemText: string | null) {
  return productId ? `${store}:p:${productId}` : `${store}:t:${(itemText ?? "").trim().toLowerCase()}`;
}

export function barcodeKinds(store?: string): BarcodeKindEntry[] {
  const all = Object.values(getSetting<Record<string, BarcodeKindEntry>>(KEY, {}));
  return (store ? all.filter((entry) => entry.store === store) : all).sort((a, b) => b.lastAt - a.lastAt);
}

/** Records one observation; the latest one decides the kind, the codes keep the history. */
export function recordBarcodeKind(input: { store: string; productId?: string | null; itemText?: string | null; code?: string | null; kind?: BarcodeKind | null; at?: number }): BarcodeKindEntry | null {
  const productId = input.productId || null;
  const itemText = input.itemText?.trim() || null;
  if (!productId && !itemText) return null;
  const kind = input.kind ?? (input.code ? kindOfCode(input.code) : null);
  if (!kind) return null;
  const map = getSetting<Record<string, BarcodeKindEntry>>(KEY, {});
  const key = entryKey(input.store, productId, itemText);
  const previous = map[key];
  const codes = input.code ? [input.code, ...(previous?.codes ?? []).filter((code) => code !== input.code)].slice(0, MAX_CODES) : previous?.codes ?? [];
  const entry: BarcodeKindEntry = {
    store: input.store,
    productId,
    itemText: itemText ?? previous?.itemText ?? null,
    kind,
    codes,
    count: (previous?.count ?? 0) + 1,
    lastAt: input.at ?? Date.now(),
  };
  map[key] = entry;
  setSetting(KEY, map);
  return entry;
}

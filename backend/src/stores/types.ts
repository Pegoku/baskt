import type { Unit } from "@/lib/units";

export type StoreCode = string;

export type StoreInfo = {
  code: StoreCode;
  name: string;
  color: string;
  /** "full" when the store publishes its shelf prices, "partial" for offers-only sources. */
  coverage: "full" | "partial";
};

export type StoreProduct = {
  store: StoreCode;
  sourceId: string;
  title: string;
  brand: string | null;
  quantityText: string;
  unitAmount: number | null;
  unit: Unit | null;
  priceCents: number;
  regularPriceCents: number | null;
  unitPriceCents: number | null;
  unitPriceUnit: Unit | null;
  dealText: string | null;
  isDeal: boolean;
  imageUrl: string | null;
  sourceUrl: string | null;
  category: string | null;
  available: boolean;
};

export interface StoreAdapter {
  readonly info: StoreInfo;
  search(query: string, limit: number): Promise<StoreProduct[]>;
  /** Optional: refresh specific products by their source ids (used by re-pricing). */
  refresh?(sourceIds: string[]): Promise<StoreProduct[]>;
  /** Optional: look up a product by EAN/GTIN barcode. Stores without it are searched with the barcode as query. */
  byBarcode?(gtin: string): Promise<StoreProduct | null>;
}

export const productKey = (store: StoreCode, sourceId: string) => `${store}:${sourceId}`;

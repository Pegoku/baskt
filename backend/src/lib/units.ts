export type Unit = "kg" | "l" | "piece";

export type Quantity = { amount: number; unit: Unit };

function toNumber(value: string) {
  return Number(value.replace(",", "."));
}

function convert(amount: number, rawUnit: string): Quantity | null {
  const unit = rawUnit.toLowerCase();
  if (unit === "g" || unit === "gram" || unit === "gr") return { amount: amount / 1000, unit: "kg" };
  if (unit === "kg" || unit === "kilo" || unit === "kilogram") return { amount, unit: "kg" };
  if (unit === "ml") return { amount: amount / 1000, unit: "l" };
  if (unit === "cl") return { amount: amount / 100, unit: "l" };
  if (unit === "l" || unit === "liter" || unit === "ltr") return { amount, unit: "l" };
  if (unit.startsWith("stuk") || unit === "st" || unit === "stuks" || unit === "pieces" || unit === "piece") return { amount, unit: "piece" };
  return null;
}

const UNIT = "(kg|kilo|kilogram|g|gram|gr|ml|cl|l|liter|ltr|stuks?|st|pieces?)";
const NUM = "(\\d+(?:[.,]\\d+)?)";
const MULTI = new RegExp(`${NUM}\\s*[x×]\\s*${NUM}\\s*${UNIT}\\b`, "i");
const SINGLE = new RegExp(`${NUM}\\s*${UNIT}\\b`, "i");
const COUNT_ONLY = new RegExp(`^(?:ca\\.?\\s*)?${NUM}\\s*(?:stuks?|st|x)?\\s*$`, "i");

/** Parses Dutch pack sizes such as "1 l", "500 g", "6 stuks", "2 x 250 g", "1,5 liter", "ca. 900 g". */
export function parseQuantity(text: string | null | undefined): Quantity | null {
  if (!text) return null;
  const value = text.trim().toLowerCase();
  const multi = value.match(MULTI);
  if (multi) {
    const converted = convert(toNumber(multi[1]) * toNumber(multi[2]), multi[3]);
    if (converted) return converted;
  }
  const single = value.match(SINGLE);
  if (single) {
    const converted = convert(toNumber(single[1]), single[2]);
    if (converted) return converted;
  }
  if (/per stuk|per bos|per krop|per tros|^stuk$/.test(value)) return { amount: 1, unit: "piece" };
  const count = value.match(COUNT_ONLY);
  if (count) return { amount: toNumber(count[1]), unit: "piece" };
  return null;
}

/** Parses "prijs per liter €0.85" / "prijs per kilo €12.50" / "prijs per stuk €0.30". */
export function parseUnitPriceDescription(text: string | null | undefined): { cents: number; unit: Unit } | null {
  if (!text) return null;
  const match = text.match(/per\s+(\w+)\s*€?\s*(\d+(?:[.,]\d+)?)/i);
  if (!match) return null;
  const unit = convert(1, match[1]);
  if (!unit) return null;
  return { cents: Math.round(toNumber(match[2]) * 100), unit: unit.unit };
}

export function normalizeUnit(raw: string | null | undefined): Unit | null {
  if (!raw) return null;
  return convert(1, raw.trim())?.unit ?? null;
}

export function unitPriceFrom(priceCents: number, quantity: Quantity | null): { cents: number; unit: Unit } | null {
  if (!quantity || quantity.amount <= 0) return null;
  return { cents: Math.round(priceCents / quantity.amount), unit: quantity.unit };
}

export function eurosToCents(value: number | null | undefined) {
  if (value === null || value === undefined || !Number.isFinite(value)) return null;
  return Math.round(value * 100);
}

/**
 * What a scanned product code says. Fresh meat, cheese and bread weighed in the store carry either a GS1 2D
 * code (DataMatrix/QR with "01" GTIN plus weight and price) or an EAN-13 starting with 2 whose last digits are
 * the price; plain packs carry an EAN-13/8 or UPC.
 */
export type ScannedCode = {
  /** The GTIN to look the product up by (13 digits for GTIN-14 with a leading 0). */
  gtin: string;
  /** Price printed in the code (GS1 AI 392n), in cents. */
  priceCents: number | null;
  /** Net weight in the code (GS1 AI 310n), in grams. */
  weightGrams: number | null;
  /** Store-internal variable-measure code (EAN-13 prefix 2): no supermarket API knows it. */
  variable: boolean;
};

const GS = "\u001d";
/** Fixed-length AIs (prefix → data length); everything else runs to the next GS or the end. */
const FIXED: Array<[RegExp, number]> = [
  [/^00/, 18], [/^0[1-3]/, 14], [/^1[1-7]/, 6], [/^20/, 2], [/^3[1-6]\d\d/, 6], [/^41\d/, 13],
];

function aiLength(rest: string): { ai: string; length: number | null } {
  for (const [pattern, length] of FIXED) {
    const match = pattern.exec(rest);
    if (match) return { ai: match[0], length };
  }
  // Variable-length AIs used on food labels: 10 lot, 21 serial, 392n/393n price.
  if (/^39[23]\d/.test(rest)) return { ai: rest.slice(0, 4), length: null };
  if (/^(10|21|22|30|37)/.test(rest)) return { ai: rest.slice(0, 2), length: null };
  return { ai: rest.slice(0, 4), length: null };
}

/** Element string ("01087...3103001234" with GS separators) to AI → value. */
export function parseElementString(raw: string): Map<string, string> {
  const values = new Map<string, string>();
  let rest = raw.replace(/^\][A-Za-z]\d/, "").replace(/[()]/g, (bracket) => (bracket === "(" ? GS : ""));
  rest = rest.replace(new RegExp(`^${GS}+`), "");
  while (rest.length > 2) {
    const { ai, length } = aiLength(rest);
    rest = rest.slice(ai.length);
    let value: string;
    if (length != null) {
      value = rest.slice(0, length);
      rest = rest.slice(length);
    } else {
      const end = rest.indexOf(GS);
      value = end === -1 ? rest : rest.slice(0, end);
      rest = end === -1 ? "" : rest.slice(end);
    }
    values.set(ai, value);
    rest = rest.replace(new RegExp(`^${GS}+`), "");
  }
  return values;
}

const gtin13 = (gtin14: string) => (gtin14.length === 14 && gtin14.startsWith("0") ? gtin14.slice(1) : gtin14);

export function parseScannedCode(raw: string): ScannedCode | null {
  const text = raw.trim();
  if (!text) return null;
  // GS1 Digital Link QR: https://.../01/08712345678906/3103/001234?...
  const link = /\/01\/(\d{14})/.exec(text);
  if (link) return { gtin: gtin13(link[1]), priceCents: null, weightGrams: null, variable: false };
  const digits = text.replace(/\D/g, "");
  if (/^\d{8,14}$/.test(text) || (digits === text && digits.length <= 14)) {
    if (digits.length < 8) return null;
    const gtin = gtin13(digits);
    return { gtin, priceCents: null, weightGrams: null, variable: gtin.length === 13 && gtin.startsWith("2") };
  }
  const values = parseElementString(text);
  const id = values.get("01") ?? values.get("02");
  if (!id || !/^\d{14}$/.test(id)) return digits.length >= 8 && digits.length <= 14 ? { gtin: digits, priceCents: null, weightGrams: null, variable: false } : null;
  let priceCents: number | null = null;
  let weightGrams: number | null = null;
  for (const [ai, value] of values) {
    if (/^392\d$/.test(ai) && /^\d+$/.test(value)) priceCents = Math.round((Number(value) / 10 ** Number(ai[3])) * 100);
    if (/^310\d$/.test(ai) && /^\d{6}$/.test(value)) weightGrams = Math.round((Number(value) / 10 ** Number(ai[3])) * 1000);
  }
  return { gtin: gtin13(id), priceCents, weightGrams, variable: false };
}

/**
 * The fixed set of pantry groups the stock page shows. Store taxonomies (AH "Groente, aardappelen /
 * Ui", Jumbo "Aardappelen, groente en fruit") are folded onto these keys; the app owns the labels and icons.
 */
export const STOCK_CATEGORIES = [
  "produce",
  "dairy",
  "meat",
  "bakery",
  "breakfast",
  "pantry",
  "spices",
  "snacks",
  "drinks",
  "frozen",
  "meals",
  "household",
  "other",
] as const;

export type StockCategory = (typeof STOCK_CATEGORIES)[number];

export function isStockCategory(value: unknown): value is StockCategory {
  return typeof value === "string" && (STOCK_CATEGORIES as readonly string[]).includes(value);
}

/** Subcategories that mean "herbs, spices and oils", even under a broader AH soups-and-sauces aisle. */
const SPICE_SUB = /zout|peper|kruid|specerij|olie|azijn|oregano|majoraan|tijm|rozemarijn|laurier|peterselie|selderij|basilicum|kaneel|nootmuskaat|paprikapoeder|knoflookpoeder|uienpoeder|kerrie|komijn|chili/;

/** Ordered: the first rule that matches the top-level aisle wins. */
const RULES: Array<[RegExp, StockCategory]> = [
  [/diepvries/, "frozen"],
  [/groente|fruit|aardappel/, "produce"],
  [/vlees|vis\b|vega|plantaardig|tapas/, "meat"],
  [/zuivel|kaas|eieren/, "dairy"],
  [/ontbijt|beleg/, "breakfast"],
  [/bakkerij|brood|gebak/, "bakery"],
  [/koek|snoep|chocolade|chips|borrel|snacks|tussendoortjes/, "snacks"],
  [/frisdrank|sappen|water|koffie|thee|bier|wijn/, "drinks"],
  [/maaltijd|salades|gemak/, "meals"],
  [/drogisterij|huishouden|baby|dieren|gezondheid|non-food|koken, tafelen/, "household"],
  [/pasta|rijst|wereldkeuken|conserven|soepen|sauzen|glutenvrij/, "pantry"],
];

/** Maps a store category path like "Soepen, sauzen, kruiden, olie / Zwarte peper" to a stock group. */
export function categoryFromStore(path: string | null | undefined): StockCategory | null {
  if (!path) return null;
  const [top, ...rest] = path.toLowerCase().split(" / ");
  const sub = rest.join(" / ");
  if (sub && /kruid|saus|olie/.test(top) && SPICE_SUB.test(sub)) return "spices";
  for (const [pattern, category] of RULES) if (pattern.test(top)) return category;
  return null;
}

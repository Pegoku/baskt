export function normalizeText(value: string) {
  return value
    .toLowerCase()
    .normalize("NFKD")
    .replace(/[̀-ͯ]/g, "")
    .replace(/[^a-z0-9\s]/g, " ")
    .replace(/\s+/g, " ")
    .trim();
}

export function tokenize(value: string) {
  return normalizeText(value).split(" ").filter(Boolean);
}

/** Small NL<->EN synonym table so lexical ranking works across the languages the user mixes. */
const SYNONYMS: Record<string, string[]> = {
  milk: ["melk"],
  melk: ["milk"],
  "semi-skimmed": ["halfvolle", "halfvol"],
  halfvolle: ["halfvol", "semi", "skimmed"],
  eggs: ["eieren", "ei"],
  eieren: ["eggs", "egg"],
  bread: ["brood"],
  brood: ["bread"],
  butter: ["boter", "roomboter"],
  boter: ["butter"],
  cheese: ["kaas"],
  kaas: ["cheese"],
  chicken: ["kip", "kipfilet"],
  kip: ["chicken"],
  apples: ["appels", "appel"],
  appels: ["apples", "apple"],
  bananas: ["bananen", "banaan"],
  bananen: ["bananas", "banana"],
  yoghurt: ["yogurt"],
  yogurt: ["yoghurt"],
  rice: ["rijst"],
  rijst: ["rice"],
  pasta: ["spaghetti", "penne", "macaroni"],
  sugar: ["suiker"],
  suiker: ["sugar"],
  coffee: ["koffie"],
  koffie: ["coffee"],
  tea: ["thee"],
  thee: ["tea"],
  organic: ["bio", "biologisch", "biologische"],
  bio: ["organic", "biologisch", "biologische"],
};

export function expandTokens(tokens: string[]) {
  const out = new Set(tokens);
  for (const token of tokens) {
    for (const synonym of SYNONYMS[token] ?? []) out.add(synonym);
  }
  return Array.from(out);
}

export function tokenSimilarity(left: string, right: string) {
  if (left === right) return 1;
  if (left.length >= 3 && right.length >= 3 && (left.startsWith(right) || right.startsWith(left))) return 0.9;
  const distance = levenshtein(left, right);
  const longest = Math.max(left.length, right.length);
  return longest ? 1 - distance / longest : 0;
}

export function levenshtein(left: string, right: string) {
  if (left === right) return 0;
  if (!left.length) return right.length;
  if (!right.length) return left.length;
  let previous = Array.from({ length: right.length + 1 }, (_, index) => index);
  for (let i = 1; i <= left.length; i += 1) {
    const current = [i];
    for (let j = 1; j <= right.length; j += 1) {
      const cost = left[i - 1] === right[j - 1] ? 0 : 1;
      current[j] = Math.min(current[j - 1] + 1, previous[j] + 1, previous[j - 1] + cost);
    }
    previous = current;
  }
  return previous[right.length];
}

export async function sha256(value: string) {
  const digest = await crypto.subtle.digest("SHA-256", new TextEncoder().encode(value));
  return Array.from(new Uint8Array(digest))
    .map((byte) => byte.toString(16).padStart(2, "0"))
    .join("");
}

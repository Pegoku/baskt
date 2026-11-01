import { describe, expect, test } from "bun:test";
import { classifyDeal, dealKind, decodeDevalue, findDevalueObjects, matchesQuery, periodOf, realDate, termRelevance } from "@/stores/promotions";
import { mapJumboPromotions } from "@/stores/jumbo";
import { mapAhBonusGroup } from "@/stores/ah";

// Mimics Nuxt's devalue layout: objects/arrays reference other entries by index.
const payload = [
  ["Reactive", 1],
  { promotions: 2 },
  [3, 8],
  { id: 4, title: 5, subtitle: 9, tags: 6, durationTexts: 10, image: 11, url: 12, start: 13, end: 13, hidden: 14 },
  "3019772",
  "Jumbo's koffiepads",
  [7],
  { text: 15, inverse: 14 },
  { id: 16, title: 17, tags: 18, durationTexts: 10, hidden: 19 },
  "Alle zakken à 36 stuks<br />M.u.v. Biologische",
  { shortTitle: 20 },
  "https://img/koffie.png",
  "/aanbiedingen/jumbos-koffiepads/3019772",
  "2026-09-08",
  false,
  "1+1 gratis",
  "999",
  "Hidden promo",
  [],
  true,
  "wo 2 t/m di 8 sep",
];

describe("devalue decoding", () => {
  test("resolves nested references and reactive wrappers", () => {
    const resolve = decodeDevalue(payload);
    const root = resolve(0) as { promotions: Array<Record<string, unknown>> };
    expect(root.promotions[0].title).toBe("Jumbo's koffiepads");
    expect((root.promotions[0].tags as Array<{ text: string }>)[0].text).toBe("1+1 gratis");
    expect(findDevalueObjects(payload, ["durationTexts", "title", "tags"])).toHaveLength(2);
  });

  test("maps Jumbo promotions from the page payload and skips hidden ones", () => {
    const html = `<html><script type="application/json" id="__NUXT_DATA__">${JSON.stringify(payload)}</script></html>`;
    const cards = mapJumboPromotions(html);
    expect(cards).toHaveLength(1);
    expect(cards[0]).toMatchObject({ store: "JUMBO", id: "3019772", title: "Jumbo's koffiepads", dealText: "1+1 gratis", url: "https://www.jumbo.com/aanbiedingen/jumbos-koffiepads/3019772", validUntil: "2026-09-08" });
    expect(cards[0].subtitle).toBe("Alle zakken à 36 stuks · M.u.v. Biologische");
    expect(matchesQuery(cards[0], "koffie")).toBe(true);
    expect(matchesQuery(cards[0], "melk")).toBe(false);
    expect(termRelevance(cards[0], ["koffie", "melk"])).toBe(1);
    expect(termRelevance(cards[0], ["melk", "zuivel"])).toBe(0);
    expect(realDate("2999-12-31")).toBeNull();
    expect(realDate("2026-09-13")).toBe("2026-09-13");
  });
});

describe("deal periods", () => {
  test("one bonus week is weekly, longer runs are seasonal, open-ended ones are neither", () => {
    expect(periodOf("2026-11-02", "2026-11-08")).toBe("week");
    expect(periodOf("2026-10-28", "2026-11-03")).toBe("week");
    expect(periodOf("2026-10-19", "2026-12-06")).toBe("season");
    expect(periodOf("2026-11-01", null)).toBeNull();
  });

  test("Jumbo keeps only the running week's sections and reads Week/Seizoen and the featured section", () => {
    const promo = (id: string, title: string, group: string) => ({ id, title, tags: [], durationTexts: {}, group, start: { iso: "2026-10-28T00:01:00+01:00" }, end: { iso: "2026-11-03T23:59:59+01:00" } });
    const page = {
      sections: [
        { id: "Nu in de aanbieding_20261028-20261103", title: "Nu in de aanbieding", promotions: [promo("1", "Maza", "Week")] },
        { id: "Vlees_20261028-20261103", title: "Vlees", promotions: [promo("1", "Maza", "Week"), promo("2", "Hamburgers", "Seizoen")] },
        { id: "Vlees_20261104-20261110", title: "Vlees", promotions: [promo("3", "Next week only", "Week")] },
      ],
    };
    const html = `<script type="application/json" id="__NUXT_DATA__">${JSON.stringify(flatten(page))}</script>`;
    const cards = mapJumboPromotions(html, new Date("2026-11-02T10:00:00Z"));
    expect(cards.map((card) => card.id).sort()).toEqual(["1", "2"]);
    expect(cards.find((card) => card.id === "1")).toMatchObject({ period: "week", featured: true, validFrom: "2026-10-28", validUntil: "2026-11-03" });
    expect(cards.find((card) => card.id === "2")).toMatchObject({ period: "season", featured: false });
  });

  test("AH bonus groups become seasonal group cards", () => {
    const card = mapAhBonusGroup({ id: "819728", title: "Sinterklaas accessoires", periodStart: "2026-10-19", periodEnd: "2026-12-06", productIds: [1, 2, 3], webPath: "/bonus/groep/819728", images: [{ url: "small", width: 200 }, { url: "big", width: 708 }], rawPromotionLabels: [{ defaultDescription: "Feestfavoriet" }] }, false);
    expect(card).toMatchObject({ store: "AH", id: "group-819728", subtitle: "3 products", dealText: "Feestfavoriet", imageUrl: "big", url: "https://www.ah.nl/bonus/groep/819728", productId: null, period: "season", featured: false });
  });
});

describe("deal kinds", () => {
  test("reads the mechanism from AH and Jumbo deal texts", () => {
    const cases: Array<[string | null, string]> = [
      ["13.5% volume voordeel", "volume"],
      ["2e gratis", "free"],
      ["1 + 1 gratis", "free"],
      ["2+3 gratis", "free"],
      ["2e Halve Prijs", "half"],
      ["2 voor 5.00", "multibuy"],
      ["3 voor 0,99", "multibuy"],
      ["25% KORTING", "percent"],
      ["VOOR 2.29", "price"],
      ["100 GRAM VOOR 1.99", "price"],
      ["1,00 korting", "price"],
      ["Gratis ontbijtblik - Voeg toe · Bij 2 Jumbo's ontbijtgranen", "gift"],
      ["Bonus", "other"],
      ["Feestfavoriet", "other"],
      [null, "other"],
    ];
    for (const [text, kind] of cases) expect([text, dealKind(text)]).toEqual([text, kind]);
  });

  test("flags online-only deals", () => {
    const card = classifyDeal({ store: "JUMBO", id: "1", title: "Koffiemachine", subtitle: null, dealText: "20% korting · Alleen online", imageUrl: null, url: null, priceCents: null, regularPriceCents: null, productId: null, validFrom: null, validUntil: null });
    expect(card).toMatchObject({ kind: "percent", onlineOnly: true });
  });
});

/** Turns a plain value into Nuxt's devalue layout (every object, array and string referenced by index). */
function flatten(root: unknown) {
  const out: unknown[] = [];
  const visit = (value: unknown): number => {
    const index = out.length;
    out.push(null);
    if (Array.isArray(value)) out[index] = value.map(visit);
    else if (value && typeof value === "object") out[index] = Object.fromEntries(Object.entries(value).map(([key, entry]) => [key, visit(entry)]));
    else out[index] = value;
    return index;
  };
  visit(root);
  return out;
}

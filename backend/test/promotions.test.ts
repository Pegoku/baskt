import { describe, expect, test } from "bun:test";
import { decodeDevalue, findDevalueObjects, matchesQuery, realDate, termRelevance } from "@/stores/promotions";
import { mapJumboPromotions } from "@/stores/jumbo";

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

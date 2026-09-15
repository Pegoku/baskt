import { describe, expect, test } from "bun:test";
import { fitsCoverage, fitsTime, parseDiscoverParams } from "@/matching/cook";

describe("fitsCoverage", () => {
  const dish = (uses: number, missing: number) => ({ uses: Array(uses).fill("x"), missing: Array(missing).fill("y") });
  test("all: nothing may be missing", () => {
    expect(fitsCoverage(dish(5, 0), "all")).toBe(true);
    expect(fitsCoverage(dish(5, 1), "all")).toBe(false);
  });
  test("most: at most two small things, and no more than a third", () => {
    expect(fitsCoverage(dish(4, 1), "most")).toBe(true);
    expect(fitsCoverage(dish(4, 2), "most")).toBe(true);
    expect(fitsCoverage(dish(2, 2), "most")).toBe(false);
    expect(fitsCoverage(dish(8, 3), "most")).toBe(false);
  });
  test("half: at least half must be at home", () => {
    expect(fitsCoverage(dish(3, 3), "half")).toBe(true);
    expect(fitsCoverage(dish(2, 3), "half")).toBe(false);
  });
  test("a dish without any ingredient lists is kept", () => {
    expect(fitsCoverage(dish(0, 0), "all")).toBe(true);
  });
});

describe("fitsTime", () => {
  test("no limit keeps everything, a limit drops slow and unknown dishes", () => {
    expect(fitsTime({ minutes: null }, null)).toBe(true);
    expect(fitsTime({ minutes: 25 }, 30)).toBe(true);
    expect(fitsTime({ minutes: 45 }, 30)).toBe(false);
    expect(fitsTime({ minutes: null }, 30)).toBe(false);
  });
});

describe("parseDiscoverParams", () => {
  test("falls back to from-stock with most ingredients at home", () => {
    expect(parseDiscoverParams({})).toEqual({ mode: "stock", coverage: "most", style: null, cuisine: null, maxMinutes: null });
  });
  test("reads every option and ignores nonsense", () => {
    expect(parseDiscoverParams({ mode: "cuisine", cuisine: " Mexico ", maxMinutes: "30", coverage: "weird" })).toEqual({ mode: "cuisine", coverage: "most", style: null, cuisine: "Mexico", maxMinutes: 30 });
    expect(parseDiscoverParams({ mode: "new", style: "vegetarian", maxMinutes: "abc" }).maxMinutes).toBeNull();
  });
});

import { describe, expect, test } from "vitest";
import { levelForExp, split } from "./payout";

// Mirrors PayoutTest.java and LevelingTest.java.
const ALL = new Set([1, 2, 3, 4]);

describe("payout split", () => {
  test("equal split counts each participant once", () => {
    const r = split(300, 20, "EQUAL", 1, [2, 2, 3], ALL);
    expect(r.treasury).toBe(60);
    expect(r.shares.map((s) => s.amount)).toEqual([80, 80, 80]);
  });

  test("by party weights recruits sent plus one for leading", () => {
    const r = split(1000, 0, "BY_PARTY", 1, [1, 2, 2, 3], ALL);
    expect(r.shares).toEqual([
      { membershipId: 1, weight: 2, amount: 400 },
      { membershipId: 2, weight: 2, amount: 400 },
      { membershipId: 3, weight: 1, amount: 200 },
    ]);
  });

  test("rounding leftovers go to the treasury", () => {
    const r = split(100, 0, "EQUAL", 1, [2, 3], ALL);
    expect(r.shares.map((s) => s.amount)).toEqual([33, 33, 33]);
    expect(r.treasury).toBe(1);
  });

  test("members who left don't share; nobody left means the team keeps it", () => {
    expect(split(200, 0, "EQUAL", 1, [4], new Set([1, 2])).shares).toEqual([{ membershipId: 1, weight: 1, amount: 200 }]);
    expect(split(500, 10, "BY_PARTY", null, [null, 9], ALL)).toEqual({ treasury: 500, shares: [] });
  });

  test("never creates or loses Poké", () => {
    let seed = 7;
    const rand = (n: number) => ((seed = (seed * 1103515245 + 12345) % 2 ** 31), seed % n);
    for (let i = 0; i < 5000; i++) {
      const reward = rand(100_001);
      const cut = rand(101);
      const party = Array.from({ length: 1 + rand(4) }, () => 1 + rand(5));
      const r = split(reward, cut, rand(2) ? "EQUAL" : "BY_PARTY", rand(6) === 0 ? null : 1 + rand(5), party, ALL);
      expect(r.treasury + r.shares.reduce((a, s) => a + s.amount, 0)).toBe(reward);
    }
  });
});

describe("leveling", () => {
  test("cubes are thresholds", () => {
    for (let level = 2; level <= 100; level++) {
      expect(levelForExp(level ** 3)).toBe(level);
      expect(levelForExp(level ** 3 - 1)).toBe(level - 1);
    }
    expect(levelForExp(0)).toBe(1);
  });
});

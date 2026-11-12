import type { SplitMode } from "../api/types";

export interface Share { membershipId: number; weight: number; amount: number }

/**
 * Port of Payout.java. The treasury takes floor(reward × cut%); the rest is split by weight among the mission
 * leader and every member who recruited someone in the party, rounding down; leftovers go to the treasury.
 */
export function split(
  reward: number,
  cutPct: number,
  mode: SplitMode,
  leaderId: number | null,
  partyRecruiters: (number | null)[],
  active: Set<number>,
): { treasury: number; shares: Share[] } {
  const weights = new Map<number, number>();
  if (leaderId != null && active.has(leaderId)) weights.set(leaderId, 1);
  for (const r of partyRecruiters) {
    if (r == null || !active.has(r)) continue;
    if (mode === "BY_PARTY") weights.set(r, (weights.get(r) ?? 0) + 1);
    else if (!weights.has(r)) weights.set(r, 1);
  }
  const cut = Math.floor((reward * cutPct) / 100);
  const pool = reward - cut;
  const total = [...weights.values()].reduce((a, b) => a + b, 0);
  if (total === 0) return { treasury: reward, shares: [] };
  const shares = [...weights.entries()]
    .sort(([a], [b]) => a - b)
    .map(([membershipId, weight]) => ({ membershipId, weight, amount: Math.floor((pool * weight) / total) }));
  const paid = shares.reduce((a, s) => a + s.amount, 0);
  return { treasury: cut + (pool - paid), shares };
}

export const expForLevel = (level: number) => (level <= 1 ? 0 : level ** 3);

export function levelForExp(exp: number) {
  let level = Math.max(1, Math.floor(Math.cbrt(exp)));
  while (expForLevel(level + 1) <= exp) level++;
  return level;
}

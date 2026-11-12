// Demo data for the browser build. Everything is created through the engine's own routes with a rewound clock,
// so the history is a real sequence of operations and the ledger reconciles like any other.

import { catalog, difficulty, dungeon } from "../api/catalog";
import type { Engine } from "./engine";

export const SEED_PASSWORD = "lantern-123";

export const SEED_ACCOUNTS = [
  { username: "mira", displayName: "Mira", speciesId: 447, note: "Leader of Team Lanterna" },
  { username: "teo", displayName: "Teo", speciesId: 158, note: "Officer" },
  { username: "sol", displayName: "Sol", speciesId: 152, note: "Member" },
  { username: "nina", displayName: "Nina", speciesId: 393, note: "Member, joined late" },
  { username: "kai", displayName: "Kai", speciesId: 390, note: "Leads Team Driftwood" },
  { username: "ines", displayName: "Inês", speciesId: 155, note: "No team yet, has an invitation" },
];

const DAY = 86_400_000;
const HOUR = 3_600_000;

function mulberry32(seed: number) {
  return () => {
    seed |= 0;
    seed = (seed + 0x6d2b79f5) | 0;
    let t = Math.imul(seed ^ (seed >>> 15), 1 | seed);
    t = (t + Math.imul(t ^ (t >>> 7), 61 | t)) ^ t;
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
  };
}

export function seed(engine: Engine, endsAt = Date.now()) {
  const rand = mulberry32(20261112);
  const pick = <T,>(list: T[]) => list[Math.floor(rand() * list.length)];
  let t = endsAt - 46 * DAY;
  const realNow = engine.now;
  engine.now = () => new Date(t);
  const advance = (ms: number) => (t += ms);

  const tokens: Record<string, string> = {};
  const call = (who: string | null, method: string, path: string, body?: unknown): any => {
    const res = engine.handle(method, path, body, who ? tokens[who] : null);
    if (res.status >= 300) throw new Error(`seed: ${method} ${path} as ${who} -> ${res.status} ${JSON.stringify(res.body)}`);
    return res.body;
  };

  for (const a of SEED_ACCOUNTS) {
    tokens[a.username] = call(null, "POST", "/api/auth/register", { username: a.username, displayName: a.displayName, password: SEED_PASSWORD, speciesId: a.speciesId }).token;
    advance(40 * 60_000);
  }

  const team = call("mira", "POST", "/api/teams", { name: "Team Lanterna" });
  const T = `/api/teams/${team.id}`;
  const m: Record<string, number> = { mira: team.myMembershipId };
  advance(3 * HOUR);

  const join = (who: string, by: string, role: "MEMBER" | "OFFICER") => {
    const inv = call(by, "POST", `${T}/invitations`, { username: who, role });
    advance(5 * HOUR);
    call(who, "POST", `/api/invitations/${inv.id}/accept`);
  };
  join("teo", "mira", "OFFICER");
  join("sol", "teo", "MEMBER");
  const members = call("mira", "GET", `${T}/members`);
  m.teo = members[1].membershipId;
  m.sol = members[2].membershipId;

  const recruit = (who: string, speciesId: number, level: number, nickname?: string) => {
    advance(2 * HOUR);
    return call(who, "POST", `${T}/recruits`, { speciesId, level, nickname }).id as number;
  };
  const roster: { id: number; by: string }[] = [];
  const add = (who: string, speciesId: number, level: number, nickname?: string) =>
    roster.push({ id: recruit(who, speciesId, level, nickname), by: who });

  add("mira", 25, 7, "Pip");      // Pikachu
  add("mira", 1, 6);               // Bulbasaur
  add("teo", 66, 8, "Brick");      // Machop
  add("teo", 74, 7);               // Geodude
  add("sol", 258, 6, "Moss");      // Mudkip
  add("sol", 175, 5);              // Togepi

  const deposit = (who: string, itemCode: string, quantity: number, memo?: string) => {
    call(who, "POST", `${T}/storage/deposit`, { itemCode, quantity, memo });
    advance(20 * 60_000);
  };
  deposit("mira", "oran-berry", 10, "Starter stock");
  deposit("mira", "apple", 6);
  deposit("teo", "reviver-seed", 2);
  deposit("sol", "escape-orb", 2);
  deposit("sol", "pecha-berry", 4);
  call("mira", "POST", `${T}/treasury/deposit`, { amount: 500, memo: "Founding funds" });

  const clients = [422, 39, 52, 133, 183, 216, 263, 298, 300, 399, 427, 446, 175, 194, 172, 393, 252];
  const outlaws = [41, 88, 92, 109, 198, 215, 228, 261, 302, 318, 451, 453];
  const finds = ["Gold Ribbon", "Link Box", "Wish Stone", "Lost Scarf", "Water Float", "Ember Pendant"];
  const title = (kind: string, client: number, floor: string, dungeonName: string) => {
    const name = catalog.species.find((s) => s.id === client)!.name;
    switch (kind) {
      case "RESCUE": return pick([`${name} fainted on ${floor}`, `Help! ${name} is stuck in ${dungeonName}`, `${name} hasn't come back from ${floor}`]);
      case "ESCORT": return pick([`Take ${name} to ${floor}`, `${name} wants to meet a friend on ${floor}`]);
      case "DELIVERY": return pick([`${name} needs an Oran Berry on ${floor}`, `Bring ${name} an Apple`, `Deliver a Reviver Seed to ${name}`]);
      case "SEARCH": return pick([`Find ${name}'s ${pick(finds)}`, `${name} dropped something on ${floor}`]);
      default: return pick([`Outlaw ${name} spotted on ${floor}`, `Arrest ${name} in ${dungeonName}`]);
    }
  };

  const post = (by: string, diffCode: string, opts: { kind?: string; dungeonCode?: string } = {}) => {
    const pool = catalog.dungeons.filter((d) => d.suggestedDifficulty === diffCode || (diffCode === "E" && d.suggestedDifficulty === "D"));
    const d = opts.dungeonCode ? dungeon(opts.dungeonCode)! : pick(pool.length ? pool : catalog.dungeons);
    const kind = opts.kind ?? pick(catalog.missionKinds);
    const floor = 1 + Math.floor(rand() * d.floors);
    const client = kind === "OUTLAW" ? pick(outlaws) : pick(clients);
    const label = d.direction === "B" ? `B${floor}F` : `${floor}F`;
    const diff = difficulty(diffCode)!;
    const reward = Math.round((diff.suggestedPoke * (0.8 + rand() * 0.5)) / 10) * 10;
    const rewardItems = rand() < 0.45 ? [{ itemCode: pick(["oran-berry", "apple", "blast-seed", "sleep-seed", "reviver-seed", "iron-thorn", "max-elixir"]), quantity: 1 + Math.floor(rand() * 2) }] : [];
    advance(3 * HOUR);
    return call(by, "POST", `${T}/missions`, { title: title(kind, client, label, d.name), kind, dungeonCode: d.code, floor, difficulty: diffCode, clientSpeciesId: client, rewardPoke: reward, rewardItems }).id as number;
  };

  const busy = new Set<number>();
  const plan = (missionId: number, leader: string, size: number, bag: { itemCode: string; quantity: number }[]) => {
    const free = roster.filter((r) => !busy.has(r.id));
    const party: number[] = [];
    while (party.length < Math.min(size, free.length)) {
      const r = pick(free);
      if (!party.includes(r.id)) party.push(r.id);
    }
    const storage = call("mira", "GET", `${T}/storage`).items as { itemCode: string; quantity: number }[];
    const packable = bag.filter((b) => (storage.find((s) => s.itemCode === b.itemCode)?.quantity ?? 0) >= b.quantity);
    advance(2 * HOUR);
    call("teo", "PUT", `${T}/missions/${missionId}/plan`, { leaderMembershipId: m[leader], recruitIds: party, bag: packable });
    return party;
  };
  const run = (missionId: number, leader: string, size: number, outcome: "COMPLETED" | "FAILED", note?: string) => {
    const party = plan(missionId, leader, size, [{ itemCode: "oran-berry", quantity: 2 }, { itemCode: "apple", quantity: 1 }, { itemCode: "reviver-seed", quantity: 1 }]);
    party.forEach((id) => busy.add(id));
    advance(1 * HOUR);
    call(leader, "POST", `${T}/missions/${missionId}/depart`);
    advance(5 * HOUR + rand() * 10 * HOUR);
    const bag = call("mira", "GET", `${T}/missions/${missionId}`).bag as { itemCode: string; quantity: number }[];
    const itemsUsed = bag.filter(() => rand() < 0.6).map((b) => ({ itemCode: b.itemCode, quantity: 1 }));
    call(leader, "POST", `${T}/missions/${missionId}/report`, { outcome, itemsUsed, note });
    party.forEach((id) => busy.delete(id));
    advance(6 * HOUR + rand() * 12 * HOUR);
  };

  // Normal Rank: E and D jobs until Bronze.
  run(post("mira", "E", { kind: "RESCUE", dungeonCode: "beach-cave" }), "mira", 2, "COMPLETED", "First rescue. Shellos was fine, just hungry.");
  run(post("mira", "E"), "teo", 2, "COMPLETED");
  run(post("teo", "D"), "teo", 3, "COMPLETED");
  run(post("mira", "D"), "sol", 2, "FAILED", "Monster house on the last floor.");
  deposit("teo", "oran-berry", 6);
  run(post("teo", "D"), "teo", 3, "COMPLETED");
  run(post("mira", "D"), "mira", 3, "COMPLETED");
  call("mira", "PUT", `${T}/policy`, { treasuryCutPct: 15, splitMode: "BY_PARTY" });
  run(post("teo", "D"), "sol", 3, "COMPLETED");
  run(post("mira", "D"), "teo", 4, "COMPLETED");
  run(post("teo", "D"), "mira", 3, "COMPLETED", "Client paid extra in apples.");

  join("nina", "mira", "MEMBER");
  m.nina = call("mira", "GET", `${T}/members`)[3].membershipId;
  add("nina", 255, 9, "Cinder");   // Torchic
  add("nina", 403, 8);             // Shinx
  add("sol", 133, 8, "Tuft");      // Eevee
  add("mira", 147, 10);            // Dratini
  deposit("nina", "blast-seed", 3);
  deposit("nina", "sleep-seed", 2);
  deposit("mira", "big-apple", 2);
  deposit("teo", "reviver-seed", 2);

  // Bronze Rank: C jobs open up.
  for (let i = 0; i < 20; i++) {
    const leader = pick(["mira", "teo", "sol", "nina"]);
    const fail = i === 6 || i === 13;
    run(post(pick(["mira", "teo"]), rand() < 0.85 ? "C" : "D"), leader, 2 + Math.floor(rand() * 3), fail ? "FAILED" : "COMPLETED",
      fail ? pick(["Ran out of PP against the outlaw.", "Got separated in the fog."]) : undefined);
    if (i % 4 === 1) deposit(pick(["mira", "teo", "nina"]), pick(["oran-berry", "apple", "reviver-seed", "escape-orb"]), 2 + Math.floor(rand() * 3));
    if (i === 8) call("mira", "POST", `${T}/treasury/payout`, { membershipId: m.teo, amount: 240, memo: "Reimbursed the Kecleon Shop run" });
    if (i === 10) call("sol", "POST", `${T}/treasury/contribute`, { amount: 120, memo: "Toward a bigger bag" });
  }
  const cancelled = post("teo", "C", { kind: "ESCORT" });
  advance(4 * HOUR);
  call("teo", "POST", `${T}/missions/${cancelled}/cancel`);

  add("teo", 304, 14, "Anvil");    // Aron
  add("sol", 280, 12);             // Ralts

  // Current board: two parties out, one ready to leave, three jobs posted.
  t = endsAt - 40 * HOUR;
  deposit("mira", "oran-berry", 8, "Restock after the Crystal Cave trip");
  const out1 = post("mira", "B", { kind: "OUTLAW" });
  const p1 = plan(out1, "teo", 3, [{ itemCode: "oran-berry", quantity: 4 }, { itemCode: "reviver-seed", quantity: 2 }, { itemCode: "blast-seed", quantity: 2 }, { itemCode: "escape-orb", quantity: 1 }]);
  p1.forEach((id) => busy.add(id));
  advance(1 * HOUR);
  call("teo", "POST", `${T}/missions/${out1}/depart`);
  const out2 = post("teo", "C", { kind: "RESCUE" });
  const p2 = plan(out2, "sol", 2, [{ itemCode: "oran-berry", quantity: 2 }, { itemCode: "apple", quantity: 2 }]);
  p2.forEach((id) => busy.add(id));
  advance(2 * HOUR);
  call("sol", "POST", `${T}/missions/${out2}/depart`);
  advance(5 * HOUR);
  const ready = post("mira", "C", { kind: "DELIVERY" });
  plan(ready, "nina", 2, [{ itemCode: "oran-berry", quantity: 2 }, { itemCode: "big-apple", quantity: 1 }]);
  advance(3 * HOUR);
  post("teo", "B", { kind: "SEARCH" });
  post("mira", "C", { kind: "ESCORT" });
  post("teo", "D", { kind: "RESCUE" });
  advance(1 * HOUR);
  call("teo", "POST", `${T}/invitations`, { username: "ines", role: "MEMBER" });

  // A second team, so invitations and the team switcher have something to show.
  advance(1 * HOUR);
  const drift = call("kai", "POST", "/api/teams", { name: "Team Driftwood" });
  call("kai", "POST", `/api/teams/${drift.id}/recruits`, { speciesId: 7, level: 9, nickname: "Shell" });
  call("kai", "POST", `/api/teams/${drift.id}/recruits`, { speciesId: 393, level: 7 });
  call("kai", "POST", `/api/teams/${drift.id}/storage/deposit`, { itemCode: "oran-berry", quantity: 5 });
  call("kai", "POST", `/api/teams/${drift.id}/invitations`, { username: "mira", role: "OFFICER" });

  engine.state.sessions = {};
  engine.now = realNow;
  return { teamId: team.id as number };
}

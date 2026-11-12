// In-browser stand-in for the Spring API, used by the GitHub Pages build. It answers the same routes with the
// same status codes, error codes and response shapes; shared/contract/scenario.json is run against both.
// Each mutating request works on a snapshot that is discarded if anything throws, like a rolled-back transaction.

import {
  catalog, difficulty, difficultyOrder, dungeon, floorLabel, item, nextRank, rankFor, speciesById,
} from "../api/catalog";
import type { ItemStack, MissionStatus, Reason, RecruitStatus, Role, SplitMode } from "../api/types";
import { expForLevel, levelForExp, split } from "./payout";

interface UserRow { id: number; username: string; displayName: string; speciesId: number; passwordHash: string; createdAt: string }
interface TeamRow { id: number; name: string; rankPoints: number; treasuryCutPct: number; splitMode: SplitMode; createdAt: string }
interface MembershipRow { id: number; teamId: number; userId: number; role: Role; joinedAt: string; leftAt: string | null }
interface InvitationRow {
  id: number; teamId: number; inviteeId: number; invitedById: number; role: Role;
  status: "PENDING" | "ACCEPTED" | "DECLINED" | "REVOKED" | "EXPIRED"; createdAt: string; expiresAt: string;
}
interface RecruitRow {
  id: number; teamId: number; speciesId: number; nickname: string | null; level: number; exp: number;
  status: RecruitStatus; recruitedById: number | null; recruitedAt: string; missionId: number | null;
}
interface MissionRow {
  id: number; teamId: number; title: string; kind: string; dungeonCode: string; floor: number; difficulty: string;
  clientSpeciesId: number | null; rewardPoke: number; rankPoints: number; rewardItems: ItemStack[];
  status: MissionStatus; leaderId: number | null; party: number[]; bag: ItemStack[]; postedById: number;
  postedAt: string; departedAt: string | null; closedAt: string | null; outcomeNote: string | null;
}
interface EntryRow {
  id: number; teamId: number; txn: string; accountKey: string; asset: string; delta: number; balanceAfter: number;
  reason: Reason; missionId: number | null; actorId: number | null; memo: string | null; createdAt: string;
}
interface EventRow { id: number; teamId: number; actorId: number | null; type: string; summary: string; createdAt: string }

export interface State {
  version: 1;
  seq: Record<string, number>;
  users: UserRow[];
  teams: TeamRow[];
  memberships: MembershipRow[];
  invitations: InvitationRow[];
  recruits: RecruitRow[];
  missions: MissionRow[];
  balances: Record<string, number>;
  entries: EntryRow[];
  events: EventRow[];
  sessions: Record<string, number>;
}

export interface Response { status: number; body: unknown }

class ApiError extends Error {
  constructor(readonly status: number, readonly code: string, message: string) {
    super(message);
  }
}

const notFound = (code: string, msg: string) => new ApiError(404, code, msg);
const forbidden = (msg: string) => new ApiError(403, "team.forbidden", msg);
const conflict = (code: string, msg: string) => new ApiError(409, code, msg);
const invalid = (code: string, msg: string) => new ApiError(422, code, msg);
const badRequest = (msg: string) => new ApiError(400, "validation", msg);

const POKE = "POKE";
const TREASURY = "treasury";
const STORAGE = "storage";
const WORLD = "world";
const wallet = (id: number) => `wallet:${id}`;
const bagAccount = (id: number) => `bag:${id}`;
const ROLE_ORDER: Role[] = ["MEMBER", "OFFICER", "LEADER"];
const atLeast = (role: Role, min: Role) => ROLE_ORDER.indexOf(role) >= ROLE_ORDER.indexOf(min);
const DAY = 86_400_000;

export function emptyState(): State {
  return {
    version: 1, seq: {}, users: [], teams: [], memberships: [], invitations: [], recruits: [], missions: [],
    balances: {}, entries: [], events: [], sessions: {},
  };
}

/** Not a security boundary (the data never leaves the browser); it only avoids storing plain passwords. */
function hashPassword(password: string, salt: string) {
  let h = 0x811c9dc5;
  for (const ch of salt + ":" + password) {
    h ^= ch.charCodeAt(0);
    h = Math.imul(h, 0x01000193) >>> 0;
  }
  return h.toString(16);
}

const randomId = () =>
  typeof crypto !== "undefined" && "randomUUID" in crypto
    ? crypto.randomUUID()
    : Math.random().toString(16).slice(2) + Math.random().toString(16).slice(2);

type Handler = (ctx: Ctx) => unknown;
interface Ctx { params: number[]; body: any; query: URLSearchParams; userId: number }
interface Route { method: string; pattern: RegExp; auth: boolean; status?: number; handler: Handler }

export class Engine {
  state: State;
  now: () => Date = () => new Date();
  private routes: Route[] = [];

  constructor(state: State = emptyState()) {
    this.state = state;
    this.defineRoutes();
  }

  handle(method: string, url: string, body?: unknown, token?: string | null): Response {
    const [path, qs] = url.split("?");
    const route = this.routes.find((r) => r.method === method && r.pattern.test(path));
    if (!route) return { status: 404, body: { status: 404, code: "not-found", detail: "No such route" } };
    const params = (path.match(route.pattern) ?? []).slice(1).map(Number);
    let userId = 0;
    if (route.auth) {
      const id = token ? this.state.sessions[token] : undefined;
      if (id == null || !this.state.users.some((u) => u.id === id)) return { status: 401, body: null };
      userId = id;
    }
    const snapshot = method === "GET" ? null : structuredClone(this.state);
    try {
      const result = route.handler({ params, body: body ?? null, query: new URLSearchParams(qs ?? ""), userId });
      const status = route.status ?? 200;
      return { status, body: status === 204 ? null : JSON.parse(JSON.stringify(result ?? null)) };
    } catch (e) {
      if (snapshot) this.state = snapshot;
      if (e instanceof ApiError) {
        return { status: e.status, body: { status: e.status, detail: e.message, code: e.code } };
      }
      throw e;
    }
  }

  // ---------------------------------------------------------------- routing

  private on(method: string, path: string, handler: Handler, opts: { status?: number; auth?: boolean } = {}) {
    const pattern = new RegExp("^" + path.replace(/:\w+/g, "(\\d+)") + "$");
    this.routes.push({ method, pattern, handler, auth: opts.auth ?? true, status: opts.status });
  }

  private defineRoutes() {
    this.on("POST", "/api/auth/register", (c) => this.register(c.body), { status: 201, auth: false });
    this.on("POST", "/api/auth/login", (c) => this.login(c.body), { auth: false });
    this.on("GET", "/api/catalog", () => catalog, { auth: false });
    this.on("GET", "/api/me", (c) => this.me(c.userId));
    this.on("PATCH", "/api/me", (c) => this.updateProfile(c.userId, c.body));

    this.on("GET", "/api/teams", (c) => this.teamsOf(c.userId));
    this.on("POST", "/api/teams", (c) => this.createTeam(c.userId, c.body), { status: 201 });
    this.on("GET", "/api/teams/:t", (c) => this.teamDetail(c.params[0], c.userId));
    this.on("PUT", "/api/teams/:t/policy", (c) => this.updatePolicy(c.params[0], c.userId, c.body));
    this.on("GET", "/api/teams/:t/members", (c) => this.members(c.params[0], c.userId));
    this.on("PATCH", "/api/teams/:t/members/:m", (c) => this.changeRole(c.params[0], c.userId, c.params[1], c.body), { status: 204 });
    this.on("DELETE", "/api/teams/:t/members/:m", (c) => this.removeMember(c.params[0], c.userId, c.params[1]), { status: 204 });
    this.on("POST", "/api/teams/:t/leadership", (c) => this.transferLeadership(c.params[0], c.userId, c.body), { status: 204 });
    this.on("GET", "/api/teams/:t/events", (c) => this.eventsOf(c.params[0], c.userId, Number(c.query.get("limit") ?? 40)));

    this.on("POST", "/api/teams/:t/invitations", (c) => this.invite(c.params[0], c.userId, c.body), { status: 201 });
    this.on("GET", "/api/teams/:t/invitations", (c) => this.teamInvitations(c.params[0], c.userId));
    this.on("DELETE", "/api/teams/:t/invitations/:i", (c) => this.revoke(c.params[0], c.userId, c.params[1]), { status: 204 });
    this.on("GET", "/api/invitations", (c) => this.myInvitations(c.userId));
    this.on("POST", "/api/invitations/:i/accept", (c) => ({ teamId: this.accept(c.userId, c.params[0]) }));
    this.on("POST", "/api/invitations/:i/decline", (c) => this.decline(c.userId, c.params[0]), { status: 204 });

    this.on("GET", "/api/teams/:t/recruits", (c) => this.recruitList(c.params[0], c.userId));
    this.on("POST", "/api/teams/:t/recruits", (c) => this.recruit(c.params[0], c.userId, c.body), { status: 201 });
    this.on("PATCH", "/api/teams/:t/recruits/:r", (c) => this.rename(c.params[0], c.userId, c.params[1], c.body));
    this.on("DELETE", "/api/teams/:t/recruits/:r", (c) => this.release(c.params[0], c.userId, c.params[1]), { status: 204 });

    this.on("GET", "/api/teams/:t/missions", (c) => this.missionList(c.params[0], c.userId));
    this.on("POST", "/api/teams/:t/missions", (c) => this.postMission(c.params[0], c.userId, c.body), { status: 201 });
    this.on("GET", "/api/teams/:t/missions/:m", (c) => { this.member(c.params[0], c.userId); return this.missionView(this.findMission(c.params[0], c.params[1])); });
    this.on("PUT", "/api/teams/:t/missions/:m/plan", (c) => this.plan(c.params[0], c.userId, c.params[1], c.body));
    this.on("POST", "/api/teams/:t/missions/:m/depart", (c) => this.depart(c.params[0], c.userId, c.params[1]));
    this.on("GET", "/api/teams/:t/missions/:m/payout-preview", (c) => this.preview(c.params[0], c.userId, c.params[1]));
    this.on("POST", "/api/teams/:t/missions/:m/report", (c) => this.report(c.params[0], c.userId, c.params[1], c.body));
    this.on("POST", "/api/teams/:t/missions/:m/cancel", (c) => this.cancel(c.params[0], c.userId, c.params[1]));

    this.on("GET", "/api/teams/:t/storage", (c) => this.storageView(c.params[0], c.userId));
    this.on("POST", "/api/teams/:t/storage/deposit", (c) => this.depositItem(c.params[0], c.userId, c.body), { status: 204 });
    this.on("POST", "/api/teams/:t/storage/discard", (c) => this.discardItem(c.params[0], c.userId, c.body), { status: 204 });
    this.on("POST", "/api/teams/:t/treasury/deposit", (c) => this.depositPoke(c.params[0], c.userId, c.body), { status: 204 });
    this.on("POST", "/api/teams/:t/treasury/contribute", (c) => this.contribute(c.params[0], c.userId, c.body), { status: 204 });
    this.on("POST", "/api/teams/:t/treasury/payout", (c) => this.payout(c.params[0], c.userId, c.body), { status: 204 });
    this.on("POST", "/api/teams/:t/wallet/cash-out", (c) => this.cashOut(c.params[0], c.userId, c.body), { status: 204 });
    this.on("GET", "/api/teams/:t/ledger", (c) => this.ledgerPage(c.params[0], c.userId, c.query));
    this.on("GET", "/api/teams/:t/ledger/reconcile", (c) => { this.member(c.params[0], c.userId); return this.reconcile(c.params[0]); });
  }

  // ---------------------------------------------------------------- helpers

  private nextId(table: string) {
    this.state.seq[table] = (this.state.seq[table] ?? 0) + 1;
    return this.state.seq[table];
  }

  private iso() {
    return this.now().toISOString();
  }

  private user(id: number) {
    return this.state.users.find((u) => u.id === id)!;
  }

  private userView(id: number) {
    const u = this.user(id);
    return { id: u.id, username: u.username, displayName: u.displayName, speciesId: u.speciesId };
  }

  private team(id: number) {
    return this.state.teams.find((t) => t.id === id)!;
  }

  private activeMembers(teamId: number) {
    return this.state.memberships.filter((m) => m.teamId === teamId && m.leftAt == null).sort((a, b) => a.id - b.id);
  }

  private membership(id: number) {
    return this.state.memberships.find((m) => m.id === id);
  }

  private displayName(membershipId: number) {
    return this.user(this.membership(membershipId)!.userId).displayName;
  }

  private member(teamId: number, userId: number) {
    const m = this.state.memberships.find((x) => x.teamId === teamId && x.userId === userId && x.leftAt == null);
    if (!m) throw notFound("team.not-found", "Team not found");
    return m;
  }

  private atLeastRole(teamId: number, userId: number, role: Role) {
    const m = this.member(teamId, userId);
    if (!atLeast(m.role, role)) throw forbidden(`Only a team ${role.toLowerCase()} can do this`);
    return m;
  }

  private activeMember(teamId: number, membershipId: number) {
    const m = this.membership(membershipId);
    if (!m || m.teamId !== teamId || m.leftAt != null) throw notFound("member.not-found", "No such member in this team");
    return m;
  }

  private record(teamId: number, actorId: number | null, type: string, summary: string) {
    this.state.events.push({ id: this.nextId("events"), teamId, actorId, type, summary, createdAt: this.iso() });
  }

  private species(id: number) {
    const s = speciesById(id);
    if (!s) throw invalid("species.unknown", `No species with dex number ${id}`);
    return s;
  }

  private item(code: string) {
    const i = item(code);
    if (!i) throw invalid("item.unknown", `Unknown item ${code}`);
    return i;
  }

  private recruitLabel(r: RecruitRow) {
    const s = this.species(r.speciesId);
    return r.nickname == null ? s.name : `${r.nickname} the ${s.name}`;
  }

  // ---------------------------------------------------------------- ledger

  private balanceKey(teamId: number, account: string, asset: string) {
    return `${teamId}|${account}|${asset}`;
  }

  balance(teamId: number, account: string, asset: string) {
    return this.state.balances[this.balanceKey(teamId, account, asset)] ?? 0;
  }

  private holdings(teamId: number, account: string) {
    const prefix = `${teamId}|${account}|`;
    const out = new Map<string, number>();
    for (const [k, v] of Object.entries(this.state.balances)) {
      if (k.startsWith(prefix) && v !== 0) out.set(k.slice(prefix.length), v);
    }
    return out;
  }

  private post(teamId: number, actorId: number | null, reason: Reason, missionId: number | null, memo: string | null,
               legs: { account: string; asset: string; delta: number }[]) {
    const net = new Map<string, Map<string, number>>();
    const perAsset = new Map<string, number>();
    for (const l of legs) {
      if (!net.has(l.account)) net.set(l.account, new Map());
      const a = net.get(l.account)!;
      a.set(l.asset, (a.get(l.asset) ?? 0) + l.delta);
      perAsset.set(l.asset, (perAsset.get(l.asset) ?? 0) + l.delta);
    }
    for (const [asset, sum] of perAsset) {
      if (sum !== 0) throw new Error(`Unbalanced ${reason} transaction: ${asset} nets to ${sum}`);
    }
    const txn = randomId();
    const createdAt = this.iso();
    for (const account of [...net.keys()].sort()) {
      const assets = net.get(account)!;
      for (const asset of [...assets.keys()].sort()) {
        const delta = assets.get(asset)!;
        if (delta === 0) continue;
        const key = this.balanceKey(teamId, account, asset);
        const have = this.state.balances[key] ?? 0;
        if (account !== WORLD && have + delta < 0) throw this.shortfall(account, asset, have, -delta);
        this.state.balances[key] = have + delta;
        this.state.entries.push({
          id: this.nextId("entries"), teamId, txn, accountKey: account, asset, delta, balanceAfter: have + delta,
          reason, missionId, actorId, memo, createdAt,
        });
      }
    }
  }

  private shortfall(account: string, asset: string, have: number, need: number) {
    const where = account.startsWith("wallet:") ? "Wallet" : account.startsWith("bag:") ? "Mission bag"
      : account[0].toUpperCase() + account.slice(1);
    if (asset === POKE) return invalid("ledger.insufficient-funds", `${where} has ${have} Poké; ${need} needed`);
    return invalid("ledger.insufficient-stock", `${where} has ${have} × ${this.item(asset).name}; ${need} needed`);
  }

  private transfer(from: string, to: string, asset: string, amount: number) {
    return [{ account: from, asset, delta: -amount }, { account: to, asset, delta: amount }];
  }

  reconcile(teamId: number) {
    const problems: string[] = [];
    const running = new Map<string, number>();
    const txns = new Map<string, Map<string, number>>();
    const entries = this.state.entries.filter((e) => e.teamId === teamId).sort((a, b) => a.id - b.id);
    for (const e of entries) {
      const key = `${e.accountKey} / ${e.asset}`;
      const after = (running.get(key) ?? 0) + e.delta;
      if (after !== e.balanceAfter) problems.push(`Entry #${e.id} (${key}) records balance ${e.balanceAfter} but replay gives ${after}`);
      running.set(key, after);
      if (!txns.has(e.txn)) txns.set(e.txn, new Map());
      const t = txns.get(e.txn)!;
      t.set(e.asset, (t.get(e.asset) ?? 0) + e.delta);
    }
    for (const [txn, totals] of txns) {
      for (const [asset, sum] of totals) if (sum !== 0) problems.push(`Transaction ${txn.slice(0, 8)} leaves ${asset} off by ${sum}`);
    }
    const cached = new Map<string, number>();
    const prefix = `${teamId}|`;
    for (const [k, v] of Object.entries(this.state.balances)) {
      if (!k.startsWith(prefix)) continue;
      const [, account, asset] = k.split("|");
      cached.set(`${account} / ${asset}`, v);
    }
    let accounts = 0;
    for (const key of [...new Set([...running.keys(), ...cached.keys()])].sort()) {
      const replayed = running.get(key) ?? 0;
      const stored = cached.get(key) ?? 0;
      if (replayed !== stored) problems.push(`${key}: balance table says ${stored}, ledger says ${replayed}`);
      const isWorld = key.startsWith(WORLD + " ");
      if (replayed < 0 && !isWorld) problems.push(`${key} is negative (${replayed})`);
      if (!isWorld) accounts++;
    }
    return { ok: problems.length === 0, entries: entries.length, transactions: txns.size, accounts, problems };
  }

  // ---------------------------------------------------------------- auth

  private register(body: any) {
    const username = String(body?.username ?? "");
    const displayName = String(body?.displayName ?? "").trim();
    const password = String(body?.password ?? "");
    const errors: string[] = [];
    if (!/^[A-Za-z0-9_]{3,20}$/.test(username)) errors.push("username must be 3-20 letters, digits or _");
    if (!displayName || displayName.length > 40) errors.push("displayName must be 1-40 characters");
    if (password.length < 8 || password.length > 72) errors.push("password size must be between 8 and 72");
    if (body?.speciesId == null) errors.push("speciesId must not be null");
    if (errors.length) throw badRequest(errors.sort().join("; "));
    const speciesId = this.species(Number(body.speciesId)).id;
    if (this.state.users.some((u) => u.username.toLowerCase() === username.toLowerCase())) {
      throw conflict("auth.username-taken", "That username is already registered");
    }
    const salt = randomId();
    const user: UserRow = { id: this.nextId("users"), username, displayName, speciesId, passwordHash: `${salt}$${hashPassword(password, salt)}`, createdAt: this.iso() };
    this.state.users.push(user);
    return this.session(user);
  }

  private login(body: any) {
    const u = this.state.users.find((x) => x.username.toLowerCase() === String(body?.username ?? "").toLowerCase());
    const [salt, hash] = u ? u.passwordHash.split("$") : ["", ""];
    if (!u || hashPassword(String(body?.password ?? ""), salt) !== hash) {
      throw new ApiError(401, "auth.bad-credentials", "Username or password is wrong");
    }
    return this.session(u);
  }

  private session(u: UserRow) {
    const token = `local.${u.id}.${randomId()}`;
    this.state.sessions[token] = u.id;
    return { token, user: this.userView(u.id) };
  }

  /** Signs in without a password. Only for the demo account switcher; there is no HTTP route for it. */
  impersonate(username: string) {
    const u = this.state.users.find((x) => x.username === username);
    if (!u) throw new Error(`No user ${username}`);
    return this.session(u);
  }

  private updateProfile(userId: number, body: any) {
    const displayName = String(body?.displayName ?? "").trim();
    if (!displayName || displayName.length > 40) throw badRequest("displayName must be 1-40 characters");
    if (body?.speciesId == null) throw badRequest("speciesId must not be null");
    const s = this.species(Number(body.speciesId));
    const u = this.user(userId);
    u.displayName = displayName;
    u.speciesId = s.id;
    return this.userView(userId);
  }

  private me(userId: number) {
    return { user: this.userView(userId), teams: this.teamsOf(userId), pendingInvitations: this.openInvitations(userId).length };
  }

  // ---------------------------------------------------------------- teams

  private teamsOf(userId: number) {
    return this.state.memberships
      .filter((m) => m.userId === userId && m.leftAt == null)
      .sort((a, b) => a.id - b.id)
      .map((m) => {
        const t = this.team(m.teamId);
        return { id: t.id, name: t.name, rank: rankFor(t.rankPoints).code, rankPoints: t.rankPoints, role: m.role, memberCount: this.activeMembers(t.id).length };
      });
  }

  private createTeam(userId: number, body: any) {
    const name = String(body?.name ?? "").trim();
    if (name.length < 3 || name.length > 40) throw badRequest("name size must be between 3 and 40");
    if (this.state.teams.some((t) => t.name.toLowerCase() === name.toLowerCase())) {
      throw conflict("team.name-taken", "Another team already uses that name");
    }
    const team: TeamRow = { id: this.nextId("teams"), name, rankPoints: 0, treasuryCutPct: 20, splitMode: "EQUAL", createdAt: this.iso() };
    this.state.teams.push(team);
    this.state.memberships.push({ id: this.nextId("memberships"), teamId: team.id, userId, role: "LEADER", joinedAt: this.iso(), leftAt: null });
    this.record(team.id, userId, "team.created", `Founded ${name}`);
    return this.teamDetail(team.id, userId);
  }

  private teamDetail(teamId: number, userId: number) {
    const me = this.member(teamId, userId);
    const t = this.team(teamId);
    const r = rankFor(t.rankPoints);
    const next = nextRank(r);
    return {
      id: t.id, name: t.name, rank: r.code, rankName: r.name, rankPoints: t.rankPoints,
      nextRank: next ? { code: next.code, name: next.name, minPoints: next.minPoints } : null,
      maxDifficulty: r.maxDifficulty, rosterCap: r.rosterCap, bagCap: r.bagCap,
      treasuryCutPct: t.treasuryCutPct, splitMode: t.splitMode, myMembershipId: me.id, myRole: me.role,
      treasury: this.balance(teamId, TREASURY, POKE), memberCount: this.activeMembers(teamId).length,
      recruitCount: this.activeRecruits(teamId).length, createdAt: t.createdAt,
    };
  }

  private members(teamId: number, userId: number) {
    this.member(teamId, userId);
    const recruits = this.activeRecruits(teamId);
    return this.activeMembers(teamId).map((m) => {
      const u = this.user(m.userId);
      return {
        membershipId: m.id, userId: u.id, username: u.username, displayName: u.displayName, speciesId: u.speciesId, role: m.role,
        joinedAt: m.joinedAt, wallet: this.balance(teamId, wallet(m.id), POKE),
        recruits: recruits.filter((r) => r.recruitedById === m.id).length,
      };
    });
  }

  private changeRole(teamId: number, actorId: number, membershipId: number, body: any) {
    const actor = this.atLeastRole(teamId, actorId, "LEADER");
    const role = body?.role as Role;
    if (!ROLE_ORDER.includes(role)) throw badRequest("role must not be null");
    if (role === "LEADER") throw invalid("role.invalid", "Use leadership transfer to hand over the team");
    const target = this.activeMember(teamId, membershipId);
    if (target.id === actor.id) throw invalid("role.invalid", "The leader can't demote themselves; transfer leadership first");
    if (target.role === role) return;
    target.role = role;
    this.record(teamId, actorId, "member.role", `${this.displayName(target.id)} is now ${role.toLowerCase()}`);
  }

  private transferLeadership(teamId: number, actorId: number, body: any) {
    const actor = this.atLeastRole(teamId, actorId, "LEADER");
    const target = this.activeMember(teamId, Number(body?.membershipId));
    if (target.id === actor.id) return;
    actor.role = "OFFICER";
    target.role = "LEADER";
    this.record(teamId, actorId, "member.leader", `${this.displayName(target.id)} now leads the team`);
  }

  private removeMember(teamId: number, actorId: number, membershipId: number) {
    const actor = this.member(teamId, actorId);
    const target = this.activeMember(teamId, membershipId);
    const leaving = target.id === actor.id;
    if (leaving && actor.role === "LEADER") throw invalid("member.leader-cannot-leave", "Transfer leadership before leaving the team");
    if (!leaving) {
      const allowed = actor.role === "LEADER" || (actor.role === "OFFICER" && target.role === "MEMBER");
      if (!allowed) throw forbidden("You can't remove this member");
    }
    const name = this.displayName(target.id);
    if (this.state.missions.some((m) => m.leaderId === target.id && (m.status === "PLANNED" || m.status === "DEPARTED"))) {
      throw conflict("member.on-mission", `${name} leads a planned or departed mission; reassign or close it first`);
    }
    const w = this.balance(teamId, wallet(target.id), POKE);
    if (w > 0) this.post(teamId, actorId, "CASH_OUT", null, `${name} left the team`, this.transfer(wallet(target.id), WORLD, POKE, w));
    target.leftAt = this.iso();
    this.record(teamId, actorId, leaving ? "member.left" : "member.removed",
      leaving ? `${name} left the team` : `${this.displayName(actor.id)} removed ${name}`);
  }

  private updatePolicy(teamId: number, actorId: number, body: any) {
    this.atLeastRole(teamId, actorId, "LEADER");
    const cut = Number(body?.treasuryCutPct);
    const mode = body?.splitMode as SplitMode;
    if (!Number.isInteger(cut) || cut < 0 || cut > 100) throw badRequest("treasuryCutPct must be between 0 and 100");
    if (mode !== "EQUAL" && mode !== "BY_PARTY") throw badRequest("splitMode must not be null");
    const t = this.team(teamId);
    if (t.treasuryCutPct !== cut || t.splitMode !== mode) {
      t.treasuryCutPct = cut;
      t.splitMode = mode;
      this.record(teamId, actorId, "team.policy", `Reward policy: ${cut}% to treasury, ${mode === "EQUAL" ? "equal split" : "split by party slots"}`);
    }
    return this.teamDetail(teamId, actorId);
  }

  private eventsOf(teamId: number, userId: number, limit: number) {
    this.member(teamId, userId);
    return this.state.events
      .filter((e) => e.teamId === teamId)
      .sort((a, b) => b.id - a.id)
      .slice(0, Math.min(Math.max(limit, 1), 200))
      .map((e) => ({ id: e.id, type: e.type, summary: e.summary, actor: e.actorId == null ? null : this.user(e.actorId).displayName, createdAt: e.createdAt }));
  }

  // ---------------------------------------------------------------- invitations

  private statusOf(i: InvitationRow) {
    return i.status === "PENDING" && this.now().getTime() >= Date.parse(i.expiresAt) ? "EXPIRED" : i.status;
  }

  private openInvitations(userId: number) {
    return this.state.invitations.filter((i) => i.inviteeId === userId && this.statusOf(i) === "PENDING").sort((a, b) => b.id - a.id);
  }

  private invitationView(i: InvitationRow) {
    return {
      id: i.id, teamId: i.teamId, teamName: this.team(i.teamId).name, invitee: this.userView(i.inviteeId),
      invitedBy: this.userView(i.invitedById), role: i.role, status: this.statusOf(i), createdAt: i.createdAt, expiresAt: i.expiresAt,
    };
  }

  private requireRoom(teamId: number) {
    if (this.activeMembers(teamId).length >= catalog.limits.teamMembers) {
      throw conflict("team.full", `Teams are capped at ${catalog.limits.teamMembers} members`);
    }
  }

  private invite(teamId: number, actorId: number, body: any) {
    const actor = this.atLeastRole(teamId, actorId, "OFFICER");
    const role = body?.role as Role;
    const username = String(body?.username ?? "").trim();
    if (!username) throw badRequest("username must not be blank");
    if (!ROLE_ORDER.includes(role)) throw badRequest("role must not be null");
    if (role === "LEADER") throw invalid("role.invalid", "Invite as member or officer; leadership is transferred");
    if (role === "OFFICER" && actor.role !== "LEADER") throw forbidden("Only the leader can invite officers");
    const invitee = this.state.users.find((u) => u.username.toLowerCase() === username.toLowerCase());
    if (!invitee) throw notFound("invite.user-not-found", `No user called ${username}`);
    if (this.state.memberships.some((m) => m.teamId === teamId && m.userId === invitee.id && m.leftAt == null)) {
      throw conflict("invite.already-member", `${invitee.displayName} is already on the team`);
    }
    if (this.state.invitations.some((i) => i.teamId === teamId && i.inviteeId === invitee.id && this.statusOf(i) === "PENDING")) {
      throw conflict("invite.pending-exists", `${invitee.displayName} already has a pending invitation`);
    }
    this.requireRoom(teamId);
    const inv: InvitationRow = {
      id: this.nextId("invitations"), teamId, inviteeId: invitee.id, invitedById: actorId, role, status: "PENDING",
      createdAt: this.iso(), expiresAt: new Date(this.now().getTime() + catalog.limits.invitationDays * DAY).toISOString(),
    };
    this.state.invitations.push(inv);
    this.record(teamId, actorId, "invite.sent", `${this.displayName(actor.id)} invited ${invitee.displayName} as ${role.toLowerCase()}`);
    return this.invitationView(inv);
  }

  private teamInvitations(teamId: number, actorId: number) {
    this.atLeastRole(teamId, actorId, "OFFICER");
    return this.state.invitations.filter((i) => i.teamId === teamId).sort((a, b) => b.id - a.id).slice(0, 50).map((i) => this.invitationView(i));
  }

  private requirePending(i: InvitationRow) {
    const status = this.statusOf(i);
    if (status === "EXPIRED") throw conflict("invite.expired", "This invitation expired");
    if (status !== "PENDING") throw conflict("invite.not-pending", `This invitation was already ${status.toLowerCase()}`);
  }

  private revoke(teamId: number, actorId: number, invitationId: number) {
    this.atLeastRole(teamId, actorId, "OFFICER");
    const inv = this.state.invitations.find((i) => i.id === invitationId && i.teamId === teamId);
    if (!inv) throw notFound("invite.not-found", "Invitation not found");
    this.requirePending(inv);
    inv.status = "REVOKED";
    this.record(teamId, actorId, "invite.revoked", `Invitation for ${this.user(inv.inviteeId).displayName} revoked`);
  }

  private myInvitations(userId: number) {
    return this.openInvitations(userId).map((i) => this.invitationView(i));
  }

  private ownInvitation(userId: number, invitationId: number) {
    const inv = this.state.invitations.find((i) => i.id === invitationId && i.inviteeId === userId);
    if (!inv) throw notFound("invite.not-found", "Invitation not found");
    return inv;
  }

  private accept(userId: number, invitationId: number) {
    const inv = this.ownInvitation(userId, invitationId);
    this.requirePending(inv);
    if (this.state.memberships.some((m) => m.teamId === inv.teamId && m.userId === userId && m.leftAt == null)) {
      inv.status = "ACCEPTED";
      return inv.teamId;
    }
    this.requireRoom(inv.teamId);
    this.state.memberships.push({ id: this.nextId("memberships"), teamId: inv.teamId, userId, role: inv.role, joinedAt: this.iso(), leftAt: null });
    inv.status = "ACCEPTED";
    this.record(inv.teamId, userId, "member.joined", `${this.user(userId).displayName} joined the team`);
    return inv.teamId;
  }

  private decline(userId: number, invitationId: number) {
    const inv = this.ownInvitation(userId, invitationId);
    this.requirePending(inv);
    inv.status = "DECLINED";
    this.record(inv.teamId, userId, "invite.declined", `${this.user(userId).displayName} declined the invitation`);
  }

  // ---------------------------------------------------------------- recruits

  private activeRecruits(teamId: number) {
    return this.state.recruits.filter((r) => r.teamId === teamId && r.status !== "RELEASED").sort((a, b) => a.id - b.id);
  }

  private recruitView(r: RecruitRow) {
    const s = this.species(r.speciesId);
    return {
      id: r.id, speciesId: s.id, speciesName: s.name, types: s.types, nickname: r.nickname, level: r.level, exp: r.exp,
      levelExp: expForLevel(r.level), nextLevelExp: r.level >= catalog.limits.maxLevel ? null : expForLevel(r.level + 1),
      status: r.status,
      recruitedBy: r.recruitedById == null ? null : { membershipId: r.recruitedById, displayName: this.displayName(r.recruitedById) },
      recruitedAt: r.recruitedAt, missionId: r.missionId,
    };
  }

  private recruitList(teamId: number, userId: number) {
    this.member(teamId, userId);
    return this.activeRecruits(teamId).map((r) => this.recruitView(r));
  }

  private cleanNickname(raw: unknown) {
    if (raw == null) return null;
    const s = String(raw).trim();
    if (s.length > 20) throw badRequest("nickname size must be between 0 and 20");
    return s || null;
  }

  private recruit(teamId: number, userId: number, body: any) {
    const me = this.member(teamId, userId);
    const level = Number(body?.level);
    if (!Number.isInteger(level) || level < 1 || level > 100) throw badRequest("level must be between 1 and 100");
    const nickname = this.cleanNickname(body?.nickname);
    const s = this.species(Number(body?.speciesId));
    const rank = rankFor(this.team(teamId).rankPoints);
    if (this.activeRecruits(teamId).length >= rank.rosterCap) {
      throw conflict("recruit.roster-full", `${rank.name} Rank teams can keep ${rank.rosterCap} recruits. Release one or rank up.`);
    }
    const r: RecruitRow = {
      id: this.nextId("recruits"), teamId, speciesId: s.id, nickname, level, exp: expForLevel(level), status: "AVAILABLE",
      recruitedById: me.id, recruitedAt: this.iso(), missionId: null,
    };
    this.state.recruits.push(r);
    this.record(teamId, userId, "recruit.joined", `${this.displayName(me.id)} recruited ${this.recruitLabel(r)} (Lv. ${level})`);
    return this.recruitView(r);
  }

  private editableRecruit(teamId: number, userId: number, recruitId: number) {
    const me = this.member(teamId, userId);
    const r = this.state.recruits.find((x) => x.id === recruitId && x.teamId === teamId && x.status !== "RELEASED");
    if (!r) throw notFound("recruit.not-found", "Recruit not found");
    if (r.recruitedById !== me.id && !atLeast(me.role, "OFFICER")) {
      throw forbidden("Only the recruiter or an officer can change this recruit");
    }
    return r;
  }

  private rename(teamId: number, userId: number, recruitId: number, body: any) {
    const nickname = this.cleanNickname(body?.nickname);
    const r = this.editableRecruit(teamId, userId, recruitId);
    r.nickname = nickname;
    return this.recruitView(r);
  }

  private release(teamId: number, userId: number, recruitId: number) {
    const r = this.editableRecruit(teamId, userId, recruitId);
    if (r.status === "ON_MISSION" || this.state.missions.some((m) => m.status === "PLANNED" && m.party.includes(recruitId))) {
      throw conflict("recruit.busy", "This recruit is assigned to a mission");
    }
    r.status = "RELEASED";
    this.record(teamId, userId, "recruit.released", `${this.recruitLabel(r)} was released`);
  }

  // ---------------------------------------------------------------- missions

  private findMission(teamId: number, missionId: number) {
    const m = this.state.missions.find((x) => x.id === missionId && x.teamId === teamId);
    if (!m) throw notFound("mission.not-found", "Mission not found");
    return m;
  }

  private missionView(m: MissionRow) {
    const d = dungeon(m.dungeonCode)!;
    return {
      id: m.id, title: m.title, kind: m.kind, dungeonCode: d.code, dungeonName: d.name, floor: m.floor,
      floorLabel: floorLabel(d, m.floor), difficulty: m.difficulty, clientSpeciesId: m.clientSpeciesId,
      clientName: m.clientSpeciesId == null ? null : this.species(m.clientSpeciesId).name, rewardPoke: m.rewardPoke,
      rewardItems: m.rewardItems.map((s) => ({ ...s })), rankPoints: m.rankPoints, status: m.status,
      leader: m.leaderId == null ? null : { membershipId: m.leaderId, displayName: this.displayName(m.leaderId), speciesId: this.user(this.membership(m.leaderId)!.userId).speciesId },
      party: m.party.map((id) => {
        const r = this.state.recruits.find((x) => x.id === id)!;
        return { recruitId: r.id, speciesId: r.speciesId, name: this.recruitLabel(r), level: r.level, recruitedBy: r.recruitedById };
      }),
      bag: m.bag.map((s) => ({ ...s })), postedBy: this.displayName(m.postedById), postedAt: m.postedAt,
      departedAt: m.departedAt, closedAt: m.closedAt, outcomeNote: m.outcomeNote,
    };
  }

  private missionList(teamId: number, userId: number) {
    this.member(teamId, userId);
    return this.state.missions.filter((m) => m.teamId === teamId).sort((a, b) => b.id - a.id).map((m) => this.missionView(m));
  }

  private merge(stacks: unknown): ItemStack[] {
    if (stacks == null) return [];
    if (!Array.isArray(stacks)) throw badRequest("Request body is missing or malformed");
    const merged = new Map<string, number>();
    for (const s of stacks) {
      const code = String(s?.itemCode ?? "");
      const qty = Number(s?.quantity);
      if (!code) throw badRequest("itemCode must not be blank");
      if (!Number.isInteger(qty) || qty < 1) throw badRequest("quantity must be greater than or equal to 1");
      this.item(code);
      merged.set(code, (merged.get(code) ?? 0) + qty);
    }
    return [...merged].map(([itemCode, quantity]) => ({ itemCode, quantity }));
  }

  private postMission(teamId: number, userId: number, body: any) {
    const me = this.atLeastRole(teamId, userId, "OFFICER");
    const title = String(body?.title ?? "").trim();
    const reward = Number(body?.rewardPoke ?? 0);
    if (!title || title.length > 80) throw badRequest("title size must be between 1 and 80");
    if (!Number.isInteger(reward) || reward < 0 || reward > 100_000) throw badRequest("rewardPoke must be between 0 and 100000");
    if (!catalog.missionKinds.includes(body?.kind)) throw invalid("mission.kind-unknown", `Unknown mission kind ${body?.kind}`);
    const d = dungeon(body?.dungeonCode);
    if (!d) throw invalid("mission.dungeon-unknown", `Unknown dungeon ${body?.dungeonCode}`);
    const floor = Number(body?.floor);
    if (!(floor >= 1 && floor <= d.floors)) throw invalid("mission.floor-out-of-range", `${d.name} has floors 1 to ${d.floors}`);
    const diff = difficulty(body?.difficulty);
    if (!diff) throw invalid("mission.difficulty-unknown", `Unknown difficulty ${body?.difficulty}`);
    const rank = rankFor(this.team(teamId).rankPoints);
    if (difficultyOrder(diff.code) > difficultyOrder(rank.maxDifficulty)) {
      throw invalid("mission.difficulty-locked", `${rank.name} Rank teams can take jobs up to ${difficulty(rank.maxDifficulty)!.label}`);
    }
    const client = body?.clientSpeciesId == null ? null : this.species(Number(body.clientSpeciesId)).id;
    const m: MissionRow = {
      id: this.nextId("missions"), teamId, title, kind: body.kind, dungeonCode: d.code, floor, difficulty: diff.code,
      clientSpeciesId: client, rewardPoke: reward, rankPoints: diff.rankPoints, rewardItems: this.merge(body?.rewardItems),
      status: "POSTED", leaderId: null, party: [], bag: [], postedById: me.id, postedAt: this.iso(),
      departedAt: null, closedAt: null, outcomeNote: null,
    };
    this.state.missions.push(m);
    this.record(teamId, userId, "mission.posted", `Posted ${diff.label} job: ${title}`);
    return this.missionView(m);
  }

  private requireStatus(m: MissionRow, ...allowed: MissionStatus[]) {
    if (!allowed.includes(m.status)) throw conflict("mission.bad-status", `This mission is ${m.status.toLowerCase()}`);
  }

  private recruitOf(teamId: number, recruitId: number) {
    const r = this.state.recruits.find((x) => x.id === recruitId && x.teamId === teamId && x.status !== "RELEASED");
    if (!r) throw invalid("recruit.not-found", `Recruit #${recruitId} is not on this team`);
    return r;
  }

  private plan(teamId: number, userId: number, missionId: number, body: any) {
    this.atLeastRole(teamId, userId, "OFFICER");
    const m = this.findMission(teamId, missionId);
    this.requireStatus(m, "POSTED", "PLANNED");
    const rank = rankFor(this.team(teamId).rankPoints);
    const leader = this.membership(Number(body?.leaderMembershipId));
    if (!leader || leader.teamId !== teamId || leader.leftAt != null) {
      throw invalid("party.leader-invalid", "The mission leader must be a current member");
    }
    const ids: number[] = Array.isArray(body?.recruitIds) ? body.recruitIds.map(Number) : [];
    if (ids.length === 0 || ids.length > catalog.limits.partySize || new Set(ids).size !== ids.length) {
      throw invalid("party.size", `A party has 1 to ${catalog.limits.partySize} different recruits`);
    }
    for (const id of ids) {
      const r = this.recruitOf(teamId, id);
      if (r.status !== "AVAILABLE") throw conflict("party.recruit-busy", `${this.recruitLabel(r)} is out on another mission`);
      const other = this.state.missions.filter((x) => x.status === "PLANNED" && x.id !== missionId && x.party.includes(id)).sort((a, b) => a.id - b.id);
      if (other.length) throw conflict("party.recruit-busy", `${this.recruitLabel(r)} is already planned for "${other[0].title}"`);
    }
    const bag = this.merge(body?.bag);
    const carried = bag.reduce((a, s) => a + s.quantity, 0);
    if (carried > rank.bagCap) throw invalid("bag.over-capacity", `${rank.name} Rank bags hold ${rank.bagCap} items`);
    const storage = this.holdings(teamId, STORAGE);
    for (const s of bag) {
      const have = storage.get(s.itemCode) ?? 0;
      if (have < s.quantity) throw invalid("ledger.insufficient-stock", `Storage has ${have} × ${this.item(s.itemCode).name}; ${s.quantity} needed`);
    }
    const replan = m.status === "PLANNED";
    m.leaderId = leader.id;
    m.party = ids;
    m.bag = bag;
    m.status = "PLANNED";
    this.record(teamId, userId, "mission.planned", `${replan ? "Re-planned" : "Planned"} ${m.title}: led by ${this.displayName(leader.id)}, party of ${ids.length}`);
    return this.missionView(m);
  }

  private requireLeaderOrOfficer(teamId: number, userId: number, m: MissionRow) {
    const me = this.member(teamId, userId);
    if (m.leaderId !== me.id && !atLeast(me.role, "OFFICER")) throw forbidden("Only the mission leader or an officer can do this");
  }

  private depart(teamId: number, userId: number, missionId: number) {
    const m = this.findMission(teamId, missionId);
    this.requireLeaderOrOfficer(teamId, userId, m);
    this.requireStatus(m, "PLANNED");
    for (const id of m.party) {
      const r = this.recruitOf(teamId, id);
      if (r.status !== "AVAILABLE") throw conflict("party.recruit-busy", `${this.recruitLabel(r)} is not available`);
      r.status = "ON_MISSION";
      r.missionId = m.id;
    }
    this.post(teamId, userId, "MISSION_PACK", m.id, `Packed for ${m.title}`,
      m.bag.flatMap((s) => this.transfer(STORAGE, bagAccount(m.id), s.itemCode, s.quantity)));
    m.status = "DEPARTED";
    m.departedAt = this.iso();
    const d = dungeon(m.dungeonCode)!;
    this.record(teamId, userId, "mission.departed", `Party left for ${d.name} ${floorLabel(d, m.floor)}`);
    return this.missionView(m);
  }

  private split(teamId: number, m: MissionRow) {
    const t = this.team(teamId);
    const active = new Set(this.activeMembers(teamId).map((x) => x.id));
    const recruiters = m.party.map((id) => this.state.recruits.find((r) => r.id === id)?.recruitedById ?? null);
    return split(m.rewardPoke, t.treasuryCutPct, t.splitMode, m.leaderId, recruiters, active);
  }

  private preview(teamId: number, userId: number, missionId: number) {
    this.member(teamId, userId);
    const m = this.findMission(teamId, missionId);
    const t = this.team(teamId);
    const s = this.split(teamId, m);
    const diff = difficulty(m.difficulty)!;
    const levelUps = m.party.flatMap((id) => {
      const r = this.state.recruits.find((x) => x.id === id)!;
      const to = Math.min(catalog.limits.maxLevel, levelForExp(r.exp + diff.exp));
      return to > r.level ? [{ recruitId: r.id, name: this.recruitLabel(r), from: r.level, to }] : [];
    });
    return {
      rewardPoke: m.rewardPoke, treasuryCutPct: t.treasuryCutPct, splitMode: t.splitMode, treasury: s.treasury,
      shares: s.shares.map((x) => ({ membershipId: x.membershipId, displayName: this.displayName(x.membershipId), speciesId: this.user(this.membership(x.membershipId)!.userId).speciesId, weight: x.weight, amount: x.amount })),
      rankPoints: m.rankPoints, rankBefore: rankFor(t.rankPoints).code, rankAfter: rankFor(t.rankPoints + m.rankPoints).code,
      expPerRecruit: diff.exp, levelUps,
    };
  }

  private report(teamId: number, userId: number, missionId: number, body: any) {
    const m = this.findMission(teamId, missionId);
    this.requireLeaderOrOfficer(teamId, userId, m);
    const outcome = body?.outcome;
    if (outcome !== "COMPLETED" && outcome !== "FAILED") throw badRequest("outcome must not be null");
    const rawNote = body?.note == null ? null : String(body.note);
    if (rawNote != null && rawNote.length > 280) throw badRequest("note size must be between 0 and 280");
    const note = rawNote == null || !rawNote.trim() ? null : rawNote.trim();
    this.requireStatus(m, "DEPARTED");
    const bagKey = bagAccount(m.id);
    const bag = this.holdings(teamId, bagKey);

    if (outcome === "FAILED") {
      this.post(teamId, userId, "ITEM_LOST", m.id, "Lost when the party fainted",
        [...bag].flatMap(([code, qty]) => this.transfer(bagKey, WORLD, code, qty)));
      this.returnParty(teamId, m);
      m.status = "FAILED";
      m.outcomeNote = note;
      m.closedAt = this.iso();
      this.record(teamId, userId, "mission.failed", `Failed: ${m.title}${bag.size ? ". The bag was lost." : ""}`);
      return this.missionView(m);
    }

    const used = new Map<string, number>();
    for (const s of this.merge(body?.itemsUsed)) {
      const packed = bag.get(s.itemCode) ?? 0;
      if (s.quantity > packed) throw invalid("report.used-exceeds-bag", `Only ${packed} × ${this.item(s.itemCode).name} were packed`);
      used.set(s.itemCode, s.quantity);
    }
    const usedLegs: { account: string; asset: string; delta: number }[] = [];
    const returnLegs: { account: string; asset: string; delta: number }[] = [];
    for (const [code, qty] of bag) {
      const u = used.get(code) ?? 0;
      usedLegs.push(...this.transfer(bagKey, WORLD, code, u));
      returnLegs.push(...this.transfer(bagKey, STORAGE, code, qty - u));
    }
    this.post(teamId, userId, "ITEM_USED", m.id, `Used during ${m.title}`, usedLegs);
    this.post(teamId, userId, "MISSION_RETURN", m.id, `Returned from ${m.title}`, returnLegs);

    const t = this.team(teamId);
    const s = this.split(teamId, m);
    const reward = [
      ...this.transfer(WORLD, TREASURY, POKE, s.treasury),
      ...s.shares.flatMap((x) => this.transfer(WORLD, wallet(x.membershipId), POKE, x.amount)),
      ...m.rewardItems.flatMap((x) => this.transfer(WORLD, STORAGE, x.itemCode, x.quantity)),
    ];
    this.post(teamId, userId, "MISSION_REWARD", m.id, `Reward for ${m.title}`, reward);

    const before = rankFor(t.rankPoints);
    t.rankPoints += m.rankPoints;
    const after = rankFor(t.rankPoints);

    const exp = difficulty(m.difficulty)!.exp;
    const grew: string[] = [];
    for (const id of m.party) {
      const r = this.recruitOf(teamId, id);
      const from = r.level;
      r.exp += exp;
      r.level = Math.min(catalog.limits.maxLevel, Math.max(r.level, levelForExp(r.exp)));
      if (r.level > from) grew.push(`${this.recruitLabel(r)} Lv. ${from}→${r.level}`);
    }
    this.returnParty(teamId, m);
    m.status = "COMPLETED";
    m.outcomeNote = note;
    m.closedAt = this.iso();
    this.record(teamId, userId, "mission.completed", `Completed: ${m.title}. +${m.rewardPoke} Poké, +${m.rankPoints} rank points`);
    if (grew.length) this.record(teamId, null, "recruit.level", grew.join(", "));
    if (after !== before) this.record(teamId, null, "team.rank", `Team reached ${after.name} Rank`);
    return this.missionView(m);
  }

  private returnParty(teamId: number, m: MissionRow) {
    for (const id of m.party) {
      const r = this.recruitOf(teamId, id);
      if (r.status === "ON_MISSION") {
        r.status = "AVAILABLE";
        r.missionId = null;
      }
    }
  }

  private cancel(teamId: number, userId: number, missionId: number) {
    this.atLeastRole(teamId, userId, "OFFICER");
    const m = this.findMission(teamId, missionId);
    this.requireStatus(m, "POSTED", "PLANNED");
    m.status = "CANCELLED";
    m.closedAt = this.iso();
    this.record(teamId, userId, "mission.cancelled", `Took down: ${m.title}`);
    return this.missionView(m);
  }

  // ---------------------------------------------------------------- storage & treasury

  private stacks(holdings: Map<string, number>) {
    return catalog.items.filter((i) => (holdings.get(i.code) ?? 0) > 0).map((i) => ({ itemCode: i.code, quantity: holdings.get(i.code)! }));
  }

  private storageView(teamId: number, userId: number) {
    const me = this.member(teamId, userId);
    const items = this.stacks(this.holdings(teamId, STORAGE));
    return {
      items,
      storageValue: items.reduce((a, s) => a + this.item(s.itemCode).refValue * s.quantity, 0),
      treasury: this.balance(teamId, TREASURY, POKE),
      myWallet: this.balance(teamId, wallet(me.id), POKE),
      inTheField: this.state.missions
        .filter((m) => m.teamId === teamId && m.status === "DEPARTED")
        .sort((a, b) => b.id - a.id)
        .map((m) => ({ missionId: m.id, title: m.title, items: this.stacks(this.holdings(teamId, bagAccount(m.id))) })),
    };
  }

  private itemMove(body: any) {
    const code = String(body?.itemCode ?? "");
    const qty = Number(body?.quantity);
    if (!code) throw badRequest("itemCode must not be blank");
    if (!Number.isInteger(qty) || qty < 1 || qty > 999) throw badRequest("quantity must be between 1 and 999");
    return { item: this.item(code), qty, memo: this.memo(body?.memo) };
  }

  private pokeMove(body: any) {
    const amount = Number(body?.amount);
    if (!Number.isInteger(amount) || amount < 1 || amount > 1_000_000) throw badRequest("amount must be between 1 and 1000000");
    return { amount, memo: this.memo(body?.memo) };
  }

  private memo(raw: unknown) {
    if (raw == null) return null;
    const s = String(raw);
    if (s.length > 140) throw badRequest("memo size must be between 0 and 140");
    return s.trim() || null;
  }

  private depositItem(teamId: number, userId: number, body: any) {
    const me = this.member(teamId, userId);
    const { item: it, qty, memo } = this.itemMove(body);
    this.post(teamId, userId, "DEPOSIT", null, memo, this.transfer(WORLD, STORAGE, it.code, qty));
    this.record(teamId, userId, "storage.deposit", `${this.displayName(me.id)} stored ${qty} × ${it.name}`);
  }

  private discardItem(teamId: number, userId: number, body: any) {
    const { item: it, qty, memo } = this.itemMove(body);
    const me = this.atLeastRole(teamId, userId, "OFFICER");
    this.post(teamId, userId, "DISCARD", null, memo, this.transfer(STORAGE, WORLD, it.code, qty));
    this.record(teamId, userId, "storage.discard", `${this.displayName(me.id)} discarded ${qty} × ${it.name}`);
  }

  private depositPoke(teamId: number, userId: number, body: any) {
    const { amount, memo } = this.pokeMove(body);
    const me = this.atLeastRole(teamId, userId, "OFFICER");
    this.post(teamId, userId, "DEPOSIT", null, memo, this.transfer(WORLD, TREASURY, POKE, amount));
    this.record(teamId, userId, "treasury.deposit", `${this.displayName(me.id)} deposited ${amount} Poké`);
  }

  private contribute(teamId: number, userId: number, body: any) {
    const { amount, memo } = this.pokeMove(body);
    const me = this.member(teamId, userId);
    this.post(teamId, userId, "CONTRIBUTION", null, memo, this.transfer(wallet(me.id), TREASURY, POKE, amount));
    this.record(teamId, userId, "treasury.contribution", `${this.displayName(me.id)} put ${amount} Poké in the treasury`);
  }

  private payout(teamId: number, userId: number, body: any) {
    const { amount, memo } = this.pokeMove(body);
    if (body?.membershipId == null) throw badRequest("membershipId must not be null");
    this.atLeastRole(teamId, userId, "LEADER");
    const target = this.activeMember(teamId, Number(body.membershipId));
    this.post(teamId, userId, "PAYOUT", null, memo, this.transfer(TREASURY, wallet(target.id), POKE, amount));
    this.record(teamId, userId, "treasury.payout", `Paid ${amount} Poké to ${this.displayName(target.id)}`);
  }

  private cashOut(teamId: number, userId: number, body: any) {
    const { amount } = this.pokeMove(body);
    const me = this.member(teamId, userId);
    this.post(teamId, userId, "CASH_OUT", null, null, this.transfer(wallet(me.id), WORLD, POKE, amount));
  }

  private ledgerPage(teamId: number, userId: number, q: URLSearchParams) {
    this.member(teamId, userId);
    const account = q.get("account");
    const asset = q.get("asset");
    const reason = q.get("reason");
    const missionId = q.get("missionId");
    const page = Math.max(Number(q.get("page") ?? 0), 0);
    const size = Math.min(Math.max(Number(q.get("size") ?? 50), 1), 200);
    const rows = this.state.entries
      .filter((e) => e.teamId === teamId)
      .filter((e) => !account || (account.endsWith(":") ? e.accountKey.startsWith(account) : e.accountKey === account))
      .filter((e) => !asset || e.asset === asset)
      .filter((e) => !reason || e.reason === reason)
      .filter((e) => !missionId || e.missionId === Number(missionId))
      .sort((a, b) => b.id - a.id);
    return {
      items: rows.slice(page * size, page * size + size).map((e) => ({
        id: e.id, txn: e.txn, accountKey: e.accountKey, asset: e.asset, delta: e.delta, balanceAfter: e.balanceAfter,
        reason: e.reason, missionId: e.missionId, memo: e.memo, actor: e.actorId == null ? null : this.user(e.actorId).displayName,
        createdAt: e.createdAt,
      })),
      page, size, total: rows.length,
    };
  }
}

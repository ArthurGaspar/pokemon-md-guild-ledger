import { Engine, type State } from "../engine/engine";
import { seed } from "../engine/seed";
import type {
  AuthResponse, Invitation, ItemStack, LedgerEntry, Me, MemberView, Mission, Page, PayoutPreview, Reconciliation,
  Recruit, Role, SplitMode, StorageView, TeamDetail, TeamEvent, TeamSummary, UserView,
} from "./types";

export class ApiError extends Error {
  constructor(readonly status: number, readonly code: string, message: string) {
    super(message);
  }
}

interface Raw { status: number; body: any }

export interface Transport {
  readonly mode: "browser" | "api";
  send(method: string, path: string, body: unknown, token: string | null): Promise<Raw>;
}

export class HttpTransport implements Transport {
  readonly mode = "api" as const;
  constructor(private base: string) {}

  async send(method: string, path: string, body: unknown, token: string | null): Promise<Raw> {
    const headers: Record<string, string> = {};
    if (body !== undefined) headers["Content-Type"] = "application/json";
    if (token) headers.Authorization = `Bearer ${token}`;
    let res: globalThis.Response;
    try {
      res = await fetch(this.base + path, { method, headers, body: body === undefined ? undefined : JSON.stringify(body) });
    } catch {
      return { status: 0, body: { code: "network", detail: `Can't reach the API at ${this.base}` } };
    }
    const text = await res.text();
    return { status: res.status, body: text ? JSON.parse(text) : null };
  }
}

const STORE_KEY = "guild-ledger.browser-state.v2";

/** Runs the engine in this tab and keeps its state in localStorage. */
export class BrowserTransport implements Transport {
  readonly mode = "browser" as const;
  readonly engine: Engine;

  constructor() {
    let state: State | null = null;
    try {
      const raw = localStorage.getItem(STORE_KEY);
      if (raw) state = JSON.parse(raw) as State;
    } catch {
      state = null;
    }
    this.engine = new Engine(state ?? undefined);
    if (!state) {
      seed(this.engine);
      this.persist();
    }
  }

  async send(method: string, path: string, body: unknown, token: string | null): Promise<Raw> {
    const res = this.engine.handle(method, path, body, token);
    if (method !== "GET") this.persist();
    return res;
  }

  signInAs(username: string): AuthResponse {
    const res = this.engine.impersonate(username);
    this.persist();
    return res;
  }

  reset() {
    try {
      localStorage.removeItem(STORE_KEY);
    } catch {
      /* storage unavailable: the reload reseeds in memory anyway */
    }
  }

  private persist() {
    try {
      localStorage.setItem(STORE_KEY, JSON.stringify(this.engine.state));
    } catch {
      /* private mode or quota: keep working in memory */
    }
  }
}

export class Api {
  constructor(readonly transport: Transport, private token: () => string | null, private onUnauthorized: () => void) {}

  private async call<T>(method: string, path: string, body?: unknown): Promise<T> {
    const res = await this.transport.send(method, path, body, this.token());
    if (res.status === 401 && this.token()) this.onUnauthorized();
    if (res.status === 0 || res.status >= 400) {
      throw new ApiError(res.status, res.body?.code ?? "error", res.body?.detail ?? `Request failed (${res.status})`);
    }
    return res.body as T;
  }

  register = (username: string, displayName: string, password: string, speciesId: number) =>
    this.call<AuthResponse>("POST", "/api/auth/register", { username, displayName, password, speciesId });
  updateProfile = (displayName: string, speciesId: number) =>
    this.call<UserView>("PATCH", "/api/me", { displayName, speciesId });
  login = (username: string, password: string) => this.call<AuthResponse>("POST", "/api/auth/login", { username, password });
  me = () => this.call<Me>("GET", "/api/me");

  teams = () => this.call<TeamSummary[]>("GET", "/api/teams");
  createTeam = (name: string) => this.call<TeamDetail>("POST", "/api/teams", { name });
  team = (id: number) => this.call<TeamDetail>("GET", `/api/teams/${id}`);
  setPolicy = (id: number, treasuryCutPct: number, splitMode: SplitMode) =>
    this.call<TeamDetail>("PUT", `/api/teams/${id}/policy`, { treasuryCutPct, splitMode });
  members = (id: number) => this.call<MemberView[]>("GET", `/api/teams/${id}/members`);
  changeRole = (id: number, membershipId: number, role: Role) =>
    this.call<void>("PATCH", `/api/teams/${id}/members/${membershipId}`, { role });
  removeMember = (id: number, membershipId: number) => this.call<void>("DELETE", `/api/teams/${id}/members/${membershipId}`);
  transferLeadership = (id: number, membershipId: number) =>
    this.call<void>("POST", `/api/teams/${id}/leadership`, { membershipId });
  events = (id: number, limit = 40) => this.call<TeamEvent[]>("GET", `/api/teams/${id}/events?limit=${limit}`);

  invite = (id: number, username: string, role: Role) =>
    this.call<Invitation>("POST", `/api/teams/${id}/invitations`, { username, role });
  teamInvitations = (id: number) => this.call<Invitation[]>("GET", `/api/teams/${id}/invitations`);
  revokeInvitation = (id: number, invitationId: number) =>
    this.call<void>("DELETE", `/api/teams/${id}/invitations/${invitationId}`);
  myInvitations = () => this.call<Invitation[]>("GET", "/api/invitations");
  accept = (invitationId: number) => this.call<{ teamId: number }>("POST", `/api/invitations/${invitationId}/accept`);
  decline = (invitationId: number) => this.call<void>("POST", `/api/invitations/${invitationId}/decline`);

  recruits = (id: number) => this.call<Recruit[]>("GET", `/api/teams/${id}/recruits`);
  recruit = (id: number, speciesId: number, level: number, nickname: string | null) =>
    this.call<Recruit>("POST", `/api/teams/${id}/recruits`, { speciesId, level, nickname });
  rename = (id: number, recruitId: number, nickname: string | null) =>
    this.call<Recruit>("PATCH", `/api/teams/${id}/recruits/${recruitId}`, { nickname });
  release = (id: number, recruitId: number) => this.call<void>("DELETE", `/api/teams/${id}/recruits/${recruitId}`);

  missions = (id: number) => this.call<Mission[]>("GET", `/api/teams/${id}/missions`);
  postMission = (id: number, body: {
    title: string; kind: string; dungeonCode: string; floor: number; difficulty: string;
    clientSpeciesId: number | null; rewardPoke: number; rewardItems: ItemStack[];
  }) => this.call<Mission>("POST", `/api/teams/${id}/missions`, body);
  plan = (id: number, missionId: number, leaderMembershipId: number, recruitIds: number[], bag: ItemStack[]) =>
    this.call<Mission>("PUT", `/api/teams/${id}/missions/${missionId}/plan`, { leaderMembershipId, recruitIds, bag });
  depart = (id: number, missionId: number) => this.call<Mission>("POST", `/api/teams/${id}/missions/${missionId}/depart`);
  preview = (id: number, missionId: number) =>
    this.call<PayoutPreview>("GET", `/api/teams/${id}/missions/${missionId}/payout-preview`);
  report = (id: number, missionId: number, outcome: "COMPLETED" | "FAILED", itemsUsed: ItemStack[], note: string | null) =>
    this.call<Mission>("POST", `/api/teams/${id}/missions/${missionId}/report`, { outcome, itemsUsed, note });
  cancel = (id: number, missionId: number) => this.call<Mission>("POST", `/api/teams/${id}/missions/${missionId}/cancel`);

  storage = (id: number) => this.call<StorageView>("GET", `/api/teams/${id}/storage`);
  depositItem = (id: number, itemCode: string, quantity: number, memo: string | null) =>
    this.call<void>("POST", `/api/teams/${id}/storage/deposit`, { itemCode, quantity, memo });
  discardItem = (id: number, itemCode: string, quantity: number, memo: string | null) =>
    this.call<void>("POST", `/api/teams/${id}/storage/discard`, { itemCode, quantity, memo });
  depositPoke = (id: number, amount: number, memo: string | null) =>
    this.call<void>("POST", `/api/teams/${id}/treasury/deposit`, { amount, memo });
  contribute = (id: number, amount: number, memo: string | null) =>
    this.call<void>("POST", `/api/teams/${id}/treasury/contribute`, { amount, memo });
  payout = (id: number, membershipId: number, amount: number, memo: string | null) =>
    this.call<void>("POST", `/api/teams/${id}/treasury/payout`, { membershipId, amount, memo });
  cashOut = (id: number, amount: number) => this.call<void>("POST", `/api/teams/${id}/wallet/cash-out`, { amount });
  ledger = (id: number, q: { account?: string; asset?: string; reason?: string; missionId?: number; page?: number; size?: number }) => {
    const params = new URLSearchParams();
    for (const [k, v] of Object.entries(q)) if (v !== undefined && v !== "") params.set(k, String(v));
    return this.call<Page<LedgerEntry>>("GET", `/api/teams/${id}/ledger?${params}`);
  };
  reconcile = (id: number) => this.call<Reconciliation>("GET", `/api/teams/${id}/ledger/reconcile`);
}

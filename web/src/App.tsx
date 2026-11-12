import { useCallback, useEffect, useMemo, useState, type FormEvent } from "react";
import { speciesById } from "./api/catalog";
import { Api, BrowserTransport, HttpTransport, type Transport } from "./api/client";
import type { Invitation, Me, TeamDetail, UserView } from "./api/types";
import { SEED_ACCOUNTS } from "./engine/seed";
import { Ctx, ErrorLine, Portrait, RankEmblem, type AppCtx } from "./ui";
import { StarterPicker } from "./views/common";
import { Base } from "./views/Base";
import { Board } from "./views/Board";
import { Ledger } from "./views/Ledger";
import { Login } from "./views/Login";
import { Members } from "./views/Members";
import { NoTeam } from "./views/NoTeam";
import { Policy } from "./views/Policy";
import { Roster } from "./views/Roster";
import { Storage } from "./views/Storage";

const apiUrl = import.meta.env.VITE_API_URL as string | undefined;
const transport: Transport = apiUrl ? new HttpTransport(apiUrl.replace(/\/$/, "")) : new BrowserTransport();
const browser = transport instanceof BrowserTransport ? transport : null;
const TOKEN_KEY = `guild-ledger.token.${transport.mode}`;

const VIEWS = [
  { id: "base", label: "Base" },
  { id: "board", label: "Job Board" },
  { id: "roster", label: "Roster" },
  { id: "storage", label: "Storage" },
  { id: "ledger", label: "Ledger" },
  { id: "members", label: "Members" },
  { id: "policy", label: "Reward policy" },
] as const;

function readStore(key: string) {
  try { return localStorage.getItem(key); } catch { return null; }
}
function writeStore(key: string, value: string | null) {
  try { if (value == null) localStorage.removeItem(key); else localStorage.setItem(key, value); } catch { /* ignore */ }
}

interface Route { teamId: number | null; view: string; query: URLSearchParams }

function parseHash(): Route {
  const [path, qs] = location.hash.replace(/^#/, "").split("?");
  const m = /^\/t\/(\d+)(?:\/(\w+))?/.exec(path ?? "");
  return { teamId: m ? Number(m[1]) : null, view: m?.[2] ?? "base", query: new URLSearchParams(qs ?? "") };
}

export function App() {
  const [token, setToken] = useState<string | null>(() => readStore(TOKEN_KEY));
  const [me, setMe] = useState<Me | null>(null);
  const [team, setTeam] = useState<TeamDetail | null>(null);
  const [revision, setRevision] = useState(0);
  const [route, setRoute] = useState(parseHash);
  const [toastMsg, setToastMsg] = useState<string | null>(null);
  const [loadError, setLoadError] = useState<string | null>(null);

  const signOut = useCallback(() => {
    writeStore(TOKEN_KEY, null);
    setToken(null);
    setMe(null);
    setTeam(null);
  }, []);

  const api = useMemo(() => new Api(transport, () => readStore(TOKEN_KEY) ?? token, signOut), [token, signOut]);

  useEffect(() => {
    const onHash = () => setRoute(parseHash());
    window.addEventListener("hashchange", onHash);
    return () => window.removeEventListener("hashchange", onHash);
  }, []);

  useEffect(() => {
    if (!toastMsg) return;
    const id = setTimeout(() => setToastMsg(null), 3200);
    return () => clearTimeout(id);
  }, [toastMsg]);

  useEffect(() => {
    if (!token) return;
    api.me().then((m) => { setMe(m); setLoadError(null); }, (e) => setLoadError(e.message));
  }, [api, token, revision]);

  const teamId = route.teamId ?? me?.teams[0]?.id ?? null;
  const isMember = me?.teams.some((t) => t.id === teamId) ?? false;

  useEffect(() => {
    if (!token || teamId == null || !isMember) { setTeam(null); return; }
    api.team(teamId).then(setTeam, (e) => setLoadError(e.message));
  }, [api, token, teamId, isMember, revision]);

  const go = useCallback((view: string, query?: Record<string, string | number>) => {
    if (teamId == null) return;
    const qs = query ? "?" + new URLSearchParams(Object.entries(query).map(([k, v]) => [k, String(v)])).toString() : "";
    location.hash = `/t/${teamId}/${view}${qs}`;
  }, [teamId]);

  const onAuth = (t: string) => {
    writeStore(TOKEN_KEY, t);
    setToken(t);
    location.hash = "";
  };

  if (!token) return <Login api={api} browser={browser} onAuth={onAuth} />;
  if (!me) {
    return (
      <div className="auth-wrap">
        {loadError ? <div className="window stack"><ErrorLine error={loadError} /><button className="btn" onClick={signOut}>Sign out</button></div>
          : <p className="pixel dim">Loading…</p>}
      </div>
    );
  }

  const bump = () => setRevision((r) => r + 1);
  const toast = (m: string) => setToastMsg(m);

  const topbar = (
    <header className="topbar">
      <div className="brand"><span className="ball" aria-hidden /><span className="hide-sm">Guild Ledger</span></div>
      {me.teams.length > 1 && (
        <select
          aria-label="Team"
          value={teamId ?? ""}
          onChange={(e) => (location.hash = `/t/${e.target.value}/${route.view}`)}
          style={{ width: "auto", maxWidth: 200 }}
        >
          {me.teams.map((t) => <option key={t.id} value={t.id}>{t.name}</option>)}
        </select>
      )}
      <span className="spacer" />
      <span className="mode-tag hide-sm" title={browser ? "Runs in this tab; data is kept in this browser's storage" : `Connected to ${apiUrl}`}>
        {browser ? "browser data" : `API ${apiUrl?.replace(/^https?:\/\//, "")}`}
      </span>
      <Inbox api={api} count={me.pendingInvitations} revision={revision} onChange={bump} onJoined={(id) => (location.hash = `/t/${id}/base`)} />
      <UserMenu api={api} user={me.user} onSaved={bump} onSignOut={signOut} onSwitch={browser ? (u) => onAuth(browser.signInAs(u).token) : undefined} onReset={browser ? () => { browser.reset(); signOut(); location.reload(); } : undefined} />
    </header>
  );

  if (!isMember || !team) {
    return (
      <>
        {topbar}
        {me.teams.length === 0 || (route.teamId != null && !isMember)
          ? <NoTeam api={api} onCreated={(id) => { bump(); location.hash = `/t/${id}/base`; }} lostTeam={route.teamId != null && !isMember && me.teams.length > 0} />
          : <div className="auth-wrap"><p className="pixel dim">Loading team…</p></div>}
        {toastMsg && <div className="toast window" role="status">{toastMsg}</div>}
      </>
    );
  }

  const ctx: AppCtx = { api, browser, me, team, revision, bump, toast, go };
  const view = VIEWS.some((v) => v.id === route.view) ? route.view : "base";

  return (
    <Ctx.Provider value={ctx}>
      {topbar}
      <div className="shell">
        <nav className="window menu" aria-label="Team sections">
          <div className="menu-team">
            <strong>{team.name}</strong>
            <div className="row" style={{ gap: 6, marginTop: 4 }}><RankEmblem code={team.rank} /><span className={`role role-${team.myRole}`}>{team.myRole.toLowerCase()}</span></div>
          </div>
          <ul>
            {VIEWS.map((v) => (
              <li key={v.id}>
                <a href={`#/t/${team.id}/${v.id}`} aria-current={view === v.id ? "page" : undefined}>
                  <span>{v.label}</span>
                  {v.id === "roster" && <span className="count">{team.recruitCount}/{team.rosterCap}</span>}
                  {v.id === "members" && <span className="count">{team.memberCount}</span>}
                </a>
              </li>
            ))}
          </ul>
        </nav>
        <main>
          {view === "base" && <Base />}
          {view === "board" && <Board selected={Number(route.query.get("m")) || null} />}
          {view === "roster" && <Roster />}
          {view === "storage" && <Storage />}
          {view === "ledger" && <Ledger query={route.query} />}
          {view === "members" && <Members />}
          {view === "policy" && <Policy />}
        </main>
      </div>
      {toastMsg && <div className="toast window" role="status">{toastMsg}</div>}
    </Ctx.Provider>
  );
}

function Inbox({ api, count, revision, onChange, onJoined }: {
  api: Api; count: number; revision: number; onChange: () => void; onJoined: (teamId: number) => void;
}) {
  const [open, setOpen] = useState(false);
  const [list, setList] = useState<Invitation[]>([]);
  const [error, setError] = useState<string | null>(null);
  useEffect(() => {
    if (open) api.myInvitations().then(setList, (e) => setError(e.message));
  }, [api, open, revision]);

  const act = async (fn: () => Promise<unknown>, after?: () => void) => {
    setError(null);
    try { await fn(); onChange(); after?.(); } catch (e) { setError((e as Error).message); }
  };

  return (
    <div className="anchor">
      <button className="btn ghost" onClick={() => setOpen(!open)} aria-expanded={open}>
        Invitations {count > 0 && <span className="badge">{count}</span>}
      </button>
      {open && (
        <div className="popover window stack">
          <div className="window-title">Invitations</div>
          <ErrorLine error={error} />
          {list.length === 0 && <div className="empty">Nothing pending.</div>}
          {list.map((i) => (
            <div key={i.id} className="inset stack" style={{ gap: 6 }}>
              <div className="row" style={{ gap: 8 }}><Portrait speciesId={i.invitedBy.speciesId} small /><div><b>{i.teamName}</b> <span className="dim">as {i.role.toLowerCase()}</span></div></div>
              <div className="faint" style={{ fontSize: 12 }}>From {i.invitedBy.displayName} · expires {new Date(i.expiresAt).toLocaleDateString()}</div>
              <div className="row">
                <button className="btn primary small" onClick={() => act(() => api.accept(i.id), () => { setOpen(false); onJoined(i.teamId); })}>Join</button>
                <button className="btn small" onClick={() => act(() => api.decline(i.id))}>Decline</button>
              </div>
            </div>
          ))}
        </div>
      )}
    </div>
  );
}

function UserMenu({ api, user, onSaved, onSignOut, onSwitch, onReset }: {
  api: Api; user: UserView; onSaved: () => void; onSignOut: () => void;
  onSwitch?: (username: string) => void; onReset?: () => void;
}) {
  const [open, setOpen] = useState(false);
  const [editing, setEditing] = useState(false);
  return (
    <div className="anchor">
      <button className="btn user-btn" onClick={() => setOpen(!open)} aria-expanded={open}>
        <Portrait speciesId={user.speciesId} small />{user.displayName}
      </button>
      {open && (
        <div className="popover window stack">
          {editing
            ? <ProfileForm api={api} user={user} onDone={(saved) => { setEditing(false); if (saved) onSaved(); }} />
            : (
              <div className="row">
                <Portrait speciesId={user.speciesId} large />
                <div style={{ flex: 1 }}>
                  <b>{user.displayName}</b>
                  <div className="faint" style={{ fontSize: 12 }}>@{user.username} · {speciesById(user.speciesId)?.name}</div>
                </div>
                <button className="btn small" onClick={() => setEditing(true)}>Edit</button>
              </div>
            )}
          {onSwitch && <AccountSwitch onSwitch={(u) => { setOpen(false); onSwitch(u); }} />}
          <div className="row">
            <button className="btn" onClick={onSignOut}>Sign out</button>
            {onReset && <button className="btn danger" onClick={() => { if (confirm("Delete all browser data and start over from the demo team?")) onReset(); }}>Reset browser data</button>}
          </div>
        </div>
      )}
    </div>
  );
}

function ProfileForm({ api, user, onDone }: { api: Api; user: UserView; onDone: (saved: boolean) => void }) {
  const [name, setName] = useState(user.displayName);
  const [speciesId, setSpeciesId] = useState<number>(user.speciesId);
  const [error, setError] = useState<string | null>(null);
  const save = async (e: FormEvent) => {
    e.preventDefault();
    setError(null);
    try { await api.updateProfile(name, speciesId); onDone(true); } catch (err) { setError((err as Error).message); }
  };
  return (
    <form className="stack" onSubmit={save}>
      <label className="field">
        <span>Display name</span>
        <input type="text" value={name} maxLength={40} required onChange={(e) => setName(e.target.value)} />
      </label>
      <StarterPicker value={speciesId} onChange={setSpeciesId} />
      <ErrorLine error={error} />
      <div className="row">
        <button className="btn primary">Save</button>
        <button type="button" className="btn ghost" onClick={() => onDone(false)}>Cancel</button>
      </div>
    </form>
  );
}

function AccountSwitch({ onSwitch }: { onSwitch: (username: string) => void }) {
  return (
    <div className="field">
      <span>Sign in as another seeded account</span>
      <div className="accounts">
        {SEED_ACCOUNTS.map((a) => (
          <button key={a.username} className="account" onClick={() => onSwitch(a.username)}>
            <Portrait speciesId={a.speciesId} small />
            <span><b>{a.displayName}</b> <span className="faint" style={{ fontSize: 12 }}>{a.note}</span></span>
            <code>{a.username}</code>
          </button>
        ))}
      </div>
    </div>
  );
}

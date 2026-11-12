import { useState, type FormEvent } from "react";
import type { Api, BrowserTransport } from "../api/client";
import { SEED_ACCOUNTS, SEED_PASSWORD } from "../engine/seed";
import { ErrorLine, Portrait, Seg } from "../ui";
import { StarterPicker } from "./common";

export function Login({ api, browser, onAuth }: { api: Api; browser: BrowserTransport | null; onAuth: (token: string) => void }) {
  const [mode, setMode] = useState<"login" | "register">("login");
  const [username, setUsername] = useState("");
  const [displayName, setDisplayName] = useState("");
  const [password, setPassword] = useState("");
  const [speciesId, setSpeciesId] = useState<number | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  const submit = async (e: FormEvent) => {
    e.preventDefault();
    setBusy(true);
    setError(null);
    try {
      const res = mode === "login" ? await api.login(username, password) : await api.register(username, displayName || username, password, speciesId ?? 0);
      onAuth(res.token);
    } catch (err) {
      setError((err as Error).message);
    } finally {
      setBusy(false);
    }
  };

  const quick = async (u: string) => {
    setError(null);
    try {
      onAuth((await api.login(u, SEED_PASSWORD)).token);
    } catch (err) {
      setError((err as Error).message);
    }
  };

  return (
    <div className="auth-wrap">
      <div className="auth" style={browser ? undefined : { gridTemplateColumns: "minmax(0, 420px)", justifyContent: "center" }}>
        <form className="window stack" onSubmit={submit}>
          <div>
            <h1><span className="ball" aria-hidden />Guild Ledger</h1>
            <p className="dim" style={{ margin: 0 }}>Roster, job board, shared storage and payouts for an exploration team.</p>
          </div>
          <Seg label="Account" value={mode} onChange={setMode} options={[{ value: "login", label: "Sign in" }, { value: "register", label: "New account" }]} />
          <label className="field">
            <span>Username</span>
            <input type="text" autoComplete="username" value={username} onChange={(e) => setUsername(e.target.value)} required />
            {mode === "register" && <small>3–20 letters, digits or _</small>}
          </label>
          {mode === "register" && (
            <label className="field">
              <span>Display name</span>
              <input type="text" value={displayName} onChange={(e) => setDisplayName(e.target.value)} maxLength={40} placeholder={username} />
            </label>
          )}
          <label className="field">
            <span>Password</span>
            <input type="password" autoComplete={mode === "login" ? "current-password" : "new-password"} value={password} onChange={(e) => setPassword(e.target.value)} required minLength={mode === "register" ? 8 : undefined} />
          </label>
          {mode === "register" && <StarterPicker value={speciesId} onChange={setSpeciesId} />}
          <ErrorLine error={error} />
          <button className="btn primary" disabled={busy || (mode === "register" && speciesId == null)}>{mode === "login" ? "Sign in" : "Create account"}</button>
        </form>

        {browser && (
          <div className="window stack">
            <div className="window-title">Seeded accounts</div>
            <p className="dim" style={{ margin: 0, fontSize: 14 }}>
              This build has no server. Accounts, teams and the ledger live in this browser. Every seeded account uses the
              password <code className="pixel">{SEED_PASSWORD}</code>; sign in as different people to see what each role can do.
            </p>
            <div className="accounts">
              {SEED_ACCOUNTS.map((a) => (
                <button key={a.username} type="button" className="account" onClick={() => quick(a.username)}>
                  <Portrait speciesId={a.speciesId} />
                  <span><b>{a.displayName}</b><br /><span className="faint" style={{ fontSize: 12 }}>{a.note}</span></span>
                  <code>{a.username}</code>
                </button>
              ))}
            </div>
          </div>
        )}
      </div>
    </div>
  );
}

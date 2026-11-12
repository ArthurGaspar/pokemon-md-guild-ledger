import { useState, type FormEvent } from "react";
import type { Api } from "../api/client";
import { ErrorLine } from "../ui";

export function NoTeam({ api, onCreated, lostTeam }: { api: Api; onCreated: (teamId: number) => void; lostTeam: boolean }) {
  const [name, setName] = useState("");
  const [error, setError] = useState<string | null>(null);

  const submit = async (e: FormEvent) => {
    e.preventDefault();
    setError(null);
    try {
      onCreated((await api.createTeam(name)).id);
    } catch (err) {
      setError((err as Error).message);
    }
  };

  return (
    <div className="auth-wrap" style={{ minHeight: "70vh" }}>
      <form className="window stack" style={{ width: "min(440px, 100%)" }} onSubmit={submit}>
        <div className="window-title">{lostTeam ? "You're not on that team" : "No team yet"}</div>
        <p className="dim" style={{ margin: 0 }}>
          Found a team and you become its leader, or join one through an invitation (top right).
        </p>
        <label className="field">
          <span>Team name</span>
          <input type="text" value={name} onChange={(e) => setName(e.target.value)} minLength={3} maxLength={40} required placeholder="Team …" />
        </label>
        <ErrorLine error={error} />
        <button className="btn primary">Found team</button>
      </form>
    </div>
  );
}

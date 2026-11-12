import { useEffect, useState } from "react";
import type { SplitMode } from "../api/types";
import { split } from "../engine/payout";
import { ErrorLine, Poke, Portrait, Seg, Stepper, useAction, useApp, useLoad } from "../ui";

export function Policy() {
  const { api, team } = useApp();
  const members = useLoad(() => api.members(team.id), [team.id]);
  const missions = useLoad(() => api.missions(team.id), [team.id]);
  const { run, busy, error } = useAction();
  const leader = team.myRole === "LEADER";

  const [cut, setCut] = useState(team.treasuryCutPct);
  const [mode, setMode] = useState<SplitMode>(team.splitMode);
  useEffect(() => { setCut(team.treasuryCutPct); setMode(team.splitMode); }, [team.treasuryCutPct, team.splitMode]);
  const dirty = cut !== team.treasuryCutPct || mode !== team.splitMode;

  // calculator: a hypothetical mission
  const upcoming = (missions.data ?? []).find((m) => m.status === "PLANNED" || m.status === "POSTED");
  const [reward, setReward] = useState<number | null>(null);
  const [lead, setLead] = useState<number | null>(null);
  const [slots, setSlots] = useState<Record<number, number>>({});
  const list = members.data ?? [];
  const effectiveReward = reward ?? upcoming?.rewardPoke ?? 600;
  const effectiveLead = lead ?? list[0]?.membershipId ?? null;
  const party = Object.entries(slots).flatMap(([id, n]) => Array.from({ length: n }, () => Number(id)));
  const used = party.length;
  const active = new Set(list.map((m) => m.membershipId));
  const draft = split(effectiveReward, cut, mode, effectiveLead, party, active);
  const saved = split(effectiveReward, team.treasuryCutPct, team.splitMode, effectiveLead, party, active);
  const name = (id: number) => list.find((m) => m.membershipId === id)?.displayName ?? "?";

  return (
    <>
      <div className="page-head"><h1>Reward policy</h1></div>
      <div className="storage-grid">
        <section className="window stack">
          <h2 style={{ marginBottom: 0 }}>How rewards are split</h2>
          <ol style={{ margin: 0, paddingLeft: 20, fontSize: 14 }} className="dim">
            <li>The treasury takes its cut, rounded down.</li>
            <li>The rest is shared by the mission leader and every member whose recruit went.</li>
            <li>Shares round down; leftover Poké goes to the treasury, so totals always match the reward.</li>
          </ol>
          <label className="field">
            <span>Treasury cut: {cut}%</span>
            <input type="range" min={0} max={100} step={5} value={cut} disabled={!leader} onChange={(e) => setCut(Number(e.target.value))} />
          </label>
          <div className="field">
            <span>Members' part</span>
            {leader
              ? <Seg label="Split" value={mode} onChange={setMode} options={[{ value: "EQUAL", label: "Equal per participant" }, { value: "BY_PARTY", label: "By party slots" }]} />
              : <b>{mode === "EQUAL" ? "Equal per participant" : "By party slots"}</b>}
            <small>
              {mode === "EQUAL"
                ? "One share each, however many recruits someone sent."
                : "One share per recruit a member sent, plus one for leading."}
            </small>
          </div>
          <ErrorLine error={error} />
          {leader
            ? <div className="row"><button className="btn primary" disabled={busy || !dirty} onClick={() => run(() => api.setPolicy(team.id, cut, mode), "Policy saved")}>Save policy</button>{dirty && <span className="warn" style={{ fontSize: 13 }}>Unsaved. Applies to missions reported after saving.</span>}</div>
            : <small className="faint">Only the leader changes the policy.</small>}
        </section>

        <section className="window stack">
          <h2 style={{ marginBottom: 0 }}>Try it</h2>
          <div className="grid2">
            <label className="field">
              <span>Reward</span>
              <input type="number" min={0} value={effectiveReward} onChange={(e) => setReward(Number(e.target.value))} />
              {upcoming && reward == null && <small>from “{upcoming.title}”</small>}
            </label>
            <label className="field">
              <span>Leader</span>
              <select value={effectiveLead ?? ""} onChange={(e) => setLead(Number(e.target.value))}>
                {list.map((m) => <option key={m.membershipId} value={m.membershipId}>{m.displayName}</option>)}
              </select>
            </label>
          </div>
          <div className="field">
            <span>Recruits each member sends ({used}/4)</span>
            {list.map((m) => (
              <div key={m.membershipId} className="bag-row" style={{ gridTemplateColumns: "1fr auto" }}>
                <span className="row" style={{ gap: 6 }}><Portrait speciesId={m.speciesId} small />{m.displayName}</span>
                <Stepper label={`${m.displayName}'s recruits`} value={slots[m.membershipId] ?? 0} max={(slots[m.membershipId] ?? 0) + 4 - used} onChange={(v) => setSlots({ ...slots, [m.membershipId]: v })} />
              </div>
            ))}
          </div>
          <table className="split-table">
            <thead><tr><td className="faint" style={{ fontSize: 12 }} /><td className="faint" style={{ fontSize: 12, textAlign: "right" }}>{dirty ? "draft  ·  saved" : "current"}</td></tr></thead>
            <tbody>
              <tr><td>Treasury</td><td><Poke n={draft.treasury} />{dirty && <span className="faint"> · {saved.treasury}</span>}</td></tr>
              {draft.shares.map((s) => {
                const old = saved.shares.find((x) => x.membershipId === s.membershipId)?.amount ?? 0;
                const sp = list.find((m) => m.membershipId === s.membershipId)?.speciesId ?? 0;
                return <tr key={s.membershipId}><td><span className="row" style={{ gap: 6 }}><Portrait speciesId={sp} small />{name(s.membershipId)} <span className="faint">× {s.weight}</span></span></td><td><Poke n={s.amount} />{dirty && <span className="faint"> · {old}</span>}</td></tr>;
              })}
              <tr className="total"><td>Reward</td><td><Poke n={effectiveReward} /></td></tr>
            </tbody>
          </table>
        </section>
      </div>
    </>
  );
}

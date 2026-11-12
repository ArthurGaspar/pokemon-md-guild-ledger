import { useState, type FormEvent } from "react";
import { catalog, speciesById } from "../api/catalog";
import type { Recruit } from "../api/types";
import { Bar, ErrorLine, Portrait, Seg, TypeChip, can, timeAgo, useAction, useApp, useLoad } from "../ui";
import { SpeciesInput, parseSpecies } from "./common";

type StatusFilter = "all" | "AVAILABLE" | "ON_MISSION";
type Sort = "level" | "recent" | "dex";

export function Roster() {
  const { api, team, go } = useApp();
  const recruits = useLoad(() => api.recruits(team.id), [team.id]);
  const members = useLoad(() => api.members(team.id), [team.id]);
  const memberSpecies = new Map((members.data ?? []).map((m) => [m.membershipId, m.speciesId]));
  const [q, setQ] = useState("");
  const [status, setStatus] = useState<StatusFilter>("all");
  const [type, setType] = useState("");
  const [by, setBy] = useState("");
  const [sort, setSort] = useState<Sort>("level");

  const list = recruits.data ?? [];
  const recruiters = [...new Set(list.map((r) => r.recruitedBy?.displayName).filter(Boolean))] as string[];
  const typesPresent = [...new Set(list.flatMap((r) => r.types))].sort();
  const shown = list
    .filter((r) => status === "all" || r.status === status)
    .filter((r) => !type || r.types.includes(type))
    .filter((r) => !by || r.recruitedBy?.displayName === by)
    .filter((r) => !q || `${r.nickname ?? ""} ${r.speciesName} ${r.speciesId}`.toLowerCase().includes(q.toLowerCase()))
    .sort((a, b) => sort === "level" ? b.level - a.level || b.exp - a.exp
      : sort === "recent" ? Date.parse(b.recruitedAt) - Date.parse(a.recruitedAt) : a.speciesId - b.speciesId);
  const out = list.filter((r) => r.status === "ON_MISSION").length;

  return (
    <>
      <div className="page-head">
        <h1>Roster</h1>
        <span className="dim num">{list.length} of {team.rosterCap} · {out} out</span>
        <div style={{ width: 120 }}><Bar value={list.length} max={team.rosterCap} gold /></div>
      </div>

      <RecruitForm full={list.length >= team.rosterCap} />

      <section className="window">
        <div className="row" style={{ marginBottom: 10 }}>
          <input type="search" placeholder="Search name, species or dex #" value={q} onChange={(e) => setQ(e.target.value)} style={{ flex: "1 1 200px", width: "auto" }} aria-label="Search recruits" />
          <Seg label="Status" value={status} onChange={setStatus} options={[{ value: "all", label: "All" }, { value: "AVAILABLE", label: "Home" }, { value: "ON_MISSION", label: "Out" }]} />
          <select value={type} onChange={(e) => setType(e.target.value)} style={{ width: "auto" }} aria-label="Type">
            <option value="">Any type</option>
            {typesPresent.map((t) => <option key={t} value={t}>{t}</option>)}
          </select>
          <select value={by} onChange={(e) => setBy(e.target.value)} style={{ width: "auto" }} aria-label="Recruited by">
            <option value="">Anyone's</option>
            {recruiters.map((n) => <option key={n} value={n}>{n}'s</option>)}
          </select>
          <select value={sort} onChange={(e) => setSort(e.target.value as Sort)} style={{ width: "auto" }} aria-label="Sort">
            <option value="level">Highest level</option>
            <option value="recent">Newest</option>
            <option value="dex">Dex order</option>
          </select>
        </div>
        <ErrorLine error={recruits.error} />
        <div className="table-wrap">
          <table className="data">
            <thead>
              <tr><th>Recruit</th><th>Types</th><th>Level</th><th>Status</th><th>Recruited by</th><th /></tr>
            </thead>
            <tbody>
              {shown.map((r) => <Row key={r.id} r={r} recruiterSpecies={r.recruitedBy ? memberSpecies.get(r.recruitedBy.membershipId) : undefined} onMission={() => r.missionId && go("board", { m: r.missionId })} />)}
            </tbody>
          </table>
        </div>
        {recruits.data && shown.length === 0 && <div className="empty">{list.length === 0 ? "No recruits yet." : "Nobody matches these filters."}</div>}
      </section>
    </>
  );
}

function Row({ r, recruiterSpecies, onMission }: { r: Recruit; recruiterSpecies?: number; onMission: () => void }) {
  const { api, team } = useApp();
  const { run, busy, error } = useAction();
  const [editing, setEditing] = useState(false);
  const [nick, setNick] = useState(r.nickname ?? "");
  const editable = can(team.myRole, "OFFICER") || r.recruitedBy?.membershipId === team.myMembershipId;
  const span = r.nextLevelExp == null ? 1 : r.nextLevelExp - r.levelExp;

  return (
    <tr>
      <td>
        {editing ? (
          <form className="row" style={{ gap: 6, flexWrap: "nowrap" }} onSubmit={(e) => { e.preventDefault(); run(() => api.rename(team.id, r.id, nick || null)).then((ok) => ok && setEditing(false)); }}>
            <Portrait speciesId={r.speciesId} small />
            <input type="text" value={nick} onChange={(e) => setNick(e.target.value)} maxLength={20} placeholder={r.speciesName} autoFocus aria-label="Nickname" style={{ width: 130 }} />
            <button className="btn small" disabled={busy}>Save</button>
            <button type="button" className="btn small ghost" onClick={() => setEditing(false)}>Cancel</button>
          </form>
        ) : (
          <div className="who">
            <Portrait speciesId={r.speciesId} />
            <div style={{ minWidth: 0 }}>
              <b>{r.nickname ?? r.speciesName}</b>
              <small>{r.nickname ? `${r.speciesName} · ` : ""}#{String(r.speciesId).padStart(3, "0")}</small>
            </div>
          </div>
        )}
        <ErrorLine error={error} />
      </td>
      <td><span className="row" style={{ gap: 3 }}>{r.types.map((t) => <TypeChip key={t} type={t} />)}</span></td>
      <td style={{ minWidth: 110 }}>
        <span className="pixel">Lv. {r.level}</span>
        <Bar value={r.exp - r.levelExp} max={span} />
        <small className="faint num">{r.nextLevelExp == null ? "max" : `${r.nextLevelExp - r.exp} exp to go`}</small>
      </td>
      <td>
        {r.status === "ON_MISSION"
          ? <button className="btn small ghost status status-ON_MISSION" onClick={onMission}>on mission →</button>
          : <span className={`status status-${r.status}`}>home</span>}
      </td>
      <td>
        <div className="who">
          {recruiterSpecies != null && <Portrait speciesId={recruiterSpecies} small />}
          <div><span className="dim">{r.recruitedBy?.displayName ?? "—"}</span><small style={{ display: "block" }}>{timeAgo(r.recruitedAt)}</small></div>
        </div>
      </td>
      <td className="r nowrap">
        {editable && !editing && (
          <>
            <button className="btn small ghost" onClick={() => setEditing(true)}>Rename</button>
            <button className="btn small ghost danger" disabled={busy || r.status === "ON_MISSION"}
              onClick={() => confirm(`Release ${r.nickname ?? r.speciesName}? It leaves the roster for good.`) && run(() => api.release(team.id, r.id), "Released")}>Release</button>
          </>
        )}
      </td>
    </tr>
  );
}

function RecruitForm({ full }: { full: boolean }) {
  const { api, team } = useApp();
  const { run, busy, error } = useAction();
  const [species, setSpecies] = useState("");
  const [level, setLevel] = useState(5);
  const [nickname, setNickname] = useState("");
  const id = parseSpecies(species);
  const s = id == null ? null : speciesById(id);

  const submit = (e: FormEvent) => {
    e.preventDefault();
    if (id == null) return;
    run(() => api.recruit(team.id, id, level, nickname || null), `${nickname || s?.name} joined the team`).then((ok) => {
      if (ok) { setSpecies(""); setNickname(""); }
    });
  };

  return (
    <form className="window" onSubmit={submit}>
      <h2>Recruit</h2>
      <div className="row" style={{ alignItems: "end" }}>
        {s ? <Portrait speciesId={s.id} /> : <span className="portrait empty">?</span>}
        <div style={{ flex: "2 1 200px" }}><SpeciesInput label="Species" value={species} onChange={setSpecies} /></div>
        <label className="field" style={{ flex: "0 1 90px" }}>
          <span>Level</span>
          <input type="number" min={1} max={catalog.limits.maxLevel} value={level} onChange={(e) => setLevel(Number(e.target.value))} />
        </label>
        <label className="field" style={{ flex: "1 1 150px" }}>
          <span>Nickname</span>
          <input type="text" maxLength={20} value={nickname} onChange={(e) => setNickname(e.target.value)} placeholder="Optional" />
        </label>
        <button className="btn primary" disabled={busy || id == null || full}>Recruit</button>
      </div>
      {s && <div className="row" style={{ gap: 4, marginTop: 8 }}>{s.types.map((t) => <TypeChip key={t} type={t} />)}</div>}
      {species && id == null && <small className="warn">Pick a species from the list.</small>}
      {full && <p className="warn" style={{ margin: "8px 0 0", fontSize: 14 }}>The roster is full for {team.rankName} Rank ({team.rosterCap}). Release someone or rank up.</p>}
      <ErrorLine error={error} />
    </form>
  );
}

import { useEffect, useMemo, useRef, useState, type FormEvent } from "react";
import { catalog, difficulty, difficultyOrder, dungeon, floorLabel, item, itemName, speciesById } from "../api/catalog";
import type { ItemStack, LedgerEntry, MemberView, Mission, Recruit, StorageView } from "../api/types";
import { split } from "../engine/payout";
import {
  Bar, Diff, ErrorLine, ItemGlyph, Poke, Portrait, Seg, Stepper, TypeChip, can, fmt, stamp, timeAgo, useAction, useApp, useLoad,
} from "../ui";
import { Items, SpeciesInput, parseSpecies, useTypeSpread } from "./common";

type Tab = "open" | "field" | "closed";

const tabOf = (m: Mission): Tab =>
  m.status === "POSTED" || m.status === "PLANNED" ? "open" : m.status === "DEPARTED" ? "field" : "closed";

const KIND_LABEL: Record<string, string> = { RESCUE: "Rescue", ESCORT: "Escort", DELIVERY: "Delivery", SEARCH: "Search", OUTLAW: "Outlaw" };

export function Board({ selected }: { selected: number | null }) {
  const { api, team, go } = useApp();
  const missions = useLoad(() => api.missions(team.id), [team.id]);
  const recruits = useLoad(() => api.recruits(team.id), [team.id]);
  const members = useLoad(() => api.members(team.id), [team.id]);
  const storage = useLoad(() => api.storage(team.id), [team.id]);
  const [tab, setTab] = useState<Tab>("open");
  const [posting, setPosting] = useState(false);

  const all = missions.data ?? [];
  const current = all.find((m) => m.id === selected) ?? null;

  useEffect(() => {
    if (current) { setTab(tabOf(current)); setPosting(false); }
  }, [current?.id, current?.status]); // eslint-disable-line react-hooks/exhaustive-deps

  const shown = all.filter((m) => tabOf(m) === tab);
  const counts = { open: 0, field: 0, closed: 0 } as Record<Tab, number>;
  all.forEach((m) => counts[tabOf(m)]++);
  const officer = can(team.myRole, "OFFICER");
  const panelOpen = posting || current != null;

  return (
    <>
      <div className="page-head">
        <h1>Job Board</h1>
        <Seg label="Missions" value={tab} onChange={setTab} options={[
          { value: "open", label: `Open ${counts.open}` },
          { value: "field", label: `In the field ${counts.field}` },
          { value: "closed", label: `Closed ${counts.closed}` },
        ]} />
        <span className="spacer" />
        {officer && <button className="btn primary" onClick={() => { setPosting(true); go("board"); }}>Post a job</button>}
      </div>
      <ErrorLine error={missions.error} />

      <div className={`board-layout${panelOpen ? "" : " single"}`}>
        <div className="cork">
          {missions.data && shown.length === 0 && (
            <div className="empty">
              {tab === "open" ? (officer ? "The board is empty. Post a job to plan a party for it." : "No open jobs. Officers post them.")
                : tab === "field" ? "No party is out." : "No finished missions yet."}
            </div>
          )}
          <div className="notices">
            {shown.map((m) => <Notice key={m.id} m={m} active={m.id === selected} onClick={() => go("board", { m: m.id })} />)}
          </div>
        </div>

        {posting && !current && <PostJob onClose={() => setPosting(false)} onPosted={(id) => { setPosting(false); go("board", { m: id }); }} />}
        {current && recruits.data && members.data && storage.data && (
          <MissionPanel key={current.id + current.status} m={current} missions={all} recruits={recruits.data} members={members.data} storage={storage.data} onClose={() => go("board")} />
        )}
      </div>
    </>
  );
}

function Notice({ m, active, onClick }: { m: Mission; active: boolean; onClick: () => void }) {
  return (
    <button className="notice" aria-pressed={active} onClick={onClick}>
      <div className="notice-top">
        <Diff code={m.difficulty} />
        <div style={{ minWidth: 0 }}>
          <h3>{m.title}</h3>
          <div className="where">{KIND_LABEL[m.kind]} · {m.dungeonName} {m.floorLabel}</div>
        </div>
      </div>
      <div className="notice-foot">
        <Poke n={m.rewardPoke} />
        {m.rewardItems.length > 0 && <span className="row" style={{ gap: 3, display: "inline-flex" }}>{m.rewardItems.map((s) => <ItemGlyph key={s.itemCode} code={s.itemCode} />)}</span>}
        <span className="faint">+{m.rankPoints} pts</span>
        <span className="spacer" />
        {m.party.length > 0 && <span className="party-strip">{m.party.map((p) => <Portrait key={p.recruitId} speciesId={p.speciesId} small />)}</span>}
        <span className={`status status-${m.status}`}>{m.status.toLowerCase()}</span>
      </div>
    </button>
  );
}

// ------------------------------------------------------------------ post

function PostJob({ onClose, onPosted }: { onClose: () => void; onPosted: (id: number) => void }) {
  const { api, team } = useApp();
  const { run, busy, error } = useAction();
  const [dungeonCode, setDungeonCode] = useState(catalog.dungeons[0].code);
  const d = dungeon(dungeonCode)!;
  const unlocked = catalog.difficulties.filter((x) => difficultyOrder(x.code) <= difficultyOrder(team.maxDifficulty));
  const [diff, setDiff] = useState(d.suggestedDifficulty);
  const [floor, setFloor] = useState(1);
  const [kind, setKind] = useState("RESCUE");
  const [title, setTitle] = useState("");
  const [client, setClient] = useState("");
  const [reward, setReward] = useState(difficulty(diff)!.suggestedPoke);
  const [rewardTouched, setRewardTouched] = useState(false);
  const [items, setItems] = useState<ItemStack[]>([]);

  useEffect(() => {
    setFloor((f) => Math.min(f, d.floors));
    if (difficultyOrder(d.suggestedDifficulty) <= difficultyOrder(team.maxDifficulty)) setDiff(d.suggestedDifficulty);
  }, [dungeonCode]); // eslint-disable-line react-hooks/exhaustive-deps
  useEffect(() => {
    if (!rewardTouched) setReward(difficulty(diff)!.suggestedPoke);
  }, [diff, rewardTouched]);

  const clientId = parseSpecies(client);
  const locked = difficultyOrder(diff) > difficultyOrder(team.maxDifficulty);

  const submit = (e: FormEvent) => {
    e.preventDefault();
    run(async () => {
      const m = await api.postMission(team.id, {
        title, kind, dungeonCode, floor, difficulty: diff, clientSpeciesId: clientId, rewardPoke: reward, rewardItems: items,
      });
      onPosted(m.id);
    }, "Job posted");
  };

  return (
    <form className="window panel stack" onSubmit={submit}>
      <div className="window-title">Post a job <span className="spacer" /><button type="button" className="btn small ghost" onClick={onClose}>Close</button></div>
      <label className="field">
        <span>Title</span>
        <input type="text" value={title} onChange={(e) => setTitle(e.target.value)} maxLength={80} required placeholder={`e.g. Shellos fainted on ${floorLabel(d, floor)}`} />
      </label>
      <div className="grid2">
        <label className="field">
          <span>Kind</span>
          <select value={kind} onChange={(e) => setKind(e.target.value)}>
            {catalog.missionKinds.map((k) => <option key={k} value={k}>{KIND_LABEL[k]}</option>)}
          </select>
        </label>
        <SpeciesInput label={kind === "OUTLAW" ? "Outlaw" : "Client"} value={client} onChange={setClient} optional />
      </div>
      {client && clientId == null && <small className="warn">Not a species in the catalog; the job will be posted without a client.</small>}
      <div className="grid2">
        <label className="field">
          <span>Dungeon</span>
          <select value={dungeonCode} onChange={(e) => setDungeonCode(e.target.value)}>
            {catalog.dungeons.map((x) => <option key={x.code} value={x.code}>{x.name} ({x.floors} floors)</option>)}
          </select>
        </label>
        <label className="field">
          <span>Floor</span>
          <select value={floor} onChange={(e) => setFloor(Number(e.target.value))}>
            {Array.from({ length: d.floors }, (_, i) => i + 1).map((f) => <option key={f} value={f}>{floorLabel(d, f)}</option>)}
          </select>
        </label>
      </div>
      <div className="field">
        <span>Difficulty</span>
        <div className="row" style={{ gap: 6 }}>
          {catalog.difficulties.map((x) => {
            const ok = unlocked.includes(x);
            return (
              <button key={x.code} type="button" className="btn small" aria-pressed={diff === x.code} disabled={!ok}
                style={diff === x.code ? { borderColor: "var(--cursor)" } : undefined}
                title={ok ? `${x.rankPoints} rank points, ${x.exp} exp per recruit` : `Needs a higher rank than ${team.rankName}`}
                onClick={() => setDiff(x.code)}>
                <Diff code={x.code} small />
              </button>
            );
          })}
        </div>
        <small>{difficulty(diff)!.rankPoints} rank points · {difficulty(diff)!.exp} exp per recruit · suggested for {d.name}: {difficulty(d.suggestedDifficulty)!.label}</small>
      </div>
      <label className="field">
        <span>Reward (Poké)</span>
        <input type="number" min={0} max={100000} value={reward} onChange={(e) => { setRewardTouched(true); setReward(Number(e.target.value)); }} />
      </label>
      <div className="field">
        <span>Reward items</span>
        {items.map((s, i) => (
          <div key={i} className="row" style={{ gap: 6 }}>
            <select value={s.itemCode} style={{ flex: 1, width: "auto" }} onChange={(e) => setItems(items.map((x, j) => (j === i ? { ...x, itemCode: e.target.value } : x)))}>
              {catalog.items.map((it) => <option key={it.code} value={it.code}>{it.name}</option>)}
            </select>
            <Stepper label={itemName(s.itemCode)} value={s.quantity} min={1} max={99} onChange={(q) => setItems(items.map((x, j) => (j === i ? { ...x, quantity: q } : x)))} />
            <button type="button" className="btn small ghost" onClick={() => setItems(items.filter((_, j) => j !== i))} aria-label="Remove item">✕</button>
          </div>
        ))}
        <div><button type="button" className="btn small" onClick={() => setItems([...items, { itemCode: "oran-berry", quantity: 1 }])}>Add item</button></div>
      </div>
      <ErrorLine error={error} />
      <button className="btn primary" disabled={busy || locked}>Post job</button>
    </form>
  );
}

// ------------------------------------------------------------------ mission panel

function MissionPanel({ m, missions, recruits, members, storage, onClose }: {
  m: Mission; missions: Mission[]; recruits: Recruit[]; members: MemberView[]; storage: StorageView; onClose: () => void;
}) {
  const { team } = useApp();
  const officer = can(team.myRole, "OFFICER");
  const leads = m.leader?.membershipId === team.myMembershipId;
  const ref = useRef<HTMLElement>(null);
  useEffect(() => {
    // below 1100px the panel stacks under the notices
    if (window.innerWidth < 1100) ref.current?.scrollIntoView({ behavior: "smooth", block: "start" });
  }, [m.id]);

  return (
    <aside ref={ref} className="window panel" aria-label="Mission">
      <div className="window-title">
        <span className={`status status-${m.status}`}>{m.status.toLowerCase()}</span>
        <span className="spacer" />
        <button className="btn small ghost" onClick={onClose}>Close</button>
      </div>
      <div className="row" style={{ alignItems: "start", flexWrap: "nowrap" }}>
        <Diff code={m.difficulty} />
        <div style={{ minWidth: 0 }}>
          <h3>{m.title}</h3>
          <div className="dim" style={{ fontSize: 14 }}>{KIND_LABEL[m.kind]} · {m.dungeonName} {m.floorLabel}{m.clientName && <> · {m.kind === "OUTLAW" ? "target" : "client"} {m.clientName}</>}</div>
        </div>
      </div>
      <dl className="facts" style={{ marginTop: 10 }}>
        <dt>Reward</dt><dd><Poke n={m.rewardPoke} /> {m.rewardItems.length > 0 && <>· <Items stacks={m.rewardItems} /></>}</dd>
        <dt>Rank points</dt><dd>+{m.rankPoints}</dd>
        <dt>Posted</dt><dd>{m.postedBy}, {timeAgo(m.postedAt)}</dd>
        {m.departedAt && <><dt>Departed</dt><dd>{stamp(m.departedAt)}</dd></>}
        {m.closedAt && <><dt>Closed</dt><dd>{stamp(m.closedAt)}</dd></>}
      </dl>

      {(m.status === "POSTED" || m.status === "PLANNED") && officer && (
        <PlanEditor m={m} missions={missions} recruits={recruits} members={members} storage={storage} />
      )}
      {(m.status === "POSTED" || m.status === "PLANNED") && !officer && <PlanReadOnly m={m} canDepart={leads && m.status === "PLANNED"} />}
      {m.status === "DEPARTED" && (officer || leads ? <ReportForm m={m} /> : <PlanReadOnly m={m} canDepart={false} />)}
      {(m.status === "COMPLETED" || m.status === "FAILED" || m.status === "CANCELLED") && <ClosedSummary m={m} />}
    </aside>
  );
}

function Leader({ m, suffix }: { m: Mission; suffix?: string }) {
  if (!m.leader) return <p className="faint" style={{ margin: "0 0 6px" }}>No leader</p>;
  return (
    <div className="who" style={{ marginBottom: 8 }}>
      <Portrait speciesId={m.leader.speciesId} />
      <div><b>Led by {m.leader.displayName}</b><small>{speciesName(m.leader.speciesId)}{suffix ? ` · ${suffix}` : ""}</small></div>
    </div>
  );
}

const speciesName = (id: number) => speciesById(id)?.name ?? "";

function PlanReadOnly({ m, canDepart }: { m: Mission; canDepart: boolean }) {
  const { api, team } = useApp();
  const { run, busy, error } = useAction();
  if (m.status === "POSTED") return <section><p className="dim" style={{ margin: 0 }}>No party yet. An officer plans it.</p></section>;
  return (
    <section>
      <h4>Party</h4>
      <Leader m={m} />
      <div className="stack" style={{ gap: 4 }}>
        {m.party.map((p) => <div key={p.recruitId} className="who"><Portrait speciesId={p.speciesId} small /><span>{p.name} <span className="faint">Lv. {p.level}</span></span></div>)}
      </div>
      <h4 style={{ marginTop: 12 }}>Bag</h4>
      <Items stacks={m.bag} empty="Empty" />
      <ErrorLine error={error} />
      {canDepart && <div style={{ marginTop: 10 }}><button className="btn primary" disabled={busy} onClick={() => run(() => api.depart(team.id, m.id), "Party departed")}>Depart</button></div>}
    </section>
  );
}

function PlanEditor({ m, missions, recruits, members, storage }: {
  m: Mission; missions: Mission[]; recruits: Recruit[]; members: MemberView[]; storage: StorageView;
}) {
  const { api, team } = useApp();
  const { run, busy, error } = useAction();
  const [leader, setLeader] = useState<number>(m.leader?.membershipId ?? team.myMembershipId);
  const [party, setParty] = useState<number[]>(m.party.map((p) => p.recruitId));
  const [bag, setBag] = useState<Record<string, number>>(Object.fromEntries(m.bag.map((s) => [s.itemCode, s.quantity])));
  const [filter, setFilter] = useState("");

  const plannedElsewhere = useMemo(() => {
    const map = new Map<number, string>();
    for (const x of missions) if (x.status === "PLANNED" && x.id !== m.id) x.party.forEach((p) => map.set(p.recruitId, x.title));
    return map;
  }, [missions, m.id]);

  const byId = new Map(recruits.map((r) => [r.id, r]));
  const carried = Object.values(bag).reduce((a, b) => a + b, 0);
  const bagList: ItemStack[] = Object.entries(bag).filter(([, q]) => q > 0).map(([itemCode, quantity]) => ({ itemCode, quantity }));
  const stock = new Map(storage.items.map((s) => [s.itemCode, s.quantity]));

  const dirty = leader !== (m.leader?.membershipId ?? -1)
    || party.join(",") !== m.party.map((p) => p.recruitId).join(",")
    || JSON.stringify(bagList) !== JSON.stringify(m.bag);

  const spread = useTypeSpread(party.map((id) => byId.get(id)?.speciesId ?? 0));
  const preview = split(m.rewardPoke, team.treasuryCutPct, team.splitMode, leader,
    party.map((id) => byId.get(id)?.recruitedBy?.membershipId ?? null), new Set(members.map((x) => x.membershipId)));
  const nameOf = (membershipId: number) => members.find((x) => x.membershipId === membershipId)?.displayName ?? "?";
  const speciesOf = (membershipId: number) => members.find((x) => x.membershipId === membershipId)?.speciesId ?? 0;
  const exp = difficulty(m.difficulty)!.exp;

  const toggle = (id: number) => setParty((p) => (p.includes(id) ? p.filter((x) => x !== id) : p.length >= catalog.limits.partySize ? p : [...p, id]));
  const candidates = recruits
    .filter((r) => !party.includes(r.id))
    .filter((r) => !filter || `${r.nickname ?? ""} ${r.speciesName} ${r.types.join(" ")}`.toLowerCase().includes(filter.toLowerCase()))
    .sort((a, b) => Number(a.status !== "AVAILABLE") - Number(b.status !== "AVAILABLE") || b.level - a.level);

  const save = () => run(() => api.plan(team.id, m.id, leader, party, bagList), m.status === "PLANNED" ? "Plan updated" : "Party planned");
  const leaderOk = members.some((x) => x.membershipId === leader);

  return (
    <>
      <section>
        <h4>Leader</h4>
        <div className="row" style={{ flexWrap: "nowrap" }}>
          <Portrait speciesId={members.find((x) => x.membershipId === leader)?.speciesId ?? 0} />
          <select value={leader} onChange={(e) => setLeader(Number(e.target.value))} aria-label="Mission leader">
            {members.map((x) => <option key={x.membershipId} value={x.membershipId}>{x.displayName} the {speciesName(x.speciesId)} ({x.role.toLowerCase()})</option>)}
          </select>
        </div>
        <small className="faint">The leader can depart and report even without officer rights.</small>
      </section>

      <section>
        <h4>Party <span className="spacer" /><span className="faint num">{party.length}/{catalog.limits.partySize}</span></h4>
        <div className="slots">
          {Array.from({ length: catalog.limits.partySize }, (_, i) => {
            const r = byId.get(party[i]);
            return r ? (
              <button key={`r${r.id}`} type="button" className="slot filled" onClick={() => toggle(r.id)} title="Remove from party">
                <Portrait speciesId={r.speciesId} />
                <b>{r.nickname ?? r.speciesName}</b>
                <span className="lv">Lv. {r.level} · {r.recruitedBy?.displayName ?? "—"}</span>
              </button>
            ) : <div key={`empty${i}`} className="slot"><span className="portrait empty">?</span><span className="faint">empty</span></div>;
          })}
        </div>
        {spread.length > 0 && <div className="row" style={{ gap: 4, marginTop: 8 }}>{spread.map(([t, n]) => <span key={t} className="row" style={{ gap: 2, display: "inline-flex" }}><TypeChip type={t} />{n > 1 && <span className="faint">×{n}</span>}</span>)}</div>}
        <input type="search" placeholder="Filter by name or type" value={filter} onChange={(e) => setFilter(e.target.value)} style={{ marginTop: 10 }} aria-label="Filter recruits" />
        <div className="pick-list" style={{ marginTop: 6 }}>
          {candidates.map((r) => {
            const elsewhere = plannedElsewhere.get(r.id);
            const blocked = r.status !== "AVAILABLE" || elsewhere != null || party.length >= catalog.limits.partySize;
            return (
              <button key={r.id} type="button" className="pick" disabled={blocked} onClick={() => toggle(r.id)}
                title={r.status === "ON_MISSION" ? "Out on a mission" : elsewhere ? `Planned for "${elsewhere}"` : undefined}>
                <Portrait speciesId={r.speciesId} small />
                <span><b>{r.nickname ?? r.speciesName}</b> <span className="faint">Lv. {r.level}</span>
                  {r.status === "ON_MISSION" ? <span className="status status-ON_MISSION"> · out</span> : elsewhere ? <span className="faint"> · planned</span> : null}
                </span>
                <span className="types">{r.types.map((t) => <TypeChip key={t} type={t} />)}</span>
              </button>
            );
          })}
        </div>
      </section>

      <section>
        <h4>Bag</h4>
        <div className="cap-meter"><Bar value={carried} max={team.bagCap} gold /><span className="num">{carried}/{team.bagCap}</span></div>
        <div style={{ marginTop: 8 }}>
          {storage.items.length === 0 && <div className="faint" style={{ fontSize: 14 }}>Storage is empty.</div>}
          {storage.items.map((s) => {
            const q = bag[s.itemCode] ?? 0;
            return (
              <div key={s.itemCode} className="bag-row">
                <ItemGlyph code={s.itemCode} />
                <span>{itemName(s.itemCode)} <span className="faint">({s.quantity} in storage)</span></span>
                <span />
                <Stepper label={itemName(s.itemCode)} value={q} max={Math.min(stock.get(s.itemCode) ?? 0, q + team.bagCap - carried)}
                  onChange={(v) => setBag({ ...bag, [s.itemCode]: v })} />
              </div>
            );
          })}
        </div>
        <small className="faint">Items leave storage when the party departs.</small>
      </section>

      <section>
        <h4>If it succeeds</h4>
        <table className="split-table">
          <tbody>
            <tr><td>Treasury ({team.treasuryCutPct}% + rounding)</td><td><Poke n={preview.treasury} /></td></tr>
            {preview.shares.map((s) => (
              <tr key={s.membershipId}><td><span className="row" style={{ gap: 6 }}><Portrait speciesId={speciesOf(s.membershipId)} small />{nameOf(s.membershipId)}{team.splitMode === "BY_PARTY" && <span className="faint">× {s.weight}</span>}</span></td><td><Poke n={s.amount} /></td></tr>
            ))}
            <tr className="total"><td>Reward</td><td><Poke n={m.rewardPoke} /></td></tr>
          </tbody>
        </table>
        <small className="faint">+{exp} exp for each recruit · +{m.rankPoints} rank points{m.rewardItems.length > 0 && " · reward items go to storage"}</small>
      </section>

      <section className="stack">
        <ErrorLine error={error} />
        <div className="row">
          <button className="btn primary" disabled={busy || party.length === 0 || !leaderOk || !dirty} onClick={save}>{m.status === "PLANNED" ? "Save changes" : "Save plan"}</button>
          {m.status === "PLANNED" && (
            <button className="btn primary" disabled={busy || dirty} title={dirty ? "Save the plan first" : undefined}
              onClick={() => run(() => api.depart(team.id, m.id), "Party departed")}>Depart</button>
          )}
          <span className="spacer" />
          <button className="btn danger small" disabled={busy} onClick={() => confirm("Take this job off the board?") && run(() => api.cancel(team.id, m.id), "Job taken down")}>Take down</button>
        </div>
      </section>
    </>
  );
}

function ReportForm({ m }: { m: Mission }) {
  const { api, team } = useApp();
  const { run, busy, error } = useAction();
  const [outcome, setOutcome] = useState<"COMPLETED" | "FAILED">("COMPLETED");
  const [used, setUsed] = useState<Record<string, number>>({});
  const [note, setNote] = useState("");
  const preview = useLoad(() => api.preview(team.id, m.id), [m.id]);

  const itemsUsed = Object.entries(used).filter(([, q]) => q > 0).map(([itemCode, quantity]) => ({ itemCode, quantity }));
  const returning = m.bag.map((s) => ({ itemCode: s.itemCode, quantity: s.quantity - (used[s.itemCode] ?? 0) })).filter((s) => s.quantity > 0);

  return (
    <>
      <section>
        <h4>Party</h4>
        <Leader m={m} suffix={`out since ${timeAgo(m.departedAt)}`} />
        <div className="row" style={{ gap: 8 }}>
          {m.party.map((p) => <div key={p.recruitId} className="who"><Portrait speciesId={p.speciesId} small /><span style={{ fontSize: 13 }}>{p.name} <span className="faint">Lv. {p.level}</span></span></div>)}
        </div>
      </section>
      <section className="stack">
        <h4>Report</h4>
        <Seg label="Outcome" value={outcome} onChange={setOutcome} options={[{ value: "COMPLETED", label: "Completed" }, { value: "FAILED", label: "Party fainted" }]} />
        {outcome === "COMPLETED" ? (
          <div>
            {m.bag.length === 0 && <div className="faint" style={{ fontSize: 14 }}>Nothing was packed.</div>}
            {m.bag.length > 0 && <div className="faint" style={{ fontSize: 12, marginBottom: 4 }}>Items used during the mission</div>}
            {m.bag.map((s) => (
              <div key={s.itemCode} className="bag-row">
                <ItemGlyph code={s.itemCode} />
                <span>{itemName(s.itemCode)} <span className="faint">({s.quantity} packed)</span></span>
                <span />
                <Stepper label={`${itemName(s.itemCode)} used`} value={used[s.itemCode] ?? 0} max={s.quantity} onChange={(v) => setUsed({ ...used, [s.itemCode]: v })} />
              </div>
            ))}
            <div style={{ fontSize: 13, marginTop: 6 }}>Back to storage: <Items stacks={returning} /></div>
          </div>
        ) : (
          <p className="bad" style={{ margin: 0, fontSize: 14 }}>
            No reward. Everything in the bag is lost{m.bag.length > 0 && <>: <Items stacks={m.bag} /></>}.
          </p>
        )}
        <label className="field">
          <span>Note</span>
          <textarea value={note} onChange={(e) => setNote(e.target.value)} maxLength={280} placeholder="What happened down there (optional)" />
        </label>
      </section>
      {outcome === "COMPLETED" && preview.data && (
        <section>
          <h4>Payout</h4>
          <table className="split-table">
            <tbody>
              <tr><td>Treasury</td><td><Poke n={preview.data.treasury} /></td></tr>
              {preview.data.shares.map((s) => <tr key={s.membershipId}><td><span className="row" style={{ gap: 6 }}><Portrait speciesId={s.speciesId} small />{s.displayName}</span></td><td><Poke n={s.amount} /></td></tr>)}
            </tbody>
          </table>
          {preview.data.levelUps.length > 0 && (
            <div style={{ fontSize: 13, marginTop: 6 }}>{preview.data.levelUps.map((l) => `${l.name} Lv. ${l.from}→${l.to}`).join(" · ")}</div>
          )}
          {preview.data.rankAfter !== preview.data.rankBefore && <div className="good" style={{ fontSize: 13 }}>The team ranks up.</div>}
        </section>
      )}
      <section className="stack">
        <ErrorLine error={error} />
        <button className={`btn ${outcome === "COMPLETED" ? "primary" : "danger"}`} disabled={busy}
          onClick={() => run(() => api.report(team.id, m.id, outcome, outcome === "COMPLETED" ? itemsUsed : [], note || null), outcome === "COMPLETED" ? "Mission completed" : "Mission marked as failed")}>
          {outcome === "COMPLETED" ? "File report and pay out" : "File report"}
        </button>
      </section>
    </>
  );
}

function ClosedSummary({ m }: { m: Mission }) {
  const { api, team, go } = useApp();
  const entries = useLoad(() => api.ledger(team.id, { missionId: m.id, size: 100 }), [m.id]);
  return (
    <>
      {m.party.length > 0 && (
        <section>
          <h4>Party</h4>
          <Leader m={m} />
          <div className="row" style={{ gap: 8 }}>
            {m.party.map((p) => <div key={p.recruitId} className="who"><Portrait speciesId={p.speciesId} small /><span style={{ fontSize: 13 }}>{p.name}</span></div>)}
          </div>
        </section>
      )}
      {m.outcomeNote && <section><h4>Note</h4><p style={{ margin: 0 }}>{m.outcomeNote}</p></section>}
      <section>
        <h4>Ledger <span className="spacer" /><button className="btn small ghost" onClick={() => go("ledger", { missionId: m.id })}>Open in ledger</button></h4>
        {entries.data && entries.data.items.length === 0 && <div className="faint" style={{ fontSize: 14 }}>No movements.</div>}
        <MovementList entries={entries.data?.items ?? []} />
      </section>
    </>
  );
}

function MovementList({ entries }: { entries: LedgerEntry[] }) {
  const rows = entries.filter((e) => e.accountKey !== "world").slice().reverse();
  return (
    <table className="split-table">
      <tbody>
        {rows.map((e) => (
          <tr key={e.id}>
            <td><span className="acct">{e.accountKey}</span> <span className="faint" style={{ fontSize: 12 }}>{e.reason.toLowerCase().replace("_", " ")}</span></td>
            <td className={e.delta > 0 ? "delta-pos" : "delta-neg"}>
              {e.asset === "POKE" ? <Poke n={e.delta} signed /> : <span className="num">{e.delta > 0 ? "+" : ""}{fmt(e.delta)} {item(e.asset)?.name}</span>}
            </td>
          </tr>
        ))}
      </tbody>
    </table>
  );
}

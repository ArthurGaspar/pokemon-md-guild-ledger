import { catalog, difficultyLabel, nextRank, rank as rankOf } from "../api/catalog";
import { Diff, Poke, Portrait, RankEmblem, fmt, timeAgo, useApp, useLoad } from "../ui";

export function Base() {
  const { api, team, go } = useApp();
  const missions = useLoad(() => api.missions(team.id), [team.id]);
  const events = useLoad(() => api.events(team.id, 30), [team.id]);
  const storage = useLoad(() => api.storage(team.id), [team.id]);

  const all = missions.data ?? [];
  const field = all.filter((m) => m.status === "DEPARTED");
  const planned = all.filter((m) => m.status === "PLANNED");
  const posted = all.filter((m) => m.status === "POSTED");
  const closed = all.filter((m) => m.status === "COMPLETED" || m.status === "FAILED");
  const completed = closed.filter((m) => m.status === "COMPLETED").length;

  const current = rankOf(team.rank)!;
  const next = nextRank(current);
  const index = catalog.ranks.indexOf(current);
  const progress = next ? (team.rankPoints - current.minPoints) / (next.minPoints - current.minPoints) : 1;

  return (
    <>
      <div className="page-head">
        <h1>{team.name}</h1>
        <RankEmblem code={team.rank} />
        <span className="spacer" />
        <span className="faint" style={{ fontSize: 13 }}>Founded {new Date(team.createdAt).toLocaleDateString()}</span>
      </div>

      <div className="stat-row">
        <div className="window stat">
          <div className="label">Treasury</div>
          <div className="value"><Poke n={team.treasury} /></div>
          <div className="sub">{team.treasuryCutPct}% of each reward</div>
        </div>
        <div className="window stat">
          <div className="label">Your wallet</div>
          <div className="value"><Poke n={storage.data?.myWallet ?? 0} /></div>
          <div className="sub">{team.splitMode === "EQUAL" ? "Equal split" : "Split by party slots"}</div>
        </div>
        <div className="window stat">
          <div className="label">Storage</div>
          <div className="value num">{fmt((storage.data?.items ?? []).reduce((a, s) => a + s.quantity, 0))}</div>
          <div className="sub">items · ref. value <Poke n={storage.data?.storageValue ?? 0} /></div>
        </div>
        <div className="window stat">
          <div className="label">Record</div>
          <div className="value num">{completed}<span className="faint" style={{ fontSize: 16 }}> / {closed.length}</span></div>
          <div className="sub">missions completed</div>
        </div>
      </div>

      <div className="base-grid">
        <div className="stack" style={{ gap: 16 }}>
          <section className="window">
            <h2>In the field <span className="spacer" /><span className="faint">{field.length}</span></h2>
            {field.length === 0 && <div className="empty">No party is out right now.</div>}
            <div className="stack">
              {field.map((m) => (
                <div key={m.id} className="inset row" style={{ alignItems: "center" }}>
                  <Diff code={m.difficulty} />
                  <div style={{ flex: 1, minWidth: 180 }}>
                    <b>{m.title}</b>
                    <div className="dim row" style={{ fontSize: 13, gap: 6 }}>
                      {m.dungeonName} {m.floorLabel} · {m.leader && <Portrait speciesId={m.leader.speciesId} small />}led by {m.leader?.displayName} · left {timeAgo(m.departedAt)}
                    </div>
                  </div>
                  <div className="row" style={{ gap: 4 }}>
                    {m.party.map((p) => <Portrait key={p.recruitId} speciesId={p.speciesId} small label={`${p.name} Lv. ${p.level}`} />)}
                  </div>
                  <button className="btn small" onClick={() => go("board", { m: m.id })}>Report</button>
                </div>
              ))}
            </div>
          </section>

          <section className="window">
            <h2>On the board</h2>
            <div className="grid2">
              <button className="inset" style={{ textAlign: "left", cursor: "pointer" }} onClick={() => go("board")}>
                <div className="pixel" style={{ fontSize: 22 }}>{planned.length}</div>
                <div className="dim" style={{ fontSize: 13 }}>planned, waiting to depart</div>
                <div className="row" style={{ gap: 4, marginTop: 6 }}>{planned.map((m) => <Diff key={m.id} code={m.difficulty} small />)}</div>
              </button>
              <button className="inset" style={{ textAlign: "left", cursor: "pointer" }} onClick={() => go("board")}>
                <div className="pixel" style={{ fontSize: 22 }}>{posted.length}</div>
                <div className="dim" style={{ fontSize: 13 }}>posted, no party yet</div>
                <div className="row" style={{ gap: 4, marginTop: 6 }}>{posted.map((m) => <Diff key={m.id} code={m.difficulty} small />)}</div>
              </button>
            </div>
          </section>

          <section className="window">
            <h2>Rank <span className="spacer" /><span className="num faint">{fmt(team.rankPoints)} pts</span></h2>
            <div className="rank-track" aria-hidden>
              {catalog.ranks.map((r, i) => (
                <span key={r.code} title={`${r.name}: ${fmt(r.minPoints)}`}
                  className={i < index || (i === index && !next) ? "on" : i === index ? "part" : ""}
                  style={i === index && next ? { ["--p" as string]: `${Math.round(progress * 100)}%` } : undefined} />
              ))}
            </div>
            {next ? (
              <>
                <p style={{ margin: "4px 0 8px" }}>
                  <b className="num">{fmt(next.minPoints - team.rankPoints)}</b> points to <RankEmblem code={next.code} />
                </p>
                <dl className="facts">
                  <dt>Hardest job</dt><dd>{difficultyLabel(current.maxDifficulty)} → <b>{difficultyLabel(next.maxDifficulty)}</b></dd>
                  <dt>Roster</dt><dd>{current.rosterCap} → <b>{next.rosterCap}</b> recruits</dd>
                  <dt>Bag</dt><dd>{current.bagCap} → <b>{next.bagCap}</b> items</dd>
                </dl>
              </>
            ) : <p className="dim">Highest rank reached.</p>}
          </section>
        </div>

        <section className="window">
          <h2>Logbook</h2>
          {events.data && events.data.length === 0 && <div className="empty">Nothing yet.</div>}
          <ul className="log">
            {(events.data ?? []).map((e) => (
              <li key={e.id}>
                <time dateTime={e.createdAt} title={new Date(e.createdAt).toLocaleString()}>{timeAgo(e.createdAt)}</time>
                <div>{e.summary}{e.actor && !e.summary.startsWith(e.actor) && <div className="actor">{e.actor}</div>}</div>
              </li>
            ))}
          </ul>
          <button className="btn small ghost" onClick={() => go("ledger")}>Full ledger →</button>
        </section>
      </div>
    </>
  );
}

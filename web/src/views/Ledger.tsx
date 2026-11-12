import { useState } from "react";
import { catalog, itemName } from "../api/catalog";
import type { LedgerEntry, Reason, Reconciliation } from "../api/types";
import { ErrorLine, ItemGlyph, Poke, fmt, stamp, useApp, useLoad } from "../ui";

const REASONS: Reason[] = ["MISSION_REWARD", "MISSION_PACK", "MISSION_RETURN", "ITEM_USED", "ITEM_LOST", "DEPOSIT", "DISCARD", "CONTRIBUTION", "PAYOUT", "CASH_OUT"];
const REASON_LABEL: Record<Reason, string> = {
  MISSION_REWARD: "reward", MISSION_PACK: "packed", MISSION_RETURN: "returned", ITEM_USED: "used", ITEM_LOST: "lost",
  DEPOSIT: "deposit", DISCARD: "discard", CONTRIBUTION: "contribution", PAYOUT: "payout", CASH_OUT: "cash-out",
};
const PAGE = 50;

export function Ledger({ query }: { query: URLSearchParams }) {
  const { api, team, go } = useApp();
  const [account, setAccount] = useState(query.get("account") ?? "");
  const [asset, setAsset] = useState(query.get("asset") ?? "");
  const [reason, setReason] = useState(query.get("reason") ?? "");
  const missionId = query.get("missionId") ? Number(query.get("missionId")) : undefined;
  const [hideWorld, setHideWorld] = useState(true);
  const [page, setPage] = useState(0);
  const [check, setCheck] = useState<Reconciliation | null>(null);
  const [checkError, setCheckError] = useState<string | null>(null);

  const filters = { account, asset, reason, missionId };
  const entries = useLoad(() => api.ledger(team.id, { ...filters, page, size: PAGE }), [team.id, account, asset, reason, missionId, page]);
  const members = useLoad(() => api.members(team.id), [team.id]);
  const missions = useLoad(() => api.missions(team.id), [team.id]);

  const walletName = new Map((members.data ?? []).map((m) => [`wallet:${m.membershipId}`, m.displayName]));
  const missionTitle = new Map((missions.data ?? []).map((m) => [m.id, m.title]));
  const label = (key: string) => {
    if (walletName.has(key)) return `${walletName.get(key)}'s wallet`;
    if (key.startsWith("wallet:")) return "former member's wallet";
    if (key.startsWith("bag:")) return `bag · ${missionTitle.get(Number(key.slice(4))) ?? key}`;
    return key;
  };

  const rows = (entries.data?.items ?? []).filter((e) => !hideWorld || account === "world" || e.accountKey !== "world");
  const total = entries.data?.total ?? 0;
  const set = (fn: (v: string) => void) => (v: string) => { fn(v); setPage(0); };

  const reconcile = async () => {
    setCheckError(null);
    try { setCheck(await api.reconcile(team.id)); } catch (e) { setCheckError((e as Error).message); }
  };

  const exportCsv = async () => {
    const all: LedgerEntry[] = [];
    for (let p = 0; ; p++) {
      const chunk = await api.ledger(team.id, { ...filters, page: p, size: 200 });
      all.push(...chunk.items);
      if (all.length >= chunk.total || chunk.items.length === 0) break;
    }
    const esc = (v: unknown) => `"${String(v ?? "").replace(/"/g, '""')}"`;
    const lines = [["id", "time", "transaction", "account", "asset", "delta", "balance_after", "reason", "mission", "memo", "by"].join(",")];
    for (const e of all) lines.push([e.id, e.createdAt, e.txn, e.accountKey, e.asset, e.delta, e.balanceAfter, e.reason, e.missionId ?? "", e.memo ?? "", e.actor ?? ""].map(esc).join(","));
    const url = URL.createObjectURL(new Blob([lines.join("\n")], { type: "text/csv" }));
    const a = Object.assign(document.createElement("a"), { href: url, download: `${team.name.replace(/\W+/g, "-").toLowerCase()}-ledger.csv` });
    a.click();
    URL.revokeObjectURL(url);
  };

  // alternate shading per transaction so the legs of one movement read together
  let shade = false;
  let lastTxn = "";

  return (
    <>
      <div className="page-head">
        <h1>Ledger</h1>
        <span className="dim num">{fmt(total)} entries</span>
        <span className="spacer" />
        <div className="reconcile">
          {check && (
            <span className={`seal ${check.ok ? "good" : "bad"}`} title={check.problems.join("\n")}>
              {check.ok ? `Balanced · ${fmt(check.transactions)} transactions, ${check.accounts} accounts` : `${check.problems.length} problem(s)`}
            </span>
          )}
          <button className="btn" onClick={reconcile}>Reconcile</button>
          <button className="btn" onClick={exportCsv}>Export CSV</button>
        </div>
      </div>
      <ErrorLine error={checkError} />
      {check && !check.ok && <div className="window"><ul style={{ margin: 0 }}>{check.problems.map((p) => <li key={p} className="bad">{p}</li>)}</ul></div>}

      <section className="window">
        <div className="row" style={{ marginBottom: 10 }}>
          <select value={account} onChange={(e) => set(setAccount)(e.target.value)} style={{ width: "auto" }} aria-label="Account">
            <option value="">All accounts</option>
            <option value="treasury">Treasury</option>
            <option value="storage">Storage</option>
            <option value="wallet:">All wallets</option>
            {(members.data ?? []).map((m) => <option key={m.membershipId} value={`wallet:${m.membershipId}`}>{m.displayName}'s wallet</option>)}
            <option value="bag:">Mission bags</option>
            <option value="world">World (in/out)</option>
          </select>
          <select value={asset} onChange={(e) => set(setAsset)(e.target.value)} style={{ width: "auto" }} aria-label="Asset">
            <option value="">Poké and items</option>
            <option value="POKE">Poké</option>
            {catalog.items.map((i) => <option key={i.code} value={i.code}>{i.name}</option>)}
          </select>
          <select value={reason} onChange={(e) => set(setReason)(e.target.value)} style={{ width: "auto" }} aria-label="Reason">
            <option value="">Any reason</option>
            {REASONS.map((r) => <option key={r} value={r}>{REASON_LABEL[r]}</option>)}
          </select>
          {missionId && (
            <span className="chip">mission: {missionTitle.get(missionId) ?? `#${missionId}`} <button className="btn small ghost" style={{ padding: 0 }} onClick={() => go("ledger")} aria-label="Clear mission filter">✕</button></span>
          )}
          <label className="row" style={{ gap: 6, fontSize: 13 }}>
            <input type="checkbox" checked={hideWorld} onChange={(e) => setHideWorld(e.target.checked)} /> Hide world legs
          </label>
        </div>
        <ErrorLine error={entries.error} />
        <div className="table-wrap">
          <table className="data">
            <thead>
              <tr><th>When</th><th>Account</th><th>Asset</th><th className="r">Change</th><th className="r">Balance</th><th>Reason</th><th>Memo</th><th>By</th></tr>
            </thead>
            <tbody>
              {rows.map((e) => {
                const first = e.txn !== lastTxn;
                if (first) { shade = !shade; lastTxn = e.txn; }
                return (
                  <tr key={e.id} className={shade ? "txn-a" : undefined}>
                    <td className="nowrap">{first ? <>{stamp(e.createdAt)}<br /><span className="txn-id" title={e.txn}>{e.txn.slice(0, 8)}</span></> : null}</td>
                    <td><span className="acct">{label(e.accountKey)}</span></td>
                    <td className="nowrap">{e.asset === "POKE" ? <span className="dim">Poké</span> : <span className="row" style={{ gap: 5, display: "inline-flex" }}><ItemGlyph code={e.asset} />{itemName(e.asset)}</span>}</td>
                    <td className={`r ${e.delta > 0 ? "delta-pos" : "delta-neg"}`}>{e.asset === "POKE" ? <Poke n={e.delta} signed /> : <span className="num pixel">{e.delta > 0 ? "+" : ""}{e.delta}</span>}</td>
                    <td className="r num">{e.accountKey === "world" ? <span className="faint">—</span> : fmt(e.balanceAfter)}</td>
                    <td className="nowrap">{REASON_LABEL[e.reason]}</td>
                    <td style={{ maxWidth: 260 }}>
                      {e.missionId && first ? <button className="btn small ghost" style={{ padding: "0 4px", textAlign: "left", whiteSpace: "normal" }} onClick={() => go("board", { m: e.missionId! })}>{missionTitle.get(e.missionId) ?? `Mission #${e.missionId}`}</button>
                        : first ? <span className="dim">{e.memo}</span> : null}
                    </td>
                    <td className="dim nowrap">{first ? e.actor : null}</td>
                  </tr>
                );
              })}
            </tbody>
          </table>
        </div>
        {entries.data && rows.length === 0 && <div className="empty">No entries match.</div>}
        <div className="row" style={{ marginTop: 10 }}>
          <button className="btn small" disabled={page === 0} onClick={() => setPage(page - 1)}>Newer</button>
          <span className="faint num" style={{ fontSize: 13 }}>{total === 0 ? 0 : page * PAGE + 1}–{Math.min(total, (page + 1) * PAGE)} of {fmt(total)}</span>
          <button className="btn small" disabled={(page + 1) * PAGE >= total} onClick={() => setPage(page + 1)}>Older</button>
          <span className="spacer" />
          <small className="faint">Every movement is a transaction whose legs sum to zero; "world" is the other side of anything entering or leaving the team.</small>
        </div>
      </section>
    </>
  );
}

import { useState, type FormEvent } from "react";
import { catalog, item, itemName } from "../api/catalog";
import { ErrorLine, ItemGlyph, Poke, Portrait, Seg, can, fmt, useAction, useApp, useLoad } from "../ui";
import { Items } from "./common";

export function Storage() {
  const { api, team, go } = useApp();
  const storage = useLoad(() => api.storage(team.id), [team.id]);
  const members = useLoad(() => api.members(team.id), [team.id]);
  const [selected, setSelected] = useState(catalog.items[0].code);

  const s = storage.data;
  const total = (s?.items ?? []).reduce((a, x) => a + x.quantity, 0);

  return (
    <>
      <div className="page-head">
        <h1>Storage</h1>
        <span className="dim">{fmt(total)} items · ref. value <Poke n={s?.storageValue ?? 0} /></span>
        <span className="spacer" />
        <button className="btn small ghost" onClick={() => go("ledger", { account: "storage" })}>Storage history →</button>
      </div>
      <ErrorLine error={storage.error} />

      <div className="storage-grid">
        <div className="stack" style={{ gap: 16 }}>
          <section className="window">
            <h2>Shelves</h2>
            {s && s.items.length === 0 && <div className="empty">Storage is empty.</div>}
            <div className="shelf">
              {(s?.items ?? []).map((x) => {
                const it = item(x.itemCode)!;
                return (
                  <button key={x.itemCode} className="shelf-item" aria-pressed={selected === x.itemCode} onClick={() => setSelected(x.itemCode)}>
                    <ItemGlyph code={x.itemCode} />
                    <span>{it.name}<small>{it.category} · {it.refValue} P each</small></span>
                    <span className="qty num">{x.quantity}</span>
                  </button>
                );
              })}
            </div>
          </section>

          <ItemForm selected={selected} setSelected={setSelected} stock={new Map((s?.items ?? []).map((x) => [x.itemCode, x.quantity]))} />

          <section className="window">
            <h2>Packed and out</h2>
            {s && s.inTheField.length === 0 && <div className="empty">No bags are out.</div>}
            <div className="stack">
              {(s?.inTheField ?? []).map((b) => (
                <button key={b.missionId} className="inset" style={{ textAlign: "left", cursor: "pointer" }} onClick={() => go("board", { m: b.missionId })}>
                  <b>{b.title}</b>
                  <div style={{ fontSize: 13, marginTop: 4 }}><Items stacks={b.items} empty="Nothing packed" /></div>
                </button>
              ))}
            </div>
          </section>
        </div>

        <div className="stack" style={{ gap: 16 }}>
          <section className="window">
            <h2>Poké</h2>
            <div className="money">
              <span>Treasury</span><span className="big num">{fmt(s?.treasury ?? 0)}</span>
              <span>Your wallet</span><span className="big num" style={{ fontSize: 22 }}>{fmt(s?.myWallet ?? 0)}</span>
            </div>
          </section>
          <MoneyForms wallet={s?.myWallet ?? 0} treasury={s?.treasury ?? 0} members={members.data ?? []} />
          <section className="window">
            <h2>Wallets</h2>
            <table className="data">
              <tbody>
                {(members.data ?? []).map((m) => (
                  <tr key={m.membershipId}>
                    <td><div className="who"><Portrait speciesId={m.speciesId} small /><span>{m.displayName} <span className={`role role-${m.role}`}>{m.role.toLowerCase()}</span></span></div></td>
                    <td className="r"><Poke n={m.wallet} /></td>
                  </tr>
                ))}
              </tbody>
            </table>
            <small className="faint">Mission shares land here. Leaving the team cashes the wallet out.</small>
          </section>
        </div>
      </div>
    </>
  );
}

function ItemForm({ selected, setSelected, stock }: { selected: string; setSelected: (c: string) => void; stock: Map<string, number> }) {
  const { api, team } = useApp();
  const { run, busy, error } = useAction();
  const [qty, setQty] = useState(1);
  const [memo, setMemo] = useState("");
  const officer = can(team.myRole, "OFFICER");
  const have = stock.get(selected) ?? 0;

  const act = (kind: "deposit" | "discard") => (e?: FormEvent) => {
    e?.preventDefault();
    const fn = kind === "deposit" ? api.depositItem : api.discardItem;
    run(() => fn(team.id, selected, qty, memo || null), `${kind === "deposit" ? "Stored" : "Discarded"} ${qty} × ${itemName(selected)}`)
      .then((ok) => ok && setMemo(""));
  };

  return (
    <form className="window" onSubmit={act("deposit")}>
      <h2>Move items</h2>
      <div className="row" style={{ alignItems: "end" }}>
        <label className="field" style={{ flex: "2 1 180px" }}>
          <span>Item</span>
          <select value={selected} onChange={(e) => setSelected(e.target.value)}>
            {catalog.items.map((it) => <option key={it.code} value={it.code}>{it.name} ({stock.get(it.code) ?? 0})</option>)}
          </select>
        </label>
        <label className="field" style={{ flex: "0 1 90px" }}>
          <span>Quantity</span>
          <input type="number" min={1} max={999} value={qty} onChange={(e) => setQty(Number(e.target.value))} />
        </label>
        <label className="field" style={{ flex: "2 1 180px" }}>
          <span>Memo</span>
          <input type="text" maxLength={140} value={memo} onChange={(e) => setMemo(e.target.value)} placeholder="Where it came from / why" />
        </label>
      </div>
      <div className="row" style={{ marginTop: 10 }}>
        <button className="btn primary" disabled={busy}>Store</button>
        {officer && <button type="button" className="btn danger" disabled={busy || have < qty} onClick={() => act("discard")()}>Discard</button>}
        <span className="faint" style={{ fontSize: 12 }}>{officer ? "Anyone can store items; officers can discard." : "Anyone can store items; only officers discard."}</span>
      </div>
      <ErrorLine error={error} />
    </form>
  );
}

type MoneyAction = "contribute" | "cashout" | "deposit" | "payout";

function MoneyForms({ wallet, treasury, members }: { wallet: number; treasury: number; members: { membershipId: number; displayName: string }[] }) {
  const { api, team } = useApp();
  const { run, busy, error, setError } = useAction();
  const [action, setAction] = useState<MoneyAction>("contribute");
  const [amount, setAmount] = useState(100);
  const [memo, setMemo] = useState("");
  const [target, setTarget] = useState<number>(members[0]?.membershipId ?? 0);

  const options: { value: MoneyAction; label: string }[] = [
    { value: "contribute", label: "Give to treasury" },
    { value: "cashout", label: "Cash out" },
  ];
  if (can(team.myRole, "OFFICER")) options.push({ value: "deposit", label: "Record income" });
  if (team.myRole === "LEADER") options.push({ value: "payout", label: "Pay a member" });

  const help: Record<MoneyAction, string> = {
    contribute: `From your wallet (${fmt(wallet)} P) into the treasury.`,
    cashout: `Take Poké out of the team ledger, e.g. to spend in town. Wallet: ${fmt(wallet)} P.`,
    deposit: "Poké that came from outside: sold loot, a client's tip.",
    payout: `From the treasury (${fmt(treasury)} P) to a member's wallet.`,
  };

  const submit = (e: FormEvent) => {
    e.preventDefault();
    const m = memo || null;
    const call = action === "contribute" ? () => api.contribute(team.id, amount, m)
      : action === "cashout" ? () => api.cashOut(team.id, amount)
      : action === "deposit" ? () => api.depositPoke(team.id, amount, m)
      : () => api.payout(team.id, target || members[0]?.membershipId, amount, m);
    run(call, "Recorded in the ledger").then((ok) => ok && setMemo(""));
  };

  return (
    <form className="window stack" onSubmit={submit}>
      <h2 style={{ marginBottom: 0 }}>Move Poké</h2>
      <Seg label="Action" value={action} onChange={(v) => { setAction(v); setError(null); }} options={options} />
      <p className="dim" style={{ margin: 0, fontSize: 13 }}>{help[action]}</p>
      <div className="grid2">
        {action === "payout" && (
          <label className="field">
            <span>To</span>
            <select value={target} onChange={(e) => setTarget(Number(e.target.value))}>
              {members.map((m) => <option key={m.membershipId} value={m.membershipId}>{m.displayName}</option>)}
            </select>
          </label>
        )}
        <label className="field">
          <span>Amount</span>
          <input type="number" min={1} max={1000000} value={amount} onChange={(e) => setAmount(Number(e.target.value))} />
        </label>
        {action !== "cashout" && (
          <label className="field" style={action === "payout" ? { gridColumn: "1 / -1" } : undefined}>
            <span>Memo</span>
            <input type="text" maxLength={140} value={memo} onChange={(e) => setMemo(e.target.value)} placeholder="Shows in the ledger" />
          </label>
        )}
      </div>
      <ErrorLine error={error} />
      <div><button className="btn primary" disabled={busy || amount < 1}>Record</button></div>
    </form>
  );
}

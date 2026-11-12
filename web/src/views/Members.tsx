import { useState, type FormEvent } from "react";
import type { MemberView, Role } from "../api/types";
import { speciesById } from "../api/catalog";
import { ErrorLine, Poke, Portrait, can, timeAgo, useAction, useApp, useLoad } from "../ui";

export function Members() {
  const { api, team } = useApp();
  const members = useLoad(() => api.members(team.id), [team.id]);
  const officer = can(team.myRole, "OFFICER");

  return (
    <>
      <div className="page-head">
        <h1>Members</h1>
        <span className="dim num">{team.memberCount} of 12</span>
      </div>
      <section className="window">
        <ErrorLine error={members.error} />
        <div className="table-wrap">
          <table className="data">
            <thead><tr><th>Member</th><th>Role</th><th className="r">Wallet</th><th className="r">Recruits</th><th>Joined</th><th /></tr></thead>
            <tbody>{(members.data ?? []).map((m) => <MemberRow key={m.membershipId} m={m} />)}</tbody>
          </table>
        </div>
      </section>
      {officer && <Invitations />}
      <Permissions />
    </>
  );
}

function MemberRow({ m }: { m: MemberView }) {
  const { api, team } = useApp();
  const { run, busy, error } = useAction();
  const self = m.membershipId === team.myMembershipId;
  const leader = team.myRole === "LEADER";
  const canRemove = !self && (leader || (team.myRole === "OFFICER" && m.role === "MEMBER"));

  const leave = () => {
    if (!confirm(`Leave ${team.name}? Your wallet (${m.wallet} P) is cashed out.`)) return;
    run(() => api.removeMember(team.id, m.membershipId)).then((ok) => { if (ok) location.hash = ""; });
  };

  return (
    <tr>
      <td>
        <div className="who">
          <Portrait speciesId={m.speciesId} />
          <div><b>{m.displayName}{self && <span className="faint"> (you)</span>}</b><small>{speciesById(m.speciesId)?.name} · @{m.username}</small></div>
        </div>
      </td>
      <td><span className={`role role-${m.role}`}>{m.role.toLowerCase()}</span></td>
      <td className="r"><Poke n={m.wallet} /></td>
      <td className="r num">{m.recruits}</td>
      <td className="dim">{timeAgo(m.joinedAt)}</td>
      <td className="r">
        <div className="row" style={{ justifyContent: "flex-end", gap: 4 }}>
          {leader && !self && m.role === "MEMBER" && <button className="btn small ghost" disabled={busy} onClick={() => run(() => api.changeRole(team.id, m.membershipId, "OFFICER"), `${m.displayName} is now an officer`)}>Make officer</button>}
          {leader && !self && m.role === "OFFICER" && <button className="btn small ghost" disabled={busy} onClick={() => run(() => api.changeRole(team.id, m.membershipId, "MEMBER"), `${m.displayName} is now a member`)}>Make member</button>}
          {leader && !self && <button className="btn small ghost" disabled={busy} onClick={() => confirm(`Hand ${team.name} over to ${m.displayName}? You become an officer.`) && run(() => api.transferLeadership(team.id, m.membershipId), `${m.displayName} leads the team now`)}>Hand over</button>}
          {canRemove && <button className="btn small ghost danger" disabled={busy} onClick={() => confirm(`Remove ${m.displayName}? Their wallet (${m.wallet} P) is cashed out.`) && run(() => api.removeMember(team.id, m.membershipId), `${m.displayName} removed`)}>Remove</button>}
          {self && m.role !== "LEADER" && <button className="btn small ghost danger" disabled={busy} onClick={leave}>Leave team</button>}
        </div>
        <ErrorLine error={error} />
      </td>
    </tr>
  );
}

function Invitations() {
  const { api, team } = useApp();
  const list = useLoad(() => api.teamInvitations(team.id), [team.id]);
  const { run, busy, error } = useAction();
  const [username, setUsername] = useState("");
  const [role, setRole] = useState<Role>("MEMBER");

  const submit = (e: FormEvent) => {
    e.preventDefault();
    run(() => api.invite(team.id, username, role), `Invitation sent to ${username}`).then((ok) => ok && setUsername(""));
  };

  return (
    <section className="window">
      <h2>Invitations</h2>
      <form className="row" style={{ alignItems: "end" }} onSubmit={submit}>
        <label className="field" style={{ flex: "2 1 180px" }}>
          <span>Username</span>
          <input type="text" value={username} onChange={(e) => setUsername(e.target.value)} required />
        </label>
        <label className="field" style={{ flex: "1 1 120px" }}>
          <span>As</span>
          <select value={role} onChange={(e) => setRole(e.target.value as Role)}>
            <option value="MEMBER">Member</option>
            {team.myRole === "LEADER" && <option value="OFFICER">Officer</option>}
          </select>
        </label>
        <button className="btn primary" disabled={busy}>Invite</button>
      </form>
      <ErrorLine error={error} />
      <div className="table-wrap" style={{ marginTop: 12 }}>
        <table className="data">
          <thead><tr><th>Invitee</th><th>As</th><th>Status</th><th>Sent by</th><th>Expires</th><th /></tr></thead>
          <tbody>
            {(list.data ?? []).map((i) => (
              <tr key={i.id}>
                <td><div className="who"><Portrait speciesId={i.invitee.speciesId} small /><span>{i.invitee.displayName} <small className="faint">@{i.invitee.username}</small></span></div></td>
                <td>{i.role.toLowerCase()}</td>
                <td><span className={`status ${i.status === "PENDING" ? "warn" : i.status === "ACCEPTED" ? "good" : "faint"}`}>{i.status.toLowerCase()}</span></td>
                <td className="dim">{i.invitedBy.displayName}, {timeAgo(i.createdAt)}</td>
                <td className="dim">{i.status === "PENDING" ? new Date(i.expiresAt).toLocaleDateString() : "—"}</td>
                <td className="r">{i.status === "PENDING" && <button className="btn small ghost danger" disabled={busy} onClick={() => run(() => api.revokeInvitation(team.id, i.id), "Invitation revoked")}>Revoke</button>}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
      {list.data && list.data.length === 0 && <div className="empty">No invitations sent.</div>}
    </section>
  );
}

const MATRIX: [string, boolean, boolean, boolean][] = [
  ["Recruit, store items, give Poké to the treasury", true, true, true],
  ["Depart and report a mission you lead", true, true, true],
  ["Rename or release your own recruits", true, true, true],
  ["Post, plan and take down jobs", false, true, true],
  ["Invite members, remove members", false, true, true],
  ["Discard items, record outside income", false, true, true],
  ["Invite officers, change roles, hand over the team", false, false, true],
  ["Pay members from the treasury, set the reward policy", false, false, true],
];

function Permissions() {
  return (
    <section className="window">
      <h2>Who can do what</h2>
      <div className="table-wrap">
        <table className="data">
          <thead><tr><th /><th>Member</th><th>Officer</th><th>Leader</th></tr></thead>
          <tbody>
            {MATRIX.map(([what, ...who]) => (
              <tr key={what}><td>{what}</td>{who.map((ok, i) => <td key={i} className={ok ? "good" : "faint"}>{ok ? "●" : "—"}</td>)}</tr>
            ))}
          </tbody>
        </table>
      </div>
    </section>
  );
}

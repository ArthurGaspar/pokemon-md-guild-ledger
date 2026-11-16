// The rush: USERS adventurers (10,000 by default) sign in during a warm-up, then all press "join" within the same
// second the expedition opens, and each one plays it out: wait in line, hold a role when admitted, pass the ready
// check (MISS_READY_CHECK of them don't, on purpose, so compensation runs under load), or give up once it's sold out.
//   k6 run expeditions/load/seed-users.js     # once
//   k6 run expeditions/load/rush.js
// Writes expeditions/load/results/summary.md and summary.json. Then check the invariants:
//   sh expeditions/load/verify.sh <expedition id printed at the end>
import http from 'k6/http';
import { check, sleep } from 'k6';
import exec from 'k6/execution';
import { Counter, Trend } from 'k6/metrics';
import { textSummary } from 'https://jslib.k6.io/k6-summary/0.1.0/index.js';

const API = __ENV.API || 'http://localhost:8080';
const KEYCLOAK = __ENV.KEYCLOAK || 'http://localhost:8180';
const USERS = Number(__ENV.USERS || 10000);
const WARMUP = Number(__ENV.WARMUP || 120); // seconds over which the logins are spread
const PARTIES = Number(__ENV.PARTIES || 50); // 50 parties of 4: 200 slots for 10,000 people
const MISS = Number(__ENV.MISS_READY_CHECK || 0.1);
const PATIENCE = Number(__ENV.PATIENCE || 120); // how long a waiter keeps trying after the expedition sells out
const POLL = 2; // seconds between position checks, like a browser would
const RESULTS = __ENV.RESULTS || 'expeditions/load/results'; // relative to where k6 runs

export const options = {
  scenarios: {
    rush: { executor: 'per-vu-iterations', vus: USERS, iterations: 1, maxDuration: '25m', gracefulStop: '30s' },
  },
  setupTimeout: '60s',
  discardResponseBodies: false,
  summaryTrendStats: ['avg', 'min', 'med', 'p(90)', 'p(95)', 'p(99)', 'max'],
  thresholds: {
    'http_req_duration{step:join}': ['p(95)<1000', 'p(99)<2500'],
    'http_req_duration{step:poll}': ['p(95)<500'],
    'http_req_duration{step:hold}': ['p(95)<1000'],
    http_req_failed: ['rate<0.01'],
    checks: ['rate>0.99'],
  },
};

// 409s are answers (role full, sold out, ready check over), not failures
http.setResponseCallback(http.expectedStatuses({ min: 200, max: 299 }, 409));

const timeToAdmission = new Trend('time_to_admission', true);
const timeToLock = new Trend('time_to_party_locked', true);
const joined = new Counter('joined');
const admitted = new Counter('admitted');
const held = new Counter('held');
const roleFallbacks = new Counter('role_fallbacks');
const confirmed = new Counter('confirmed');
const missedOnPurpose = new Counter('missed_ready_check_on_purpose');
const reverted = new Counter('saw_party_revert');
const locked = new Counter('in_locked_party');
const lapsed = new Counter('admission_lapsed');
const gaveUp = new Counter('gave_up_sold_out');
const rateLimited = new Counter('rate_limited');

function racer(n) {
  return `racer-${String(n).padStart(5, '0')}`;
}

function login(username, password) {
  const r = http.post(`${KEYCLOAK}/realms/guild/protocol/openid-connect/token`,
    { grant_type: 'password', client_id: 'expeditions', username, password }, { tags: { step: 'login' } });
  check(r, { 'signed in': (res) => res.status === 200 });
  return r.status === 200 ? r.json('access_token') : null;
}

function api(token, method, path, body, step) {
  const params = { headers: { Authorization: `Bearer ${token}` }, tags: { step } };
  if (body !== undefined) {
    params.headers['Content-Type'] = 'application/json';
  }
  const r = http.request(method, `${API}${path}`, body === undefined ? null : JSON.stringify(body), params);
  if (r.status === 429) {
    rateLimited.add(1);
  }
  return r;
}

const jitter = (seconds) => sleep(seconds * (0.75 + Math.random() * 0.5));

export function setup() {
  const staff = login('wigglytuff', 'lantern-123');
  const opensAt = new Date(Date.now() + (WARMUP + 20) * 1000);
  const r = api(staff, 'POST', '/api/expeditions', {
    code: `rush-${Date.now()}`,
    name: `The Rush: ${USERS} adventurers, ${PARTIES * 4} slots`,
    dungeon: 'zero-isle-north',
    difficulty: 'STAR',
    opensAt: opensAt.toISOString(),
    parties: PARTIES,
    composition: { TANK: 1, HEALER: 1, DAMAGE: 2 },
  }, 'setup');
  if (!check(r, { 'expedition published': (res) => res.status === 201 })) {
    throw new Error(`Publishing failed: ${r.status} ${r.body}`);
  }
  console.log(`Expedition ${r.json('id')} opens at ${opensAt.toISOString()}`);
  return { expedition: r.json('id'), opensAt: opensAt.getTime() };
}

export default function (data) {
  const exp = data.expedition;
  sleep(Math.random() * WARMUP);
  const token = login(racer(exec.vu.idInTest), 'race-123');
  if (!token) {
    return;
  }

  // everyone presses "join" within the first second
  const untilOpen = (data.opensAt - Date.now()) / 1000;
  if (untilOpen > 0) {
    sleep(untilOpen + Math.random());
  }
  const joinedAt = Date.now();
  let r = api(token, 'POST', `/api/expeditions/${exp}/queue`, undefined, 'join');
  if (!check(r, { 'joined the line': (res) => res.status === 200 })) {
    return;
  }
  joined.add(1);

  // wait for admission
  let admission = null;
  let soldOutSince = null;
  while (admission === null) {
    jitter(POLL);
    r = api(token, 'GET', `/api/expeditions/${exp}/queue`, undefined, 'poll');
    if (r.status !== 200) {
      continue;
    }
    const s = r.json();
    if (s.state === 'ADMITTED') {
      admission = s.admissionToken;
      admitted.add(1);
      timeToAdmission.add(Date.now() - joinedAt);
    } else if (s.state === 'NOT_IN_LINE') {
      // a previous admission lapsed (we were too slow): line up again at the back
      lapsed.add(1);
      api(token, 'POST', `/api/expeditions/${exp}/queue`, undefined, 'join');
    } else if (s.soldOut) {
      soldOutSince = soldOutSince || Date.now();
      if (Date.now() - soldOutSince > PATIENCE * 1000) {
        gaveUp.add(1);
        api(token, 'DELETE', `/api/expeditions/${exp}/queue`, undefined, 'leave');
        return;
      }
    } else {
      soldOutSince = null;
    }
  }

  // hold a role: a preferred one, then whatever is open
  const roles = ['TANK', 'HEALER', 'DAMAGE', 'DAMAGE'];
  const preferred = roles[Math.floor(Math.random() * roles.length)];
  let reservation = null;
  for (const role of [preferred, ...['TANK', 'HEALER', 'DAMAGE'].filter((x) => x !== preferred)]) {
    r = api(token, 'POST', `/api/expeditions/${exp}/reservations`, { role, admissionToken: admission }, 'hold');
    if (r.status === 201) {
      reservation = r.json();
      break;
    }
    const code = r.status === 409 ? r.json('code') : null;
    if (code !== 'reservation.role-full') {
      break; // sold out, or something unexpected
    }
    roleFallbacks.add(1);
  }
  if (reservation === null) {
    gaveUp.add(1);
    return;
  }
  held.add(1);
  const heldAt = Date.now();

  // the ready check, until the party sets out or our place is gone
  const deadline = Date.now() + 12 * 60 * 1000;
  let wasConfirmed = false;
  while (Date.now() < deadline) {
    jitter(POLL);
    r = api(token, 'GET', `/api/reservations/${reservation.id}`, undefined, 'reservation');
    if (r.status !== 200) {
      continue;
    }
    const me = r.json();
    if (me.party.status === 'LOCKED' || ['LOCKED', 'COMPLETED', 'FAILED'].includes(me.status)) {
      locked.add(1);
      timeToLock.add(Date.now() - heldAt);
      return;
    }
    if (!['HELD', 'CONFIRMED'].includes(me.status)) {
      return; // expired or declined
    }
    if (wasConfirmed && me.status === 'HELD') {
      reverted.add(1); // someone missed the check; we keep our place and wait for the next one
      wasConfirmed = false;
    }
    if (me.party.status === 'ASSEMBLED' && me.status === 'HELD') {
      if (Math.random() < MISS) {
        missedOnPurpose.add(1);
        return; // walk away from the screen
      }
      r = api(token, 'POST', `/api/reservations/${reservation.id}/confirm`, undefined, 'confirm');
      if (r.status === 200) {
        confirmed.add(1);
        wasConfirmed = true;
      }
    } else if (me.status === 'CONFIRMED') {
      wasConfirmed = true;
    }
  }
}

export function teardown(data) {
  const staff = login('wigglytuff', 'lantern-123');
  const r = api(staff, 'GET', `/api/expeditions/${data.expedition}/capacity`, undefined, 'teardown');
  console.log(`Expedition ${data.expedition} capacity after the rush: ${r.body}`);
  console.log(`Check the invariants: sh expeditions/load/verify.sh ${data.expedition}`);
}

function metric(data, name, stat) {
  const m = data.metrics[name];
  if (!m) {
    return '–';
  }
  const v = m.values[stat];
  if (v === undefined) {
    return '–';
  }
  if (m.contains === 'time') {
    return v >= 1000 ? `${(v / 1000).toFixed(2)} s` : `${v.toFixed(0)} ms`;
  }
  return Number.isInteger(v) ? String(v) : v.toFixed(4);
}

export function handleSummary(data) {
  const latency = (name) => `| ${name} | ${['med', 'p(95)', 'p(99)', 'max'].map((s) => metric(data, name, s)).join(' | ')} |`;
  const count = (label, name) => `| ${label} | ${metric(data, name, 'count')} |`;
  const md = `# Rush results

${USERS} adventurers, ${PARTIES} parties of 4 (${PARTIES * 4} slots), logins spread over ${WARMUP} s, everyone joins
within one second of opening, ${Math.round(MISS * 100)}% of assembled members skip the ready check on purpose.
Run at ${new Date().toISOString()}.

| Latency | median | p95 | p99 | max |
|---|---|---|---|---|
${latency('http_req_duration{step:join}')}
${latency('http_req_duration{step:poll}')}
${latency('http_req_duration{step:hold}')}
${latency('http_req_duration{step:confirm}')}
${latency('time_to_admission')}
${latency('time_to_party_locked')}

| Outcome | count |
|---|---|
${count('joined the line', 'joined')}
${count('admitted', 'admitted')}
${count('held a slot', 'held')}
${count('switched role because theirs was full', 'role_fallbacks')}
${count('confirmed a ready check', 'confirmed')}
${count('skipped the ready check (on purpose)', 'missed_ready_check_on_purpose')}
${count('saw their party revert and kept their place', 'saw_party_revert')}
${count('ended in a locked party', 'in_locked_party')}
${count('admission lapsed, lined up again', 'admission_lapsed')}
${count('gave up after it sold out', 'gave_up_sold_out')}
${count('rate limited (429)', 'rate_limited')}

| Traffic | |
|---|---|
| requests | ${metric(data, 'http_reqs', 'count')} (${metric(data, 'http_reqs', 'rate')}/s) |
| failed requests (409s are expected answers, not failures) | ${metric(data, 'http_req_failed', 'rate')} |
| peak VUs | ${metric(data, 'vus_max', 'max')} |
`;
  return {
    stdout: textSummary(data, { indent: ' ', enableColors: true }),
    [`${RESULTS}/summary.md`]: md,
    [`${RESULTS}/summary.json`]: JSON.stringify(data, null, 2),
  };
}

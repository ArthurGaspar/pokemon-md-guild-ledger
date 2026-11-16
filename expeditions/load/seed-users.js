// Creates the load-test adventurers (racer-00001 ... racer-10000) in the guild realm. Run once, before rush.js:
//   k6 run expeditions/load/seed-users.js
// Safe to rerun: existing users are skipped.
import http from 'k6/http';
import { check, fail } from 'k6';

const KEYCLOAK = __ENV.KEYCLOAK || 'http://localhost:8180';
const USERS = Number(__ENV.USERS || 10000);
const BATCH = 500;
export const PASSWORD = 'race-123';

export const options = {
  vus: 1,
  iterations: 1,
  thresholds: { checks: ['rate==1'] },
};

export function racer(n) {
  return `racer-${String(n).padStart(5, '0')}`;
}

export default function () {
  const login = http.post(`${KEYCLOAK}/realms/master/protocol/openid-connect/token`, {
    grant_type: 'password',
    client_id: 'admin-cli',
    username: __ENV.KC_ADMIN || 'admin',
    password: __ENV.KC_ADMIN_PASSWORD || 'admin',
  });
  if (!check(login, { 'admin signed in': (r) => r.status === 200 })) {
    fail(`Keycloak admin login failed: ${login.status} ${login.body}`);
  }
  const params = {
    headers: { Authorization: `Bearer ${login.json('access_token')}`, 'Content-Type': 'application/json' },
    timeout: '120s',
  };

  // Keycloak's default hashing is deliberately slow (that's its job), which would make 10,000 logins cost minutes of
  // CPU and turn this into a Keycloak benchmark. The load realm hashes new passwords cheaply instead. Users that
  // already exist (the demo accounts) keep their hashes. Never do this to a realm with real people in it.
  const policy = http.put(`${KEYCLOAK}/admin/realms/guild`,
    JSON.stringify({ passwordPolicy: 'hashAlgorithm(pbkdf2-sha256) and hashIterations(1)', accessTokenLifespan: 1800 }),
    params);
  check(policy, { 'cheap hashing for load users': (r) => r.status === 204 });

  for (let first = 1; first <= USERS; first += BATCH) {
    const users = [];
    for (let n = first; n < Math.min(first + BATCH, USERS + 1); n++) {
      users.push({
        username: racer(n),
        // the declarative user profile wants these, or logins fail with "account not fully set up"
        firstName: 'Racer',
        lastName: String(n),
        email: `${racer(n)}@load.test`,
        emailVerified: true,
        enabled: true,
        credentials: [{ type: 'password', value: PASSWORD, temporary: false }],
        realmRoles: ['adventurer'],
      });
    }
    const r = http.post(`${KEYCLOAK}/admin/realms/guild/partialImport`,
      JSON.stringify({ ifResourceExists: 'SKIP', users }), params);
    check(r, { 'batch imported': (res) => res.status === 200 });
    console.log(`users ${first}-${first + users.length - 1}: HTTP ${r.status}`);
  }
}

import { bootstrap, login, MEMBER_PASSWORD } from "../lib/flows.ts";
import { del, get, post, sql, type Scenario, type Step } from "../lib/scenario.ts";

/**
 * Helpers for the sidecar bank scenarios. The harness stub answers 503 to every sidecar call, so a
 * login reaches the bank-specific error mapping, and stored sessions come from SQL fixtures.
 */

/** bcrypt of MEMBER_PASSWORD, so a member exists without the family endpoints. */
const MEMBER_HASH = "$2a$10$ddsZL8EVB3TRIkget93MmedPYqqtEE0wLRj/66ZKBR6IuSrxOwYeG";

/** A second member with a login, signed in as client `as` (SQL, then the login endpoint). */
export function sqlMember(as: string, username: string): Step[] {
  return [
    sql(`member ${username}`, `WITH m AS (INSERT INTO family_member (display_name, is_managed) VALUES ('${username}', false) RETURNING id) ` +
      `INSERT INTO app_user (username, password_hash, member_id, role, is_activated, acknowledged_warning) ` +
      `SELECT '${username}', '${MEMBER_HASH}', id, 'MEMBER', true, true FROM m`),
    ...login(as, username, MEMBER_PASSWORD),
  ];
}

/** The admin's member id, for SQL fixtures. */
export const ADMIN_MEMBER = "(SELECT member_id FROM app_user WHERE username = 'admin')";

/** A sidecar_session row in the given sync state (a fixture: no login can succeed against the stub). */
export function storedSession(
  bank: string,
  state: { active: boolean; status: string; error?: string; extra?: Record<string, string> },
): Step {
  const columns = Object.keys(state.extra ?? {});
  const values = Object.values(state.extra ?? {});
  return sql(
    `${bank} session ${state.status}`,
    `INSERT INTO sidecar_session (bank, member_id, session_state, last_validated_at, is_active, sync_status, ` +
      `last_sync_started_at, last_sync_completed_at, last_sync_error${columns.map((c) => ", " + c).join("")}) ` +
      `VALUES ('${bank}', ${ADMIN_MEMBER}, 'not-decryptable', TIMESTAMPTZ '2024-01-02 03:04:05+00', ${state.active}, ` +
      `'${state.status}', TIMESTAMPTZ '2024-01-02 03:04:05+00', TIMESTAMPTZ '2024-01-02 03:05:06+00', ` +
      `${state.error ? `'${state.error}'` : "NULL"}${values.map((v) => ", " + v).join("")})`,
  );
}

/**
 * What the frontend does around a sidecar connector that is not connected: read the status,
 * try a login the sidecar cannot answer, send malformed bodies, sync without a session, disconnect.
 */
export function disconnectedFlow(base: string, login: { initiate: unknown; invalidInitiate: unknown }): Step[] {
  return [
    get(`${base}/status`, { expect: 200 }),
    get(`${base}/status`, { as: "anon", expect: 401 }),
    post(`${base}/auth/initiate`, login.initiate, { expect: 422, note: "the stub sidecar answers 503" }),
    post(`${base}/auth/initiate`, login.invalidInitiate, { expect: 422 }),
    post(`${base}/auth/initiate`, {}, { expect: 422 }),
    post(`${base}/sync`, undefined, { expect: 422 }),
    del(`${base}/session`, { expect: 204 }),
  ];
}

const bourseDirect: Scenario = {
  name: "connectors.bourse-direct",
  description: "Bourse Direct: status, failing logins, validation, sync without a session, stored session states, member isolation",
  steps: [
    ...bootstrap(),
    ...sqlMember("member", "dominique"),
    ...disconnectedFlow("/api/bourse-direct", {
      initiate: { login: "jdoe", password: "secret" },
      invalidInitiate: { login: "x".repeat(101), password: "" },
    }),
    post("/api/bourse-direct/auth/complete", { processId: "p-1", code: "123456" }, { expect: 422 }),
    post("/api/bourse-direct/auth/complete", { processId: "", code: "12a456" }, { expect: 422 }),
    post("/api/bourse-direct/auth/complete", { processId: "p-1", code: "123456" }, { expect: 422 }),
    post("/api/bourse-direct/auth/complete", { processId: "p-2", code: "654321" }, { expect: 422 }),
    post("/api/bourse-direct/auth/complete", { processId: "p-3", code: "000000" }, { expect: 422, note: "fifth counted attempt: invalid bodies never reach the budget" }),
    post("/api/bourse-direct/auth/initiate", { login: "jdoe", password: "secret" }, { expect: 429 }),
    storedSession("BOURSE_DIRECT", { active: false, status: "FAILED", error: "SESSION_EXPIRED" }),
    get("/api/bourse-direct/status", { expect: 200 }),
    get("/api/bourse-direct/status", { as: "member", expect: 200 }),
    post("/api/bourse-direct/sync", undefined, { expect: 422, note: "an expired session asks to reconnect" }),
    del("/api/bourse-direct/session", { as: "member", expect: 204 }),
    get("/api/bourse-direct/status", { expect: 200, note: "the member's delete left the admin's session" }),
    sql("session back to a finished sync", `UPDATE sidecar_session SET is_active = true, sync_status = 'SUCCESS', last_sync_error = NULL WHERE bank = 'BOURSE_DIRECT'`),
    get("/api/bourse-direct/status", { expect: 200 }),
    sql("session queued", `UPDATE sidecar_session SET sync_status = 'QUEUED', last_sync_started_at = NULL, last_sync_completed_at = NULL WHERE bank = 'BOURSE_DIRECT'`),
    post("/api/bourse-direct/sync", undefined, { expect: 202, note: "a sync in flight is left alone" }),
    del("/api/bourse-direct/session", { expect: 204 }),
    get("/api/bourse-direct/status", { expect: 200 }),
  ],
};

const amundi: Scenario = {
  name: "connectors.amundi",
  description: "Amundi: status, failing logins (code and app push), validation, sync without a session, stored session states",
  steps: [
    ...bootstrap(),
    ...disconnectedFlow("/api/amundi", {
      initiate: { login: "jdoe", password: "secret" },
      invalidInitiate: { login: "", password: "x".repeat(101) },
    }),
    post("/api/amundi/auth/complete", { processId: "p-1" }, { expect: 422, note: "an app push carries no code" }),
    post("/api/amundi/auth/complete", { processId: "p-1", code: "12" }, { expect: 422 }),
    storedSession("AMUNDI", { active: false, status: "FAILED", error: "CAPTCHA_BLOCKED" }),
    get("/api/amundi/status", { expect: 200 }),
    post("/api/amundi/sync", undefined, { expect: 422 }),
    sql("session queued", `UPDATE sidecar_session SET is_active = true, sync_status = 'QUEUED', last_sync_error = NULL WHERE bank = 'AMUNDI'`),
    post("/api/amundi/sync", undefined, { expect: 202 }),
    get("/api/amundi/status", { expect: 200 }),
  ],
};

const cmes: Scenario = {
  name: "connectors.cmes",
  description: "CMES: failing logins, an unknown pending attempt, validation, budgets, stored session states",
  steps: [
    ...bootstrap(),
    ...disconnectedFlow("/api/cmes", {
      initiate: { login: "jdoe", password: "secret" },
      invalidInitiate: { login: "jdoe" },
    }),
    post("/api/cmes/auth/complete", { processId: "p-1", code: "1234" }, { expect: 422, note: "no pending attempt" }),
    post("/api/cmes/auth/complete", { processId: "p-1", code: "12" }, { expect: 422 }),
    storedSession("CMES", { active: false, status: "FAILED", error: "INVALID_CREDENTIALS" }),
    get("/api/cmes/status", { expect: 200 }),
    post("/api/cmes/sync", undefined, { expect: 422 }),
    del("/api/cmes/session", { expect: 204 }),
    get("/api/cmes/status", { expect: 200 }),
  ],
};

const natixis: Scenario = {
  name: "connectors.natixis",
  description: "Natixis: failing logins, the code rules, stored session states",
  steps: [
    ...bootstrap(),
    ...disconnectedFlow("/api/natixis", {
      initiate: { email: "jdoe@example.com", password: "secret" },
      invalidInitiate: { email: "x".repeat(255), password: "secret" },
    }),
    post("/api/natixis/auth/complete", { processId: "p-1", code: "123456" }, { expect: 422, note: "no pending attempt" }),
    post("/api/natixis/auth/complete", { processId: "p-1" }, { expect: 422 }),
    post("/api/natixis/auth/complete", { processId: "p-1", code: "12ab" }, { expect: 422 }),
    storedSession("NATIXIS", { active: true, status: "SUCCESS" }),
    get("/api/natixis/status", { expect: 200 }),
    sql("session queued", `UPDATE sidecar_session SET sync_status = 'QUEUED' WHERE bank = 'NATIXIS'`),
    post("/api/natixis/sync", undefined, { expect: 202 }),
    del("/api/natixis/session", { expect: 204 }),
  ],
};

const degiro: Scenario = {
  name: "connectors.degiro",
  description: "DEGIRO: status, failing logins, validation, sync without a session and with an expired one",
  steps: [
    ...bootstrap(),
    get("/api/degiro/status", { expect: 200 }),
    get("/api/degiro/status", { as: "anon", expect: 401 }),
    post("/api/degiro/auth/initiate", { username: "jdoe", password: "secret" }, { expect: 422 }),
    post("/api/degiro/auth/initiate", { username: "", password: "secret" }, { expect: 422 }),
    post("/api/degiro/auth/complete", { processId: "p", code: "123456" }, { expect: 422 }),
    post("/api/degiro/auth/complete", { processId: "p", code: "12" }, { expect: 422 }),
    post("/api/degiro/sync", undefined, { expect: 422 }),
    sql("expired DEGIRO session", `INSERT INTO degiro_session (member_id, session_blob, status, last_synced_at, last_error) VALUES (${ADMIN_MEMBER}, 'x', 'REAUTH_REQUIRED', TIMESTAMPTZ '2024-01-02 03:04:05+00', 'SESSION_EXPIRED')`),
    get("/api/degiro/status", { expect: 200 }),
    post("/api/degiro/sync", undefined, { expect: 422 }),
    del("/api/degiro/session", { expect: 204 }),
    get("/api/degiro/status", { expect: 200 }),
  ],
};

export const scenarios = [bourseDirect, amundi, cmes, natixis, degiro];

import { bootstrap } from "../lib/flows.ts";
import { del, get, post, sql, type Scenario } from "../lib/scenario.ts";
import { ADMIN_MEMBER, sqlMember } from "./connectors.ts";

const BASE = "/api/lbp";
const LOGIN = { customerId: "1234567890" };
const KEYPAD = { processId: "p-1", positions: [0, 1, 2, 3, 4, 5] };

const lbp: Scenario = {
  name: "connectors.lbp",
  description:
    "La Banque Postale: keypad login steps, strict validation, per-IP budgets, sync without and with stored sessions, member isolation",
  steps: [
    ...bootstrap(),
    ...sqlMember("member", "dominique"),
    get(`${BASE}/status`, { expect: 200 }),
    get(`${BASE}/status`, { as: "anon", expect: 401 }),
    post(`${BASE}/auth/initiate`, LOGIN, { expect: 422, note: "the stub sidecar answers 503" }),
    post(`${BASE}/auth/initiate`, { ...LOGIN, password: "secret" }, { expect: 400 }),
    post(`${BASE}/auth/initiate`, { ...LOGIN, rememberIdentifier: "yes" }, { expect: 400 }),
    post(`${BASE}/auth/initiate`, { customerId: "abc" }, { expect: 400 }),
    post(`${BASE}/auth/initiate`, {}, { expect: 400 }),
    post(`${BASE}/auth/initiate`, { ...LOGIN, rememberIdentifier: true }, { expect: 422 }),
    post(`${BASE}/auth/initiate`, LOGIN, { expect: 422, note: "third counted attempt" }),
    post(`${BASE}/auth/initiate`, LOGIN, { expect: 429 }),
    post(`${BASE}/auth/keypad`, KEYPAD, { expect: 422, note: "no login is pending" }),
    post(`${BASE}/auth/keypad`, { ...KEYPAD, positions: [0, 1, 2] }, { expect: 400 }),
    post(`${BASE}/auth/keypad`, { ...KEYPAD, positions: [0, 1, 2, 3, 4, 10] }, { expect: 400 }),
    post(`${BASE}/auth/keypad`, { ...KEYPAD, password: "x" }, { expect: 400 }),
    post(`${BASE}/auth/keypad`, KEYPAD, { expect: 422 }),
    post(`${BASE}/auth/keypad`, KEYPAD, { expect: 422 }),
    post(`${BASE}/auth/keypad`, KEYPAD, { expect: 422 }),
    post(`${BASE}/auth/keypad`, KEYPAD, { expect: 422, note: "fifth counted keypad request" }),
    post(`${BASE}/auth/keypad`, KEYPAD, { expect: 429 }),
    post(`${BASE}/auth/complete`, { processId: "p-1" }, { expect: 422 }),
    post(`${BASE}/auth/complete`, { processId: "p-1", code: "123456" }, { expect: 400 }),
    post(`${BASE}/auth/complete`, {}, { expect: 400 }),
    post(`${BASE}/sync`, undefined, { expect: 422, note: "no session" }),
    del(`${BASE}/session`, { expect: 200 }),
    sql(
      "LBP failed session with unsupported products",
      `INSERT INTO lbp_session (member_id, session_state, encrypted_identifier, last_validated_at, is_active, sync_status, ` +
        `last_sync_started_at, last_sync_completed_at, last_sync_error, unsupported_products) ` +
        `VALUES (${ADMIN_MEMBER}, 'not-decryptable', 'not-decryptable', TIMESTAMPTZ '2024-01-02 03:04:05+00', false, 'FAILED', ` +
        `TIMESTAMPTZ '2024-01-02 03:04:05+00', TIMESTAMPTZ '2024-01-02 03:05:06+00', 'SESSION_EXPIRED', ` +
        `'[{"externalId":"9001","familyCode":"ASSURANCE_VIE"}]')`,
    ),
    get(`${BASE}/status`, { expect: 200 }),
    get(`${BASE}/status`, { as: "member", expect: 200 }),
    post(`${BASE}/sync`, undefined, { expect: 422 }),
    del(`${BASE}/session`, { as: "member", expect: 200 }),
    get(`${BASE}/status`, { expect: 200, note: "member delete left admin session intact" }),
    sql(
      "LBP session queued",
      `UPDATE lbp_session SET is_active = true, sync_status = 'QUEUED', last_sync_error = NULL, last_sync_started_at = NULL, last_sync_completed_at = NULL`,
    ),
    post(`${BASE}/sync`, undefined, { expect: 202 }),
    get(`${BASE}/status`, { expect: 200 }),
    del(`${BASE}/session`, { expect: 200 }),
    get(`${BASE}/status`, { expect: 200 }),
  ],
};

export const scenarios = [lbp];

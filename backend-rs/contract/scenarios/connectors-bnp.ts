import { bootstrap } from "../lib/flows.ts";
import { del, get, post, sql, type Scenario } from "../lib/scenario.ts";
import { sqlMember, storedSession } from "./connectors.ts";

const BASE = "/api/bnp";
const LOGIN = { customerId: "1234567890" };
const KEYPAD = { processId: "p-1", positions: [0, 1, 2, 3, 4, 5] };

const bnp: Scenario = {
  name: "connectors.bnp",
  description:
    "BNP Paribas / Hello bank!: keypad login steps, strict validation, separate per-IP budgets, sync and stored session states, member isolation",
  steps: [
    ...bootstrap(),
    ...sqlMember("member", "dominique"),
    get(`${BASE}/status`, { expect: 200 }),
    get(`${BASE}/status`, { as: "anon", expect: 401 }),
    post(`${BASE}/auth/initiate`, LOGIN, { expect: 422, note: "the stub sidecar answers 503" }),
    post(`${BASE}/auth/initiate`, { ...LOGIN, brand: "hellobank" }, { expect: 422 }),
    post(`${BASE}/auth/initiate`, { ...LOGIN, brand: "other" }, { expect: 400 }),
    post(`${BASE}/auth/initiate`, { ...LOGIN, password: "secret" }, { expect: 400 }),
    post(`${BASE}/auth/initiate`, { customerId: "123456" }, { expect: 400 }),
    post(`${BASE}/auth/initiate`, LOGIN, { expect: 422, note: "third counted attempt" }),
    post(`${BASE}/auth/initiate`, LOGIN, { expect: 429 }),
    post(`${BASE}/auth/keypad`, KEYPAD, { expect: 422 }),
    post(`${BASE}/auth/keypad`, { ...KEYPAD, positions: [0, 1, 2, 3, 4] }, { expect: 400 }),
    post(`${BASE}/auth/keypad`, { ...KEYPAD, code: "123456" }, { expect: 400 }),
    post(`${BASE}/auth/keypad`, KEYPAD, { expect: 422 }),
    post(`${BASE}/auth/keypad`, KEYPAD, { expect: 422 }),
    post(`${BASE}/auth/keypad`, KEYPAD, { expect: 422 }),
    post(`${BASE}/auth/keypad`, KEYPAD, { expect: 422, note: "fifth counted keypad request" }),
    post(`${BASE}/auth/keypad`, KEYPAD, { expect: 429 }),
    post(`${BASE}/auth/complete`, { processId: "p-1" }, { expect: 422 }),
    post(`${BASE}/auth/complete`, { processId: "p-1", code: "123456" }, { expect: 422 }),
    post(`${BASE}/auth/complete`, { processId: "p-1", code: "12ab" }, { expect: 400 }),
    post(`${BASE}/sync`, undefined, { expect: 422 }),
    del(`${BASE}/session`, { expect: 200 }),
    storedSession("BNP", {
      active: false,
      status: "FAILED",
      error: "SESSION_EXPIRED",
    }),
    get(`${BASE}/status`, { expect: 200 }),
    get(`${BASE}/status`, { as: "member", expect: 200 }),
    post(`${BASE}/sync`, undefined, { expect: 422 }),
    del(`${BASE}/session`, { as: "member", expect: 200 }),
    get(`${BASE}/status`, { expect: 200 }),
    sql(
      "BNP session queued",
      `UPDATE sidecar_session SET is_active = true, sync_status = 'QUEUED', last_sync_error = NULL, last_sync_started_at = NULL, last_sync_completed_at = NULL WHERE bank = 'BNP'`,
    ),
    post(`${BASE}/sync`, undefined, { expect: 202 }),
    del(`${BASE}/session`, { expect: 200 }),
    get(`${BASE}/status`, { expect: 200 }),
  ],
};

export const scenarios = [bnp];

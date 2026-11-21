import { bootstrap } from "../lib/flows.ts";
import { del, get, post, sql, type Scenario } from "../lib/scenario.ts";
import { ADMIN_MEMBER, sqlMember } from "./connectors.ts";

const BASE = "/api/revolut";

const revolut: Scenario = {
  name: "connectors.revolut",
  description:
    "Revolut: status, idle progress, confirm without pending discovery, auth rate limit, stored session states, member isolation",
  steps: [
    ...bootstrap(),
    ...sqlMember("member", "dominique"),
    get(`${BASE}/status`, { expect: 200 }),
    get(`${BASE}/status`, { as: "anon", expect: 401 }),
    get(`${BASE}/sync/progress`, { expect: 200 }),
    post(
      `${BASE}/sync/confirm`,
      { selectedExternalIds: ["w-1"], remember: false, voluntary: false },
      { expect: 422, note: "no pending discovery" },
    ),
    del(`${BASE}/session`, { expect: 204 }),
    sql(
      "stored Revolut session",
      `INSERT INTO revolut_session (member_id, credentials_enc, remember_credentials, last_synced_at, created_at, updated_at) ` +
        `VALUES (${ADMIN_MEMBER}, 'not-decryptable', true, TIMESTAMPTZ '2024-01-02 03:04:05+00', ` +
        `TIMESTAMPTZ '2024-01-02 03:04:05+00', TIMESTAMPTZ '2024-01-02 03:04:05+00')`,
    ),
    get(`${BASE}/status`, { expect: 200 }),
    get(`${BASE}/status`, { as: "member", expect: 200 }),
    del(`${BASE}/session`, { as: "member", expect: 204 }),
    get(`${BASE}/status`, { expect: 200, note: "member delete left admin session intact" }),
    del(`${BASE}/session`, { expect: 204 }),
    get(`${BASE}/status`, { expect: 200 }),
    // Spend the 5-attempt per-IP sync bucket (background stub answers 503; skip comparing transient progress).
    post(`${BASE}/sync`, {}, { expect: 202, compare: false }),
    post(`${BASE}/sync`, {}, { expect: 202, compare: false }),
    post(`${BASE}/sync`, {}, { expect: 202, compare: false }),
    post(`${BASE}/sync`, {}, { expect: 202, compare: false }),
    post(`${BASE}/sync`, {}, { expect: 202, compare: false }),
    post(`${BASE}/sync`, {}, { expect: 429 }),
  ],
};

export const scenarios = [revolut];

import { bootstrap } from "../lib/flows.ts";
import { del, get, post, sql, type Scenario } from "../lib/scenario.ts";
import { sqlMember, storedSession } from "./connectors.ts";

const BASE = "/api/corum";

const corum: Scenario = {
  name: "connectors.corum",
  description:
    "CORUM: status, single-step login against stub, validation, auth budget, sync without a session, stored session states, member isolation",
  steps: [
    ...bootstrap(),
    ...sqlMember("member", "dominique"),
    get(`${BASE}/status`, { expect: 200 }),
    get(`${BASE}/status`, { as: "anon", expect: 401 }),
    post(`${BASE}/auth`, { login: "C12345", password: "secret", remember: false }, { expect: 422, note: "stub 503" }),
    post(`${BASE}/auth`, { login: "", password: "secret" }, { expect: 422 }),
    post(`${BASE}/auth`, {}, { expect: 422 }),
    post(`${BASE}/auth`, { login: "C12345", password: "secret", remember: true }, { expect: 422 }),
    post(`${BASE}/auth`, { login: "C12345", password: "secret" }, { expect: 422 }),
    post(`${BASE}/auth`, { login: "C12345", password: "secret" }, { expect: 422 }),
    post(`${BASE}/auth`, { login: "C12345", password: "secret" }, { expect: 422, note: "fifth counted attempt" }),
    post(`${BASE}/auth`, { login: "C12345", password: "secret" }, { expect: 429 }),
    post(`${BASE}/sync`, undefined, { expect: 422 }),
    del(`${BASE}/session`, { expect: 204 }),
    storedSession("CORUM", {
      active: false,
      status: "FAILED",
      error: "SESSION_EXPIRED",
      extra: {
        encrypted_credentials: "'not-decryptable'",
        last_sync_life_ambiguous: "2",
      },
    }),
    get(`${BASE}/status`, { expect: 200 }),
    get(`${BASE}/status`, { as: "member", expect: 200 }),
    post(`${BASE}/sync`, undefined, { expect: 422 }),
    del(`${BASE}/session`, { as: "member", expect: 204 }),
    get(`${BASE}/status`, { expect: 200 }),
    sql(
      "CORUM session queued",
      `UPDATE sidecar_session SET is_active = true, sync_status = 'QUEUED', last_sync_error = NULL, last_sync_started_at = NULL, last_sync_completed_at = NULL WHERE bank = 'CORUM'`,
    ),
    post(`${BASE}/sync`, undefined, { expect: 202 }),
    del(`${BASE}/session`, { expect: 204 }),
    get(`${BASE}/status`, { expect: 200 }),
  ],
};

export const scenarios = [corum];

import { bootstrap } from "../lib/flows.ts";
import { del, get, post, sql, type Scenario } from "../lib/scenario.ts";
import { disconnectedFlow, sqlMember, storedSession } from "./connectors.ts";

const BASE = "/api/sofidy";

const sofidy: Scenario = {
  name: "connectors.sofidy",
  description:
    "Sofidy: status, failing logins, validation, auth budget, sync without a session, stored session states, member isolation",
  steps: [
    ...bootstrap(),
    ...sqlMember("member", "dominique"),
    ...disconnectedFlow(BASE, {
      initiate: { associateCode: "123456", password: "secret" },
      invalidInitiate: { associateCode: "12345", password: "secret" },
    }),
    post(`${BASE}/auth/complete`, { processId: "p-1", code: "123456" }, { expect: 422 }),
    post(`${BASE}/auth/complete`, { processId: "p-1", code: "12ab" }, { expect: 422 }),
    post(`${BASE}/auth/complete`, { processId: "p-2", code: "654321" }, { expect: 422 }),
    post(`${BASE}/auth/complete`, { processId: "p-3", code: "111111" }, { expect: 422 }),
    post(`${BASE}/auth/complete`, { processId: "p-4", code: "222222" }, { expect: 422, note: "fifth counted attempt" }),
    post(`${BASE}/auth/initiate`, { associateCode: "123456", password: "secret" }, { expect: 429 }),
    storedSession("SOFIDY", { active: false, status: "FAILED", error: "SESSION_EXPIRED" }),
    get(`${BASE}/status`, { expect: 200 }),
    get(`${BASE}/status`, { as: "member", expect: 200 }),
    post(`${BASE}/sync`, undefined, { expect: 422 }),
    del(`${BASE}/session`, { as: "member", expect: 204 }),
    get(`${BASE}/status`, { expect: 200 }),
    sql(
      "Sofidy session queued",
      `UPDATE sidecar_session SET is_active = true, sync_status = 'QUEUED', last_sync_error = NULL, last_sync_started_at = NULL, last_sync_completed_at = NULL WHERE bank = 'SOFIDY'`,
    ),
    post(`${BASE}/sync`, undefined, { expect: 202 }),
    del(`${BASE}/session`, { expect: 204 }),
    get(`${BASE}/status`, { expect: 200 }),
  ],
};

export const scenarios = [sofidy];

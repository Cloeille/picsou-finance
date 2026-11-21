import { bootstrap } from "../lib/flows.ts";
import { del, get, post, sql, type Scenario } from "../lib/scenario.ts";
import { disconnectedFlow, sqlMember, storedSession } from "./connectors.ts";

const BASE = "/api/amex";

const amex: Scenario = {
  name: "connectors.amex",
  description:
    "American Express: status, failing logins, validation, auth budget, sync and history recovery, stored session states, member isolation",
  steps: [
    ...bootstrap(),
    ...sqlMember("member", "dominique"),
    ...disconnectedFlow(BASE, {
      initiate: { login: "jdoe", password: "secret", method: "sms" },
      invalidInitiate: { login: "jdoe", password: "secret", method: "push" },
    }),
    post(`${BASE}/auth/complete`, { processId: "p-1", otp: "123456" }, { expect: 422 }),
    post(`${BASE}/auth/complete`, { processId: "p-1", otp: "12ab" }, { expect: 422 }),
    post(`${BASE}/auth/complete`, { processId: "p-2", otp: "654321" }, { expect: 422 }),
    post(`${BASE}/auth/complete`, { processId: "p-3", otp: "111111" }, { expect: 422 }),
    post(`${BASE}/auth/complete`, { processId: "p-4", otp: "222222" }, { expect: 422, note: "fifth counted auth attempt" }),
    post(`${BASE}/auth/initiate`, { login: "jdoe", password: "secret", method: "email" }, { expect: 429 }),
    post(`${BASE}/history-recovery`, undefined, { expect: 422, note: "no session" }),
    storedSession("AMEX", { active: false, status: "FAILED", error: "SESSION_EXPIRED" }),
    get(`${BASE}/status`, { expect: 200 }),
    get(`${BASE}/status`, { as: "member", expect: 200 }),
    post(`${BASE}/sync`, undefined, { expect: 422 }),
    post(`${BASE}/history-recovery`, undefined, { expect: 422 }),
    del(`${BASE}/session`, { as: "member", expect: 204 }),
    get(`${BASE}/status`, { expect: 200 }),
    sql(
      "AMEX session queued",
      `UPDATE sidecar_session SET is_active = true, sync_status = 'QUEUED', last_sync_error = NULL, last_sync_started_at = NULL, last_sync_completed_at = NULL WHERE bank = 'AMEX'`,
    ),
    post(`${BASE}/sync`, undefined, { expect: 202 }),
    post(`${BASE}/history-recovery`, undefined, { expect: 202 }),
    del(`${BASE}/session`, { expect: 204 }),
    get(`${BASE}/status`, { expect: 200 }),
  ],
};

export const scenarios = [amex];

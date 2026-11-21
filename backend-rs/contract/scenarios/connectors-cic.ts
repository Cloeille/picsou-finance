import { bootstrap } from "../lib/flows.ts";
import { del, get, post, sql, type Scenario } from "../lib/scenario.ts";
import { disconnectedFlow, sqlMember, storedSession } from "./connectors.ts";

const cic: Scenario = {
  name: "connectors.cic",
  description: "CIC: status, failing logins and reconnections, validation, budgets, stored credentials, session states, member isolation",
  steps: [
    ...bootstrap(),
    ...sqlMember("member", "dominique"),
    ...disconnectedFlow("/api/cic", {
      initiate: { login: "12345678", password: "secret" },
      invalidInitiate: { login: "x".repeat(101), password: " " },
    }),
    post("/api/cic/auth/reconnect", undefined, { expect: 422, note: "no session, so no stored credentials" }),
    post("/api/cic/auth/complete", { processId: "p-1", code: "123456" }, { expect: 422, note: "unknown attempt" }),
    post("/api/cic/auth/complete", { processId: "p-1" }, { expect: 422, note: "a mobile confirmation carries no code" }),
    post("/api/cic/auth/complete", { processId: "", code: "12a456" }, { expect: 422 }),
    post("/api/cic/auth/complete", { processId: "x".repeat(101), code: "123" }, { expect: 422 }),
    storedSession("CIC", { active: false, status: "FAILED", error: "SESSION_EXPIRED" }),
    get("/api/cic/status", { expect: 200 }),
    get("/api/cic/status", { as: "member", expect: 200 }),
    post("/api/cic/sync", undefined, { expect: 422, note: "an expired session asks to reconnect" }),
    post("/api/cic/auth/reconnect", undefined, { expect: 422, note: "a session without stored credentials" }),
    sql("stored credentials", `UPDATE sidecar_session SET encrypted_credentials = 'not-decryptable' WHERE bank = 'CIC'`),
    get("/api/cic/status", { expect: 200, note: "canReconnect" }),
    post("/api/cic/auth/reconnect", undefined, { expect: 422, note: "unreadable stored credentials" }),
    post("/api/cic/auth/reconnect", undefined, { as: "member", expect: 422, note: "the member has none; fifth counted attempt" }),
    del("/api/cic/session", { as: "member", expect: 204 }),
    get("/api/cic/status", { expect: 200, note: "the member's delete left the admin's session" }),
    sql("session back to a finished sync", `UPDATE sidecar_session SET is_active = true, sync_status = 'SUCCESS', last_sync_error = NULL WHERE bank = 'CIC'`),
    get("/api/cic/status", { expect: 200 }),
    sql("session queued", `UPDATE sidecar_session SET sync_status = 'QUEUED', last_sync_started_at = NULL, last_sync_completed_at = NULL WHERE bank = 'CIC'`),
    post("/api/cic/sync", undefined, { expect: 202, note: "a sync in flight is left alone" }),
    post("/api/cic/auth/initiate", { login: "12345678", password: "secret" }, { expect: 429, note: "five counted attempts: invalid bodies never reach the budget" }),
    post("/api/cic/auth/reconnect", undefined, { expect: 429, note: "initiate and reconnect share the budget" }),
    post("/api/cic/auth/complete", { processId: "p-2" }, { expect: 422, note: "completion has its own budget" }),
    del("/api/cic/session", { expect: 204 }),
    get("/api/cic/status", { expect: 200 }),
  ],
};

export const scenarios = [cic];

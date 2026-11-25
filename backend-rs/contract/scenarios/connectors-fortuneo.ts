import { bootstrap } from "../lib/flows.ts";
import { del, get, post, sql, type Scenario } from "../lib/scenario.ts";
import { disconnectedFlow, sqlMember, storedSession } from "./connectors.ts";

const fortuneo: Scenario = {
  name: "connectors.fortuneo",
  description: "Fortuneo: status, failing logins, validation, auth budget, sync without a session, stored session states, member isolation",
  steps: [
    ...bootstrap(),
    ...sqlMember("member", "dominique"),
    ...disconnectedFlow("/api/fortuneo", {
      initiate: { login: "jdoe", password: "secret" },
      invalidInitiate: { login: "x".repeat(101), password: "" },
    }),
    post("/api/fortuneo/auth/complete", { processId: "p-1", code: "123456" }, { expect: 422, note: "the stub sidecar answers 503" }),
    post("/api/fortuneo/auth/complete", { processId: "", code: "12a456" }, { expect: 422 }),
    post("/api/fortuneo/auth/complete", { processId: "p-1" }, { expect: 422 }),
    post("/api/fortuneo/auth/complete", { processId: "p-2", code: "654321" }, { expect: 422 }),
    post("/api/fortuneo/auth/complete", { processId: "p-3", code: "000000" }, { expect: 422 }),
    post("/api/fortuneo/auth/complete", { processId: "p-4", code: "111111" }, { expect: 422, note: "fifth counted attempt" }),
    post("/api/fortuneo/auth/initiate", { login: "jdoe", password: "secret" }, { expect: 429 }),
    storedSession("FORTUNEO", { active: false, status: "FAILED", error: "SESSION_EXPIRED" }),
    get("/api/fortuneo/status", { expect: 200 }),
    get("/api/fortuneo/status", { as: "member", expect: 200 }),
    post("/api/fortuneo/sync", undefined, { expect: 422, note: "an expired session asks to reconnect" }),
    del("/api/fortuneo/session", { as: "member", expect: 204 }),
    get("/api/fortuneo/status", { expect: 200, note: "the member's delete left the admin's session" }),
    sql("session back to a finished sync", `UPDATE sidecar_session SET is_active = true, sync_status = 'SUCCESS', last_sync_error = NULL WHERE bank = 'FORTUNEO'`),
    get("/api/fortuneo/status", { expect: 200 }),
    sql("session failed on the investor profile", `UPDATE sidecar_session SET sync_status = 'FAILED', last_sync_error = 'INVESTOR_PROFILE_REQUIRED' WHERE bank = 'FORTUNEO'`),
    get("/api/fortuneo/status", { expect: 200 }),
    sql("session queued", `UPDATE sidecar_session SET sync_status = 'QUEUED', last_sync_started_at = NULL, last_sync_completed_at = NULL, last_sync_error = NULL WHERE bank = 'FORTUNEO'`),
    post("/api/fortuneo/sync", undefined, { expect: 202, note: "a sync in flight is left alone" }),
    del("/api/fortuneo/session", { expect: 204 }),
    get("/api/fortuneo/status", { expect: 200 }),
  ],
};

export const scenarios = [fortuneo];

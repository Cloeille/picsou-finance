import { bootstrap } from "../lib/flows.ts";
import { del, get, post, sql, type Scenario } from "../lib/scenario.ts";
import { disconnectedFlow, sqlMember, storedSession } from "./connectors.ts";

const bourso: Scenario = {
  name: "connectors.bourso",
  description: "BoursoBank: status, failing logins and app-push completion, validation, auth and sync budgets, stored session states, member isolation",
  steps: [
    ...bootstrap(),
    ...sqlMember("member", "dominique"),
    ...disconnectedFlow("/api/bourso", {
      initiate: { customerId: "12345678", password: "246810" },
      invalidInitiate: { customerId: "x".repeat(101), password: "" },
    }),
    post("/api/bourso/auth/complete", { processId: "p-1" }, { expect: 422, note: "the stub sidecar answers 503" }),
    post("/api/bourso/auth/complete", { processId: "" }, { expect: 422 }),
    post("/api/bourso/auth/complete", { processId: "x".repeat(101) }, { expect: 422 }),
    post("/api/bourso/auth/complete", {}, { expect: 422 }),
    post("/api/bourso/auth/complete", { processId: "p-2", code: "123456" }, { expect: 422, note: "an extra code is ignored" }),
    post("/api/bourso/auth/complete", { processId: "p-3" }, { expect: 422 }),
    post("/api/bourso/auth/complete", { processId: "p-4" }, { expect: 422, note: "fifth counted attempt" }),
    post("/api/bourso/auth/initiate", { customerId: "12345678", password: "246810" }, { expect: 429 }),
    post("/api/bourso/auth/complete", { processId: "p-5" }, { expect: 429 }),
    storedSession("BOURSO", {
      active: false,
      status: "FAILED",
      error: "SESSION_EXPIRED",
      extra: { encrypted_credentials: "'not-decryptable'" },
    }),
    get("/api/bourso/status", { expect: 200 }),
    get("/api/bourso/status", { as: "member", expect: 200 }),
    post("/api/bourso/sync", undefined, { expect: 422, note: "an expired session asks to reconnect" }),
    del("/api/bourso/session", { as: "member", expect: 204 }),
    get("/api/bourso/status", { expect: 200, note: "the member's delete left the admin's session" }),
    sql("session back to a finished sync", `UPDATE sidecar_session SET is_active = true, sync_status = 'SUCCESS', last_sync_error = NULL WHERE bank = 'BOURSO'`),
    get("/api/bourso/status", { expect: 200 }),
    sql("session failed on a declared code", `UPDATE sidecar_session SET sync_status = 'FAILED', last_sync_error = 'APP_VALIDATION_TIMEOUT' WHERE bank = 'BOURSO'`),
    get("/api/bourso/status", { expect: 200 }),
    sql("session queued", `UPDATE sidecar_session SET sync_status = 'QUEUED', last_sync_started_at = NULL, last_sync_completed_at = NULL, last_sync_error = NULL WHERE bank = 'BOURSO'`),
    post("/api/bourso/sync", undefined, { expect: 202, note: "a sync in flight is left alone" }),
    get("/api/bourso/status", { expect: 200 }),
    del("/api/bourso/session", { expect: 204 }),
    get("/api/bourso/status", { expect: 200 }),
    post("/api/bourso/sync", undefined, { expect: 422 }),
    post("/api/bourso/sync", undefined, { expect: 422 }),
    post("/api/bourso/sync", undefined, { expect: 422 }),
    post("/api/bourso/sync", undefined, { expect: 422 }),
    post("/api/bourso/sync", undefined, { expect: 422 }),
    post("/api/bourso/sync", undefined, { expect: 422 }),
    post("/api/bourso/sync", undefined, { expect: 422, note: "tenth sync of the minute, counting the flow's and the queued one" }),
    post("/api/bourso/sync", undefined, { expect: 429 }),
  ],
};

export const scenarios = [bourso];

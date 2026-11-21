import { bootstrap, login, MEMBER_PASSWORD } from "../lib/flows.ts";
import { del, get, post, sql, type Scenario } from "../lib/scenario.ts";

const notConnected: Scenario = {
  name: "sync.finary-not-connected",
  description: "Finary API sync before any credentials are stored: status, TOTP check, preview, execute, auto and delete",
  steps: [
    ...bootstrap(),
    get("/api/finary/status", { expect: 200 }),
    post("/api/finary/check-totp", undefined, { expect: 404 }),
    post("/api/finary/api-sync/preview", undefined, { expect: 422 }),
    post("/api/finary/api-sync/preview?totp=123456", undefined, { expect: 422 }),
    post("/api/finary/api-sync/auto", undefined, { expect: 200 }),
    post("/api/finary/api-sync/execute", { syncToken: "0b0d67e9-2f34-4a09-a8bd-6733a34e919e", mappings: [] }, { expect: 422 }),
    post("/api/finary/api-sync/execute", { mappings: [] }, { expect: 500, note: "ConcurrentHashMap.get(null) throws" }),
    post("/api/finary/api-sync/execute", { syncToken: "nope", mappings: [{ finaryId: "a", finaryCategory: "checkings", action: "NOPE" }] }, { expect: 400 }),
    post("/api/finary/api-sync/execute", undefined, { raw: { contentType: "application/json", body: "{\"syncToken\":" }, expect: 400 }),
    post("/api/finary/api-sync/execute", undefined, { expect: 400 }),
    del("/api/finary/session", { expect: 204 }),
    get("/api/finary/status", { as: "anon", expect: 401 }),
    post("/api/finary/api-sync/auto", undefined, { as: "anon", expect: 401 }),
  ],
};

const credentials: Scenario = {
  name: "sync.finary-credentials",
  description: "Stored Finary credentials: masked status, update, member isolation, auto-sync statuses and deletion",
  steps: [
    ...bootstrap(),
    sql("a second member with a login, without the family endpoints", `WITH m AS (INSERT INTO family_member (display_name, is_managed) VALUES ('Dominique', false) RETURNING id)
      INSERT INTO app_user (username, password_hash, member_id, role, is_activated, acknowledged_warning)
      SELECT 'dominique', '$2b$10$Zc4VJMtk/bicRM7D7KldfuGnrVbnUTWMMfpmM3gAsM1IWwns7lYda', id, 'MEMBER', true, true FROM m`),
    ...login("member", "dominique", MEMBER_PASSWORD),
    post("/api/finary/login", { email: "contract.user@example.com", password: "synthetic-pass-1" }, { expect: 200 }),
    get("/api/finary/status", { expect: 200 }),
    post("/api/finary/login", { email: "x@example.com", password: "synthetic-pass-2" }, { expect: 200 }),
    get("/api/finary/status", { expect: 200 }),
    post("/api/finary/login", { email: "second.user@example.com", password: "synthetic-pass-3", extra: true }, { expect: 200 }),
    get("/api/finary/status", { expect: 200 }),
    get("/api/finary/status", { as: "member", expect: 200 }),
    post("/api/finary/check-totp", undefined, { as: "member", expect: 404 }),
    post("/api/finary/api-sync/auto", undefined, { as: "member", expect: 200 }),
    del("/api/finary/session", { as: "member", expect: 204 }),
    get("/api/finary/status", { expect: 200 }),
    sql("the daily auto-sync flagged the session", "UPDATE finary_session SET status = 'TOTP_REQUIRED'"),
    get("/api/finary/status", { expect: 200 }),
    post("/api/finary/api-sync/auto", undefined, { expect: 200 }),
    sql("a status no code path writes", "UPDATE finary_session SET status = 'EXPIRED'"),
    post("/api/finary/api-sync/auto", undefined, { expect: 200 }),
    post("/api/finary/login", { email: "contract.user@example.com", password: "synthetic-pass-1" }, { expect: 200 }),
    get("/api/finary/status", { expect: 200 }),
    del("/api/finary/session", { expect: 204 }),
    get("/api/finary/status", { expect: 200 }),
    del("/api/finary/session", { expect: 204 }),
  ],
};

const invalidLogin: Scenario = {
  name: "sync.finary-invalid-login",
  description: "Login bodies Java does not validate: missing fields, a malformed body, the wrong content type",
  steps: [
    ...bootstrap(),
    post("/api/finary/login", {}, {}),
    post("/api/finary/login", { email: "only@example.com" }, {}),
    post("/api/finary/login", undefined, { raw: { contentType: "application/json", body: "{\"email\":" }, expect: 400 }),
    post("/api/finary/login", undefined, { raw: { contentType: "text/plain", body: "hello" } }),
    post("/api/finary/login", undefined, { expect: 400 }),
    get("/api/finary/status", { expect: 200 }),
  ],
};

const upstream: Scenario = {
  name: "sync.finary-upstream-down",
  description: "Clerk and Finary unreachable: TOTP check, preview and auto-sync map the failure, the session is untouched",
  steps: [
    ...bootstrap(),
    post("/api/finary/login", { email: "contract.user@example.com", password: "synthetic-pass-1" }, { expect: 200 }),
    // The detail quotes the JDK's "Tunnel failed, got: 503", which reqwest cannot reproduce.
    post("/api/finary/check-totp", undefined, { compare: false }),
    post("/api/finary/api-sync/preview", undefined, { compare: false }),
    post("/api/finary/api-sync/preview?totp=123456", undefined, { compare: false }),
    post("/api/finary/api-sync/auto", undefined, { compare: false }),
    get("/api/finary/status", { expect: 200 }),
  ],
};

export const scenarios = [notConnected, credentials, invalidLogin, upstream];

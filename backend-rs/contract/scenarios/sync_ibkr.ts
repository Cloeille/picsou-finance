import { bootstrap, login, MEMBER_PASSWORD } from "../lib/flows.ts";
import { del, get, post, sql, type Scenario } from "../lib/scenario.ts";

// Synthetic credentials: a Flex token is 24 digits, a query id 6.
const credentials = { token: "123456789012345678904321", queryId: "654321" };

const notConnected: Scenario = {
  name: "sync.ibkr-not-connected",
  description: "Status, sync and disconnect of a member who never connected IBKR",
  steps: [
    ...bootstrap(),
    get("/api/ibkr/status", { as: "anon", expect: 401 }),
    post("/api/ibkr/sync", undefined, { as: "anon", expect: 401 }),
    get("/api/ibkr/status", { expect: 200 }),
    post("/api/ibkr/sync", undefined, { expect: 422 }),
    del("/api/ibkr/connection", { expect: 204 }),
    get("/api/ibkr/status", { expect: 200 }),
  ],
};

const validation: Scenario = {
  name: "sync.ibkr-validation",
  description: "Connect refuses blank or missing credentials and unreadable bodies",
  steps: [
    ...bootstrap(),
    post("/api/ibkr/connect", {}, { expect: 422 }),
    post("/api/ibkr/connect", { token: "   ", queryId: "", extra: true }, { expect: 422 }),
    post("/api/ibkr/connect", { token: "abc" }, { expect: 422 }),
    post("/api/ibkr/connect", undefined, { raw: { contentType: "application/json", body: "{\"token\":" }, expect: 400 }),
    get("/api/ibkr/status", { expect: 200 }),
  ],
};

const connect: Scenario = {
  name: "sync.ibkr-connect",
  description: "Connect, masked status, reconnect, member isolation and disconnect",
  steps: [
    ...bootstrap(),
    // A second signed-in member, written directly: the family endpoints belong to another track.
    sql(
      "a member with a login",
      `WITH m AS (INSERT INTO family_member (display_name, is_managed) VALUES ('Dominique', false) RETURNING id)
       INSERT INTO app_user (username, password_hash, member_id, role, is_activated, acknowledged_warning)
       SELECT 'dominique', '${Bun.password.hashSync(MEMBER_PASSWORD, { algorithm: "bcrypt", cost: 4 })}', id, 'MEMBER', true, true FROM m`,
    ),
    ...login("member", "dominique", MEMBER_PASSWORD),
    post("/api/ibkr/connect", { token: `  ${credentials.token} `, queryId: credentials.queryId }, { expect: 204 }),
    get("/api/ibkr/status", { expect: 200 }),
    post("/api/ibkr/connect", { token: "abcd", queryId: credentials.queryId }, { expect: 204 }),
    get("/api/ibkr/status", { expect: 200 }),
    get("/api/ibkr/status", { as: "member", expect: 200 }),
    post("/api/ibkr/sync", undefined, { as: "member", expect: 422 }),
    del("/api/ibkr/connection", { as: "member", expect: 204 }),
    get("/api/ibkr/status", { expect: 200 }),
    del("/api/ibkr/connection", { expect: 204 }),
    get("/api/ibkr/status", { expect: 200 }),
  ],
};

const upstreamFailure: Scenario = {
  name: "sync.ibkr-upstream-failure",
  description: "A sync whose Flex request cannot complete is a 422 and leaves the connection in ERROR",
  steps: [
    ...bootstrap(),
    post("/api/ibkr/connect", credentials, { expect: 204 }),
    // The detail carries the HTTP client's own I/O message, which differs between the JDK and
    // reqwest; the status and the ERROR it leaves behind are the contract.
    post("/api/ibkr/sync", undefined, { expect: 422, compare: false }),
    get("/api/ibkr/status", { expect: 200 }),
  ],
};

const rateLimit: Scenario = {
  name: "sync.ibkr-rate-limit",
  description: "Six syncs a minute per client IP, then 429",
  steps: [
    ...bootstrap(),
    ...Array.from({ length: 6 }, () => post("/api/ibkr/sync", undefined, { expect: 422 })),
    post("/api/ibkr/sync", undefined, { expect: 429 }),
  ],
};

export const scenarios = [notConnected, validation, connect, upstreamFailure, rateLimit];

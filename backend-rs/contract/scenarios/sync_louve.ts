import { bootstrap } from "../lib/flows.ts";
import { del, get, post, sql, type Scenario } from "../lib/scenario.ts";

const ISSUER = "https://app.louveinvest.com";
const ADMIN_MEMBER = "(SELECT member_id FROM app_user WHERE username = 'admin')";

export const scenarios: Scenario[] = [
  {
    name: "sync.louve.not-connected",
    description: "Louve Invest panel of a member who never connected: status, sync and disconnect",
    steps: [
      ...bootstrap(),
      get("/api/louve/status", { expect: 200 }),
      post("/api/louve/sync", undefined, { expect: 422 }),
      del("/api/louve/connection", { expect: 204 }),
      get("/api/louve/status", { expect: 200 }),
      get("/api/louve/status", { as: "anon", expect: 401 }),
    ],
  },
  {
    name: "sync.louve.callback-refused",
    description: "Callback validation, a foreign issuer and a callback with no live attempt",
    steps: [
      ...bootstrap(),
      post("/api/louve/auth/complete", {}, { expect: 422 }),
      post("/api/louve/auth/complete", { code: " ", state: "x".repeat(513), iss: ISSUER }, { expect: 422 }),
      post("/api/louve/auth/complete", undefined, { raw: { contentType: "application/json", body: "{\"code\":" }, expect: 400 }),
      post("/api/louve/auth/complete", { code: "code-1", state: "state-1", iss: "https://evil.example" }, { expect: 422 }),
      post("/api/louve/auth/complete", { code: "code-1", state: "state-1", iss: ISSUER }, { expect: 422 }),
      get("/api/louve/status", { expect: 200 }),
    ],
  },
  {
    name: "sync.louve.origins",
    description: "The redirect URI comes from the configured origins; an unreachable Louve fails closed",
    steps: [
      ...bootstrap(),
      post("/api/louve/auth/start", undefined, { expect: 400 }),
      sql("configure the allowed origins", "INSERT INTO app_setting (setting_key, value) VALUES ('cors.allowed-origins', 'http://localhost:5173, http://192.0.2.10:8080')"),
      post("/api/louve/auth/start", undefined, { headers: { origin: "http://192.0.2.10:8080" }, expect: 400 }),
      post("/api/louve/auth/start", undefined, { headers: { origin: "http://localhost:5173" }, expect: 422 }),
      get("/api/louve/status", { expect: 200 }),
    ],
  },
  {
    name: "sync.louve.unreadable-state",
    description: "A stored token set that no longer decrypts asks for a new consent; disconnect removes it",
    steps: [
      ...bootstrap(),
      sql("a connection whose token set is unreadable", `INSERT INTO louve_connection (member_id, token_state, status) VALUES (${ADMIN_MEMBER}, 'garbage', 'CONNECTED')`),
      get("/api/louve/status", { expect: 200 }),
      post("/api/louve/sync", undefined, { expect: 422 }),
      get("/api/louve/status", { expect: 200 }),
      del("/api/louve/connection", { expect: 204 }),
      get("/api/louve/status", { expect: 200 }),
    ],
  },
];

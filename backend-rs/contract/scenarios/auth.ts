import { ADMIN, bootstrap, login } from "../lib/flows.ts";
import { del, get, post, type Scenario } from "../lib/scenario.ts";

const firstRun: Scenario = {
  name: "setup.first-run",
  description: "Wizard on an empty database: status, admin, security, complete, then 410 on a second admin",
  steps: [
    get("/api/setup/status", { as: "anon", expect: 200 }),
    get("/api/accounts", { as: "anon", note: "SetupFilter blocks the API before setup" }),
    post("/api/setup/admin", { username: "x", password: "short" }, { as: "anon", expect: 422 }),
    post("/api/setup/admin", { username: ADMIN.username, password: ADMIN.password, displayName: "Admin", avatarColor: "#6366f1" }, { as: "anon", expect: 200 }),
    get("/api/setup/status", { as: "anon", expect: 200 }),
    post("/api/setup/security", { allowedOrigins: ["http://localhost:5173"], secureCookies: false }, { as: "anon", expect: 204 }),
    post("/api/setup/integrations/traderepublic/acknowledge", undefined, { as: "anon", expect: 204 }),
    post("/api/setup/integrations/bogus/acknowledge", undefined, { as: "anon", expect: 400 }),
    post("/api/setup/complete", undefined, { as: "anon", expect: 204 }),
    get("/api/setup/status", { as: "anon", expect: 200 }),
    post("/api/setup/admin", { username: "second", password: ADMIN.password }, { as: "anon", expect: 410 }),
    post("/api/auth/login", { username: ADMIN.username, password: ADMIN.password, rememberMe: false }, { as: "admin", expect: 200 }),
  ],
};

const loginFlow: Scenario = {
  name: "auth.login",
  description: "Login, authenticated read, refresh, logout, then every cookie is gone",
  steps: [
    ...bootstrap(),
    post("/api/auth/login", { username: ADMIN.username, password: ADMIN.password, rememberMe: false }, { as: "browser", expect: 200 }),
    get("/api/accounts", { as: "browser", expect: 200 }),
    post("/api/auth/refresh", undefined, { as: "browser", expect: 200 }),
    get("/api/auth/mfa/status", { as: "browser", expect: 200 }),
    post("/api/auth/logout", undefined, { as: "browser", expect: 204 }),
    post("/api/auth/refresh", undefined, { as: "browser", expect: 401 }),
    get("/api/accounts", { as: "browser", expect: 401 }),
    post("/api/auth/login", { username: ADMIN.username, password: ADMIN.password, rememberMe: true }, { as: "remembered", expect: 200 }),
    post("/api/auth/refresh", undefined, { as: "remembered", expect: 200 }),
  ],
};

const wrongPassword: Scenario = {
  name: "auth.wrong-password",
  description: "Bad credentials, unknown user, blank fields, malformed JSON",
  steps: [
    ...bootstrap(),
    post("/api/auth/login", { username: ADMIN.username, password: "not-the-password", rememberMe: false }, { as: "anon", expect: 401 }),
    post("/api/auth/login", { username: "nobody", password: "not-the-password", rememberMe: false }, { as: "anon", expect: 401 }),
    post("/api/auth/login", { username: "", password: "" }, { as: "anon", expect: 422 }),
    post("/api/auth/login", undefined, { as: "anon", raw: { contentType: "application/json", body: "{not json" } }),
  ],
};

const mfa: Scenario = {
  name: "auth.mfa",
  description: "TOTP enrolment, then a login that needs the second factor",
  steps: [
    ...bootstrap(),
    get("/api/auth/mfa/status", { expect: 200 }),
    post("/api/auth/mfa/enroll/init", { currentPassword: "wrong-password" }, { expect: 400 }),
    post("/api/auth/mfa/enroll/init", { currentPassword: ADMIN.password }, { expect: 200, capture: { secret: (r) => r.json.secret } }),
    post("/api/auth/mfa/enroll/verify", { code: "000000" }, { expect: 400 }),
    post("/api/auth/mfa/enroll/verify", async (v) => ({ code: await v.totp("secret") }), { expect: 200 }),
    get("/api/auth/mfa/status", { expect: 200 }),
    post("/api/auth/login", { username: ADMIN.username, password: ADMIN.password, rememberMe: false }, { as: "phone", expect: 200 }),
    get("/api/accounts", { as: "phone", expect: 401 }),
    post("/api/auth/mfa/verify", { code: "000000", isRecoveryCode: false, trustDevice: false }, { as: "phone", expect: 400 }),
    post("/api/auth/mfa/verify", async (v) => ({ code: await v.totp("secret"), isRecoveryCode: false, trustDevice: true }), { as: "phone", expect: 200 }),
    get("/api/accounts", { as: "phone", expect: 200 }),
    get("/api/auth/sessions", { as: "phone", expect: 200 }),
    post("/api/auth/mfa/verify", { code: "123456", isRecoveryCode: false, trustDevice: false }, { as: "stranger", expect: 401 }),
  ],
};

const sessions: Scenario = {
  name: "auth.sessions",
  description: "Two remembered devices, list, revoke one, revoke all others",
  steps: [
    ...bootstrap(),
    ...login("laptop", ADMIN.username, ADMIN.password, { rememberMe: true }),
    ...login("tablet", ADMIN.username, ADMIN.password, { rememberMe: true }),
    get("/api/auth/sessions", {
      as: "laptop",
      expect: 200,
      capture: { tabletSession: (r) => r.json.find((s: { current: boolean }) => !s.current)?.id },
    }),
    del("/api/auth/sessions/{tabletSession}", { as: "laptop" }),
    get("/api/auth/sessions", { as: "laptop", expect: 200 }),
    post("/api/auth/refresh", undefined, { as: "tablet" }),
    del("/api/auth/sessions/999999", { as: "laptop" }),
    del("/api/auth/sessions", { as: "laptop" }),
    get("/api/auth/sessions", { as: "laptop", expect: 200 }),
    get("/api/auth/sessions", { as: "anon", expect: 401 }),
  ],
};

export const scenarios = [firstRun, loginFlow, wrongPassword, mfa, sessions];

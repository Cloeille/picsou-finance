import { bootstrap } from "../lib/flows.ts";
import { del, get, post, type Response, type Scenario, type Vars } from "../lib/scenario.ts";
import type { Json } from "../lib/json.ts";

/** Responses that carry the instance's own URL are requested for a fixed host. */
const HOST = { host: "picsou.test" };
const VERIFIER = "contract-pkce-verifier-0123456789-abcdefghijklmnop";
const CHALLENGE = new Bun.CryptoHasher("sha256").update(VERIFIER).digest("base64url");
const MCP_REDIRECT = "https://claude.ai/api/mcp/auth_callback";

const form = (fields: (v: Vars) => Record<string, string>) => ({
  contentType: "application/x-www-form-urlencoded",
  body: (v: Vars) => new URLSearchParams(fields(v)).toString(),
});
const query = (q: Record<string, string>) => new URLSearchParams(q).toString();
const fromLocation = (name: string) => (r: Response) =>
  new URL(r.headers.get("location")!, "http://picsou.test").searchParams.get(name);

const ios = (extra: Record<string, string> = {}) =>
  "/oauth2/authorize?" +
  query({ response_type: "code", client_id: "picsou-ios", redirect_uri: "picsou://callback", scope: "read", state: "st", code_challenge: CHALLENGE, code_challenge_method: "S256", ...extra });
const mcp = (client: string, scope: string, state: string) =>
  `/oauth2/authorize?response_type=code&client_id={${client}}&redirect_uri=${encodeURIComponent(MCP_REDIRECT)}&scope=${encodeURIComponent(scope)}&state=${state}&code_challenge=${CHALLENGE}&code_challenge_method=S256`;
const exchange = (code: string, client: (v: Vars) => string, redirect: string, verifier = VERIFIER) =>
  form((v) => ({ grant_type: "authorization_code", code: v.str(code), client_id: client(v), redirect_uri: redirect, code_verifier: verifier }));

/** client_id_issued_at is the registration second. */
const withoutIssuedAt = (body: Json) => ({ ...(body as { [k: string]: Json }), client_id_issued_at: "<epoch-seconds>" });

const discovery: Scenario = {
  name: "oauth.discovery",
  description: "Authorization-server and protected-resource metadata, the empty JWKS",
  steps: [
    ...bootstrap(),
    get("/.well-known/oauth-authorization-server", { as: "anon", headers: HOST, expect: 200 }),
    get("/.well-known/oauth-protected-resource", { as: "anon", headers: HOST, expect: 200 }),
    get("/oauth2/jwks", { as: "anon", expect: 200 }),
  ],
};

const iosApp: Scenario = {
  name: "oauth.ios-pkce",
  description: "The iOS app: authorization code with PKCE, request errors, token errors, the token on /api",
  steps: [
    ...bootstrap(),
    get(ios(), { as: "anon", headers: HOST, expect: 302 }),
    get(ios({ client_id: "unknown" }), { expect: 400 }),
    get(ios({ redirect_uri: "picsou://elsewhere" }), { expect: 400 }),
    get(ios({ response_type: "token" }), { expect: 400 }),
    get(ios({ scope: "admin" }), { expect: 302 }),
    get(ios({ code_challenge_method: "plain" }), { expect: 302 }),
    get("/oauth2/authorize?" + query({ response_type: "code", client_id: "picsou-ios", redirect_uri: "picsou://callback", scope: "read", state: "nopkce" }), { expect: 302 }),
    get(ios(), { expect: 302, capture: { code1: fromLocation("code") } }),
    post("/oauth2/token", undefined, { as: "anon", raw: exchange("code1", () => "picsou-ios", "picsou://callback", "a-wrong-verifier-a-wrong-verifier-a-wrong-verif"), expect: 400 }),
    post("/oauth2/token", undefined, { as: "anon", raw: exchange("code1", () => "picsou-ios", "picsou://wrong"), expect: 400 }),
    post("/oauth2/token", undefined, { as: "anon", raw: exchange("code1", () => "picsou-ios", "picsou://callback"), expect: 200, capture: { token: (r) => r.json.access_token } }),
    post("/oauth2/token", undefined, { as: "anon", raw: exchange("code1", () => "picsou-ios", "picsou://callback"), expect: 400, note: "a code is single-use" }),
    post("/oauth2/token", undefined, { as: "anon", raw: exchange("code1", () => "unknown-client", "picsou://callback"), expect: 401 }),
    get("/api/auth/sign-in-methods", { as: "ios", headers: (v) => ({ authorization: "Bearer " + v.str("token") }), expect: 200 }),
    get(ios({ scope: "read write", state: "both" }), { expect: 302, capture: { code2: fromLocation("code") } }),
    post("/oauth2/token", undefined, { as: "anon", raw: exchange("code2", () => "picsou-ios", "picsou://callback"), expect: 200 }),
    post("/oauth2/token", undefined, { as: "anon", headers: HOST, raw: form(() => ({ grant_type: "refresh_token", refresh_token: "anything", client_id: "picsou-ios" })), expect: 302, note: "a public client gets no refresh token, so a refresh never authenticates" }),
    post("/oauth2/token", undefined, { as: "anon", headers: { authorization: "Basic " + btoa("picsou-ios:guess") }, raw: form(() => ({ grant_type: "refresh_token", refresh_token: "anything" })), expect: 401 }),
    post("/oauth2/revoke", undefined, { as: "anon", headers: HOST, raw: form((v) => ({ token: v.str("token"), client_id: "picsou-ios" })), expect: 302 }),
    post("/oauth2/introspect", undefined, { as: "anon", headers: { authorization: "Basic " + btoa("picsou-ios:guess") }, raw: form((v) => ({ token: v.str("token") })), expect: 401 }),
    get("/api/connected-apps", { expect: 200 }),
  ],
};

const mcpClient: Scenario = {
  name: "oauth.mcp-consent",
  description: "A remote MCP client: dynamic registration, consent, token, connected apps",
  steps: [
    ...bootstrap(),
    post("/oauth2/register", { redirect_uris: ["http://evil.example/cb"] }, { as: "anon", expect: 400 }),
    post("/oauth2/register", { redirect_uris: ["https://ok.example/cb"], scope: "admin:all" }, { as: "anon", expect: 400 }),
    post("/oauth2/register", { client_name: "No redirect" }, { as: "anon", expect: 400 }),
    post("/oauth2/register", { redirect_uris: ["custom-scheme:/cb"] }, { as: "anon", expect: 400 }),
    post("/oauth2/register", { client_name: "x".repeat(201), redirect_uris: ["https://ok.example/cb"] }, { as: "anon", expect: 400 }),
    post("/oauth2/register", { client_name: "Claude", redirect_uris: [MCP_REDIRECT], scope: "accounts:read transactions:read", grant_types: ["client_credentials"], token_endpoint_auth_method: "client_secret_basic" }, {
      as: "anon",
      expect: 201,
      keep: withoutIssuedAt,
      capture: { client: (r) => r.json.client_id },
    }),
    get(mcp("client", "accounts:read", "c1"), { headers: HOST, expect: 302, capture: { consent: fromLocation("state") } }),
    get("/api/oauth2/consent-info?client_id={client}&scope=accounts%3Aread%20bogus&state={consent}", { expect: 200 }),
    get("/api/oauth2/consent-info?client_id=unknown&scope=accounts%3Aread", { expect: 404 }),
    post("/oauth2/authorize", undefined, { as: "anon", raw: form((v) => ({ client_id: v.str("client"), state: v.str("consent"), scope: "accounts:read" })), expect: 401 }),
    post("/oauth2/authorize", undefined, { raw: form((v) => ({ client_id: v.str("client"), state: "not-a-pending-consent" })), expect: 401 }),
    post("/oauth2/authorize", undefined, { raw: form((v) => ({ client_id: v.str("client"), state: v.str("consent"), scope: "accounts:read" })), expect: 302, capture: { code: fromLocation("code") } }),
    post("/oauth2/token", undefined, { as: "anon", raw: exchange("code", (v) => v.str("client"), MCP_REDIRECT), expect: 200 }),
    get(mcp("client", "accounts:read", "c2"), { expect: 302, note: "consented scopes need no new consent" }),
    get(mcp("client", "accounts:read transactions:read", "c3"), { headers: HOST, expect: 302, capture: { consent2: fromLocation("state") } }),
    post("/oauth2/authorize", undefined, { raw: form((v) => ({ client_id: v.str("client"), state: v.str("consent2"), scope: "goals:read" })), expect: 302 }),
    get(mcp("client", "transactions:read", "c4"), { headers: HOST, expect: 302, capture: { consent3: fromLocation("state") } }),
    post("/oauth2/authorize", undefined, { raw: form((v) => ({ client_id: v.str("client"), state: v.str("consent3") })), expect: 302, note: "nothing approved and nothing consented before for it: denied" }),
    get("/api/connected-apps", { expect: 200, capture: { app: (r) => r.json[0]?.id } }),
    del("/api/connected-apps/{app}", { as: "member", expect: 401 }),
    del("/api/connected-apps/{app}", { expect: 204 }),
    get("/api/connected-apps", { expect: 200 }),
    del("/api/connected-apps/not-an-id", { expect: 404 }),
  ],
};

export const scenarios: Scenario[] = [discovery, iosApp, mcpClient];

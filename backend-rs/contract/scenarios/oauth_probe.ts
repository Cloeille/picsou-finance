import { bootstrap } from "../lib/flows.ts";
import { get, post, del, type Scenario, type Vars } from "../lib/scenario.ts";

const VERIFIER = "contract-pkce-verifier-0123456789-abcdefghijklmnop";
const challenge = () => new Bun.CryptoHasher("sha256").update(VERIFIER).digest("base64url");
const form = (fields: (v: Vars) => Record<string, string>) => ({
  contentType: "application/x-www-form-urlencoded",
  body: (v: Vars) => new URLSearchParams(fields(v)).toString(),
});
const query = (q: Record<string, string>) => new URLSearchParams(q).toString();
const codeOf = (r: { headers: Headers }) => new URL(r.headers.get("location")!, "http://x").searchParams.get("code");
const param = (name: string) => (r: { headers: Headers }) => new URL(r.headers.get("location")!, "http://x").searchParams.get(name);

const ios = (state: string, extra: Record<string, string> = {}) =>
  "/oauth2/authorize?" + query({ response_type: "code", client_id: "picsou-ios", redirect_uri: "picsou://callback", scope: "read", state, code_challenge: challenge(), code_challenge_method: "S256", ...extra });

export const scenarios: Scenario[] = [{
  name: "oauth.probe",
  description: "probe",
  steps: [
    ...bootstrap(),
    get("/.well-known/oauth-authorization-server", { as: "anon" }),
    get("/.well-known/oauth-protected-resource", { as: "anon" }),
    get("/oauth2/jwks", { as: "anon" }),
    get(ios("s1"), { as: "anon", capture: { l: (r) => (console.log("ANON LOC", r.status, r.headers.get("location")), true) } }),
    get(ios("s2"), { capture: { l: (r) => (console.log("IOS LOC", r.status, r.headers.get("location")), true) } }),
    get(ios("s3"), { capture: { code1: codeOf } }),
    get(ios("s4", { client_id: "nope" })),
    get(ios("s5", { redirect_uri: "picsou://elsewhere" })),
    get("/oauth2/authorize?" + query({ response_type: "code", client_id: "picsou-ios", redirect_uri: "picsou://callback", scope: "read", state: "s6" })),
    get(ios("s7", { scope: "admin" })),
    post("/oauth2/token", undefined, { as: "anon", raw: form((v) => ({ grant_type: "authorization_code", code: v.str("code1"), client_id: "picsou-ios", redirect_uri: "picsou://callback", code_verifier: "wrong-verifier-wrong-verifier-wrong-verifier-x" })) }),
    get(ios("s8"), { capture: { code2: codeOf } }),
    post("/oauth2/token", undefined, { as: "anon", raw: form((v) => ({ grant_type: "authorization_code", code: v.str("code2"), client_id: "picsou-ios", redirect_uri: "picsou://callback", code_verifier: VERIFIER })), capture: { at: (r) => r.json.access_token, l: (r) => (console.log("IOS TOKEN", r.text), true) } }),
    post("/oauth2/token", undefined, { as: "anon", raw: form((v) => ({ grant_type: "authorization_code", code: v.str("code2"), client_id: "picsou-ios", redirect_uri: "picsou://callback", code_verifier: VERIFIER })) }),
    get("/api/auth/sign-in-methods", { as: "ios", headers: (v) => ({ authorization: "Bearer " + v.str("at") }) }),
    post("/oauth2/token", undefined, { as: "anon", raw: form(() => ({ grant_type: "refresh_token", refresh_token: "whatever", client_id: "picsou-ios" })) }),
    post("/oauth2/revoke", undefined, { as: "anon", raw: form((v) => ({ token: v.str("at"), client_id: "picsou-ios" })) }),
    post("/oauth2/introspect", undefined, { as: "anon", raw: form((v) => ({ token: v.str("at"), client_id: "picsou-ios" })) }),
    post("/oauth2/token", undefined, { as: "anon", raw: form(() => ({ grant_type: "client_credentials" })) }),
    post("/oauth2/token", undefined, { as: "anon", raw: form(() => ({ grant_type: "authorization_code", code: "bogus", client_id: "picsou-ios", redirect_uri: "picsou://callback", code_verifier: VERIFIER })) }),
    post("/oauth2/register", { client_name: "Claude", redirect_uris: ["https://claude.ai/api/mcp/auth_callback"], scope: "accounts:read transactions:read" }, { as: "anon", capture: { client: (r) => r.json.client_id } }),
    post("/oauth2/register", { redirect_uris: ["http://evil.example/cb"] }, { as: "anon" }),
    post("/oauth2/register", { redirect_uris: ["https://ok.example/cb"], scope: "admin:all" }, { as: "anon" }),
    post("/oauth2/register", { client_name: "x" }, { as: "anon" }),
    post("/oauth2/register", { redirect_uris: ["https://ok.example/cb"] }, { as: "anon" }),
    get("/oauth2/authorize?response_type=code&client_id={client}&redirect_uri=https%3A%2F%2Fclaude.ai%2Fapi%2Fmcp%2Fauth_callback&scope=accounts%3Aread&state=dcr1&code_challenge=" + challenge() + "&code_challenge_method=S256", { capture: { cstate: param("state"), log1: (r) => (console.log("CONSENT LOC", r.status, r.headers.get("location")), true) } }),
    get("/api/oauth2/consent-info?client_id={client}&scope=accounts%3Aread%20bogus&state={cstate}"),
    post("/oauth2/authorize", undefined, { raw: form((v) => ({ client_id: v.str("client"), state: v.str("cstate"), scope: "accounts:read" })), capture: { dcode: codeOf, log2: (r) => (console.log("APPROVED LOC", r.status, r.headers.get("location")), true) } }),
    post("/oauth2/token", undefined, { as: "anon", raw: form((v) => ({ grant_type: "authorization_code", code: v.str("dcode"), client_id: v.str("client"), redirect_uri: "https://claude.ai/api/mcp/auth_callback", code_verifier: VERIFIER })), capture: { mcp: (r) => r.json.access_token, log3: (r) => (console.log("TOKEN", r.text), true) } }),
    get("/api/connected-apps", { capture: { app: (r) => r.json[0]?.id, log4: (r) => (console.log("APPS", r.text), true) } }),
    get("/oauth2/authorize?response_type=code&client_id={client}&redirect_uri=https%3A%2F%2Fclaude.ai%2Fapi%2Fmcp%2Fauth_callback&scope=accounts%3Aread&state=dcr2&code_challenge=" + challenge() + "&code_challenge_method=S256", { capture: { log5: (r) => (console.log("SECOND AUTHZ", r.status, r.headers.get("location")), true) } }),
    get("/oauth2/authorize?response_type=code&client_id={client}&redirect_uri=https%3A%2F%2Fclaude.ai%2Fapi%2Fmcp%2Fauth_callback&scope=accounts%3Aread%20transactions%3Aread&state=dcr3&code_challenge=" + challenge() + "&code_challenge_method=S256", { capture: { cstate2: param("state"), log6: (r) => (console.log("WIDER AUTHZ", r.status, r.headers.get("location")), true) } }),
    post("/oauth2/authorize", undefined, { raw: form((v) => ({ client_id: v.str("client"), state: v.str("cstate2") })), capture: { log7: (r) => (console.log("DENIED", r.status, r.headers.get("location")), true) } }),
    del("/api/connected-apps/{app}"),
    get("/api/connected-apps"),
    del("/api/connected-apps/not-an-id"),

    
  ],
}];

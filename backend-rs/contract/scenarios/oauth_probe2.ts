import { bootstrap } from "../lib/flows.ts";
import { get, post, sql, type Scenario, type Vars } from "../lib/scenario.ts";

const VERIFIER = "contract-pkce-verifier-0123456789-abcdefghijklmnop";
const challenge = () => new Bun.CryptoHasher("sha256").update(VERIFIER).digest("base64url");
const form = (fields: (v: Vars) => Record<string, string>) => ({
  contentType: "application/x-www-form-urlencoded",
  body: (v: Vars) => new URLSearchParams(fields(v)).toString(),
});
const H = { host: "picsou.test" };
const L = (tag: string) => (r: any) => (console.log(tag, r.status, r.headers.get("location"), r.headers.get("content-type"), r.text.slice(0, 600)), true);
const param = (name: string) => (r: { headers: Headers }) => new URL(r.headers.get("location")!, "http://x").searchParams.get(name);
const q = (o: Record<string,string>) => new URLSearchParams(o).toString();
const ios = (extra: Record<string, string> = {}) => "/oauth2/authorize?" + q({ response_type: "code", client_id: "picsou-ios", redirect_uri: "picsou://callback", scope: "read", state: "st", code_challenge: challenge(), code_challenge_method: "S256", ...extra });

export const scenarios: Scenario[] = [{
  name: "oauth.probe2",
  description: "probe",
  steps: [
    ...bootstrap(),
    get("/.well-known/oauth-authorization-server", { as: "anon", headers: H, capture: { l: L("META-HOST") } }),
    get("/.well-known/oauth-protected-resource", { as: "anon", headers: H, capture: { l: L("PRM-HOST") } }),
    get(ios(), { as: "anon", headers: H, capture: { l: L("ANON-HOST") } }),
    get("/oauth2/device_verification", { as: "anon", capture: { l: L("DEVVER-ANON") } }),
    get("/oauth2/device_verification", { capture: { l: L("DEVVER-ADMIN") } }),
    post("/oauth2/device_authorization", undefined, { as: "anon", raw: form(() => ({ client_id: "picsou-ios" })), capture: { l: L("DEVAUTH") } }),
    post("/oauth2/revoke", undefined, { as: "anon", headers: { authorization: "Basic " + btoa("picsou-ios:x") }, raw: form(() => ({ token: "abc" })), capture: { l: L("REVOKE-BASIC") } }),
    post("/oauth2/token", undefined, { as: "anon", headers: { authorization: "Basic " + btoa("picsou-ios:x") }, raw: form(() => ({ grant_type: "refresh_token", refresh_token: "abc" })), capture: { l: L("TOKEN-BASIC") } }),
    post("/oauth2/token", undefined, { as: "anon", raw: form(() => ({ grant_type: "authorization_code", client_id: "picsou-ios", client_secret: "x", code: "abc", redirect_uri: "picsou://callback" })), capture: { l: L("TOKEN-POST-SECRET") } }),
    post("/oauth2/token", undefined, { as: "anon", raw: form(() => ({ grant_type: "authorization_code", client_id: "unknown", code: "abc", code_verifier: VERIFIER })), capture: { l: L("TOKEN-UNKNOWN-CLIENT") } }),
    get("/oauth2/token", { as: "anon", capture: { l: L("GET-TOKEN") } }),
    get(ios({ response_type: "token" }), { capture: { l: L("RT-TOKEN") } }),
    get("/oauth2/authorize?" + q({ response_type: "code", redirect_uri: "picsou://callback", state: "x" }), { capture: { l: L("NO-CLIENT") } }),
    get(ios({ redirect_uri: "" }), { capture: { l: L("EMPTY-REDIRECT") } }),
    get("/oauth2/authorize?" + q({ response_type: "code", client_id: "picsou-ios", scope: "read", code_challenge: challenge(), code_challenge_method: "S256" }), { capture: { l: L("NO-REDIRECT-NO-STATE"), code: param("code") } }),
    post("/oauth2/token", undefined, { as: "anon", raw: form((v) => ({ grant_type: "authorization_code", code: v.str("code"), client_id: "picsou-ios", redirect_uri: "picsou://callback", code_verifier: VERIFIER })), capture: { l: L("TOKEN-REDIRECT-NOT-IN-REQUEST") } }),
    get(ios({ code_challenge_method: "plain" }), { capture: { l: L("PLAIN") } }),
    get(ios({ scope: "" }), { capture: { l: L("NO-SCOPE"), code2: param("code") } }),
    post("/oauth2/token", undefined, { as: "anon", raw: form((v) => ({ grant_type: "authorization_code", code: v.str("code2"), client_id: "picsou-ios", redirect_uri: "picsou://callback", code_verifier: VERIFIER })), capture: { l: L("TOKEN-NO-SCOPE") } }),
    get(ios({ scope: "read write" }), { capture: { code3: param("code") } }),
    post("/oauth2/token", undefined, { as: "anon", raw: form((v) => ({ grant_type: "authorization_code", code: v.str("code3"), client_id: "picsou-ios", redirect_uri: "picsou://wrong", code_verifier: VERIFIER })), capture: { l: L("TOKEN-WRONG-REDIRECT") } }),
    post("/oauth2/token", undefined, { as: "anon", raw: form((v) => ({ grant_type: "authorization_code", code: v.str("code3"), client_id: "picsou-ios", redirect_uri: "picsou://callback", code_verifier: VERIFIER })), capture: { l: L("TOKEN-AFTER-WRONG-REDIRECT") } }),
    post("/oauth2/register", { client_name: "Loop", redirect_uris: ["http://127.0.0.1:8080/cb"], scope: "accounts:read" }, { as: "anon", capture: { client: (r) => r.json.client_id } }),
    get("/oauth2/authorize?response_type=code&client_id={client}&redirect_uri=http%3A%2F%2F127.0.0.1%3A9999%2Fcb&scope=accounts%3Aread&state=lp&code_challenge=" + challenge() + "&code_challenge_method=S256", { capture: { l: L("LOOPBACK-PORT"), cs: param("state") } }),
    post("/oauth2/authorize", undefined, { raw: form((v) => ({ client_id: v.str("client"), state: "nope" })), capture: { l: L("CONSENT-BAD-STATE") } }),
    post("/oauth2/authorize", undefined, { as: "anon", raw: form((v) => ({ client_id: v.str("client"), state: v.str("cs"), scope: "accounts:read" })), capture: { l: L("CONSENT-ANON") } }),
    post("/oauth2/authorize", undefined, { raw: form((v) => ({ client_id: v.str("client"), state: v.str("cs") })), capture: { l: L("CONSENT-DENY") } }),
    get("/oauth2/authorize?response_type=code&client_id={client}&redirect_uri=http%3A%2F%2F127.0.0.1%3A8080%2Fcb&scope=accounts%3Aread&state=lp2&code_challenge=" + challenge() + "&code_challenge_method=S256", { capture: { l: L("AFTER-DENY"), cs2: param("state") } }),
    post("/oauth2/authorize", undefined, { raw: form((v) => ({ client_id: v.str("client"), state: v.str("cs2"), scope: "accounts:read transactions:read" })), capture: { l: L("CONSENT-EXTRA-SCOPE") } }),
    sql("dump", "COPY (SELECT id, registered_client_id, principal_name, authorization_grant_type, authorized_scopes, attributes, state, authorization_code_value IS NOT NULL, authorization_code_metadata, access_token_metadata, access_token_type, access_token_scopes FROM oauth2_authorization) TO '<scratchpad>/probe/authz.csv' CSV HEADER"),
    sql("dump2", "COPY (SELECT * FROM oauth2_registered_client) TO '<scratchpad>/probe/clients.csv' CSV HEADER"),
    sql("dump3", "COPY (SELECT * FROM oauth2_authorization_consent) TO '<scratchpad>/probe/consent.csv' CSV HEADER"),
  ],
}];

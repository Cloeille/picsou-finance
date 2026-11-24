import { ADMIN, bootstrap, login } from "../lib/flows.ts";
import { del, get, post, sql, type Scenario, type Step } from "../lib/scenario.ts";

/** The wizard with Enable Banking configured: application id, redirect URL and a generated key pair. */
function configuredBootstrap(): Step[] {
  return [
    post("/api/setup/admin", { username: ADMIN.username, password: ADMIN.password, displayName: "Admin" }, { as: "anon", expect: 200, compare: false }),
    post("/api/setup/integrations/enablebanking/config", { applicationId: "0b4c7e2a-5d1f-4a3b-9c8e-7f6a5b4c3d2e", redirectUri: "http://localhost:5173/sync/callback" }, { as: "anon", expect: 204, compare: false }),
    post("/api/setup/integrations/enablebanking/keypair", undefined, { as: "anon", expect: 200, compare: false }),
    post("/api/setup/complete", undefined, { as: "anon", expect: 204, compare: false }),
    ...login("admin", ADMIN.username, ADMIN.password),
  ];
}

const adminMember = "(SELECT member_id FROM app_user WHERE username = 'admin')";

function requisitionRow(session: string, institution: string, status: string): string {
  return `INSERT INTO requisition (member_id, requisition_id, institution_id, institution_name, status, auth_link, created_at, updated_at)
    VALUES (${adminMember}, '${session}', '${institution}', '${institution.split("::")[0]}', '${status}', 'https://bank.example/auth', now(), now())`;
}

const idOf = (session: string) => (r: { json: Array<{ id: number; requisitionId: string }> }) =>
  r.json.find((x) => x.requisitionId === session)?.id;

const notConfigured: Scenario = {
  name: "sync.enable-banking-not-configured",
  description: "Bank sync reads and writes without Enable Banking configured: empty status, NOT_CONFIGURED codes, validation, unknown ids",
  steps: [
    ...bootstrap(),
    get("/api/sync/status", { expect: 200 }),
    get("/api/sync/institutions?query=bnp&country=FR", { expect: 422 }),
    get("/api/sync/institutions", { expect: 422 }),
    get("/api/sync/countries", { expect: 422 }),
    post("/api/sync/initiate", { institutionId: "BNP Paribas::FR::personal", institutionName: "BNP Paribas" }, { expect: 422 }),
    post("/api/sync/initiate", { institutionId: "" }, { expect: 422 }),
    post("/api/sync/complete", { state: "nonce" }, { expect: 422 }),
    post("/api/sync/complete", { code: "some-code", state: "unknown-nonce" }, { expect: 422 }),
    post("/api/sync/complete", { code: "some-code" }, { expect: 422 }),
    post("/api/sync/999999/retry", undefined, { expect: 404 }),
    post("/api/sync/999999/reconnect", undefined, { expect: 404 }),
    post("/api/sync/abc/retry", undefined, { expect: 400 }),
    del("/api/sync/999999", { expect: 404 }),
    get("/api/sync/status", { as: "anon", expect: 401 }),
  ],
};

const upstream: Scenario = {
  name: "sync.enable-banking-upstream-down",
  description: "Enable Banking configured but down (503): failures map to 422, retry marks FAILED, reconnect and delete",
  steps: [
    ...configuredBootstrap(),
    get("/api/sync/institutions?query=bnp", { expect: 422 }),
    get("/api/sync/countries", { expect: 422 }),
    post("/api/sync/initiate", { institutionId: "BNP Paribas::FR::personal", institutionName: "BNP Paribas" }, { expect: 422 }),
    get("/api/sync/status", { expect: 200 }),
    sql("a connection created before the outage, as a successful initiation would", requisitionRow("contract-session-created", "Banque Test::FR::personal", "CREATED")),
    sql("a linked connection, as a completed callback would", requisitionRow("contract-session-linked", "Banque Liee::FR::personal", "LINKED")),
    get("/api/sync/status", {
      expect: 200,
      capture: { created: idOf("contract-session-created"), linked: idOf("contract-session-linked") },
    }),
    post("/api/sync/{created}/retry", undefined, { expect: 422 }),
    get("/api/sync/status", { expect: 200, note: "the failed retry left the requisition FAILED" }),
    post("/api/sync/{linked}/reconnect", undefined, { expect: 422 }),
    post("/api/sync/{created}/reconnect", undefined, { expect: 422 }),
    del("/api/sync/{linked}", { expect: 204 }),
    get("/api/sync/status", { expect: 200 }),
  ],
};

export const scenarios: Scenario[] = [notConfigured, upstream];

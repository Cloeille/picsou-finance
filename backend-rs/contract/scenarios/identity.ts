import { ADMIN, MEMBER_PASSWORD, bootstrap } from "../lib/flows.ts";
import { day, del, get, post, put, sql, type Scenario } from "../lib/scenario.ts";
import type { Json } from "../lib/json.ts";

/** A member with a login, written straight to the database: the family endpoints belong to another track. */
const memberFixture = (username: string) =>
  sql("a member who can sign in", async () => {
    const hash = await Bun.password.hash(MEMBER_PASSWORD, { algorithm: "bcrypt", cost: 4 });
    return `WITH m AS (INSERT INTO family_member (display_name, is_managed) VALUES ('${username}', false) RETURNING id)
      INSERT INTO app_user (username, password_hash, member_id, role, is_activated, acknowledged_warning)
      SELECT '${username}', '${hash}', id, 'MEMBER', true, true FROM m`;
  });

const loginAs = (as: string, username: string, password: string, capture: string) =>
  post("/api/auth/login", { username, password, rememberMe: false }, {
    as,
    expect: 200,
    compare: false,
    capture: { [capture]: (r) => r.json.memberId },
  });

/** The key prefix is the secret's first random characters. */
const maskPrefix = (body: Json): Json => {
  if (Array.isArray(body)) return body.map(maskPrefix);
  if (body && typeof body === "object" && !("raw" in body)) {
    const out: { [k: string]: Json } = {};
    for (const [k, v] of Object.entries(body)) out[k] = k === "keyPrefix" ? "psk_<random>" : maskPrefix(v);
    return out;
  }
  return body;
};

const accessKeys: Scenario = {
  name: "identity.access-keys",
  description: "MCP access keys: validation, creation, listing, revocation, member isolation",
  steps: [
    ...bootstrap(),
    memberFixture("bob"),
    loginAs("member", "bob", MEMBER_PASSWORD, "bobMember"),
    get("/api/access-keys", { expect: 200, keep: maskPrefix }),
    post("/api/access-keys", { name: " ", scopes: [] }, { expect: 422 }),
    post("/api/access-keys", { name: "x".repeat(101), scopes: ["accounts:read"] }, { expect: 422 }),
    post("/api/access-keys", { name: "Old", scopes: ["accounts:read"], expiresAt: "2020-01-01T00:00:00Z" }, { expect: 422 }),
    post("/api/access-keys", { name: "Bad", scopes: ["accounts:read", "admin:everything"] }, { expect: 400 }),
    post("/api/access-keys", { name: "Claude desktop", scopes: ["transactions:read", "accounts:read"] }, {
      expect: 201,
      keep: maskPrefix,
      capture: { key: (r) => r.json.key.id },
    }),
    post("/api/access-keys", { name: "Expiring", scopes: ["goals:read"], expiresAt: day(30) + "T12:00:00Z" }, { expect: 201, keep: maskPrefix }),
    get("/api/access-keys", { expect: 200, keep: maskPrefix }),
    get("/api/access-keys", { as: "member", expect: 200 }),
    del("/api/access-keys/{key}", { as: "member", expect: 404 }),
    del("/api/access-keys/{key}", { expect: 204 }),
    del("/api/access-keys/{key}", { expect: 204, note: "revoking twice stays a success" }),
    get("/api/access-keys", { expect: 200, keep: maskPrefix }),
    del("/api/access-keys/999999", { expect: 404 }),
    del("/api/access-keys/abc", { expect: 400 }),
    get("/api/access-keys", { as: "anon", expect: 401 }),
  ],
};

/** The age moves with the calendar; the date it comes from is compared. */
const withoutAge = (body: Json) => {
  const { age: _age, ...rest } = body as { [k: string]: Json };
  return rest;
};

const profile: Scenario = {
  name: "identity.profile",
  description: "Member profile: empty read, replace, derived net income, validation, clearing",
  steps: [
    ...bootstrap(),
    get("/api/me/profile", { expect: 200 }),
    put("/api/me/profile", {
      birthDate: "1988-03-14",
      marginalTaxRate: 30,
      householdStatus: "COUPLE",
      taxHouseholdParts: 2.5,
      dependents: 1,
      annualGrossIncome: 52000.5,
      monthlyNetBeforeTax: 3333.33,
      withholdingTaxRate: 7.5,
      monthlySavingsCapacity: 800,
      targetRetirementAge: 64,
      riskProfile: "BALANCED",
    }, { expect: 200, keep: withoutAge }),
    get("/api/me/profile", { expect: 200, keep: withoutAge }),
    put("/api/me/profile", { birthDate: day(1), marginalTaxRate: 101, taxHouseholdParts: 0.5, dependents: 21, targetRetirementAge: 39, monthlySavingsCapacity: -1 }, { expect: 422 }),
    put("/api/me/profile", { householdStatus: "WIDOWED" }, { expect: 400 }),
    put("/api/me/profile", { monthlyNetBeforeTax: 2000 }, { expect: 200 }),
    get("/api/me/profile", { expect: 200 }),
    get("/api/me/profile", { as: "anon", expect: 401 }),
  ],
};

const signInMethods: Scenario = {
  name: "identity.sign-in-methods",
  description: "SSO info, sign-in methods, the re-auth proof and the guards of password login",
  steps: [
    ...bootstrap(),
    get("/api/auth/sso", { as: "anon", expect: 200 }),
    get("/api/auth/sign-in-methods", { expect: 200 }),
    del("/api/auth/password", { expect: 401, note: "no re-auth proof yet" }),
    post("/api/auth/reauth", { password: "not-the-password" }, { expect: 401 }),
    post("/api/auth/reauth", {}, { expect: 401 }),
    post("/api/auth/reauth", { password: ADMIN.password }, { expect: 204 }),
    del("/api/auth/password", { expect: 409 }),
    put("/api/auth/password", { newPassword: "short" }, { expect: 422 }),
    put("/api/auth/password", { newPassword: "a-long-enough-password" }, { expect: 409 }),
    del("/api/auth/identities/12345", { expect: 404 }),
    del("/api/auth/identities/12345", { as: "other", expect: 401 }),
    get("/api/auth/sign-in-methods", { as: "anon", expect: 401 }),
  ],
};

const adminMembers: Scenario = {
  name: "identity.admin-members",
  description: "An admin resets another member's 2FA and SSO identities, never their own",
  steps: [
    ...bootstrap(),
    memberFixture("carol"),
    loginAs("member", "carol", MEMBER_PASSWORD, "carolMember"),
    loginAs("admin", ADMIN.username, ADMIN.password, "adminMember"),
    del("/api/admin/members/{carolMember}/mfa", { expect: 204 }),
    del("/api/admin/members/{adminMember}/mfa", { expect: 403 }),
    del("/api/admin/members/999999/mfa", { expect: 404 }),
    del("/api/admin/members/{carolMember}/identities", { expect: 204 }),
    del("/api/admin/members/{adminMember}/identities", { expect: 403 }),
    del("/api/admin/members/999999/identities", { expect: 404 }),
    get("/api/access-keys", { as: "member", expect: 401, note: "the identity reset bumped carol's token version" }),
    del("/api/admin/members/{adminMember}/mfa", { as: "anon", expect: 401 }),
  ],
};

export const scenarios: Scenario[] = [accessKeys, profile, signInMethods, adminMembers];

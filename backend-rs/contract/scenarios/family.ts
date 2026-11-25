import { bootstrap, inviteMember, manualAccount, MEMBER_PASSWORD } from "../lib/flows.ts";
import { del, get, post, put, sql, type Scenario } from "../lib/scenario.ts";

const members: Scenario = {
  name: "family.members",
  description: "Admin creates and invites a member; the member activates, signs in, and is not an admin",
  steps: [
    ...bootstrap(),
    post("/api/family/members", { displayName: "Camille", avatarColor: "#16a34a" }, { expect: 201, capture: { camilleId: (r) => r.json.id } }),
    get("/api/family/members", { expect: 200 }),
    post("/api/family/members/{camilleId}/activate", undefined, {
      expect: 200,
      capture: { camilleToken: (r) => String(r.json.activationLink).split("/").pop() },
    }),
    get("/api/family/members", { expect: 200, capture: { camilleLogin: (r) => r.json.find((m: { displayName: string }) => m.displayName === "Camille")?.loginName } }),
    post("/api/auth/activate/{camilleToken}", { password: "contract-member-pass-1", acknowledgedWarning: false }, { as: "camille", expect: 422 }),
    post("/api/auth/activate/{camilleToken}", { password: "contract-member-pass-1", acknowledgedWarning: true }, { as: "camille", expect: 200 }),
    post("/api/auth/activate/{camilleToken}", { password: "contract-member-pass-1", acknowledgedWarning: true }, { as: "camille", expect: 401 }),
    post("/api/auth/login", (v) => ({ username: v.str("camilleLogin"), password: "contract-member-pass-1", rememberMe: false }), { as: "camille", expect: 200 }),
    get("/api/family/members", { as: "camille", expect: 403 }),
    get("/api/accounts", { as: "camille", expect: 200 }),
    put("/api/family/members/{camilleId}", { displayName: "Camille D." }, { expect: 200 }),
    put("/api/family/members/{camilleId}", { displayName: " " }, { expect: 400 }),
    get("/api/family/members", { expect: 200 }),
  ],
};

const sharing: Scenario = {
  name: "family.sharing",
  description: "A member shares accounts with the family; the admin sees them in the family dashboard",
  steps: [
    ...bootstrap(),
    ...inviteMember("camille", "Camille"),
    manualAccount("camilleChecking", { name: "Compte Camille", currentBalance: 840.1 }, { as: "camille" }),
    manualAccount("camilleSavings", { name: "Épargne Camille", type: "LIVRET_A", currentBalance: 5000 }, { as: "camille" }),
    manualAccount("adminChecking", { name: "Compte Admin", currentBalance: 100 }),
    get("/api/family/sharing?resourceType=ACCOUNT", { as: "camille", expect: 200 }),
    get("/api/family/dashboard", { expect: 200 }),
    put("/api/family/sharing", (v) => ({ resourceType: "ACCOUNT", sharingLevel: "MANUAL", sharedResourceIds: [v.id("camilleSavings")] }), { as: "camille", expect: 200 }),
    get("/api/family/sharing?resourceType=ACCOUNT", { as: "camille", expect: 200 }),
    get("/api/family/dashboard", { expect: 200 }),
    put("/api/family/sharing", { resourceType: "ACCOUNT", sharingLevel: "ALL" }, { as: "camille", expect: 200 }),
    get("/api/family/dashboard", { expect: 200 }),
    get("/api/accounts/{camilleChecking}", { expect: 404 }),
    put("/api/family/sharing", { resourceType: "BOGUS", sharingLevel: "ALL" }, { as: "camille", expect: 422 }),
    get("/api/family/sharing?resourceType=GOAL", { as: "camille", expect: 200 }),
  ],
};

/** bcrypt (cost 4) of MEMBER_PASSWORD, for logins written as fixtures rather than activated through T1's endpoint. */
const MEMBER_HASH = "$2b$04$gym41n5a/0nfYumcZdzdhuv.JvvUHSloAVphc6qhWRJm1exSGfYEW";

const loginOf = (displayName: string) =>
  `UPDATE app_user SET password_hash = '${MEMBER_HASH}', is_activated = true, acknowledged_warning = true, activation_token = NULL ` +
  `WHERE member_id = (SELECT id FROM family_member WHERE display_name = '${displayName}')`;

const memberOf = (displayName: string) => `(SELECT id FROM family_member WHERE display_name = '${displayName}')`;

const admin: Scenario = {
  name: "family.member-admin",
  description: "Member management without the activation endpoint: validation, derived usernames, rename, reset links, delete guards, member 403",
  steps: [
    ...bootstrap(),
    post("/api/family/members", { displayName: " " }, { expect: 422 }),
    post("/api/family/members", { displayName: "x".repeat(101) }, { expect: 422 }),
    post("/api/family/members", { displayName: "Jean Dupont" }, { expect: 201, capture: { jean: (r) => r.json.id } }),
    post("/api/family/members", { displayName: "Jean-Dupont", avatarColor: "#16a34a" }, { expect: 201, capture: { jean2: (r) => r.json.id } }),
    post("/api/family/members", { displayName: "Kid" }, { expect: 201, capture: { kid: (r) => r.json.id } }),
    get("/api/family/members", { expect: 200, capture: { adminId: (r) => r.json.find((m: { loginName?: string }) => m.loginName === "admin")?.id } }),
    post("/api/family/members/{jean}/activate", undefined, { expect: 200 }),
    post("/api/family/members/{jean2}/activate", undefined, { expect: 200 }),
    post("/api/family/members/{jean2}/activate", undefined, { expect: 200 }),
    post("/api/family/members/{adminId}/activate", undefined, { expect: 400 }),
    post("/api/family/members/999999/activate", undefined, { expect: 404 }),
    get("/api/family/members", { expect: 200 }),
    put("/api/family/members/{jean}", { displayName: "Jean D." }, { expect: 200 }),
    put("/api/family/members/{jean}", {}, { expect: 400 }),
    put("/api/family/members/999999", { displayName: "Nobody" }, { expect: 404 }),
    post("/api/family/members/{jean2}/reset-password", undefined, { expect: 200 }),
    post("/api/family/members/{kid}/reset-password", undefined, { expect: 400 }),
    post("/api/family/members/999999/reset-password", undefined, { expect: 404 }),
    del("/api/family/members/{adminId}", { expect: 403 }),
    del("/api/family/members/999999", { expect: 404 }),
    del("/api/family/members/{jean2}", { expect: 204 }),
    del("/api/family/members/{kid}", { expect: 204 }),
    get("/api/family/members", { expect: 200 }),
    sql("give Jean a password login", loginOf("Jean D.")),
    post("/api/auth/login", { username: "jean.dupont", password: MEMBER_PASSWORD, rememberMe: false }, { as: "jean", expect: 200, compare: false }),
    get("/api/family/members", { as: "jean", expect: 403 }),
    post("/api/family/members", { displayName: "Sneaky" }, { as: "jean", expect: 403 }),
    del("/api/family/members/{adminId}", { as: "jean", expect: 403 }),
    post("/api/family/members/{jean}/reset-password", undefined, { expect: 200 }),
  ],
};

const account = (member: string, name: string, type: string, balance: number, extra = "") =>
  `INSERT INTO account (member_id, name, type, currency, current_balance, is_manual, color, created_at, updated_at${extra ? ", hidden" : ""}) ` +
  `VALUES (${memberOf(member)}, '${name}', '${type}', 'EUR', ${balance}, true, '#000000', now(), now()${extra ? `, ${extra}` : ""})`;

const accountId = (name: string) => `(SELECT id FROM account WHERE name = '${name}')`;

const dashboard: Scenario = {
  name: "family.dashboard",
  description: "Family dashboard: loans negative, hidden accounts and pockets, manual shares, shared goals and their contributions",
  steps: [
    ...bootstrap(),
    post("/api/family/members", { displayName: "Bob" }, { expect: 201, compare: false }),
    get("/api/family/members", { expect: 200, compare: false, capture: { bobId: (r) => r.json.find((m: { displayName: string }) => m.displayName === "Bob")?.id } }),
    post("/api/family/members/{bobId}/activate", undefined, { expect: 200, compare: false }),
    sql("give Bob a password login", loginOf("Bob")),
    post("/api/auth/login", { username: "bob", password: MEMBER_PASSWORD, rememberMe: false }, { as: "bob", expect: 200, compare: false }),
    sql("Bob's accounts", [
      account("Bob", "Bob Checking", "CHECKING", 2000),
      account("Bob", "Bob Mortgage", "LOAN", 10000),
      account("Bob", "Bob Hidden", "SAVINGS", 7, "true"),
      account("Bob", "Bob Pocket", "CHECKING", 400),
      `UPDATE account SET parent_account_id = ${accountId("Bob Checking")} WHERE name = 'Bob Pocket'`,
      account("Admin", "Admin Checking", "CHECKING", 50),
    ].join("; ")),
    sql("Bob's goals and contributions", [
      `INSERT INTO goal (member_id, name, target_amount, deadline) VALUES (${memberOf("Bob")}, 'House', 50000, '2099-01-01')`,
      `INSERT INTO goal (member_id, name, target_amount, deadline) VALUES (${memberOf("Bob")}, 'Trip', 1200, '2099-01-01')`,
      `INSERT INTO goal_account (goal_id, account_id) SELECT g.id, a.id FROM goal g, account a WHERE g.name = 'House' AND a.name IN ('Bob Checking', 'Bob Mortgage')`,
      `INSERT INTO goal_manual_contribution (goal_id, member_id, year_month, amount) SELECT id, ${memberOf("Bob")}, '2024-01', 100.50 FROM goal WHERE name = 'House'`,
      `INSERT INTO goal_manual_contribution (goal_id, member_id, year_month, amount) SELECT id, ${memberOf("Admin")}, '2024-02', 25 FROM goal WHERE name = 'House'`,
    ].join("; ")),
    get("/api/family/dashboard", { expect: 200 }),
    put("/api/family/sharing", { resourceType: "ACCOUNT", sharingLevel: "ALL" }, { as: "bob", expect: 200 }),
    put("/api/family/sharing", { resourceType: "GOAL", sharingLevel: "ALL" }, { as: "bob", expect: 200 }),
    get("/api/family/dashboard", {
      expect: 200,
      capture: {
        house: (r) => r.json.sharedGoals.find((g: { name: string }) => g.name === "House")?.id,
        trip: (r) => r.json.sharedGoals.find((g: { name: string }) => g.name === "Trip")?.id,
        pocket: (r) => r.json.sharedAccounts.find((a: { name: string }) => a.name === "Bob Pocket")?.id,
      },
    }),
    get("/api/family/dashboard", { as: "bob", expect: 200 }),
    get("/api/family/goals/{house}/contributions", { expect: 200 }),
    get("/api/family/goals/{house}/contributions", { as: "bob", expect: 200 }),
    put("/api/family/sharing", (v) => ({ resourceType: "GOAL", sharingLevel: "MANUAL", sharedResourceIds: [v.id("trip")] }), { as: "bob", expect: 200 }),
    get("/api/family/goals/{house}/contributions", { expect: 403 }),
    get("/api/family/goals/{trip}/contributions", { expect: 200 }),
    get("/api/family/goals/999999/contributions", { expect: 404 }),
    put("/api/family/sharing", (v) => ({ resourceType: "ACCOUNT", sharingLevel: "MANUAL", sharedResourceIds: [v.id("pocket"), v.id("pocket")] }), { as: "bob", expect: 200 }),
    get("/api/family/sharing?resourceType=ACCOUNT", { as: "bob", expect: 200 }),
    get("/api/family/dashboard", { expect: 200 }),
    put("/api/family/sharing", { resourceType: "ACCOUNT", sharingLevel: "MANUAL", sharedResourceIds: [999999] }, { as: "bob", expect: 400 }),
    put("/api/family/sharing", { resourceType: "GOAL" }, { as: "bob", expect: 422 }),
    put("/api/family/sharing", { resourceType: "ACCOUNT", sharingLevel: "MANUAL", sharedResourceIds: [1, null] }, { as: "bob", expect: 422 }),
    put("/api/family/sharing", { resourceType: "ACCOUNT", sharingLevel: "SOME" }, { as: "bob", expect: 400 }),
    get("/api/family/sharing", { as: "bob", expect: 400 }),
    get("/api/family/sharing?resourceType=GOAL", { as: "bob", expect: 200 }),
  ],
};

export const scenarios = [members, sharing, admin, dashboard];

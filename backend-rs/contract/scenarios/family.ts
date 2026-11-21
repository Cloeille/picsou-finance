import { bootstrap, inviteMember, manualAccount } from "../lib/flows.ts";
import { get, post, put, type Scenario } from "../lib/scenario.ts";

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

export const scenarios = [members, sharing];

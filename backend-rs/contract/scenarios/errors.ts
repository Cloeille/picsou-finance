import { bootstrap, inviteMember, manualAccount } from "../lib/flows.ts";
import { del, get, post, put, type Scenario } from "../lib/scenario.ts";

const errors: Scenario = {
  name: "errors.problem-details",
  description: "404, 400, 401, 403 and 422 bodies across controllers, and member isolation",
  steps: [
    ...bootstrap(),
    ...inviteMember("member", "Dominique"),
    manualAccount("adminAccount", { name: "Compte admin", currentBalance: 10 }),
    get("/api/accounts", { as: "anon", expect: 401 }),
    get("/api/dashboard", { as: "anon", expect: 401 }),
    get("/api/accounts/999999", { expect: 404 }),
    get("/api/accounts/not-a-number", { expect: 400 }),
    get("/api/goals/999999", { expect: 404 }),
    get("/api/accounts/{adminAccount}", { as: "member", expect: 404 }),
    put("/api/accounts/{adminAccount}", { name: "pirate", type: "CHECKING", currency: "EUR", isManual: true }, { as: "member", expect: 404 }),
    del("/api/accounts/{adminAccount}", { as: "member", expect: 404 }),
    get("/api/family/members", { as: "member", expect: 403 }),
    post("/api/accounts", { name: "", type: "NOPE", currency: "EUR", isManual: true }, { expect: 400 }),
    post("/api/accounts", { name: "", type: "CHECKING", currency: "", isManual: true, color: "red", currentBalance: -5 }, { expect: 422 }),
    post("/api/accounts", { name: "", type: "CHECKING", currency: "EUR", isManual: true }, {
      expect: 422,
      headers: { "accept-language": "fr-FR,fr;q=0.9" },
      note: "a French browser: does the validation message follow Accept-Language?",
    }),
    post("/api/accounts", undefined, { raw: { contentType: "application/json", body: "{\"name\":" }, expect: 400 }),
    post("/api/accounts", undefined, { raw: { contentType: "text/plain", body: "hello" } }),
    put("/api/accounts/{adminAccount}/ownership", (v) => ({ shares: [{ memberId: v.id("memberId"), sharePercent: 50 }] }), { expect: 422 }),
    get("/api/does-not-exist", { expect: 404 }),
    post("/api/dashboard"),
    get("/api/history?accountIds=abc", { expect: 400 }),
  ],
};

export const scenarios = [errors];

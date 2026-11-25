import { bootstrap } from "../lib/flows.ts";
import { day, del, get, post, put, sql, type Scenario } from "../lib/scenario.ts";

// Accounts are written with SQL: the account endpoints belong to another track.
const account = (id: number, name: string, type: string) =>
  sql(`${name} account`, () =>
    `INSERT INTO account (id, member_id, name, type, is_manual, current_balance) SELECT ${id}, member_id, '${name}', '${type}'::account_type, true, 0 FROM app_user WHERE username = 'admin'`);

const lifecycle: Scenario = {
  name: "peer_loans.lifecycle",
  description: "A peer loan: unconfigured read, terms with the cash leg, repayments, cap, closure, reopening, simulation",
  steps: [
    ...bootstrap(),
    account(900001, "Alice", "PEER_LOAN"),
    account(900002, "Courant", "CHECKING"),
    get("/api/peer-loans/overview", { expect: 200 }),
    get("/api/accounts/900001/peer-loan", { expect: 200 }),
    get("/api/accounts/900002/peer-loan", { expect: 400 }),
    put("/api/accounts/900001/peer-loan", { principal: 0, annualRate: 2000 }, { expect: 422 }),
    put("/api/accounts/900001/peer-loan", { principal: 1000, lentAt: day(-30), linkedAccountId: 900002, plannedMonths: 3 }, { expect: 400 }),
    put("/api/accounts/900001/peer-loan", { principal: 1000, lentAt: day(-30), annualRate: 0.05, linkedAccountId: 900002, note: "Voiture" }, { expect: 200 }),
    get("/api/accounts/900001/peer-loan", { expect: 200 }),
    post("/api/accounts/900001/peer-loan/repayments", { paidOn: day(-10), amount: 5000 }, { expect: 400 }),
    post("/api/accounts/900001/peer-loan/repayments", { paidOn: day(-10), amount: 250.5, note: "first" }, { expect: 201, capture: { first: (r) => r.json.id } }),
    post("/api/accounts/900001/peer-loan/repayments", { paidOn: day(-5), amount: 100, withCashLeg: false }, { expect: 201, capture: { second: (r) => r.json.id } }),
    put("/api/accounts/900001/peer-loan/repayments/{second}", { paidOn: day(-4), amount: 120 }, { expect: 200 }),
    post("/api/accounts/900001/peer-loan/repayments", { paidOn: day(-3), amount: 10, kind: "WRITE_OFF" }, { expect: 400 }),
    get("/api/accounts/900001/peer-loan", { expect: 200 }),
    get("/api/peer-loans/open", { expect: 200 }),
    post(`/api/accounts/900001/peer-loan/close?date=${day(-1)}`, undefined, { expect: 200, capture: { writeOff: (r) => r.json.repayments.find((x: { kind: string }) => x.kind === "WRITE_OFF").id } }),
    post("/api/accounts/900001/peer-loan/repayments", { paidOn: day(0), amount: 1 }, { expect: 400 }),
    del("/api/accounts/900001/peer-loan/repayments/{writeOff}", { expect: 204 }),
    del("/api/accounts/900001/peer-loan/repayments/{first}", { expect: 204 }),
    get("/api/accounts/900001/peer-loan", { expect: 200 }),
    del("/api/accounts/900001/peer-loan/repayments/999999", { expect: 404 }),
    post("/api/peer-loans/simulate", { principal: 1200, months: 12, annualRate: 0.05 }, { expect: 200 }),
    post("/api/peer-loans/simulate", { principal: 1200, monthlyPayment: 300 }, { expect: 200 }),
    post("/api/peer-loans/simulate", { principal: 1200 }, { expect: 400 }),
    post("/api/peer-loans/from-transaction", { transactionId: 999999 }, { expect: 404 }),
  ],
};

export const scenarios = [lifecycle];

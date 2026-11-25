import { bootstrap, manualAccount } from "../lib/flows.ts";
import { day, del, get, post, put, sql, type Scenario } from "../lib/scenario.ts";

const manual: Scenario = {
  name: "accounts.manual",
  description: "Manual accounts: create, update, hide, collapse, reorder, sub-account, delete",
  steps: [
    ...bootstrap(),
    manualAccount("checking", { name: "Compte courant", currentBalance: 1250.5, color: "#2563eb", provider: "Ma Banque" }),
    manualAccount("livret", { name: "Livret A", type: "LIVRET_A", currentBalance: 10000 }),
    manualAccount("pocket", { name: "Poche voyage", type: "SAVINGS", currentBalance: 300.25 }),
    get("/api/accounts", { expect: 200 }),
    get("/api/accounts/{checking}", { expect: 200 }),
    put("/api/accounts/{checking}", { name: "Compte joint", type: "CHECKING", currency: "EUR", isManual: true, currentBalance: 1300, color: "#dc2626", openedAt: "2020-03-15" }, { expect: 200 }),
    put("/api/accounts/{livret}/visibility", { hidden: true }, { expect: 200 }),
    get("/api/accounts", { expect: 200 }),
    get("/api/accounts?includeHidden=true", { expect: 200 }),
    put("/api/accounts/{livret}/visibility", { hidden: false }, { expect: 200 }),
    put("/api/accounts/{checking}/display", { collapsed: true }, { expect: 200 }),
    put("/api/accounts/display-order", (v) => ({ accountIds: [v.id("pocket"), v.id("livret"), v.id("checking")] }), { expect: 200 }),
    get("/api/accounts", { expect: 200 }),
    sql("make the pocket a sub-account of the checking account, as a Revolut sync does", (v) =>
      `UPDATE account SET parent_account_id = ${v.id("checking")} WHERE id = ${v.id("pocket")}`),
    get("/api/accounts", { expect: 200 }),
    get("/api/accounts/{pocket}", { expect: 200 }),
    post("/api/accounts/{livret}/history", { balance: 9500, date: day(-2) }, { expect: 201 }),
    get("/api/accounts/{livret}/history", { expect: 200 }),
    get("/api/accounts/{livret}/deletion-impact", { expect: 200 }),
    del("/api/accounts/{livret}", { expect: 204 }),
    get("/api/accounts/{livret}", { expect: 404 }),
    put("/api/accounts/display-order", { accountIds: [] }, { expect: 200 }),
    get("/api/accounts", { expect: 200 }),
  ],
};

const transactions: Scenario = {
  name: "accounts.transactions",
  description: "Manual transactions on a checking account: add, categorize, edit, delete, global list",
  steps: [
    ...bootstrap(),
    manualAccount("checking", { name: "Courant", currentBalance: 1000 }),
    get("/api/categories", { expect: 200, capture: { groceries: (r) => r.json.find((c: { kind: string }) => c.kind === "EXPENSE")?.id } }),
    post("/api/accounts/{checking}/transactions", { date: day(-1), description: "Salaire", amount: 2500.0, txType: "DEPOSIT" }, { expect: 201, capture: { salary: (r) => r.json.id } }),
    post("/api/accounts/{checking}/transactions", (v) => ({ date: day(0), description: "Courses", amount: -82.4, txType: "WITHDRAWAL", categoryId: v.id("groceries") }), {
      expect: 201,
      capture: { groceriesTx: (r) => r.json.id },
    }),
    post("/api/accounts/{checking}/transactions", { date: "2019-06-30", description: "Old refund", amount: 12.345, txType: null }, { expect: 201 }),
    get("/api/accounts/{checking}/transactions", { expect: 200 }),
    get("/api/accounts/{checking}", { expect: 200 }),
    put("/api/accounts/{checking}/transactions/{groceriesTx}", { date: day(0), description: "Courses marché", amount: -90, txType: "WITHDRAWAL" }, { expect: 200 }),
    get("/api/transactions", { expect: 400 }),
    get(`/api/transactions?from=${day(-7)}&to=${day(0)}`, { expect: 200 }),
    get("/api/transactions/uncategorized", { expect: 200 }),
    put("/api/transactions/{salary}/category", (v) => ({ categoryId: v.id("groceries"), createRule: false }), { expect: 204 }),
    del("/api/accounts/{checking}/transactions/{groceriesTx}", { expect: 204 }),
    get("/api/accounts/{checking}/transactions", { expect: 200 }),
    post("/api/accounts/{checking}/transactions", { description: "", amount: null }, { expect: 422 }),
    del("/api/accounts/{checking}/transactions/999999", { expect: 404 }),
  ],
};

export const scenarios = [manual, transactions];

import { bootstrap, manualAccount } from "../lib/flows.ts";
import { day, del, get, post, put, type Scenario } from "../lib/scenario.ts";

const categories: Scenario = {
  name: "budget.categories",
  description: "Default categories, create a root and a child, rename, archive, unarchive, invalid parent",
  steps: [
    ...bootstrap(),
    get("/api/categories", { expect: 200 }),
    post("/api/categories", { name: "Loisirs", kind: "EXPENSE", color: "#f59e0b", icon: "gamepad" }, { expect: 201, capture: { leisure: (r) => r.json.id } }),
    post("/api/categories", (v) => ({ name: "Cinéma", kind: "EXPENSE", parentId: v.id("leisure") }), { expect: 201, capture: { cinema: (r) => r.json.id } }),
    post("/api/categories", (v) => ({ name: "Prime", kind: "INCOME", parentId: v.id("leisure") }), { expect: 400 }),
    put("/api/categories/{cinema}", (v) => ({ name: "Cinéma & théâtre", kind: "EXPENSE", parentId: v.id("leisure"), sortOrder: 3 }), { expect: 200 }),
    del("/api/categories/{cinema}"),
    get("/api/categories", { expect: 200 }),
    post("/api/categories/{cinema}/unarchive"),
    post("/api/categories", { name: "" }, { expect: 422 }),
    get("/api/categories", { expect: 200 }),
  ],
};

const budgets: Scenario = {
  name: "budget.envelopes",
  description: "Budget settings and a monthly envelope with spending against it",
  steps: [
    ...bootstrap(),
    manualAccount("checking", { name: "Courant", currentBalance: 500 }),
    get("/api/budget/settings", { expect: 200 }),
    put("/api/budget/settings", { cycleStartDay: 1, logoFetchEnabled: false, aiCategorizationEnabled: false, aiMode: "SUGGEST", aiConfidenceThreshold: 80 }, { expect: 200 }),
    post("/api/categories", { name: "Courses", kind: "EXPENSE" }, { expect: 201, capture: { food: (r) => r.json.id } }),
    post("/api/budgets", (v) => ({ categoryId: v.id("food"), monthlyLimit: 400.0 }), { expect: 201, capture: { budget: (r) => r.json.id } }),
    post("/api/accounts/{checking}/transactions", (v) => ({ date: day(0), description: "Supermarché", amount: -123.45, txType: "WITHDRAWAL", categoryId: v.id("food") }), { expect: 201 }),
    get("/api/budgets", { expect: 200 }),
    put("/api/budgets/{budget}", (v) => ({ categoryId: v.id("food"), monthlyLimit: 450.5 }), { expect: 200 }),
    get("/api/budgets", { expect: 200 }),
    get("/api/spending/by-category", { expect: 200 }),
    post("/api/budgets", (v) => ({ categoryId: v.id("food"), monthlyLimit: -1 }), { expect: 422 }),
    del("/api/budgets/{budget}", { expect: 204 }),
    get("/api/budgets", { expect: 200 }),
  ],
};

export const scenarios = [categories, budgets];

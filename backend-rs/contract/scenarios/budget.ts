import { bootstrap } from "../lib/flows.ts";
import { day, del, get, monthEdge, post, put, sql, type Scenario } from "../lib/scenario.ts";

const ME = "(SELECT member_id FROM app_user WHERE username = 'admin')";

/** A manual account of the admin, written straight to the database (the accounts API is another track's). */
const account = (name: string, type: string, extra: { hidden?: boolean; dueDate?: string; dueAmount?: string } = {}) =>
  sql(
    `account ${name}`,
    `INSERT INTO account (member_id, name, type, currency, current_balance, is_manual, color, hidden, payment_due_date, payment_due_amount, created_at, updated_at)
     VALUES (${ME}, '${name}', '${type}', 'EUR', 1000, true, '#0ea5e9', ${extra.hidden ?? false}, ${extra.dueDate ? `DATE '${extra.dueDate}'` : "NULL"}, ${extra.dueAmount ?? "NULL"}, now(), now())`,
  );

/** A synced row on the named account, categorized with the named category when given. */
const tx = (accountName: string, date: string, amount: string, label: string, category?: string) =>
  sql(
    `transaction ${label} ${date}`,
    `INSERT INTO "transaction" (account_id, date, description, amount, counterparty, category_id, native_currency, created_at, is_manual, budget_neutral, category_manual)
     SELECT a.id, DATE '${date}', '${label}', ${amount}, '${label}', ${category ? `(SELECT id FROM category WHERE name = '${category}' AND member_id = ${ME})` : "NULL"}, 'EUR', now(), false, false, false
     FROM account a WHERE a.name = '${accountName}'`,
  );

const category = (name: string, kind: string, capture: string, parent?: string) =>
  post("/api/categories", (v) => ({ name, kind, ...(parent ? { parentId: v.id(parent) } : {}) }), {
    expect: 201,
    capture: { [capture]: (r) => r.json.id },
  });

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
  description: "Budget settings and envelopes: spent from outflows and refunds, parent rollup, overlap rules, validation",
  steps: [
    ...bootstrap(),
    get("/api/budget/settings", { expect: 200 }),
    put("/api/budget/settings", { cycleStartDay: 1, logoFetchEnabled: false, aiCategorizationEnabled: false, aiMode: "SUGGEST", aiConfidenceThreshold: 80 }, { expect: 200 }),
    put("/api/budget/settings", { cycleStartDay: 0, aiConfidenceThreshold: 120 }, { expect: 422 }),
    account("Courant", "CHECKING"),
    account("Doublon", "CHECKING", { hidden: true }),
    category("Courses", "EXPENSE", "food"),
    category("Snacks", "EXPENSE", "snacks", "food"),
    post("/api/budgets", (v) => ({ categoryId: v.id("food"), monthlyLimit: 400.0 }), { expect: 201, capture: { budget: (r) => r.json.id } }),
    tx("Courant", monthEdge(0, "first"), "-123.45", "Supermarché", "Courses"),
    tx("Courant", monthEdge(0, "first"), "-20.10", "Chips", "Snacks"),
    tx("Courant", monthEdge(0, "first"), "15", "Remboursement", "Courses"),
    tx("Courant", monthEdge(-1, "last"), "-999", "Mois dernier", "Courses"),
    tx("Doublon", monthEdge(0, "first"), "-500", "Copie", "Courses"),
    get("/api/budgets", { expect: 200 }),
    put("/api/budgets/{budget}", (v) => ({ categoryId: v.id("food"), monthlyLimit: 120.5 }), { expect: 200 }),
    post("/api/budgets", (v) => ({ categoryId: v.id("snacks"), monthlyLimit: 10 }), { expect: 400 }),
    post("/api/budgets", (v) => ({ categoryId: v.id("food"), monthlyLimit: 10 }), { expect: 400 }),
    post("/api/budgets", (v) => ({ categoryId: v.id("food"), monthlyLimit: -1 }), { expect: 422 }),
    post("/api/budgets", { categoryId: 999999, monthlyLimit: 1 }, { expect: 404 }),
    get("/api/spending/by-category", { expect: 200 }),
    del("/api/budgets/{budget}", { expect: 204 }),
    del("/api/budgets/{budget}", { expect: 404 }),
    post("/api/budgets", (v) => ({ categoryId: v.id("snacks"), monthlyLimit: 0 }), { expect: 201 }),
    post("/api/budgets", (v) => ({ categoryId: v.id("food"), monthlyLimit: 10 }), { expect: 400 }),
    get("/api/budgets", { expect: 200 }),
  ],
};

const cashflow: Scenario = {
  name: "budget.cashflow",
  description: "Cashflow, Sankey flow, spending breakdown and drill, allocation, over the current cycle, a past anchor and YTD",
  steps: [
    ...bootstrap(),
    account("Courant", "CHECKING"),
    account("Livret A", "LIVRET_A"),
    account("PEA", "PEA"),
    account("Maison", "REAL_ESTATE"),
    category("Salaire net", "INCOME", "pay"),
    category("Maison", "EXPENSE", "home"),
    category("Énergie", "EXPENSE", "energy", "home"),
    category("Épargne perso", "TRANSFER", "move"),
    tx("Courant", monthEdge(0, "first"), "2500", "Employeur", "Salaire net"),
    tx("Courant", monthEdge(0, "first"), "40", "Vente", undefined),
    tx("Courant", monthEdge(0, "first"), "-900", "Loyer", "Maison"),
    tx("Courant", monthEdge(0, "first"), "-80.40", "EDF", "Énergie"),
    tx("Courant", monthEdge(0, "first"), "-12.30", "Boulangerie", undefined),
    tx("Courant", monthEdge(0, "first"), "-300", "Vers livret", "Épargne perso"),
    tx("Livret A", monthEdge(0, "first"), "300", "Depuis courant", "Épargne perso"),
    tx("PEA", monthEdge(0, "first"), "150", "Versement externe", "Épargne perso"),
    tx("Courant", "2024-06-10", "1000", "Ancien salaire", "Salaire net"),
    tx("Courant", "2024-06-12", "-1500", "Ancien achat", "Maison"),
    tx("Livret A", "2024-06-12", "-400", "Retrait", "Épargne perso"),
    get("/api/cashflow", { expect: 200 }),
    get("/api/cashflow?period=CYCLE&anchor=2024-06-15", { expect: 200 }),
    get("/api/cashflow?period=YTD&anchor=2024-06-15", { expect: 200 }),
    get("/api/cashflow?period=MONTH", { expect: 400 }),
    get("/api/cashflow?anchor=nope", { expect: 400 }),
    get("/api/cashflow/flow?period=CYCLE", { expect: 200 }),
    get("/api/cashflow/flow?period=CYCLE&anchor=2024-06-15", { expect: 200 }),
    get("/api/cashflow/flow?anchor=2020-01-15", { expect: 200 }),
    get("/api/spending/by-category?period=CYCLE", { expect: 200 }),
    get("/api/spending/by-category?period=YTD&anchor=2024-06-15", { expect: 200 }),
    get("/api/spending/category/{home}?period=CYCLE", { expect: 200 }),
    get("/api/spending/category/{energy}", { expect: 200 }),
    get("/api/spending/category/999999", { expect: 404 }),
    get("/api/allocation?period=CYCLE", { expect: 200 }),
    get("/api/allocation?period=YTD&anchor=2024-06-15", { expect: 200 }),
  ],
};

const rules: Scenario = {
  name: "budget.rules",
  description: "Rule CRUD, preview, recategorize, manual categorization learning a rule, the inbox and the cross-account search",
  steps: [
    ...bootstrap(),
    account("Courant", "CHECKING"),
    account("Caché", "CHECKING", { hidden: true }),
    category("Abonnements perso", "EXPENSE", "subs"),
    category("Autre", "EXPENSE", "other"),
    tx("Courant", day(-3), "-9.99", "NETFLIX", undefined),
    tx("Courant", day(-2), "-9.99", "NETFLIX", undefined),
    tx("Courant", day(-1), "-42.10", "CARREFOUR CITY PARIS", undefined),
    tx("Courant", day(-1), "-3.20", "Boulangerie du coin", undefined),
    tx("Caché", day(-1), "-5", "Café", undefined),
    get("/api/transactions/uncategorized", { expect: 200, capture: { netflix: (r) => r.json.find((t: { description: string }) => t.description === "NETFLIX").id } }),
    post("/api/categorization-rules/preview", { matchType: "KEYWORD", pattern: "  carrefour " }, { expect: 200 }),
    post("/api/categorization-rules/preview", { matchType: "KEYWORDS_ANY", pattern: "boulangerie netflix" }, { expect: 200 }),
    post("/api/categorization-rules/preview", { pattern: "" }, { expect: 422 }),
    post("/api/categorization-rules", (v) => ({ matchType: "KEYWORDS_ALL", pattern: " city carrefour ", categoryId: v.id("other") }), { expect: 201, capture: { rule: (r) => r.json.id } }),
    post("/api/categorization-rules", (v) => ({ matchType: "KEYWORD", pattern: "x", categoryId: 999999 }), { expect: 404 }),
    post("/api/categorization-rules/recategorize", undefined, { expect: 200 }),
    put("/api/categorization-rules/{rule}", (v) => ({ matchType: "KEYWORD", pattern: "carrefour", categoryId: v.id("other"), priority: 5 }), { expect: 200 }),
    get("/api/categorization-rules", { expect: 200 }),
    put("/api/transactions/{netflix}/category", (v) => ({ categoryId: v.id("subs"), createRule: true }), { expect: 204 }),
    put("/api/transactions/{netflix}/category", {}, { expect: 422 }),
    put("/api/transactions/999999/category", (v) => ({ categoryId: v.id("subs") }), { expect: 404 }),
    get("/api/categorization-rules", { expect: 200 }),
    get("/api/transactions/uncategorized", { expect: 200 }),
    get(`/api/transactions?from=2024-01-01&to=2099-12-31`, { expect: 200, capture: { hiddenAccount: (r) => r.json[0].accountId } }),
    get(`/api/transactions?from=2024-01-01&to=2099-12-31&categoryId={subs}`, { expect: 200 }),
    get("/api/transactions?to=2099-12-31", { expect: 400 }),
    get("/api/transactions?from=x&to=2099-12-31", { expect: 400 }),
    del("/api/categorization-rules/{rule}", { expect: 204 }),
    del("/api/categorization-rules/{rule}", { expect: 404 }),
    get("/api/transactions/categorize-ai/status", { expect: 200 }),
    post("/api/transactions/categorize-ai", undefined, { expect: 202 }),
    get("/api/merchants/1/logo", { expect: 404 }),
    get("/api/merchants/abc/logo", { expect: 400 }),
  ],
};

const recurring: Scenario = {
  name: "budget.recurring",
  description: "Manual series, detection with silent auto-confirm, activity feed and undo, triage, calendar with a card payment, errors",
  steps: [
    ...bootstrap(),
    account("Courant", "CHECKING"),
    account("Amex", "CREDIT_CARD", { dueDate: day(10), dueAmount: "120.5" }),
    category("Sport", "EXPENSE", "sport"),
    post("/api/recurring", (v) => ({ label: " Salle de sport ", counterparty: " GYM ", expectedAmount: -29.999, cadence: "WEEKLY", nextDueDate: day(3), categoryId: v.id("sport") }), { expect: 201, capture: { gym: (r) => r.json.id } }),
    post("/api/recurring", { label: "" }, { expect: 422 }),
    post("/api/recurring", { label: "x", expectedAmount: 1, cadence: "MONTHLY", categoryId: 999999 }, { expect: 404 }),
    get("/api/recurring", { expect: 200 }),
    get("/api/recurring/calendar?horizonDays=20", { expect: 200 }),
    tx("Courant", day(-21), "-4.99", "SPOTIFY", undefined),
    tx("Courant", day(-14), "-4.99", "SPOTIFY", undefined),
    tx("Courant", day(-7), "-4.99", "SPOTIFY", undefined),
    tx("Courant", day(0), "-4.99", "SPOTIFY", undefined),
    tx("Courant", day(-21), "-30", "EDF", undefined),
    tx("Courant", day(-14), "-40", "EDF", undefined),
    tx("Courant", day(-7), "-36", "EDF", undefined),
    tx("Courant", day(-2), "-7", "Unique", undefined),
    post("/api/recurring/detect", undefined, { expect: 200 }),
    get("/api/recurring", { expect: 200, capture: { spotify: (r) => r.json.find((s: { label: string }) => s.label === "SPOTIFY").id, edf: (r) => r.json.find((s: { label: string }) => s.label === "EDF").id } }),
    get("/api/recurring?status=SUGGESTED", { expect: 200 }),
    get("/api/recurring?status=NOPE", { expect: 400 }),
    get("/api/recurring/activity", { expect: 200 }),
    post("/api/recurring/{spotify}/undo", undefined, { expect: 200 }),
    post("/api/recurring/detect", undefined, { expect: 200 }),
    post("/api/recurring/{edf}/confirm", undefined, { expect: 200 }),
    put("/api/recurring/{edf}", { label: "EDF Énergie", expectedAmount: -36.5, cadence: "WEEKLY", nextDueDate: day(5) }, { expect: 200 }),
    get("/api/recurring/calendar", { expect: 200 }),
    get("/api/recurring/calendar?horizonDays=abc", { expect: 400 }),
    post("/api/recurring/{gym}/ignore", undefined, { expect: 200 }),
    del("/api/recurring/{gym}", { expect: 204 }),
    post("/api/recurring/{gym}/confirm", undefined, { expect: 404 }),
    get("/api/recurring", { expect: 200 }),
  ],
};

export const scenarios = [categories, budgets, cashflow, rules, recurring];

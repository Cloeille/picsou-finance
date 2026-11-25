import { Database } from "bun:sqlite";
import { bootstrap } from "../lib/flows.ts";
import { post, sql, type Scenario, type Vars } from "../lib/scenario.ts";

/** A synthetic Actual Budget db.sqlite with the loot-core tables the importer reads. */
function household(extra: string[] = []): Uint8Array {
  const db = new Database(":memory:");
  const statements = [
    "CREATE TABLE accounts (id TEXT PRIMARY KEY, name TEXT, offbudget INTEGER DEFAULT 0, closed INTEGER DEFAULT 0, tombstone INTEGER DEFAULT 0)",
    "CREATE TABLE category_groups (id TEXT PRIMARY KEY, name TEXT, is_income INTEGER DEFAULT 0, tombstone INTEGER DEFAULT 0)",
    "CREATE TABLE categories (id TEXT PRIMARY KEY, name TEXT, is_income INTEGER DEFAULT 0, cat_group TEXT, tombstone INTEGER DEFAULT 0)",
    "CREATE TABLE category_mapping (id TEXT PRIMARY KEY, transferId TEXT)",
    "CREATE TABLE payees (id TEXT PRIMARY KEY, name TEXT, tombstone INTEGER DEFAULT 0, transfer_acct TEXT)",
    "CREATE TABLE payee_mapping (id TEXT PRIMARY KEY, targetId TEXT)",
    "CREATE TABLE transactions (id TEXT PRIMARY KEY, isParent INTEGER DEFAULT 0, isChild INTEGER DEFAULT 0, acct TEXT, category TEXT, amount INTEGER, description TEXT, notes TEXT, date INTEGER, starting_balance_flag INTEGER DEFAULT 0, transferred_id TEXT, tombstone INTEGER DEFAULT 0, parent_id TEXT)",
    "CREATE TABLE preferences (id TEXT PRIMARY KEY, value TEXT)",
    "INSERT INTO preferences VALUES ('defaultCurrencyCode', 'EUR')",
    "INSERT INTO accounts (id, name, offbudget) VALUES ('acc-checking', 'Everyday', 0), ('acc-savings', 'Rainy day', 1)",
    "INSERT INTO accounts (id, name, tombstone) VALUES ('acc-old', 'Old card', 1)",
    "INSERT INTO category_groups (id, name, is_income) VALUES ('grp-food', 'Food', 0), ('grp-income', 'Income', 1)",
    "INSERT INTO categories (id, name, is_income, cat_group) VALUES ('cat-groceries', 'Groceries', 0, 'grp-food'), ('cat-restaurants', 'Restaurants', 0, 'grp-food'), ('cat-salary', 'Salary', 1, 'grp-income')",
    "INSERT INTO payees (id, name, transfer_acct) VALUES ('p-market', 'Market', NULL), ('p-employer', 'Employer', NULL), ('p-to-savings', '', 'acc-savings'), ('p-to-checking', '', 'acc-checking')",
    "INSERT INTO transactions (id, acct, amount, date, category, description, notes, starting_balance_flag) VALUES ('t-start', 'acc-checking', 100000, 20240101, NULL, NULL, NULL, 1)",
    "INSERT INTO transactions (id, acct, amount, date, category, description) VALUES ('t-groceries', 'acc-checking', -1234, 20240102, 'cat-groceries', 'p-market')",
    "INSERT INTO transactions (id, acct, amount, date, description, transferred_id) VALUES ('t-out', 'acc-checking', -50000, 20240115, 'p-to-savings', 't-in'), ('t-in', 'acc-savings', 50000, 20240115, 'p-to-checking', 't-out')",
    "INSERT INTO transactions (id, acct, amount, date, description, notes, isParent) VALUES ('t-split', 'acc-checking', -3000, 20240120, 'p-market', 'Weekly shop', 1)",
    "INSERT INTO transactions (id, acct, amount, date, category, notes, isChild, parent_id) VALUES ('t-split/1', 'acc-checking', -2000, 20240120, 'cat-groceries', NULL, 1, 't-split'), ('t-split/2', 'acc-checking', -1000, 20240120, 'cat-restaurants', 'Lunch', 1, 't-split')",
    "INSERT INTO transactions (id, acct, amount, date, category, description, tombstone) VALUES ('t-deleted', 'acc-checking', -777, 20240121, 'cat-groceries', 'p-market', 1)",
    "INSERT INTO transactions (id, acct, amount, date, category, description, notes) VALUES ('t-salary', 'acc-checking', 250000, 20240131, 'cat-salary', 'p-employer', 'January pay')",
    ...extra,
  ];
  for (const s of statements) db.run(s);
  const bytes = new Uint8Array(db.serialize());
  db.close();
  return bytes;
}

const upload = (content: Uint8Array | string, filename = "db.sqlite") => ({
  file: { filename, contentType: "application/octet-stream", content },
});

type Preview = { fileToken: string; accounts: { sourceId: string; name: string; suggestedType: string }[]; categories: { sourceId: string; name: string }[] };

/** The wizard's request: checking onto the user's account, savings created, categories created. */
function request(v: Vars, key: string, overrides: Record<string, unknown> = {}) {
  const p = v.get(key) as Preview;
  return {
    fileToken: p.fileToken,
    currency: "EUR",
    accountMappings: p.accounts.map((a) =>
      a.sourceId === "acc-checking"
        ? { sourceId: a.sourceId, action: "MAP_EXISTING", targetAccountId: 9001 }
        : { sourceId: a.sourceId, action: "CREATE_NEW", newAccount: { name: a.name, type: a.suggestedType, currency: "EUR" } },
    ),
    categoryMappings: p.categories.map((c) => ({ sourceId: c.sourceId, action: "CREATE_NEW", name: c.name })),
    acknowledgeLargeDeletion: false,
    ...overrides,
  };
}

const userAccount = sql(
  "the user's own checking account",
  "INSERT INTO account (id, member_id, name, type, currency, current_balance, is_manual, color, hidden, created_at, updated_at) " +
    "SELECT 9001, member_id, 'Courant', 'CHECKING', 'EUR', 12.00, true, '#6366f1', false, now(), now() FROM app_user WHERE username = 'admin'",
);

const importFlow: Scenario = {
  name: "transfer.actual.import",
  description: "Actual Budget preview, dry run, import, then a re-import that reports what it kept",
  steps: [
    ...bootstrap(),
    userAccount,
    post("/api/actual/import/preview", undefined, { multipart: upload(household()), expect: 200, capture: { first: (r) => r.json } }),
    post("/api/actual/import/plan", (v) => request(v, "first"), { expect: 200 }),
    post("/api/actual/import", (v) => request(v, "first"), { expect: 201 }),
    post("/api/actual/import", (v) => request(v, "first"), { expect: 400 }),
    post("/api/actual/import/preview", undefined, {
      multipart: upload(household([
        "UPDATE transactions SET tombstone = 1 WHERE id = 't-groceries'",
        "UPDATE transactions SET acct = 'acc-savings' WHERE id = 't-salary'",
      ])),
      expect: 200,
      capture: { second: (r) => r.json },
    }),
    post("/api/actual/import/plan", (v) => request(v, "second"), { expect: 200 }),
    post("/api/actual/import", (v) => request(v, "second"), { expect: 201 }),
  ],
};

const errors: Scenario = {
  name: "transfer.actual.errors",
  description: "Actual Budget import refusals: unsupported file, invalid request, wrong currency, foreign mapping",
  steps: [
    ...bootstrap(),
    userAccount,
    post("/api/actual/import/preview", undefined, { multipart: upload("date,amount\n2024-01-01,1", "budget.csv"), expect: 400 }),
    post("/api/actual/import/preview", undefined, { multipart: upload(household(["DROP TABLE payees"])), expect: 400 }),
    post("/api/actual/import/preview", undefined, { multipart: upload(household()), expect: 200, capture: { p: (r) => r.json } }),
    post("/api/actual/import", (v) => request(v, "p", { currency: "euro" }), { expect: 422 }),
    post("/api/actual/import/plan", (v) => request(v, "p", { currency: "USD" }), { expect: 400 }),
    post("/api/actual/import/plan", (v) => request(v, "p", { accountMappings: [] }), { expect: 400 }),
    post("/api/actual/import/plan", { fileToken: "00000000-0000-0000-0000-000000000000", currency: "EUR", accountMappings: [], categoryMappings: [] }, { expect: 400 }),
  ],
};

export const scenarios = [importFlow, errors];

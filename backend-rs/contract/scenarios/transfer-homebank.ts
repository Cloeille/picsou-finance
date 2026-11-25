import { deflateRawSync } from "node:zlib";
import { bootstrap } from "../lib/flows.ts";
import { post, sql, type Scenario } from "../lib/scenario.ts";

const CHECKING = "a12b3c4d-1111-4111-8111-111111111111";
const SAVINGS = "a12b3c4d-2222-4222-8222-222222222222";

const PAYLOAD = JSON.stringify({
  data: {
    accounts: [
      { id: CHECKING, name: "Synthetic Checking", institution: "Synthetic Bank", type: "bank", currency: { code: "EUR" }, initialAmount: { amount: 0, currencyCode: "EUR" }, isClosed: false },
      { id: SAVINGS, name: "Synthetic Savings", type: "savings", currency: { code: "EUR" }, initialAmount: { amount: 0, currencyCode: "EUR" }, isClosed: true },
    ],
    categories: [
      { id: "b12b3c4d-2222-4222-8222-222222222222", name: "Fresh produce", parentID: "b12b3c4d-1111-4111-8111-111111111111", isIncomeType: false },
      { id: "b12b3c4d-1111-4111-8111-111111111111", name: "Groceries", isIncomeType: false },
      { id: "b12b3c4d-3333-4333-8333-333333333333", name: "Salary", isIncomeType: true },
    ],
    payees: [{ id: "c12b3c4d-1111-4111-8111-111111111111", name: "Synthetic Market" }],
    transactions: [
      { id: "d12b3c4d-1111-4111-8111-111111111111", accountID: CHECKING, amount: { amount: -12.34000001, currencyCode: "EUR" }, date: { year: 2024, month: 2, day: 29 }, category: { id: "b12b3c4d-2222-4222-8222-222222222222" }, payee: { id: "c12b3c4d-1111-4111-8111-111111111111" }, memo: "Fresh food note", isForecast: false },
      { id: "d12b3c4d-2222-4222-8222-222222222222", accountID: CHECKING, amount: { amount: 2400.55, currencyCode: "EUR" }, date: { year: 2024, month: 3, day: 1 }, category: { id: "b12b3c4d-3333-4333-8333-333333333333" }, payee: { id: "c12b3c4d-1111-4111-8111-111111111111" }, isForecast: false },
      { id: "d12b3c4d-3333-4333-8333-333333333333", accountID: CHECKING, amount: { amount: -25.25, currencyCode: "EUR" }, date: { year: 2024, month: 3, day: 2 }, transferAccountID: SAVINGS, isForecast: false },
      { id: "d12b3c4d-4444-4444-8444-444444444444", accountID: SAVINGS, amount: { amount: 25.25, currencyCode: "EUR" }, date: { year: 2024, month: 3, day: 2 }, transferAccountID: CHECKING, isForecast: false },
      { id: "d12b3c4d-5555-4555-8555-555555555555", accountID: SAVINGS, amount: { amount: 99.5, currencyCode: "EUR" }, date: { year: 2024, month: 4, day: 2 }, isForecast: true },
    ],
  },
  manifest: { schemaVersion: 3, platform: "iOS", appVersion: "synthetic", appBuild: "test", exportDate: "2024-03-03" },
});

const HBK = new Uint8Array(deflateRawSync(new TextEncoder().encode(PAYLOAD)));

/** A version-1 .hbexport: big-endian version, salt, nonce, AES-256-GCM of the raw DEFLATE stream. */
async function encrypted(password: string): Promise<Uint8Array> {
  const salt = new Uint8Array(32).fill(7);
  const nonce = new Uint8Array(12).fill(9);
  const material = await crypto.subtle.importKey("raw", new TextEncoder().encode(password), "PBKDF2", false, ["deriveKey"]);
  const key = await crypto.subtle.deriveKey(
    { name: "PBKDF2", hash: "SHA-256", salt, iterations: 600_000 },
    material,
    { name: "AES-GCM", length: 256 },
    false,
    ["encrypt"],
  );
  const sealed = new Uint8Array(await crypto.subtle.encrypt({ name: "AES-GCM", iv: nonce }, key, HBK));
  return new Uint8Array([0, 0, 0, 1, ...salt, ...nonce, ...sealed]);
}

const QIF = [
  "!Account", "NSynthetic Checking", "TBank", "^",
  "!Type:Bank",
  "D29/02/2024", "T-12.34000001", "PSynthetic Market", "Mfirst note", "LFood:Fresh", "^",
  "D01/03/2024", "T-30.00", "PSynthetic Split", "Msplit note", "SFood:Fresh", "Efresh note", "$-10.00", "SHome:Energy", "$-20.00", "^",
  "D02/03/2024", "T-25.25", "L[Synthetic Savings]", "^",
  "D03/03/2024", "T1500", "PEmployer", "LSalary", "^",
  "D13/03/2024", "T-4.5", "L[Elsewhere]", "^",
  "!Account", "NSynthetic Savings", "TBank", "^",
  "!Type:Bank",
  "D02/03/2024", "T25.25", "L[Synthetic Checking]", "^",
  "!Type:Cat", "NSalary", "I", "^",
].join("\n");

const file = (filename: string, content: string | Uint8Array) => ({ filename, contentType: "application/octet-stream", content });

type Preview = { fileToken: string; accounts: { sourceId: string; name: string; currency: string }[]; categories: { sourceId: string; name: string }[] };

const createAll = (p: Preview) => ({
  fileToken: p.fileToken,
  accountMappings: p.accounts.map((a, i) => ({
    sourceId: a.sourceId,
    action: "CREATE_NEW",
    newAccount: { name: a.name, type: i === 0 ? "CHECKING" : "SAVINGS", currency: a.currency, ...(i === 0 ? { color: "#16a34a" } : {}) },
  })),
  categoryMappings: p.categories.map((c) => ({ sourceId: c.sourceId, action: "CREATE_NEW", name: c.name })),
});

const remap = (p: Preview, target: number) => ({
  fileToken: p.fileToken,
  accountMappings: [
    { sourceId: CHECKING, action: "MAP_EXISTING", targetAccountId: target },
    { sourceId: SAVINGS, action: "SKIP" },
  ],
  categoryMappings: p.categories.map((c) => ({ sourceId: c.sourceId, action: "UNCATEGORIZED" })),
});

const memberAccount = (id: number, name: string, type: string, manual: boolean) =>
  sql(`${name} account`, `INSERT INTO account (id, member_id, name, type, currency, current_balance, is_manual, color, hidden, created_at, updated_at) SELECT ${id}, member_id, '${name}', '${type}', 'EUR', 100, ${manual}, '#6366f1', false, now(), now() FROM app_user WHERE username = 'admin'`);

const ios: Scenario = {
  name: "transfer.homebank.ios",
  description: "iOS .hbk preview, import into new accounts and categories, then a deduplicated replay into the existing ones",
  steps: [
    ...bootstrap(),
    memberAccount(9001, "Courant", "CHECKING", true),
    post("/api/homebank/import/preview", undefined, {
      multipart: { file: file("synthetic.hbk", HBK) },
      expect: 200,
      capture: { first: (r) => r.json },
    }),
    post("/api/homebank/import", (v) => createAll(v.get("first") as Preview), { expect: 201 }),
    post("/api/homebank/import", (v) => createAll(v.get("first") as Preview), { expect: 400 }),
    post("/api/homebank/import/preview", undefined, {
      multipart: { file: file("synthetic.hbk", HBK) },
      expect: 200,
      capture: { second: (r) => r.json },
    }),
    post("/api/homebank/import", (v) => remap(v.get("second") as Preview, 9001), { expect: 400 }),
    post("/api/homebank/import", (v) => {
      const p = v.get("second") as Preview & { existingAccounts: { id: number; name: string }[] };
      return remap(p, p.existingAccounts.find((a) => a.name === "Synthetic Checking")!.id);
    }, { expect: 201 }),
  ],
};

const encryptedExport: Scenario = {
  name: "transfer.homebank.encrypted",
  description: "Password-protected .hbexport: the right password decodes it, a wrong or missing one is a 400",
  steps: [
    ...bootstrap(),
    post("/api/homebank/import/preview", undefined, {
      multipart: async () => ({ file: file("sample.hbexport", await encrypted("sécurité🔐")), password: "sécurité🔐" }),
      expect: 200,
    }),
    post("/api/homebank/import/preview", undefined, {
      multipart: async () => ({ file: file("sample.hbexport", await encrypted("sécurité🔐")), password: "wrong" }),
      expect: 400,
    }),
    post("/api/homebank/import/preview", undefined, {
      multipart: async () => ({ file: file("sample.hbexport", await encrypted("x")) }),
      expect: 400,
    }),
  ],
};

const qif: Scenario = {
  name: "transfer.homebank.qif",
  description: "Desktop QIF with an explicit currency: day-first dates, splits, transfers, inferred kinds, then the import",
  steps: [
    ...bootstrap(),
    post("/api/homebank/import/preview", undefined, {
      multipart: { file: file("desktop.qif", QIF), currency: "EUR" },
      expect: 200,
      capture: { preview: (r) => r.json },
    }),
    post("/api/homebank/import", (v) => createAll(v.get("preview") as Preview), { expect: 201 }),
    post("/api/homebank/import/preview", undefined, { multipart: { file: file("desktop.qif", QIF) }, expect: 400 }),
    post("/api/homebank/import/preview", undefined, { multipart: { file: file("desktop.qif", QIF), currency: "ZZZ" }, expect: 400 }),
    post("/api/homebank/import/preview", undefined, {
      multipart: { file: file("ambiguous.qif", "!Account\nNA\nTBank\n^\n!Type:Bank\nD01/02/2024\nT-1\n^\n"), currency: "USD" },
      expect: 400,
    }),
  ],
};

const errors: Scenario = {
  name: "transfer.homebank.errors",
  description: "Upload, validation and mapping errors of the HomeBank import",
  steps: [
    ...bootstrap(),
    memberAccount(9002, "PEA", "PEA", true),
    post("/api/homebank/import/preview", undefined, { multipart: { file: file("sample.zip", "x") }, expect: 400 }),
    post("/api/homebank/import/preview", undefined, { multipart: { file: file("sample.hbk", "not deflate") }, expect: 400 }),
    post("/api/homebank/import/preview", undefined, { multipart: { password: "x" }, expect: 400 }),
    post("/api/homebank/import/preview", undefined, {
      multipart: { file: file("bad.hbk", new Uint8Array(deflateRawSync(new TextEncoder().encode(PAYLOAD.replace('"day":29', '"day":30'))))) },
      expect: 400,
    }),
    post("/api/homebank/import", { fileToken: "token", accountMappings: [null], categoryMappings: [] }, { expect: 422 }),
    post("/api/homebank/import", { fileToken: " ", accountMappings: [{ action: "SKIP" }] }, { expect: 422 }),
    post("/api/homebank/import", { fileToken: "00000000-0000-0000-0000-000000000000", accountMappings: [], categoryMappings: [] }, { expect: 400 }),
    post("/api/homebank/import/preview", undefined, {
      multipart: { file: file("synthetic.hbk", HBK) },
      expect: 200,
      capture: { preview: (r) => r.json },
    }),
    post("/api/homebank/import", (v) => {
      const p = createAll(v.get("preview") as Preview);
      return { ...p, accountMappings: [{ sourceId: CHECKING, action: "MAP_EXISTING", targetAccountId: 9002 }, p.accountMappings[1]] };
    }, { expect: 400 }),
    post("/api/homebank/import", (v) => {
      const p = createAll(v.get("preview") as Preview);
      return { ...p, categoryMappings: p.categoryMappings.slice(1) };
    }, { expect: 400 }),
    post("/api/homebank/import", (v) => {
      const p = createAll(v.get("preview") as Preview);
      return { ...p, accountMappings: [p.accountMappings[0], { ...p.accountMappings[1], newAccount: { name: "x", type: "PEA", currency: "EUR" } }] };
    }, { expect: 400 }),
  ],
};

export const scenarios: Scenario[] = [ios, encryptedExport, qif, errors];

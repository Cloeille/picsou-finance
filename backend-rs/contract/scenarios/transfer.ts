import { bootstrap } from "../lib/flows.ts";
import { post, sql, type Scenario } from "../lib/scenario.ts";

// Accounts are written with SQL rather than POST /api/accounts so the transfer endpoints can be
// checked on their own, before the accounts track is merged.
const account = (id: number, name: string, type: string, balance = 0) =>
  sql(
    `${type} account ${name}`,
    `INSERT INTO account (id, member_id, name, type, currency, current_balance, is_manual, color, hidden, created_at, updated_at) ` +
      `SELECT ${id}, member_id, '${name}', '${type}', 'EUR', ${balance}, true, '#6366f1', false, now(), now() FROM app_user WHERE username = 'admin'`,
  );

const CSV = [
  "Date;Sens;Ticker;Nom;Quantité;Prix;Frais;Devise",
  "15/01/2020;ACHAT;CW8.PA;Amundi MSCI World;3;412,50;1,99;EUR",
  "20/02/2020;ACHAT;CW8.PA;Amundi MSCI World;2;398,10;1,99;EUR",
  "10/03/2020;VENTE;CW8.PA;Amundi MSCI World;1;350;0;EUR",
  "11/03/2020;VENTE;;Sans ticker;1;350;0;EUR",
].join("\n");

const STATEMENT = [
  "Date;Libellé;Débit;Crédit",
  "02/11/2024;CB CARREFOUR;45,30;",
  "03/11/2024;VIR SALAIRE;;2 100,00",
  "05/11/2024;CB BOULANGERIE;3,20;",
  "06/11/2024;;;",
].join("\n");

const csvImport: Scenario = {
  name: "transfer.csv-import",
  description: "CSV preview then import of trades into a securities account and of statement lines into a checking account",
  steps: [
    ...bootstrap(),
    account(9001, "Compte titres", "COMPTE_TITRES"),
    account(9002, "Courant", "CHECKING"),
    post("/api/accounts/9001/transactions/import/preview", undefined, {
      multipart: { file: { filename: "ordres.csv", contentType: "text/csv", content: CSV } },
      expect: 200,
      capture: { preview: (r) => r.json },
    }),
    post("/api/accounts/9001/transactions/import", (v) => {
      const p = v.get("preview") as { fileToken: string; dialect: unknown; hasHeaderRow: boolean };
      return {
        fileToken: p.fileToken,
        mapping: { date: 0, side: 1, tickerOrIsin: 2, name: 3, quantity: 4, unitPrice: 5, fees: 6, currency: 7, amount: null },
        dialect: p.dialect,
        hasHeaderRow: p.hasHeaderRow,
        feesIncludedInAmount: false,
        sideValueMap: { ACHAT: "BUY", VENTE: "SELL" },
      };
    }, { expect: 201 }),
    post("/api/accounts/9001/transactions/import", (v) => ({
      fileToken: (v.get("preview") as { fileToken: string }).fileToken,
      mapping: { date: 0 },
      dialect: { delimiter: ";", decimal: "COMMA", dateFormat: "dd/MM/yyyy" },
      hasHeaderRow: true,
      feesIncludedInAmount: false,
    }), { expect: 400 }),
    post("/api/accounts/9001/transactions/import", { fileToken: "00000000-0000-0000-0000-000000000000", mapping: {}, dialect: { delimiter: ";", decimal: "COMMA", dateFormat: "dd/MM/yyyy" }, hasHeaderRow: true, feesIncludedInAmount: false }),
    post("/api/accounts/9001/transactions/import", { fileToken: " " }, { expect: 422 }),
    post("/api/accounts/9001/transactions/import/preview", undefined, {
      multipart: { file: { filename: "empty.csv", contentType: "text/csv", content: "" } },
    }),
    account(9003, "Prêt", "LOAN"),
    post("/api/accounts/9003/transactions/import/preview", undefined, {
      multipart: { file: { filename: "releve.csv", contentType: "text/csv", content: STATEMENT } },
      expect: 400,
    }),
    post("/api/accounts/9002/transactions/import/preview", undefined, {
      multipart: { file: { filename: "releve.csv", contentType: "text/csv", content: STATEMENT } },
      expect: 200,
      capture: { statement: (r) => r.json },
    }),
    post("/api/accounts/9002/transactions/import", (v) => {
      const p = v.get("statement") as { fileToken: string; dialect: unknown; hasHeaderRow: boolean; suggestedMapping: unknown };
      return {
        fileToken: p.fileToken,
        mapping: p.suggestedMapping,
        dialect: p.dialect,
        hasHeaderRow: p.hasHeaderRow,
        feesIncludedInAmount: false,
        invertSign: false,
      };
    }, { expect: 201 }),
    post("/api/accounts/424242/transactions/import/preview", undefined, {
      multipart: { file: { filename: "ordres.csv", contentType: "text/csv", content: CSV } },
      expect: 404,
    }),
    post("/api/accounts/9001/transactions/import/preview", undefined, {
      multipart: { other: "x" },
      expect: 400,
    }),
  ],
};

const xlsxExport: Scenario = {
  name: "transfer.xlsx-export",
  description: "XLSX export of selected accounts: status, content type and disposition only",
  steps: [
    ...bootstrap(),
    account(9001, "Courant", "CHECKING", 321.09),
    sql("a rent payment", "INSERT INTO \"transaction\" (account_id, date, description, amount, native_currency, created_at, is_manual, budget_neutral, category_manual) VALUES (9001, '2021-05-04', 'Loyer', -750, 'EUR', now(), true, false, false)"),
    post("/api/accounts/export", { accountIds: [9001], labels: { date: "Date", description: "Libellé" } }, {
      expect: 200,
      headers: { accept: "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet, application/json" },
    }),
    post("/api/accounts/export", { accountIds: [], labels: {} }, { expect: 422 }),
  ],
};

const xlsxExportScope: Scenario = {
  name: "transfer.xlsx-export-scope",
  description: "XLSX export refuses an account outside the member's perimeter and more than 200 ids",
  steps: [
    ...bootstrap(),
    account(9001, "Courant", "CHECKING", 10),
    post("/api/accounts/export", { accountIds: [9001, 987654] }, {
      headers: { accept: "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet, application/json" },
    }),
    post("/api/accounts/export", { accountIds: Array.from({ length: 201 }, (_, i) => i + 1) }, { expect: 422 }),
  ],
};

export const scenarios = [csvImport, xlsxExport, xlsxExportScope];

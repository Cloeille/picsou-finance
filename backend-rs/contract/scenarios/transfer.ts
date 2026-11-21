import { bootstrap, manualAccount } from "../lib/flows.ts";
import { get, post, type Scenario } from "../lib/scenario.ts";

const CSV = [
  "Date;Sens;Ticker;Nom;Quantité;Prix;Frais;Devise",
  "15/01/2020;ACHAT;CW8.PA;Amundi MSCI World;3;412,50;1,99;EUR",
  "20/02/2020;ACHAT;CW8.PA;Amundi MSCI World;2;398,10;1,99;EUR",
  "10/03/2020;VENTE;CW8.PA;Amundi MSCI World;1;350;0;EUR",
].join("\n");

const csvImport: Scenario = {
  name: "transfer.csv-import",
  description: "CSV preview then import of investment transactions into a securities account",
  steps: [
    ...bootstrap(),
    manualAccount("cto", { name: "Compte titres", type: "COMPTE_TITRES", currentBalance: 0 }),
    post("/api/accounts/{cto}/transactions/import/preview", undefined, {
      multipart: { file: { filename: "ordres.csv", contentType: "text/csv", content: CSV } },
      expect: 200,
      capture: { preview: (r) => r.json },
    }),
    post("/api/accounts/{cto}/transactions/import", (v) => {
      const p = v.get("preview") as { fileToken: string; dialect: unknown; hasHeaderRow: boolean; suggestedMapping: unknown };
      return {
        fileToken: p.fileToken,
        mapping: { date: 0, side: 1, tickerOrIsin: 2, name: 3, quantity: 4, unitPrice: 5, fees: 6, currency: 7, amount: null },
        dialect: p.dialect,
        hasHeaderRow: p.hasHeaderRow,
        feesIncludedInAmount: false,
        sideValueMap: { ACHAT: "BUY", VENTE: "SELL" },
      };
    }, { expect: 201 }),
    get("/api/accounts/{cto}/transactions", { expect: 200 }),
    post("/api/accounts/{cto}/transactions/import", { fileToken: "00000000-0000-0000-0000-000000000000", mapping: {}, dialect: { delimiter: ";", decimal: "COMMA", dateFormat: "dd/MM/yyyy" }, hasHeaderRow: true, feesIncludedInAmount: false }),
    post("/api/accounts/{cto}/transactions/import/preview", undefined, {
      multipart: { file: { filename: "empty.csv", contentType: "text/csv", content: "" } },
    }),
  ],
};

const xlsxExport: Scenario = {
  name: "transfer.xlsx-export",
  description: "XLSX export of selected accounts: status, content type and disposition only",
  steps: [
    ...bootstrap(),
    manualAccount("checking", { name: "Courant", currentBalance: 321.09 }),
    post("/api/accounts/{checking}/transactions", { date: "2021-05-04", description: "Loyer", amount: -750, txType: "WITHDRAWAL" }, { expect: 201 }),
    post("/api/accounts/export", (v) => ({ accountIds: [v.id("checking")], labels: { date: "Date", description: "Libellé" } }), {
      expect: 200,
      headers: { accept: "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet, application/json" },
    }),
    post("/api/accounts/export", { accountIds: [], labels: {} }, { expect: 422 }),
  ],
};

export const scenarios = [csvImport, xlsxExport];

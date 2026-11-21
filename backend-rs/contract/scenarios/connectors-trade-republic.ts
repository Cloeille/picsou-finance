import { bootstrap } from "../lib/flows.ts";
import { del, get, post, sql, type Scenario } from "../lib/scenario.ts";
import { ADMIN_MEMBER, sqlMember, storedSession } from "./connectors.ts";

const BASE = "/api/tr";

const accountsCsv = [
  "name,type,balance",
  "PEA Trade Republic,PEA,15000.50",
  "CTO Trade Republic,COMPTE_TITRES,5000.00",
  "Cash TR,CHECKING,250.00",
  "Mystery,NOT_A_TYPE,12",
  "Broken line",
  "Bad balance,PEA,abc",
].join("\n");

const HEADER =
  "datetime,date,account_type,category,type,asset_class,name,symbol,shares,price,amount,fee,tax,currency,original_amount,original_currency,fx_rate,description,transaction_id";

const transactionsCsv = [
  HEADER,
  "2024-03-01T10:00:00Z,2024-03-01,PEA,TRADING,BUY,ETF,S&P 500,SPY,2,100,-200.00,0,0,EUR,,,,Buy S&P,tx-1",
  "2024-03-02T10:00:00Z,2024-03-02,DEFAULT,TRADING,SELL,STOCK,Dell,DELL,1,50,\"50,25\",0,0,EUR,,,,Sell Dell,tx-2",
  "2024-03-03T10:00:00Z,2024-03-03,DEFAULT,CASH,INTEREST,,,,,,1.10,0,0,EUR,,,,Interest,tx-3",
  "2024-03-04T10:00:00Z,2024-03-04,DEFAULT,CASH,TRANSFER_IN,,,,,,1000,0,0,EUR,,,,Deposit,tx-4",
  "2024-03-05T10:00:00Z,2024-03-05,DEFAULT,CASH,CARD_PAYMENT,,,,,,-12.00,0,0,USD,,,,Shop,tx-5",
  "2024-03-06T10:00:00Z,2024-03-06,DEFAULT,CASH,INTEREST,,,,,,1.20,0,0,EUR,,,,Interest,",
  "2024-03-07T10:00:00Z,not-a-date,DEFAULT,CASH,INTEREST,,,,,,1.30,0,0,EUR,,,,Interest,tx-7",
  "too,short",
].join("\n");

const csvFile = (content: string) => ({ file: { filename: "export.csv", contentType: "text/csv", content } });

const tradeRepublic: Scenario = {
  name: "connectors.trade-republic",
  description:
    "Trade Republic: status, failing logins and reconnect, validation, auth rate limit, CSV imports, sync without a session, stored session states, member isolation",
  steps: [
    ...bootstrap(),
    ...sqlMember("member", "dominique"),
    get(`${BASE}/status`, { expect: 200 }),
    get(`${BASE}/status`, { as: "anon", expect: 401 }),
    post(`${BASE}/auth/initiate`, { phoneNumber: "+33612345678", pin: "1234" }, { expect: 422, note: "the stub sidecar answers 503" }),
    post(`${BASE}/auth/initiate`, { phoneNumber: " ", pin: "x".repeat(129) }, { expect: 422 }),
    post(`${BASE}/auth/initiate`, { phoneNumber: "+".repeat(33), pin: "" }, { expect: 422 }),
    post(`${BASE}/auth/initiate`, {}, { expect: 422 }),
    post(`${BASE}/auth/reconnect`, {}, { expect: 422 }),
    post(`${BASE}/auth/reconnect`, { pin: "" }, { expect: 422 }),
    post(`${BASE}/auth/reconnect`, { pin: "1234" }, { expect: 409, note: "no stored phone" }),
    post(`${BASE}/auth/complete`, { processId: "p-1" }, { expect: 422, note: "in-app confirmation, the stub answers 503" }),
    post(`${BASE}/auth/complete`, { processId: "p-1", tan: "1234" }, { expect: 422 }),
    post(`${BASE}/auth/complete`, { processId: "p-1", tan: "123456" }, { expect: 422 }),
    post(`${BASE}/auth/complete`, { processId: "p-1", tan: "12345" }, { expect: 422 }),
    post(`${BASE}/auth/complete`, { processId: "", tan: "abcd" }, { expect: 422 }),
    post(`${BASE}/auth/complete`, { processId: "x".repeat(101) }, { expect: 422 }),
    post(`${BASE}/auth/complete`, {}, { expect: 422 }),
    post(`${BASE}/sync`, undefined, { expect: 422, note: "no session" }),
    del(`${BASE}/session`, { expect: 204 }),
    // CSV fallback: accounts, then the transaction history.
    post(`${BASE}/import`, undefined, {
      multipart: csvFile(accountsCsv),
      expect: 200,
      capture: { cash: (r) => r.json.find((a: { name: string }) => a.name === "Cash TR").id },
    }),
    post(`${BASE}/import`, undefined, { multipart: csvFile("name,type,balance\nCash TR,CHECKING,300.10"), expect: 200, note: "same name updates the account" }),
    post(`${BASE}/import`, undefined, { multipart: csvFile(""), expect: 200 }),
    post(`${BASE}/import`, undefined, { multipart: { other: "x" }, expect: 400, note: "no file part" }),
    post(`${BASE}/import`, { file: "x" }, { expect: 500, note: "not a multipart request" }),
    post(`${BASE}/import`, undefined, { as: "member", multipart: csvFile("name,type,balance\nCash TR,CHECKING,5"), expect: 200 }),
    post(`${BASE}/accounts/999999/transactions/import-csv`, undefined, { multipart: csvFile(transactionsCsv), expect: 404 }),
    post("/api/tr/accounts/{cash}/transactions/import-csv", undefined, { multipart: csvFile(transactionsCsv), expect: 200 }),
    post("/api/tr/accounts/{cash}/transactions/import-csv", undefined, { multipart: csvFile(transactionsCsv), expect: 200, note: "a re-import skips every row" }),
    post("/api/tr/accounts/{cash}/transactions/import-csv", undefined, { as: "member", multipart: csvFile(transactionsCsv), expect: 404 }),
    del(`${BASE}/session`, { as: "member", expect: 204 }),
    // Stored session states.
    storedSession("TRADE_REPUBLIC", { active: false, status: "FAILED", error: "SESSION_EXPIRED" }),
    get(`${BASE}/status`, { expect: 200 }),
    get(`${BASE}/status`, { as: "member", expect: 200 }),
    post(`${BASE}/auth/reconnect`, { pin: "1234" }, { expect: 409, note: "the stored state cannot be read: no phone" }),
    post(`${BASE}/auth/initiate`, { phoneNumber: "+33612345678", pin: "1234" }, { expect: 429, note: "fourth counted attempt" }),
    post(`${BASE}/auth/reconnect`, { pin: "1234" }, { expect: 429 }),
    post(`${BASE}/sync`, undefined, { expect: 422, note: "an expired session asks to reconnect" }),
    del(`${BASE}/session`, { as: "member", expect: 204 }),
    get(`${BASE}/status`, { expect: 200, note: "the member's delete left the admin's session" }),
    sql("session back to a finished sync", `UPDATE sidecar_session SET is_active = true, sync_status = 'SUCCESS', last_sync_error = NULL WHERE bank = 'TRADE_REPUBLIC'`),
    get(`${BASE}/status`, { expect: 200 }),
    sql("session queued", `UPDATE sidecar_session SET sync_status = 'QUEUED', last_sync_started_at = NULL, last_sync_completed_at = NULL WHERE bank = 'TRADE_REPUBLIC'`),
    post(`${BASE}/sync`, undefined, { expect: 200, note: "a sync in flight is left alone" }),
    sql("session idle with an unreadable state", `UPDATE sidecar_session SET sync_status = 'IDLE' WHERE bank = 'TRADE_REPUBLIC' AND member_id = ${ADMIN_MEMBER}`),
    post(`${BASE}/sync`, undefined, { expect: 422, note: "the stored state cannot be decrypted" }),
    get(`${BASE}/status`, { expect: 200 }),
    del(`${BASE}/session`, { expect: 204 }),
    get(`${BASE}/status`, { expect: 200 }),
  ],
};

export const scenarios = [tradeRepublic];

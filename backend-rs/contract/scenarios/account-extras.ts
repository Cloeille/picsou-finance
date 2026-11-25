import { bootstrap } from "../lib/flows.ts";
import { day, get, post, sql, type Scenario } from "../lib/scenario.ts";

const ADMIN_MEMBER = "(SELECT member_id FROM app_user WHERE username = 'admin')";
const CAROL = "(SELECT id FROM family_member WHERE display_name = 'Carol')";

// Fixed ids: no endpoint of this slice lists accounts, and these are far above any sequence value.
const account = (id: number, member: string, type: string, currency: string, domain?: string) =>
  `INSERT INTO account (id, member_id, name, type, currency, current_balance, is_manual, color, institution_domain, created_at, updated_at) ` +
  `VALUES (${id}, ${member}, 'Account ${id}', '${type}', '${currency}', 0, true, '#000000', ${domain ? `'${domain}'` : "NULL"}, now(), now())`;

const trade = (account: number, date: string, type: string, ticker: string, qty: string, price: string, fees = "NULL") =>
  `INSERT INTO "transaction" (account_id, date, description, amount, tx_type, ticker, name, quantity, price_per_unit, fees, created_at) ` +
  `VALUES (${account}, '${date}', 'trade', 0, '${type}', '${ticker}', '${ticker} Inc', ${qty}, ${price}, ${fees}, now())`;

const position = (account: number, product: string, ticker: string, qty: string, principal = "NULL", interest = "NULL") =>
  `INSERT INTO crypto_exchange_position (account_id, product, ticker, quantity, principal, interest) ` +
  `VALUES (${account}, '${product}', '${ticker}', ${qty}, ${principal}, ${interest})`;

const extras: Scenario = {
  name: "accounts.extras",
  description: "Realized P&L, exchange positions and the institution logo of an account, scoped to its owner",
  steps: [
    ...bootstrap(),
    post("/api/family/members", { displayName: "Carol" }, { expect: 201, compare: false }),
    sql("accounts", [
      account(900001, ADMIN_MEMBER, "PEA", "EUR"),
      account(900002, ADMIN_MEMBER, "PEA", "USD"),
      account(900003, ADMIN_MEMBER, "CRYPTO", "EUR"),
      account(900004, ADMIN_MEMBER, "CRYPTO", "EUR"),
      account(900005, ADMIN_MEMBER, "CHECKING", "USD", "chase.com"),
      account(900006, CAROL, "PEA", "EUR", "chase.com"),
    ].join("; ")),
    sql("trades", [
      trade(900001, "2024-01-02", "BUY", "AAPL", "10", "100", "5"),
      trade(900001, "2024-02-01", "BUY", "AAPL", "10", "200"),
      trade(900001, "2024-03-01", "SELL", "AAPL", "4", "180", "2.5"),
      trade(900001, "2024-03-01", "BUY", "MSFT", "3", "300.333"),
      trade(900001, "2024-04-01", "SELL", "MSFT", "3", "300.333"),
      trade(900001, "2024-05-01", "SELL", "TSLA", "2", "50"),
      trade(900001, "2024-06-01", "SELL", "AAPL", "20", "150"),
      trade(900002, "2024-01-02", "BUY", "VOO", "1", "400"),
    ].join("; ")),
    sql("exchange positions and a recorded BTC price", [
      position(900003, "SPOT", "BTC", "0.2"),
      position(900003, "STAKING", "BTC", "0.3", "0.25", "0.05"),
      position(900003, "LENDING", "NOPE", "5"),
      position(900004, "SPOT", "ETH", "2"),
      `INSERT INTO account_holding (account_id, ticker, quantity, average_buy_in, created_at, updated_at) VALUES (900003, 'BTC', 0.5, 20000, now(), now())`,
      `INSERT INTO price_snapshot (ticker, date, price_eur, created_at) VALUES ('BTC', '${day(-1)}', 30000, now())`,
    ].join("; ")),
    get("/api/accounts/900001/realized-pnl", { expect: 200 }),
    get("/api/accounts/900002/realized-pnl", { expect: 200 }),
    get("/api/accounts/900006/realized-pnl", { expect: 404 }),
    get("/api/accounts/abc/realized-pnl", { expect: 400 }),
    get("/api/accounts/900003/positions", { expect: 200 }),
    get("/api/accounts/900004/positions", { expect: 200 }),
    get("/api/accounts/900001/positions", { expect: 200 }),
    get("/api/accounts/900006/positions", { expect: 404 }),
    get("/api/accounts/900005/institution-logo", { expect: 404 }),
    sql("opt in to logos", `INSERT INTO budget_settings (member_id, logo_fetch_enabled) VALUES (${ADMIN_MEMBER}, true)`),
    get("/api/accounts/900001/institution-logo", { expect: 404 }),
    get("/api/accounts/900006/institution-logo", { expect: 404 }),
  ],
};

export const scenarios: Scenario[] = [extras];

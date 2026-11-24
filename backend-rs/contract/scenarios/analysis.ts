import { bootstrap, login, MEMBER_PASSWORD } from "../lib/flows.ts";
import { del, get, post, put, sql, type Scenario } from "../lib/scenario.ts";

// Tickers are fictitious so no live quote (Yahoo, CoinGecko) can reach either backend.
// Accounts are written with SQL and fixed ids: the account endpoints belong to another slice, and
// holdings, profiles and logos only exist through syncs and background passes anyway.
const ADMIN_MEMBER = "(SELECT member_id FROM app_user WHERE username = 'admin')";
const YESTERDAY = "CURRENT_DATE - 1";

const portfolio = [
  sql("accounts of every tier, a hidden brokerage account and a pocket", `
    INSERT INTO account (id, member_id, name, type, current_balance, cash_balance, is_manual, color, hidden, parent_account_id) VALUES
      (9001, ${ADMIN_MEMBER}, 'Courant', 'CHECKING', 1500.00, NULL, true, '#111111', false, NULL),
      (9002, ${ADMIN_MEMBER}, 'Livret A', 'LIVRET_A', 8000.00, NULL, true, '#222222', false, NULL),
      (9003, ${ADMIN_MEMBER}, 'PEA', 'PEA', 0, 200.00, true, '#333333', false, NULL),
      (9004, ${ADMIN_MEMBER}, 'Wallet', 'CRYPTO', 0, NULL, true, '#444444', false, NULL),
      (9005, ${ADMIN_MEMBER}, 'Appartement', 'REAL_ESTATE', 250000.00, NULL, true, '#555555', false, NULL),
      (9006, ${ADMIN_MEMBER}, 'Prêt immo', 'LOAN', 150000.00, NULL, true, '#666666', false, NULL),
      (9007, ${ADMIN_MEMBER}, 'CTO caché', 'COMPTE_TITRES', 0, NULL, true, '#777777', true, NULL),
      (9008, ${ADMIN_MEMBER}, 'Poche', 'SAVINGS', 400.00, NULL, true, '#888888', false, 9001),
      (9009, ${ADMIN_MEMBER}, 'Exchange', 'CRYPTO', 3000.00, NULL, true, '#999999', false, NULL)`),
  sql("a mortgage on the flat, without a schedule", `
    INSERT INTO debt (id, member_id, account_id, linked_account_id, borrowed_amount)
    VALUES (9101, ${ADMIN_MEMBER}, 9006, 9005, 180000.00)`),
  sql("holdings", `
    INSERT INTO account_holding (account_id, ticker, name, quantity, average_buy_in, current_price, quote_currency, created_at, updated_at) VALUES
      (9003, 'PSWORLD', 'Amundi MSCI World', 10.00000000, 400.00000000, NULL, NULL, now(), now()),
      (9003, 'PSAPPLE', 'Apple', 5.00000000, 150.00000000, NULL, NULL, now(), now()),
      (9003, 'PSGOLD', 'Gold ETC', 2.00000000, 180.00000000, NULL, NULL, now(), now()),
      (9003, 'PSNOPRICE', 'Unquoted fund', 3.00000000, 10.00000000, NULL, NULL, now(), now()),
      (9003, 'PSZERO', 'Sold out', 0.00000000, 0.00000000, 0.00000000, 'EUR', now(), now()),
      (9004, 'PSMEME', 'Meme coin', 1000000.00000000, NULL, NULL, NULL, now(), now()),
      (9007, 'PSAPPLE', 'Apple', 1.00000000, 120.00000000, NULL, NULL, now(), now())`),
  sql("recorded prices, as the hourly pass leaves them", `
    INSERT INTO price_snapshot (ticker, date, price_eur) VALUES
      ('PSWORLD', ${YESTERDAY}, 450.00000000), ('PSAPPLE', ${YESTERDAY}, 180.00000000), ('PSGOLD', ${YESTERDAY}, 200.00000000),
      ('PSMEME', ${YESTERDAY}, 0.00001000)`),
  sql("security profiles: a fund looked through, a share, a seeded never-fetched row", `
    INSERT INTO security_profile (id, ticker, asset_type, sector_key, country_key, status, refreshed_at) VALUES
      (9201, 'PSWORLD', 'ETF', NULL, NULL, 'OK', now()),
      (9202, 'PSAPPLE', 'STOCK', 'technology', 'US', 'OK', now()),
      (9203, 'PSNOPRICE', 'UNKNOWN', NULL, NULL, 'NEVER_FETCHED', NULL);
    INSERT INTO security_composition_slice (profile_id, kind, label, percent) VALUES
      (9201, 'SECTOR', 'technology', 25.000), (9201, 'SECTOR', 'financial_services', 15.000),
      (9201, 'SECTOR', 'healthcare', 15.000), (9201, 'COUNTRY', 'US', 60.000), (9201, 'COUNTRY', 'JP', 6.000),
      (9201, 'COUNTRY', 'GB', 6.000), (9201, 'COMPANY', 'Apple', 4.500)`),
  sql("a stored mark for CW8", `
    INSERT INTO instrument_logo (id, ticker, status, image, content_type, attempted_at, fetched_at)
    VALUES (9301, 'PSWORLD', 'STORED', decode('89504e470d0a1a0a', 'hex'), 'image/png', '2024-05-01T10:00:00Z', '2024-05-01T10:00:00Z')`),
  sql("a monthly plan into the PEA", `
    INSERT INTO goal (id, member_id, name, type, monthly_amount, expected_return) VALUES
      (9401, ${ADMIN_MEMBER}, 'DCA PEA', 'RECURRING_INVESTMENT', 300.00, NULL),
      (9402, ${ADMIN_MEMBER}, 'Livret', 'RECURRING_INVESTMENT', 100.00, 1.700);
    INSERT INTO goal_account (goal_id, account_id) VALUES (9401, 9003), (9402, 9002)`),
  sql("current-account history over the last complete months", `
    INSERT INTO transaction (account_id, date, description, amount, external_id) VALUES
      (9001, date_trunc('month', CURRENT_DATE)::date - 20, 'Loyer', -900.00, NULL),
      (9001, date_trunc('month', CURRENT_DATE)::date - 18, 'Courses', -312.45, NULL),
      (9001, date_trunc('month', CURRENT_DATE)::date - 15, 'Vers livret', -500.00, NULL),
      (9002, date_trunc('month', CURRENT_DATE)::date - 14, 'Virement reçu', 500.00, NULL),
      (9001, date_trunc('month', CURRENT_DATE)::date - 50, 'Loyer', -900.00, NULL),
      (9001, date_trunc('month', CURRENT_DATE)::date - 48, 'VIREMENT INTERNE epargne', -250.00, NULL),
      (9001, date_trunc('month', CURRENT_DATE)::date + 1, 'Ce mois-ci', -40.00, NULL)`),
];

const holdings: Scenario = {
  name: "analysis.holdings",
  description: "Holdings read model, edits, classification overrides, stored instrument logos, security insight",
  steps: [
    ...bootstrap(),
    ...portfolio,
    get("/api/accounts/9003/holdings", { expect: 200 }),
    get("/api/accounts/9004/holdings", { expect: 200 }),
    get("/api/accounts/9001/holdings", { expect: 200 }),
    get("/api/accounts/999999/holdings", { expect: 404 }),
    get("/api/accounts/abc/holdings", { expect: 400 }),
    put("/api/accounts/9003/holdings/PSAPPLE", { quantity: 6, averageBuyIn: 155.5 }, { expect: 200 }),
    put("/api/accounts/9003/holdings/PSAPPLE", { averageBuyIn: 1 }, { expect: 422 }),
    put("/api/accounts/9003/holdings/MISSING", { quantity: 1 }, { expect: 404 }),
    get("/api/accounts/9003/holdings/psgold/classification", { expect: 200 }),
    put("/api/accounts/9003/holdings/psgold/classification", { wealthTier: "ALTERNATIVE" }, { expect: 200 }),
    get("/api/accounts/9003/holdings/PSGOLD/classification", { expect: 200 }),
    get("/api/accounts/9003/holdings/PSWORLD/classification", { expect: 200 }),
    get("/api/accounts/9003/holdings/PSNOPRICE/classification", { expect: 200 }),
    put("/api/accounts/9003/holdings/PSNOPRICE/classification", { sectorKey: "private_equity", countryKey: "fr" }, { expect: 422 }),
    put("/api/accounts/9003/holdings/PSNOPRICE/classification", { wealthTier: "BOGUS" }, { expect: 400 }),
    put("/api/accounts/9003/holdings/PSNOPRICE/classification", { sectorKey: "private_equity", countryKey: "XU" }, { expect: 200 }),
    put("/api/accounts/9003/holdings/PSNOPRICE/classification", { wealthTier: null, sectorKey: null, countryKey: null }, { expect: 200 }),
    get("/api/accounts/9003/holdings/PSNOPRICE/classification", { expect: 200 }),
    get("/api/instrument-logos/PSWORLD", { expect: 200 }),
    get("/api/instrument-logos/psworld?variant=dark", { expect: 200 }),
    get("/api/instrument-logos/PSWORLD", { expect: 304, headers: { "If-None-Match": '"1714557600-light"' } }),
    get("/api/instrument-logos/PSAPPLE", { expect: 404 }),
    get("/api/instrument-logos/PSWORLD", { as: "anon", expect: 401 }),
    get("/api/securities/btc/insight", { expect: 200 }),
    get("/api/securities/PSWORLD/insight?name=Amundi", { expect: 200 }),
    del("/api/accounts/9003/holdings/PSGOLD", { expect: 204 }),
    del("/api/accounts/9003/holdings/PSGOLD", { expect: 404 }),
    get("/api/accounts/9003/holdings", { expect: 200 }),
  ],
};

const analysis: Scenario = {
  name: "analysis.portfolio",
  description: "Pyramid, diversification, projection, allocation targets and expense estimate over one portfolio",
  steps: [
    ...bootstrap(),
    ...portfolio,
    get("/api/analysis/allocation-targets", { expect: 200 }),
    get("/api/analysis/pyramid", { expect: 200 }),
    put("/api/accounts/9003/holdings/PSGOLD/classification", { wealthTier: "ALTERNATIVE" }, { expect: 200 }),
    put("/api/accounts/9003/holdings/PSWORLD/classification", { countryKey: "FR" }, { expect: 200 }),
    get("/api/analysis/pyramid", { expect: 200 }),
    get("/api/analysis/diversification", { expect: 200 }),
    get("/api/analysis/essential-expenses/estimate", { expect: 200 }),
    put("/api/analysis/allocation-targets", { monthlyEssentialExpenses: 1500, safetyNetMonths: 6, realEstatePct: 40, equityPct: 40, cryptoPct: 10, alternativePct: 5 }, { expect: 422 }),
    put("/api/analysis/allocation-targets", { safetyNetMonths: 30, realEstatePct: 40, equityPct: -1 }, { expect: 422 }),
    put("/api/analysis/allocation-targets", { monthlyEssentialExpenses: 1500.5, safetyNetMonths: 3, realEstatePct: 40, equityPct: 45.5, cryptoPct: 14.5, alternativePct: 0 }, { expect: 200 }),
    get("/api/analysis/allocation-targets", { expect: 200 }),
    get("/api/analysis/pyramid", { expect: 200 }),
    get("/api/analysis/projection?years=3", { expect: 200 }),
    get("/api/analysis/projection?years=0", { expect: 200 }),
    get("/api/analysis/projection?years=abc", { expect: 400 }),
    post("/api/analysis/security-profiles/refresh", undefined, { expect: 202 }),
  ],
};

const MEMBER_HASH = "$2b$04$FACvmj3IBcsSdS67azOiEuqsHxe.q1HIWn9wO3SdrR7OoXFCbiH/e";

const isolation: Scenario = {
  name: "analysis.isolation",
  description: "Another member sees none of the admin's holdings; a co-owner reads but cannot reclassify",
  steps: [
    ...bootstrap(),
    ...portfolio,
    sql("a second member with a login", `
      INSERT INTO family_member (id, display_name, is_managed) VALUES (9501, 'Camille', false);
      INSERT INTO app_user (username, password_hash, member_id, role, is_activated, acknowledged_warning)
      VALUES ('camille', '${MEMBER_HASH}', 9501, 'MEMBER', true, true)`),
    ...login("camille", "camille", MEMBER_PASSWORD),
    get("/api/accounts/9003/holdings", { as: "camille", expect: 404 }),
    get("/api/accounts/9003/holdings/PSWORLD/classification", { as: "camille", expect: 404 }),
    put("/api/accounts/9003/holdings/PSAPPLE", { quantity: 1 }, { as: "camille", expect: 404 }),
    del("/api/accounts/9003/holdings/PSAPPLE", { as: "camille", expect: 404 }),
    get("/api/analysis/pyramid", { as: "camille", expect: 200 }),
    get("/api/analysis/diversification", { as: "camille", expect: 200 }),
    get("/api/analysis/essential-expenses/estimate", { as: "camille", expect: 200 }),
    get("/api/analysis/projection", { as: "camille", expect: 200 }),
    sql("the flat and the current account are held half and half", `
      INSERT INTO account_ownership (account_id, member_id, share_percent) VALUES
        (9005, ${ADMIN_MEMBER}, 50.00), (9005, 9501, 50.00), (9006, ${ADMIN_MEMBER}, 50.00), (9006, 9501, 50.00),
        (9001, ${ADMIN_MEMBER}, 50.00), (9001, 9501, 50.00)`),
    get("/api/analysis/pyramid", { as: "camille", expect: 200 }),
    get("/api/analysis/pyramid", { expect: 200 }),
    get("/api/analysis/essential-expenses/estimate", { as: "camille", expect: 200 }),
    sql("the PEA is shared too", `
      INSERT INTO account_ownership (account_id, member_id, share_percent) VALUES (9003, ${ADMIN_MEMBER}, 70.00), (9003, 9501, 30.00)`),
    get("/api/accounts/9003/holdings/PSWORLD/classification", { as: "camille", expect: 200 }),
    put("/api/accounts/9003/holdings/PSWORLD/classification", { wealthTier: "EQUITY" }, { as: "camille", expect: 403 }),
    get("/api/accounts/9003/holdings", { as: "camille", expect: 404 }),
    get("/api/analysis/pyramid", { as: "camille", expect: 200 }),
    get("/api/analysis/diversification", { as: "camille", expect: 200 }),
    get("/api/analysis/pyramid", { expect: 200 }),
    get("/api/analysis/diversification", { expect: 200 }),
  ],
};

export const scenarios = [holdings, analysis, isolation];

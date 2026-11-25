import { bootstrap } from "../lib/flows.ts";
import { get, sql, type Scenario } from "../lib/scenario.ts";

const summary: Scenario = {
  name: "real_estate.summary",
  description: "Real estate summary with a property, its metadata and loan, an SCPI; valuations; geocoding with the stub down",
  steps: [
    ...bootstrap(),
    get("/api/real-estate/summary", { expect: 200 }),
    sql("a property, its loan and an SCPI", () => `
      INSERT INTO account (id, member_id, name, type, is_manual, current_balance) SELECT 900101, member_id, 'Appartement', 'REAL_ESTATE', true, 300000 FROM app_user WHERE username = 'admin';
      INSERT INTO account (id, member_id, name, type, is_manual, current_balance) SELECT 900102, member_id, 'Prêt', 'LOAN', true, 120000 FROM app_user WHERE username = 'admin';
      INSERT INTO account (id, member_id, name, type, is_manual, current_balance) SELECT 900103, member_id, 'CORUM XL', 'SCPI', true, 5000 FROM app_user WHERE username = 'admin';
      INSERT INTO real_estate_metadata (account_id, member_id, purchase_price, notary_fees, city, rental_income) SELECT 900101, member_id, 250000, 20000, 'Lyon', 900 FROM app_user WHERE username = 'admin';
      INSERT INTO debt (member_id, account_id, linked_account_id, borrowed_amount, lender_name, monthly_payment) SELECT member_id, 900102, 900101, 150000, 'Banque', 800 FROM app_user WHERE username = 'admin';
      INSERT INTO scpi_position (account_id, member_id, share_count, management_company) SELECT 900103, member_id, 25, 'CORUM' FROM app_user WHERE username = 'admin';
      INSERT INTO property_valuation (account_id, member_id, valued_at, estimated_value, provider, confidence) SELECT 900101, member_id, '2020-06-01', 290000, 'CEREMA_DVF', 'MEDIUM' FROM app_user WHERE username = 'admin'`),
    get("/api/real-estate/summary", { expect: 200 }),
    get("/api/real-estate/900101/valuations", { expect: 200 }),
    get("/api/real-estate/999999/valuations", { expect: 404 }),
    get("/api/geocode?q=ab", { expect: 200 }),
    get("/api/geocode?q=8%20boulevard%20du%20port", { expect: 200 }),
    get("/api/geocode", { expect: 400 }),
  ],
};

export const scenarios = [summary];

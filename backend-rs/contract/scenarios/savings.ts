import { bootstrap } from "../lib/flows.ts";
import { del, get, put, sql, type Scenario } from "../lib/scenario.ts";

// The upsert answers an AccountResponse (track T2): only the paths that fail before it are here.
const config: Scenario = {
  name: "savings.config",
  description: "Savings suggestions, a regulated book refused a GROSS rate, projection without config, delete",
  steps: [
    ...bootstrap(),
    sql("a synced livret and a manual one", () => `
      INSERT INTO account (id, member_id, name, type, is_manual, current_balance) SELECT 900201, member_id, 'LIVRET A M DUPONT', 'CHECKING', false, 1000 FROM app_user WHERE username = 'admin';
      INSERT INTO account (id, member_id, name, type, is_manual, current_balance) SELECT 900202, member_id, 'Livret A', 'CHECKING', true, 1000 FROM app_user WHERE username = 'admin'`),
    get("/api/savings/suggestions", { expect: 200 }),
    put("/api/accounts/900201/savings-config", { product: "LIVRET_A", annualRate: 3, rateBasis: "GROSS" }, { expect: 400 }),
    put("/api/accounts/999999/savings-config", { product: "LIVRET_A", annualRate: 3, rateBasis: "NET" }, { expect: 404 }),
    get("/api/accounts/900201/savings-interest", { expect: 404 }),
    del("/api/accounts/900201/savings-config", { expect: 204 }),
    del("/api/accounts/999999/savings-config", { expect: 404 }),
  ],
};

export const scenarios = [config];

import { bootstrap } from "../lib/flows.ts";
import { get, sql, type Scenario } from "../lib/scenario.ts";

const offline: Scenario = {
  name: "prices.offline",
  description: "Price endpoints with every provider refused: EUR, recorded history, metals catalogue, errors",
  steps: [
    ...bootstrap(),
    get("/api/prices/precious-metals", { expect: 200 }),
    get("/api/prices?tickers=EUR", { expect: 200 }),
    get("/api/prices", { expect: 400 }),
    sql("a recorded price", () => "INSERT INTO price_snapshot (ticker, date, price_eur, created_at) VALUES ('CW8', '2020-01-02', 400.5, now())"),
    get("/api/prices/cw8/history?months=120", { expect: 200 }),
    get("/api/prices/CW8/history", { expect: 200 }),
    get("/api/prices/CW8/history?months=x", { expect: 400 }),
  ],
};

export const scenarios = [offline];

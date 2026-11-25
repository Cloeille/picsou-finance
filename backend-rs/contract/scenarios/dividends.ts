import { bootstrap } from "../lib/flows.ts";
import { get, type Scenario } from "../lib/scenario.ts";

const empty: Scenario = {
  name: "dividends.empty",
  description: "Dividend forecast and received list without holdings, parameters and an unknown account",
  steps: [
    ...bootstrap(),
    get("/api/dividends/upcoming", { expect: 200 }),
    get("/api/dividends/upcoming?horizonDays=5000", { expect: 200 }),
    get("/api/dividends/upcoming?horizonDays=abc", { expect: 400 }),
    get("/api/dividends/upcoming?accountId=999999", { expect: 404 }),
    get("/api/dividends/received", { expect: 200 }),
    get("/api/dividends/received?days=0", { expect: 200 }),
  ],
};

export const scenarios = [empty];

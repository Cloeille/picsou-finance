import { bootstrap } from "../lib/flows.ts";
import { get, post, type Scenario } from "../lib/scenario.ts";

const funds: Scenario = {
  name: "scpi.funds",
  description: "SCPI fund catalog: empty list, tombstone ahead of a sync, restore, unknown fund and provider",
  steps: [
    ...bootstrap(),
    get("/api/scpi/funds", { expect: 200 }),
    post("/api/scpi/funds/CORUM/XL/restore", undefined, { expect: 404 }),
    post("/api/scpi/funds/CORUM/XL/ignore", undefined, { expect: 204 }),
    get("/api/scpi/funds", { expect: 200 }),
    post("/api/scpi/funds/CORUM/XL/restore", undefined, { expect: 204 }),
    get("/api/scpi/funds", { expect: 200 }),
    post("/api/scpi/funds/NOPE/XL/ignore", undefined, { expect: 400 }),
  ],
};

export const scenarios = [funds];

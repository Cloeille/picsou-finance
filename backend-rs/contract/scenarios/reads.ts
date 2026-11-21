import { bootstrap, manualAccount } from "../lib/flows.ts";
import type { Json } from "../lib/json.ts";
import { day, get, post, type Scenario } from "../lib/scenario.ts";

const reads: Scenario = {
  name: "reads.dashboard-history",
  description: "Dashboard, net-worth history, P&L and intraday over manual accounts with snapshots",
  steps: [
    ...bootstrap(),
    get("/api/dashboard", { expect: 200 }),
    manualAccount("checking", { name: "Courant", currentBalance: 1500.75 }),
    manualAccount("livret", { name: "Livret A", type: "LIVRET_A", currentBalance: 8000 }),
    post("/api/accounts/{livret}/history", { balance: 7800, date: day(-3) }, { expect: 201 }),
    post("/api/accounts/{checking}/history", { balance: 1400.5, date: day(-2) }, { expect: 201 }),
    get("/api/dashboard", { expect: 200 }),
    get("/api/dashboard?range=1M", { expect: 200 }),
    get("/api/dashboard?range=ALL", { expect: 200 }),
    get("/api/history?accountIds={checking},{livret}&months=1&split=false", { expect: 200 }),
    get("/api/history?accountIds={checking},{livret}&months=1&split=true", { expect: 200 }),
    get("/api/history/pnl?accountIds={checking},{livret}", { expect: 200 }),
    get(`/api/history/pnl?accountIds={checking}&from=${day(-3)}`, { expect: 200 }),
    get("/api/history/net-worth/intraday?accountIds={checking},{livret}", {
      expect: 200,
      // One point per slot since midnight: the count depends on when the scenario runs.
      keep: (body) => (body as Json[]).slice(0, 1),
    }),
    get("/api/history?months=1", { expect: 400 }),
  ],
};

export const scenarios = [reads];

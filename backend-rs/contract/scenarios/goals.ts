import { bootstrap, manualAccount } from "../lib/flows.ts";
import { del, get, monthEdge, post, put, type Scenario } from "../lib/scenario.ts";

const DEADLINE = monthEdge(18, "last");

const goals: Scenario = {
  name: "goals.savings-target",
  description: "A savings target over one account: create, read, update, months, delete, validation",
  steps: [
    ...bootstrap(),
    manualAccount("livret", { name: "Livret A", type: "LIVRET_A", currentBalance: 2000 }),
    post("/api/goals", (v) => ({
      name: "Apport maison",
      type: "SAVINGS_TARGET",
      targetAmount: 20000,
      deadline: DEADLINE,
      monthlyAmount: null,
      expectedReturn: null,
      startDate: null,
      endDate: null,
      allocations: [],
      accountIds: [v.id("livret")],
    }), { expect: 201, capture: { goal: (r) => r.json.id } }),
    get("/api/goals", { expect: 200 }),
    get("/api/goals/{goal}", { expect: 200 }),
    put("/api/goals/{goal}", (v) => ({
      name: "Apport appartement",
      type: "SAVINGS_TARGET",
      targetAmount: 25000.5,
      deadline: DEADLINE,
      monthlyAmount: null,
      expectedReturn: null,
      startDate: null,
      endDate: null,
      allocations: [],
      accountIds: [v.id("livret")],
    }), { expect: 200 }),
    get("/api/goals/{goal}/months", { expect: 200 }),
    post("/api/goals", { name: "Sans compte", type: "SAVINGS_TARGET", targetAmount: 100, deadline: "2001-01-01", accountIds: [] }, { expect: 422 }),
    del("/api/goals/{goal}", { expect: 204 }),
    get("/api/goals/{goal}", { expect: 404 }),
  ],
};

export const scenarios = [goals];

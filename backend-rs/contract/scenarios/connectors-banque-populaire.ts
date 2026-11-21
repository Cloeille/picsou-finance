import { post, type Scenario } from "../lib/scenario.ts";
import { bpceFlow } from "./connectors-caisse-epargne.ts";

const banquePopulaire: Scenario = {
  name: "connectors.banque-populaire",
  description: "Banque Populaire: keypad login at a regional bank, validation, budgets, sync and stored session states",
  steps: [
    ...bpceFlow("/api/banque-populaire", "BANQUE_POPULAIRE", { customerId: "A1234567", region: "BRED" }, { region: "'BRED'" }),
    post("/api/banque-populaire/auth/initiate", { customerId: "A1234567", region: "bred" }, { expect: 400, note: "the region by its exact name" }),
    post("/api/banque-populaire/auth/initiate", { customerId: "A1234567", region: 3 }, { expect: 400 }),
    post("/api/banque-populaire/auth/initiate", { customerId: "", region: "NOWHERE" }, { expect: 429, note: "an empty identifier ignores the region; the budget is spent" }),
  ],
};

export const scenarios = [banquePopulaire];

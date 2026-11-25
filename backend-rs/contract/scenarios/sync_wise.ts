import { bootstrap } from "../lib/flows.ts";
import { del, get, post, type Scenario } from "../lib/scenario.ts";

const TOKEN = "0f1e2d3c-4b5a-6978-8796-a5b4c3d2e1f0";

export const scenarios: Scenario[] = [
  {
    name: "sync.wise-not-connected",
    description: "Wise Jars tab before any token: status, sync and disconnect",
    steps: [
      ...bootstrap(),
      get("/api/wise/status", { expect: 200 }),
      post("/api/wise/sync", undefined, { expect: 422 }),
      del("/api/wise/connection", { expect: 204 }),
      get("/api/wise/status", { expect: 200 }),
    ],
  },
  {
    name: "sync.wise-validation",
    description: "connect body validation and tokens refused before any request to Wise",
    steps: [
      ...bootstrap(),
      post("/api/wise/connect", { token: "   " }, { expect: 422 }),
      post("/api/wise/connect", {}, { expect: 422 }),
      post("/api/wise/connect", { token: "a".repeat(513) }, { expect: 422 }),
      post("/api/wise/connect", undefined, { raw: { contentType: "application/json", body: "{\"token\":" }, expect: 400 }),
      post("/api/wise/connect", { token: "jeton avec espaces" }, { expect: 422 }),
      post("/api/wise/connect", { token: "Bearer abc" }, { expect: 422 }),
      get("/api/wise/status", { expect: 200 }),
    ],
  },
  {
    name: "sync.wise-upstream-failure",
    description: "Wise unreachable: connect stores nothing and the member stays disconnected",
    steps: [
      ...bootstrap(),
      post("/api/wise/connect", { token: TOKEN }, { expect: 422 }),
      get("/api/wise/status", { expect: 200 }),
      post("/api/wise/sync", undefined, { expect: 422 }),
      del("/api/wise/connection", { expect: 204 }),
    ],
  },
  {
    name: "sync.wise-rate-limit",
    description: "connect and sync share six requests a minute per IP; status and disconnect stay open",
    steps: [
      ...bootstrap(),
      post("/api/wise/connect", { token: "not a token" }, { expect: 422 }),
      post("/api/wise/sync", undefined, { expect: 422 }),
      post("/api/wise/connect", { token: "not a token" }, { expect: 422 }),
      post("/api/wise/sync", undefined, { expect: 422 }),
      post("/api/wise/connect", { token: "not a token" }, { expect: 422 }),
      post("/api/wise/sync", undefined, { expect: 422 }),
      post("/api/wise/connect", { token: "not a token" }, { expect: 429 }),
      post("/api/wise/sync", undefined, { expect: 429 }),
      get("/api/wise/status", { expect: 200 }),
      del("/api/wise/connection", { expect: 204 }),
    ],
  },
];

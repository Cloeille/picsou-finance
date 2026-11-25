import { bootstrap } from "../lib/flows.ts";
import { del, get, patch, post, put, type Scenario } from "../lib/scenario.ts";

const settings: Scenario = {
  name: "admin.settings",
  description: "Admin settings: read, security, Enable Banking, integrations, AI, telemetry consent",
  steps: [
    ...bootstrap(),
    get("/api/admin/settings", { expect: 200 }),
    put("/api/admin/settings/security", { allowedOrigins: [], secureCookies: true }, { expect: 422 }),
    put("/api/admin/settings/security", { allowedOrigins: ["https://*.example"] }, { expect: 400 }),
    put("/api/admin/settings/security", { allowedOrigins: ["https://picsou.example", "http://localhost:5173"], secureCookies: false }, { expect: 204 }),
    post("/api/admin/settings/cors/reload-from-env", undefined, { expect: 400 }),
    put("/api/admin/settings/enablebanking", { applicationId: "", redirectUri: "" }, { expect: 422 }),
    put("/api/admin/settings/enablebanking", { applicationId: "0f8fad5b-d9cb-469f-a165-70867728950e", redirectUri: "https://picsou.example/callback" }, { expect: 204 }),
    post("/api/admin/settings/enablebanking/keypair/import", { privatePem: "-----BEGIN RSA PRIVATE KEY-----\nabc\n-----END RSA PRIVATE KEY-----" }, { expect: 422 }),
    post("/api/admin/settings/enablebanking/keypair/import", { privatePem: "" }, { expect: 422 }),
    patch("/api/admin/settings/integrations/finary?enabled=true", undefined, { expect: 204 }),
    patch("/api/admin/settings/integrations/bogus?enabled=true", undefined, { expect: 400 }),
    patch("/api/admin/settings/integrations/finary?enabled=maybe", undefined, { expect: 400 }),
    patch("/api/admin/settings/integrations/finary", undefined, { expect: 400 }),
    put("/api/admin/settings/ai", { provider: "", maxConcurrency: 17 }, { expect: 422 }),
    put("/api/admin/settings/ai", { provider: "openai", model: "gpt-4o-mini", apiKey: "sk-test-not-a-real-key", maxConcurrency: 2 }, { expect: 204 }),
    post("/api/admin/settings/ai/test", { provider: "none" }, { expect: 200 }),
    get("/api/admin/settings", { expect: 200 }),
    put("/api/admin/settings/ai", { provider: "none" }, { expect: 204 }),
    put("/api/admin/settings/telemetry", {}, { expect: 422 }),
    put("/api/admin/settings/telemetry", { enabled: true }, { expect: 409 }),
    put("/api/admin/settings/telemetry", { enabled: false }, { expect: 204 }),
    get("/api/admin/settings", { expect: 200 }),
    get("/api/telemetry/config", { expect: 200 }),
    post("/api/telemetry/tunnel", undefined, { raw: { contentType: "text/plain", body: "{}\n" }, expect: 204 }),
    get("/api/telemetry/config", { as: "anon", expect: 401 }),
    get("/api/admin/settings", { as: "anon", expect: 401 }),
  ],
};

const logs: Scenario = {
  name: "admin.logs-and-status",
  description: "AI call log paging, the Enable Banking call log, the instance status",
  steps: [
    ...bootstrap(),
    get("/api/admin/ai-calls", { expect: 200 }),
    get("/api/admin/ai-calls?limit=500&offset=-3", { expect: 200 }),
    get("/api/admin/ai-calls?limit=abc", { expect: 400 }),
    get("/api/admin/enablebanking/call-log", { expect: 200 }),
    del("/api/admin/enablebanking/call-log", { expect: 204 }),
    get("/api/admin/instance-status", { expect: 200 }),
  ],
};

export const scenarios: Scenario[] = [settings, logs];

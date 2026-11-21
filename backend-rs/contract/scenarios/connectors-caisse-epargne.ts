import { bootstrap } from "../lib/flows.ts";
import { del, get, post, sql, type Scenario, type Step } from "../lib/scenario.ts";
import { sqlMember, storedSession } from "./connectors.ts";

/**
 * The BPCE keypad login (Caisse d'Epargne, Banque Populaire) as the frontend drives it, against the
 * harness stub that answers 503 to every sidecar call: status, the three login steps with valid and
 * invalid bodies, the per-IP budgets, sync without and with stored sessions, member isolation.
 */
export function bpceFlow(base: string, bank: string, login: Record<string, string>, extra: Record<string, string>): Step[] {
  const keypad = { processId: "p-1", positions: [1, 2, 3, 4, 5, 6] };
  const unsupported = `'[{"externalId":"ce_9","familyCode":"AV","subFamilyCode":null},{"externalId":"x","familyCode":"TOO-LONG-CODE"}]'`;
  return [
    ...bootstrap(),
    ...sqlMember("member", "dominique"),
    get(base + "/status", { expect: 200 }),
    get(base + "/status", { as: "anon", expect: 401 }),
    post(base + "/auth/initiate", login, { expect: 422, note: "the stub sidecar answers 503" }),
    post(base + "/auth/initiate", { ...login, password: "secret" }, { expect: 400, note: "a password is refused, never ignored" }),
    post(base + "/auth/initiate", { ...login, customerId: "12 34" }, { expect: 400 }),
    post(base + "/auth/initiate", {}, { expect: 400 }),
    post(base + "/auth/initiate", ["customerId"], { expect: 400 }),
    post(base + "/auth/initiate", undefined, { raw: { contentType: "application/json", body: "{" }, expect: 400 }),
    post(base + "/auth/initiate", { ...login, customerId: "" }, { expect: 422, note: "nothing is remembered" }),
    post(base + "/auth/initiate", login, { expect: 422, note: "third counted attempt" }),
    post(base + "/auth/initiate", login, { expect: 429, note: "three attempts per 15 minutes per IP" }),
    post(base + "/auth/keypad", keypad, { expect: 422, note: "no login is pending" }),
    post(base + "/auth/keypad", { ...keypad, positions: [1, 2, 3, 4, 5] }, { expect: 400 }),
    post(base + "/auth/keypad", { ...keypad, positions: [1, 2, 3, 4, 5, 10] }, { expect: 400 }),
    post(base + "/auth/keypad", { ...keypad, positions: [1, 2, 3, 4, 5, 1.5] }, { expect: 400 }),
    post(base + "/auth/keypad", { ...keypad, processId: " " }, { expect: 400 }),
    post(base + "/auth/keypad", { processId: "p-1" }, { expect: 400 }),
    post(base + "/auth/keypad", keypad, { expect: 422 }),
    post(base + "/auth/keypad", keypad, { expect: 422 }),
    post(base + "/auth/keypad", keypad, { expect: 422 }),
    post(base + "/auth/keypad", keypad, { expect: 422, note: "fifth counted keypad request" }),
    post(base + "/auth/keypad", keypad, { expect: 429 }),
    post(base + "/auth/complete", { processId: "p-1" }, { expect: 422 }),
    post(base + "/auth/complete", { processId: "" }, { expect: 400 }),
    post(base + "/auth/complete", { processId: "p-1", code: "123456" }, { expect: 400 }),
    post(base + "/sync", undefined, { expect: 422, note: "no session" }),
    del(base + "/session", { expect: 200 }),
    storedSession(bank, {
      active: false, status: "FAILED", error: "SESSION_EXPIRED",
      extra: { ...extra, unsupported_contracts: unsupported, encrypted_credentials: "'not-decryptable'" },
    }),
    get(base + "/status", { expect: 200, note: "an unreadable identifier is not remembered" }),
    get(base + "/status", { as: "member", expect: 200 }),
    post(base + "/sync", undefined, { expect: 422, note: "an expired session asks to reconnect" }),
    del(base + "/session", { as: "member", expect: 200, note: "nothing of the member's to delete" }),
    get(base + "/status", { expect: 200, note: "the member's delete left the admin's session" }),
    sql("session back to a finished sync", "UPDATE sidecar_session SET is_active = true, sync_status = 'SUCCESS', last_sync_error = NULL WHERE bank = '" + bank + "'"),
    get(base + "/status", { expect: 200 }),
    post(base + "/sync", undefined, { expect: 422, note: "the stored session cannot be decrypted" }),
    get(base + "/status", { expect: 200 }),
    sql("session queued", "UPDATE sidecar_session SET is_active = true, sync_status = 'QUEUED', last_sync_error = NULL, last_sync_started_at = NULL, last_sync_completed_at = NULL WHERE bank = '" + bank + "'"),
    post(base + "/sync", undefined, { expect: 202, note: "a sync in flight is left alone" }),
    del(base + "/session", { expect: 200 }),
    get(base + "/status", { expect: 200 }),
  ];
}

const caisseEpargne: Scenario = {
  name: "connectors.caisse-epargne",
  description: "Caisse d'Epargne: keypad login steps, validation, budgets, sync and stored session states, member isolation",
  steps: bpceFlow("/api/caisse-epargne", "CAISSE_EPARGNE", { customerId: "12345678" }, {}),
};

export const scenarios = [caisseEpargne];

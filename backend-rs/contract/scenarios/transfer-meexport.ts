import { ADMIN, bootstrap } from "../lib/flows.ts";
import { post, sql, type Scenario } from "../lib/scenario.ts";

const EXPORT = "/api/me/export";

const fixtures = [
  sql("a manual account with one transaction and one snapshot", `
    INSERT INTO account (id, member_id, name, type, currency, current_balance, is_manual, color, hidden, created_at, updated_at)
      SELECT 9001, member_id, 'Courant', 'CHECKING', 'EUR', -750, true, '#6366f1', false, now(), now() FROM app_user WHERE username = 'admin';
    INSERT INTO "transaction" (account_id, date, description, amount, native_currency, created_at, is_manual, tx_type, category_manual, budget_neutral)
      VALUES (9001, '2021-05-04', 'Loyer', -750, 'EUR', now(), true, 'WITHDRAWAL', false, false);
    INSERT INTO balance_snapshot (account_id, date, balance, invested_amount, created_at, source)
      VALUES (9001, '2021-05-04', -750, 0, now(), 'MANUAL');`),
];

const password: Scenario = {
  name: "transfer.meexport.password",
  description: "GDPR ZIP export re-authenticated by password: status, content type and disposition; re-auth and body errors",
  steps: [
    ...bootstrap(),
    ...fixtures,
    post(EXPORT, { reAuth: { password: ADMIN.password }, includeBalanceSnapshots: false }, { expect: 200 }),
    post(EXPORT, { reAuth: { password: ADMIN.password }, includeBalanceSnapshots: true }, { expect: 200 }),
    post(EXPORT, { reAuth: { password: "wrong-password" }, includeBalanceSnapshots: false }, { expect: 401 }),
    post(EXPORT, { includeBalanceSnapshots: false }, { expect: 401 }),
    post(EXPORT, { reAuth: { password: "  ", totpCode: "123456" } }, { expect: 401, note: "rate limit: fifth and last export this hour" }),
    post(EXPORT, undefined, { raw: { contentType: "application/json", body: "{" }, expect: 400 }),
  ],
};

const rateLimit: Scenario = {
  name: "transfer.meexport.rate-limit",
  description: "Five exports per hour per user, counted before re-authentication: the sixth is an empty 429",
  steps: [
    ...bootstrap(),
    ...Array.from({ length: 5 }, () => post(EXPORT, { reAuth: { password: "wrong-password" } }, { expect: 401 })),
    post(EXPORT, { reAuth: { password: ADMIN.password }, includeBalanceSnapshots: false }, { expect: 429 }),
  ],
};

const mfa: Scenario = {
  name: "transfer.meexport.mfa",
  description: "With 2FA on, the export takes the TOTP code and refuses the password alone",
  steps: [
    ...bootstrap(),
    ...fixtures,
    post("/api/auth/mfa/enroll/init", { currentPassword: ADMIN.password }, { expect: 200, compare: false, capture: { secret: (r) => r.json.secret } }),
    post("/api/auth/mfa/enroll/verify", async (v) => ({ code: await v.totp("secret") }), { expect: 200, compare: false }),
    post(EXPORT, { reAuth: { password: ADMIN.password }, includeBalanceSnapshots: false }, { expect: 401 }),
    post(EXPORT, { reAuth: { totpCode: "000000" }, includeBalanceSnapshots: false }, { expect: 401 }),
    post(EXPORT, async (v) => ({ reAuth: { totpCode: await v.totp("secret") }, includeBalanceSnapshots: true }), { expect: 200 }),
  ],
};

export const scenarios = [password, rateLimit, mfa];

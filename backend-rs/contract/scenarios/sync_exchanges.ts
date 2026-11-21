import { bootstrap } from "../lib/flows.ts";
import { del, get, post, sql, type Scenario } from "../lib/scenario.ts";

const EXCHANGE = "/api/crypto/exchange";

const notConnected: Scenario = {
  name: "sync.exchanges.not-connected",
  description: "Status, sync and delete with no exchange connected: an empty list and 404s",
  steps: [
    ...bootstrap(),
    get(`${EXCHANGE}/status`, { expect: 200 }),
    post(`${EXCHANGE}/999999/sync`, undefined, { expect: 404 }),
    del(`${EXCHANGE}/999999`, { expect: 404 }),
    post(`${EXCHANGE}/abc/sync`, undefined, { expect: 400 }),
    del(`${EXCHANGE}/abc`, { expect: 400 }),
    get(`${EXCHANGE}/status`, { as: "anon", expect: 401 }),
  ],
};

const validation: Scenario = {
  name: "sync.exchanges.validation",
  description: "Bean validation and unreadable bodies on POST /api/crypto/exchange",
  steps: [
    ...bootstrap(),
    post(EXCHANGE, {}, { expect: 422 }),
    post(EXCHANGE, { type: null, apiKey: "k" }, { expect: 422 }),
    post(EXCHANGE, { type: "BINANCE", apiKey: "k".repeat(201), apiSecret: "s".repeat(301), apiPassphrase: "test-passphrase".repeat(14), region: "r".repeat(21) }, { expect: 422 }),
    post(EXCHANGE, { apiKey: "k".repeat(201) }, { expect: 422 }),
    post(EXCHANGE, { type: "BINANCE", apiKey: "k".repeat(200), apiSecret: "s".repeat(300), apiPassphrase: "p".repeat(200), region: "r".repeat(20) }, { expect: 400 }),
    post(EXCHANGE, { type: "COINBASE", apiKey: "k", apiSecret: "s" }, { expect: 400 }),
    post(EXCHANGE, { type: "binance", apiKey: "k", apiSecret: "s" }, { expect: 400 }),
    post(EXCHANGE, { type: "", apiKey: "k" }, { expect: 400 }),
    post(EXCHANGE, { type: 2, apiKey: "k", apiSecret: "s" }, { expect: 400 }),
    post(EXCHANGE, { type: 7, apiKey: "k" }, { expect: 400 }),
    post(EXCHANGE, { type: ["MERIA"], apiKey: "k" }, { expect: 400 }),
    post(EXCHANGE, { type: "MERIA", apiKey: { k: 1 } }, { expect: 400 }),
    post(EXCHANGE, undefined, { raw: { contentType: "application/json", body: "{\"type\": \"MERIA\"," }, expect: 400 }),
  ],
};

const credentials: Scenario = {
  name: "sync.exchanges.credentials",
  description: "The adapter's credential, passphrase and region rules, enforced before any network call",
  steps: [
    ...bootstrap(),
    post(EXCHANGE, { type: "BINANCE", apiSecret: "s" }, { expect: 400 }),
    post(EXCHANGE, { type: "BINANCE", apiKey: "   ", apiSecret: "s" }, { expect: 400 }),
    post(EXCHANGE, { type: "BINANCE", apiKey: "k", apiSecret: "  " }, { expect: 400 }),
    post(EXCHANGE, { type: "BINANCE", apiKey: "k" }, { expect: 400 }),
    post(EXCHANGE, { type: "MERIA", apiKey: "k", apiSecret: "s" }, { expect: 400 }),
    post(EXCHANGE, { type: "BINANCE", apiKey: "k", apiSecret: "s", apiPassphrase: "secret-words" }, { expect: 400 }),
    post(EXCHANGE, { type: "MERIA", apiKey: "k", apiPassphrase: "secret-words" }, { expect: 400 }),
    post(EXCHANGE, { type: "BINANCE", apiKey: "k", apiSecret: "s", region: "eea" }, { expect: 400 }),
    post(EXCHANGE, { type: "MERIA", apiKey: "k", region: " us " }, { expect: 400 }),
    post(EXCHANGE, { type: "KRAKEN", apiKey: "k", apiSecret: "s" }, { expect: 422 }),
    post(EXCHANGE, { type: "KRAKEN" }, { expect: 422 }),
    post(EXCHANGE, { type: "MERIA", apiKey: "k", apiSecret: "s".repeat(301) }, { expect: 422 }),
    get(`${EXCHANGE}/status`, { expect: 200 }),
  ],
};

const upstream: Scenario = {
  name: "sync.exchanges.upstream-failure",
  description: "A connection test the upstream refuses is a 422 naming the exchange, and saves nothing",
  steps: [
    ...bootstrap(),
    post(EXCHANGE, { type: "MERIA", apiKey: "read-only-key" }, { expect: 422 }),
    post(EXCHANGE, { type: "MERIA", apiKey: "  read-only-key  ", apiSecret: "", apiPassphrase: "   ", region: "" }, { expect: 422 }),
    post(EXCHANGE, { type: "MERIA", apiKey: 12345 }, { expect: 422 }),
    post(EXCHANGE, { type: "BINANCE", apiKey: "key", apiSecret: "secret", region: "  " }, { expect: 422 }),
    post(EXCHANGE, { type: "MERIA", apiKey: "k", unknownField: true }, { expect: 422 }),
    get(`${EXCHANGE}/status`, { expect: 200 }),
  ],
};

const SESSION_ROW = (exchange: string, status: string) =>
  `INSERT INTO crypto_exchange_session (member_id, exchange_type, api_key, api_secret, status, last_synced_at, created_at, updated_at) \
   SELECT member_id, '${exchange}', 'bm90LWEtY2lwaGVydGV4dA==', NULL, '${status}', '2020-01-02T03:04:05Z', now(), now() \
   FROM app_user WHERE username = 'admin'`;

const isolation: Scenario = {
  name: "sync.exchanges.member-isolation",
  description: "A session is read, synced and removed by its member only; removal soft-deletes its account",
  steps: [
    ...bootstrap(),
    sql("another member's profile", "INSERT INTO family_member (display_name, avatar_color, is_managed, created_at) VALUES ('Autre', '#16a34a', true, now())"),
    sql("another member's Binance session", `INSERT INTO crypto_exchange_session (member_id, exchange_type, api_key, api_secret, status, created_at, updated_at) \
      SELECT id, 'BINANCE', 'bm90LWEtY2lwaGVydGV4dA==', 'bm90LWEtY2lwaGVydGV4dA==', 'CONNECTED', now(), now() FROM family_member WHERE display_name = 'Autre'`),
    sql("a Meria session no endpoint can create without the upstream", SESSION_ROW("MERIA", "ERROR")),
    sql("a Kraken session from before its adapter was dropped", SESSION_ROW("KRAKEN", "CONNECTED")),
    sql("the account the Meria session synced", `INSERT INTO account (member_id, name, type, provider, currency, current_balance, external_account_id, is_manual, color, hidden, created_at, updated_at) \
      SELECT member_id, 'MERIA Crypto', 'CRYPTO', 'MERIA', 'EUR', 448.00, 'crypto_exchange_meria', false, '#f59e0b', false, now(), now() FROM app_user WHERE username = 'admin'`),
    get(`${EXCHANGE}/status`, {
      expect: 200,
      capture: {
        meria: (r) => r.json.find((s: { exchangeType: string }) => s.exchangeType === "MERIA").id,
        kraken: (r) => r.json.find((s: { exchangeType: string }) => s.exchangeType === "KRAKEN").id,
        // Inserted just before on a fresh database: the other member's session has the previous id.
        other: (r) => r.json.find((s: { exchangeType: string }) => s.exchangeType === "MERIA").id - 1,
      },
    }),
    post(`${EXCHANGE}/{other}/sync`, undefined, { expect: 404 }),
    del(`${EXCHANGE}/{other}`, { expect: 404 }),
    post(`${EXCHANGE}/{kraken}/sync`, undefined, { expect: 422 }),
    post(`${EXCHANGE}/{meria}/sync`, undefined, { expect: 500 }),
    del(`${EXCHANGE}/{meria}`, { expect: 204 }),
    get(`${EXCHANGE}/status`, { expect: 200 }),
    del(`${EXCHANGE}/{kraken}`, { expect: 204 }),
    get(`${EXCHANGE}/status`, { expect: 200 }),
  ],
};

export const scenarios: Scenario[] = [notConnected, validation, credentials, upstream, isolation];

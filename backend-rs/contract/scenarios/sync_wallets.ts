import { bootstrap } from "../lib/flows.ts";
import { del, get, post, sql, type Scenario } from "../lib/scenario.ts";

const WALLETS = "/api/crypto/wallet";

/** A key the adapter rejects while decoding it, so the sync fails without any network call. */
const BAD_XPUB = "xpub-not-a-real-key";

const adminWallet = (label: string | null, address: string) =>
  `INSERT INTO wallet_address (member_id, chain, address, label)
   SELECT member_id, 'BITCOIN', '${address}', ${label === null ? "NULL" : `'${label}'`} FROM app_user WHERE username = 'admin'`;

export const scenarios: Scenario[] = [
  {
    name: "sync.wallets.empty",
    description: "Wallet list before any wallet is added, and the sign-in requirement",
    steps: [
      ...bootstrap(),
      get(WALLETS, { expect: 200 }),
      get(WALLETS, { as: "anon", expect: 401 }),
      post(WALLETS, { chain: "EVM", address: "0x0000000000000000000000000000000000000001" }, { as: "anon", expect: 401 }),
    ],
  },
  {
    name: "sync.wallets.validation",
    description: "Add-wallet input checks: chain, address presence, length and EVM format, Jackson binding",
    steps: [
      ...bootstrap(),
      post(WALLETS, {}, { expect: 400, note: "no chain" }),
      post(WALLETS, { chain: null, address: "bc1qexample" }, { expect: 400 }),
      post(WALLETS, { chain: "EVM" }, { expect: 400, note: "no address" }),
      post(WALLETS, { chain: "SOLANA", address: " \t " }, { expect: 400, note: "blank address on a chain without a format check" }),
      post(WALLETS, { chain: "BITCOIN", address: "x".repeat(201) }, { expect: 400, note: "longer than the column" }),
      post(WALLETS, { chain: "BITCOIN", address: "  xpub" + "y".repeat(196) + "  " }, { expect: 422, note: "200 once trimmed: accepted, then the key fails to decode, offline" }),
      post(WALLETS, { chain: "EVM", address: "  0xnope  ", label: "Ledger" }, { expect: 400 }),
      post(WALLETS, { chain: "EVM", address: "0x" + "g".repeat(40) }, { expect: 400 }),
      post(WALLETS, { chain: "EVM", address: "0x" + "a".repeat(39) }, { expect: 400 }),
      post(WALLETS, { chain: "EVM", address: "0x" + "a".repeat(80) + "\n\tforged log line " + "b".repeat(40) }, { expect: 400, note: "echo bounded to 64 chars, whitespace flattened" }),
      post(WALLETS, { chain: "EVM", address: 12345 }, { expect: 400, note: "a number binds to a String" }),
      post(WALLETS, { chain: "EVM", address: { nested: true } }, { expect: 400 }),
      post(WALLETS, { chain: "DOGE", address: "D123" }, { expect: 400, note: "unknown enum value" }),
      post(WALLETS, { chain: "evm", address: "0xnope" }, { expect: 400, note: "enum names are case-sensitive" }),
      post(WALLETS, { chain: 1, address: "0xnope" }, { note: "Jackson may bind an enum by index" }),
      post(WALLETS, { chain: "1", address: "0xnope" }, { note: "a numeric string" }),
      post(WALLETS, { chain: "", address: "0xnope" }, { note: "empty string for an enum" }),
      post(WALLETS, { chain: "EVM", address: "0xnope", extra: "ignored" }, { expect: 400 }),
      post(WALLETS, undefined, { raw: { contentType: "application/json", body: "{\"chain\":" }, expect: 400 }),
      post(WALLETS, undefined, { raw: { contentType: "application/json", body: "null" } }),
      post(WALLETS, undefined, { raw: { contentType: "application/json", body: "[]" } }),
      get(WALLETS, { expect: 200, note: "nothing was persisted" }),
    ],
  },
  {
    name: "sync.wallets.not-found",
    description: "Sync and delete of a wallet that does not exist, and malformed ids",
    steps: [
      ...bootstrap(),
      post(`${WALLETS}/999999/sync`, undefined, { expect: 404 }),
      del(`${WALLETS}/999999`, { expect: 404 }),
      post(`${WALLETS}/abc/sync`, undefined, { expect: 400 }),
      del(`${WALLETS}/abc`, { expect: 400 }),
    ],
  },
  {
    name: "sync.wallets.upstream",
    description: "A Bitcoin key the adapter cannot decode fails the sync with the friendly 422 and persists nothing",
    steps: [
      ...bootstrap(),
      post(WALLETS, { chain: "BITCOIN", address: BAD_XPUB, label: "Cold" }, { expect: 422 }),
      post(WALLETS, { chain: "BITCOIN", address: "zpub-broken" }, { expect: 422 }),
      post(WALLETS, { chain: "BITCOIN", address: "wpkh([d34db33f/84h/0h/0h]nokeyhere/0/*)" }, { expect: 422, note: "descriptor without an extended key" }),
      post(WALLETS, { chain: "BITCOIN", address: "xpub661MyMwAqRbcFtXgS5sYJABqqG9YLmC4Q1Rdap9gSE8NqtwybGhePY2gZ29ESFjqJoCu1Rupje8YtGqsefD265TMg7usUDFdp6W1EGMcet9" }, { expect: 422, note: "BIP32 vector 1 master xpub with its last character changed: bad checksum" }),
      get(WALLETS, { expect: 200 }),
    ],
  },
  {
    name: "sync.wallets.isolation",
    description: "Wallets are member-scoped: list, sync and delete of another member's wallet",
    steps: [
      ...bootstrap(),
      sql("another member's wallet, at an id the admin can guess", `WITH m AS (INSERT INTO family_member (display_name, is_managed) VALUES ('Dominique', true) RETURNING id)
       INSERT INTO wallet_address (id, member_id, chain, address) SELECT 900001, id, 'BITCOIN', '${BAD_XPUB}' FROM m`),
      sql("admin wallets with an undecodable key, one unlabelled", adminWallet("Cold storage", BAD_XPUB)),
      sql("second admin wallet", adminWallet(null, "ypub-broken")),
      get(WALLETS, { expect: 200, capture: { w: (r) => r.json[0].id, w2: (r) => r.json[1].id }, note: "the other member's wallet is not listed" }),
      post(`${WALLETS}/900001/sync`, undefined, { expect: 404 }),
      del(`${WALLETS}/900001`, { expect: 404 }),
      post(`${WALLETS}/{w}/sync`, undefined, { expect: 422 }),
      del(`${WALLETS}/{w}`, { expect: 204 }),
      del(`${WALLETS}/{w}`, { expect: 404 }),
      get(WALLETS, { expect: 200 }),
      del(`${WALLETS}/{w2}`, { expect: 204 }),
      get(WALLETS, { expect: 200 }),
    ],
  },
];

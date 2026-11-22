//! On-chain wallets: WalletController over HTTP, the chains stubbed with wiremock.

use std::collections::{BTreeSet, HashMap};
use std::sync::Arc;

use async_trait::async_trait;
use chrono::NaiveDate;
use http::StatusCode;
use picsou::sync::wallets::{self, Chain, WalletPrices};
use picsou::testing::{Client, TestApp};
use rust_decimal::Decimal;
use serde_json::{Value, json};
use wiremock::matchers::{body_partial_json, method, path, path_regex};
use wiremock::{Mock, MockServer, ResponseTemplate};

const EVM_ADDRESS: &str = "0x52908400098527886E0F7030069857D2E4169EE7";
const SOL_ADDRESS: &str = "So1anaTestAddress1111111111111111111111111";
const USDC_ETH: &str = "0xA0b86991c6218b36c1d19D4a2e9Eb0cE3606eB48";
const USDC_BASE: &str = "0x833589fCD6eDb6E08f4c7C32D4f71b54bdA02913";
const USDT_ETH: &str = "0xdAC17F958D2ee523a2206206994597C13D831ec7";
/// BIP84's published reference account and its first receive and change addresses.
const BIP84_ZPUB: &str = "zpub6rFR7y4Q2AijBEqTUquhVz398htDFrtymD9xYYfG1m4wAcvPhXNfE3EfH1r1ADqtfSdVCToUG868RvUUkgDKf31mGDtKsAYz2oz2AGutZYs";
const BIP84_RECEIVE_0: &str = "bc1qcr8te4kr609gcawutmrza0j4xv80jy8z306fyu";
const BIP84_CHANGE_0: &str = "bc1q8c6fshw2dlwun7ekn9qwf37cu2rn755upcp6el";

const NETWORKS: [(&str, &str); 7] = [
    ("EVM_ETHEREUM_RPC_URL", "ethereum"),
    ("EVM_BNB_RPC_URL", "bnb"),
    ("EVM_POLYGON_RPC_URL", "polygon"),
    ("EVM_ARBITRUM_RPC_URL", "arbitrum"),
    ("EVM_OPTIMISM_RPC_URL", "optimism"),
    ("EVM_BASE_RPC_URL", "base"),
    ("EVM_AVALANCHE_RPC_URL", "avalanche"),
];

fn num(text: &str) -> Value {
    serde_json::from_str(text).expect("number literal")
}

fn d(value: &str) -> Decimal {
    value.parse().expect("decimal literal")
}

struct FixedPrices(HashMap<String, Decimal>);

#[async_trait]
impl WalletPrices for FixedPrices {
    async fn refresh_prices(
        &self,
        tickers: &BTreeSet<String>,
        _today: NaiveDate,
    ) -> HashMap<String, Decimal> {
        tickers
            .iter()
            .filter_map(|t| self.0.get(t).map(|p| (t.clone(), *p)))
            .collect()
    }
}

struct Harness {
    app: TestApp,
    chain: MockServer,
    client: Client,
    member_id: i64,
}

impl Harness {
    async fn new() -> Self {
        let chain = MockServer::start().await;
        let mut builder = TestApp::builder()
            .with_admin()
            .env("BITCOIN_API_URL", chain.uri())
            .env("SOLANA_RPC_URL", format!("{}/solana", chain.uri()));
        for (key, name) in NETWORKS {
            builder = builder.env(key, format!("{}/evm/{name}", chain.uri()));
        }
        let app = builder.spawn().await;
        let (_, member_id) = app.create_member("alex", "password-1234").await;
        let client = app.login("alex", "password-1234").await;
        Self {
            app,
            chain,
            client,
            member_id,
        }
    }

    fn prices(&self, prices: &[(&str, &str)]) {
        let prices = prices
            .iter()
            .map(|(t, p)| ((*t).to_owned(), d(p)))
            .collect();
        self.app
            .state
            .sync
            .wallets
            .set_prices(Arc::new(FixedPrices(prices)));
    }

    async fn add(
        &self,
        chain: &str,
        address: &str,
        label: Option<&str>,
    ) -> picsou::testing::Response {
        self.client
            .post("/api/crypto/wallet")
            .json(&json!({"chain": chain, "address": address, "label": label}))
            .send()
            .await
    }

    async fn wallets(&self) -> Value {
        self.client.get("/api/crypto/wallet").send().await.json()
    }

    async fn scalar_decimal(&self, sql: &str, id: i64) -> Option<Decimal> {
        picsou::db::query_scalar(sql)
            .bind(id)
            .fetch_optional(&self.app.state.db)
            .await
            .expect("query")
    }

    async fn holdings(&self, account_id: i64) -> Vec<(String, Decimal)> {
        picsou::db::query_as(
            "SELECT ticker, quantity FROM account_holding WHERE account_id = $1 ORDER BY ticker",
        )
        .bind(account_id)
        .fetch_all(&self.app.state.db)
        .await
        .expect("holdings")
    }

    async fn snapshot(&self, account_id: i64) -> Option<Decimal> {
        picsou::db::query_scalar(
            "SELECT balance FROM balance_snapshot WHERE account_id = $1 AND date = $2",
        )
        .bind(account_id)
        .bind(self.app.state.clock.today())
        .fetch_optional(&self.app.state.db)
        .await
        .expect("snapshot")
    }

    async fn account_count(&self) -> i64 {
        picsou::db::query_scalar("SELECT count(*) FROM account WHERE member_id = $1")
            .bind(self.member_id)
            .fetch_one(&self.app.state.db)
            .await
            .expect("count")
    }

    /// Every EVM network answers: the given natives (wei, hex) and tokens (contract, raw hex),
    /// zero for everything else.
    async fn evm(&self, natives: &[(&str, &str)], tokens: &[(&str, &str, &str)]) {
        for (network, wei) in natives {
            Mock::given(method("POST"))
                .and(path(format!("/evm/{network}")))
                .and(body_partial_json(json!({"method": "eth_getBalance"})))
                .respond_with(rpc_result(json!(wei)))
                .with_priority(1)
                .mount(&self.chain)
                .await;
        }
        for (network, contract, raw) in tokens {
            Mock::given(method("POST"))
                .and(path(format!("/evm/{network}")))
                .and(body_partial_json(
                    json!({"method": "eth_call", "params": [{"to": contract}]}),
                ))
                .respond_with(rpc_result(json!(raw)))
                .with_priority(1)
                .mount(&self.chain)
                .await;
        }
        Mock::given(method("POST"))
            .and(path_regex("^/evm/"))
            .and(body_partial_json(json!({"method": "eth_getBalance"})))
            .respond_with(rpc_result(json!("0x0")))
            .with_priority(5)
            .mount(&self.chain)
            .await;
        Mock::given(method("POST"))
            .and(path_regex("^/evm/"))
            .and(body_partial_json(json!({"method": "eth_call"})))
            .respond_with(rpc_result(json!(format!("0x{}", "0".repeat(64)))))
            .with_priority(5)
            .mount(&self.chain)
            .await;
    }
}

fn rpc_result(result: Value) -> ResponseTemplate {
    ResponseTemplate::new(200).set_body_json(json!({"jsonrpc": "2.0", "id": 1, "result": result}))
}

fn rpc_error() -> ResponseTemplate {
    ResponseTemplate::new(200)
        .set_body_json(json!({"jsonrpc": "2.0", "id": 1, "error": {"code": -32000, "message": "header not found"}}))
}

/// coin amount in wei, as the hex an RPC returns.
fn wei(coins_e18: u128) -> String {
    format!("0x{coins_e18:x}")
}

fn sync_failed(chain: &str) -> String {
    format!("Could not sync your {chain} wallet. Please try again later.")
}

#[tokio::test]
async fn evm_wallet_fans_out_aggregates_by_symbol_and_rounds_each_line_half_up() {
    let h = Harness::new().await;
    h.evm(
        &[
            ("ethereum", &wei(1_500_000_000_000_000_000)),
            ("arbitrum", &wei(250_000_000_000_000_000)),
            ("bnb", &wei(2_000_000_000_000_000_000)),
        ],
        &[
            ("ethereum", USDC_ETH, "0x5f5e100"),
            ("base", USDC_BASE, "0x2faf080"),
        ],
    )
    .await;
    // 1.75 ETH x 2000.123 = 3500.21525 -> 3500.22; 150 USDC x 0.9 = 135.00; 2 BNB = 600.00.
    h.prices(&[("ETH", "2000.123"), ("USDC", "0.9"), ("BNB", "300")]);

    let response = h
        .add("EVM", &format!("  {EVM_ADDRESS}  "), Some("  Ledger  "))
        .await;
    assert_eq!(response.status, StatusCode::OK, "{}", response.text());
    let account = response.json();
    assert_eq!(account["currentBalance"], num("4235.22"));
    assert_eq!(account["name"], "Ledger");
    assert_eq!(account["type"], "CRYPTO");
    assert_eq!(account["provider"], "ETH");
    assert_eq!(account["currency"], "EUR");
    assert_eq!(account["color"], "#f59e0b");
    assert_eq!(account["logoKey"], wallets::DEFAULT_LOGO_KEY);
    assert_eq!(account["isManual"], false);
    assert_eq!(account["hidden"], false);
    assert_eq!(account["listCollapsed"], false);
    assert!(
        account.get("ticker").is_none(),
        "no ticker: the balance is already EUR"
    );

    let id = account["id"].as_i64().unwrap();
    assert_eq!(
        h.holdings(id).await,
        vec![
            ("BNB".into(), d("2")),
            ("ETH".into(), d("1.75")),
            ("USDC".into(), d("150"))
        ]
    );
    assert_eq!(h.snapshot(id).await, Some(d("4235.22")));
    let external: Option<String> =
        picsou::db::query_scalar("SELECT external_account_id FROM account WHERE id = $1")
            .bind(id)
            .fetch_one(&h.app.state.db)
            .await
            .unwrap();
    let list = h.wallets().await;
    assert_eq!(external, Some(format!("wallet_evm_{}", list[0]["id"])));
    assert_eq!(list[0]["address"], EVM_ADDRESS, "stored trimmed");
    assert_eq!(list[0]["label"], "Ledger");
    assert_eq!(list[0]["chain"], "EVM");
    assert!(list[0]["lastSyncedAt"].as_str().unwrap().ends_with('Z'));
}

#[tokio::test]
async fn a_native_failure_on_any_network_fails_the_sync_and_persists_nothing() {
    let h = Harness::new().await;
    Mock::given(method("POST"))
        .and(path("/evm/polygon"))
        .and(body_partial_json(json!({"method": "eth_getBalance"})))
        .respond_with(rpc_error())
        .with_priority(1)
        .mount(&h.chain)
        .await;
    h.evm(&[("ethereum", &wei(1_000_000_000_000_000_000))], &[])
        .await;
    h.prices(&[("ETH", "2000")]);

    let response = h.add("EVM", EVM_ADDRESS, None).await;
    assert_eq!(response.status, StatusCode::UNPROCESSABLE_ENTITY);
    assert_eq!(response.json()["detail"], sync_failed("EVM"));
    assert_eq!(h.wallets().await, json!([]), "the insert rolled back");
    assert_eq!(h.account_count().await, 0);
}

#[tokio::test]
async fn a_failing_token_is_skipped_without_dropping_the_native_or_other_tokens() {
    let h = Harness::new().await;
    Mock::given(method("POST"))
        .and(path("/evm/ethereum"))
        .and(body_partial_json(
            json!({"method": "eth_call", "params": [{"to": USDT_ETH}]}),
        ))
        .respond_with(rpc_error())
        .with_priority(1)
        .mount(&h.chain)
        .await;
    h.evm(
        &[("ethereum", &wei(1_000_000_000_000_000_000))],
        &[("ethereum", USDC_ETH, "0xf4240")],
    )
    .await;
    h.prices(&[("ETH", "2000"), ("USDC", "1"), ("USDT", "1")]);

    let response = h.add("EVM", EVM_ADDRESS, None).await;
    assert_eq!(response.status, StatusCode::OK, "{}", response.text());
    assert_eq!(response.json()["currentBalance"], num("2001.00"));
    assert_eq!(response.json()["name"], "EVM Wallet");
}

#[tokio::test]
async fn a_transient_transport_failure_is_retried() {
    let h = Harness::new().await;
    Mock::given(method("POST"))
        .and(path("/evm/optimism"))
        .respond_with(ResponseTemplate::new(503))
        .up_to_n_times(2)
        .with_priority(1)
        .mount(&h.chain)
        .await;
    h.evm(&[("optimism", &wei(1_000_000_000_000_000_000))], &[])
        .await;
    h.prices(&[("ETH", "2000")]);

    let response = h.add("EVM", EVM_ADDRESS, None).await;
    assert_eq!(response.status, StatusCode::OK, "{}", response.text());
    assert_eq!(response.json()["currentBalance"], num("2000.00"));
}

#[tokio::test]
async fn holding_assets_none_of_which_can_be_priced_fails_instead_of_recording_zero() {
    // The default seam: no price provider, so only EUR would be priced.
    let h = Harness::new().await;
    h.evm(&[("ethereum", &wei(1_000_000_000_000_000_000))], &[])
        .await;

    let response = h.add("EVM", EVM_ADDRESS, None).await;
    assert_eq!(response.status, StatusCode::UNPROCESSABLE_ENTITY);
    assert_eq!(response.json()["detail"], sync_failed("EVM"));
    assert_eq!(h.account_count().await, 0);
}

#[tokio::test]
async fn an_empty_wallet_syncs_to_zero_without_any_price() {
    let h = Harness::new().await;
    h.evm(&[], &[]).await;

    let response = h.add("EVM", EVM_ADDRESS, None).await;
    assert_eq!(response.status, StatusCode::OK, "{}", response.text());
    let account = response.json();
    assert_eq!(account["currentBalance"], num("0"));
    let id = account["id"].as_i64().unwrap();
    assert_eq!(h.snapshot(id).await, Some(d("0")));
    assert!(h.holdings(id).await.is_empty());
}

#[tokio::test]
async fn a_partial_price_outage_records_the_partial_total_and_keeps_the_unpriced_holding() {
    let h = Harness::new().await;
    h.evm(
        &[("ethereum", &wei(1_000_000_000_000_000_000))],
        &[("ethereum", USDC_ETH, "0x2faf080")],
    )
    .await;
    h.prices(&[("ETH", "2000"), ("USDC", "1")]);
    let account = h.add("EVM", EVM_ADDRESS, None).await.json();
    let id = account["id"].as_i64().unwrap();
    let wallet = h.wallets().await[0]["id"].as_i64().unwrap();

    h.prices(&[("ETH", "2100")]);
    let response = h
        .client
        .post(&format!("/api/crypto/wallet/{wallet}/sync"))
        .send()
        .await;
    assert_eq!(response.status, StatusCode::OK, "{}", response.text());
    assert_eq!(response.json()["currentBalance"], num("2100.00"));
    assert_eq!(h.snapshot(id).await, Some(d("2100.00")));
    assert_eq!(
        h.holdings(id).await,
        vec![("ETH".into(), d("1")), ("USDC".into(), d("50"))]
    );
    let cost: Option<Decimal> = h
        .scalar_decimal(
            "SELECT average_buy_in FROM account_holding WHERE account_id = $1 AND ticker = 'ETH'",
            id,
        )
        .await;
    assert_eq!(
        cost,
        Some(d("2000")),
        "the first sync's price stays the cost basis"
    );
}

#[tokio::test]
async fn a_token_no_longer_held_is_pruned() {
    let h = Harness::new().await;
    h.evm(
        &[("ethereum", &wei(1_000_000_000_000_000_000))],
        &[("ethereum", USDC_ETH, "0x2faf080")],
    )
    .await;
    h.prices(&[("ETH", "2000"), ("USDC", "1")]);
    let id = h.add("EVM", EVM_ADDRESS, None).await.json()["id"]
        .as_i64()
        .unwrap();
    let wallet = h.wallets().await[0]["id"].as_i64().unwrap();

    h.chain.reset().await;
    h.evm(&[("ethereum", &wei(1_000_000_000_000_000_000))], &[])
        .await;
    let response = h
        .client
        .post(&format!("/api/crypto/wallet/{wallet}/sync"))
        .send()
        .await;
    assert_eq!(response.status, StatusCode::OK);
    assert_eq!(h.holdings(id).await, vec![("ETH".into(), d("1"))]);
}

async fn solana(h: &Harness, token_accounts: Value) {
    Mock::given(method("POST"))
        .and(path("/solana"))
        .and(body_partial_json(json!({"method": "getBalance"})))
        .respond_with(rpc_result(
            json!({"context": {"slot": 1}, "value": 1_000_000_000}),
        ))
        .mount(&h.chain)
        .await;
    Mock::given(method("POST"))
        .and(path("/solana"))
        .and(body_partial_json(
            json!({"method": "getTokenAccountsByOwner"}),
        ))
        .respond_with(rpc_result(json!({"value": token_accounts})))
        .mount(&h.chain)
        .await;
}

fn spl(mint: &str, amount: Value) -> Value {
    json!({"account": {"data": {"parsed": {"info": {"mint": mint, "tokenAmount": {"uiAmountString": amount}}}}}})
}

#[tokio::test]
async fn solana_keeps_known_tokens_and_skips_unknown_mints_and_corrupt_amounts() {
    let h = Harness::new().await;
    solana(
        &h,
        json!([
            spl(
                "EPjFWdd5AufqSSqeM2qN1xzybapC8G4wEGGkZwyTDt1v",
                json!("12.5")
            ),
            spl(
                "UnknownMint111111111111111111111111111111111",
                json!("1000")
            ),
            spl(
                "Es9vMFrzaCERmJfrF4H2FYD4KCoNkY11McCe8BenwNYB",
                json!("not-a-number")
            ),
        ]),
    )
    .await;
    // 1 SOL x 0.125 rounds half up to 0.13; 12.5 USDC = 12.50.
    h.prices(&[("SOL", "0.125"), ("USDC", "1")]);

    let response = h.add("SOLANA", SOL_ADDRESS, None).await;
    assert_eq!(response.status, StatusCode::OK, "{}", response.text());
    let account = response.json();
    assert_eq!(account["currentBalance"], num("12.63"));
    assert_eq!(account["provider"], "SOL");
    assert_eq!(account["name"], "SOLANA Wallet");
    let id = account["id"].as_i64().unwrap();
    assert_eq!(
        h.holdings(id).await,
        vec![("SOL".into(), d("1")), ("USDC".into(), d("12.5"))]
    );
}

#[tokio::test]
async fn a_solana_token_rpc_error_fails_the_whole_sync() {
    let h = Harness::new().await;
    Mock::given(method("POST"))
        .and(path("/solana"))
        .and(body_partial_json(
            json!({"method": "getTokenAccountsByOwner"}),
        ))
        .respond_with(rpc_error())
        .with_priority(1)
        .mount(&h.chain)
        .await;
    solana(&h, json!([])).await;
    h.prices(&[("SOL", "100")]);

    let response = h.add("SOLANA", SOL_ADDRESS, None).await;
    assert_eq!(response.status, StatusCode::UNPROCESSABLE_ENTITY);
    assert_eq!(response.json()["detail"], sync_failed("SOLANA"));
}

fn esplora(funded: i64, spent: i64, tx_count: i64) -> ResponseTemplate {
    ResponseTemplate::new(200).set_body_json(json!({
        "chain_stats": {"funded_txo_sum": funded, "spent_txo_sum": spent, "tx_count": tx_count}
    }))
}

#[tokio::test]
async fn a_bitcoin_address_reads_its_confirmed_balance() {
    let h = Harness::new().await;
    Mock::given(method("GET"))
        .and(path(format!("/api/address/{BIP84_RECEIVE_0}")))
        .respond_with(esplora(150_000_000, 25_000_000, 3))
        .mount(&h.chain)
        .await;
    h.prices(&[("BTC", "60000")]);

    let response = h.add("BITCOIN", BIP84_RECEIVE_0, Some("Cold")).await;
    assert_eq!(response.status, StatusCode::OK, "{}", response.text());
    assert_eq!(response.json()["currentBalance"], num("75000.00"));
    let id = response.json()["id"].as_i64().unwrap();
    assert_eq!(h.holdings(id).await, vec![("BTC".into(), d("1.25"))]);
}

#[tokio::test]
async fn a_zpub_scans_both_chains_until_the_gap_limit() {
    let h = Harness::new().await;
    Mock::given(method("GET"))
        .and(path(format!("/api/address/{BIP84_RECEIVE_0}")))
        .respond_with(esplora(10_000_000, 0, 1))
        .with_priority(1)
        .mount(&h.chain)
        .await;
    Mock::given(method("GET"))
        .and(path(format!("/api/address/{BIP84_CHANGE_0}")))
        .respond_with(esplora(5_000_000, 0, 1))
        .with_priority(1)
        .mount(&h.chain)
        .await;
    Mock::given(method("GET"))
        .and(path_regex("^/api/address/"))
        .respond_with(esplora(0, 0, 0))
        .with_priority(5)
        .expect(40)
        .mount(&h.chain)
        .await;
    h.prices(&[("BTC", "60000")]);

    let response = h.add("BITCOIN", BIP84_ZPUB, None).await;
    assert_eq!(response.status, StatusCode::OK, "{}", response.text());
    assert_eq!(
        response.json()["currentBalance"],
        num("9000.00"),
        "0.15 BTC"
    );
}

#[tokio::test]
async fn an_undecodable_bitcoin_key_fails_without_calling_the_explorer() {
    let h = Harness::new().await;
    Mock::given(method("GET"))
        .respond_with(esplora(0, 0, 0))
        .expect(0)
        .mount(&h.chain)
        .await;
    let response = h.add("BITCOIN", "xpub-not-a-real-key", None).await;
    assert_eq!(response.status, StatusCode::UNPROCESSABLE_ENTITY);
    assert_eq!(response.json()["detail"], sync_failed("BITCOIN"));
}

#[tokio::test]
async fn an_explorer_outage_is_a_failed_sync_not_a_zero_balance() {
    let h = Harness::new().await;
    Mock::given(method("GET"))
        .respond_with(ResponseTemplate::new(429))
        .mount(&h.chain)
        .await;
    h.prices(&[("BTC", "60000")]);
    let response = h.add("BITCOIN", BIP84_ZPUB, None).await;
    assert_eq!(response.status, StatusCode::UNPROCESSABLE_ENTITY);
}

#[tokio::test]
async fn input_is_checked_before_anything_is_stored_or_called() {
    let h = Harness::new().await;
    Mock::given(method("POST"))
        .respond_with(rpc_result(json!("0x0")))
        .expect(0)
        .mount(&h.chain)
        .await;

    let cases = [
        (
            json!({"address": EVM_ADDRESS}),
            "A wallet chain is required",
        ),
        (
            json!({"chain": "SOLANA", "address": "   "}),
            "A wallet address is required",
        ),
        (
            json!({"chain": "BITCOIN", "address": "x".repeat(201)}),
            "Wallet address is too long (201 characters, maximum 200)",
        ),
        (
            json!({"chain": "EVM", "address": "0xnope"}),
            "Invalid EVM address '0xnope' (expected 0x followed by 40 hex characters)",
        ),
        (
            json!({"chain": 1, "address": "0xnope"}),
            "Invalid EVM address '0xnope' (expected 0x followed by 40 hex characters)",
        ),
        (
            json!({"chain": "DOGE", "address": "D1"}),
            "Failed to read request",
        ),
    ];
    for (body, detail) in cases {
        let response = h.client.post("/api/crypto/wallet").json(&body).send().await;
        assert_eq!(response.status, StatusCode::BAD_REQUEST, "{body}");
        assert_eq!(response.json()["detail"], detail, "{body}");
    }
    assert_eq!(h.wallets().await, json!([]));
}

async fn insert_wallet(h: &Harness, member_id: i64, chain: &str, address: &str) -> i64 {
    picsou::db::query_scalar(
        "INSERT INTO wallet_address (member_id, chain, address) VALUES ($1, $2, $3) RETURNING id",
    )
    .bind(member_id)
    .bind(chain)
    .bind(address)
    .fetch_one(&h.app.state.db)
    .await
    .expect("wallet")
}

#[tokio::test]
async fn another_members_wallet_can_be_neither_listed_synced_nor_removed() {
    let h = Harness::new().await;
    let (_, other) = h.app.create_member("sam", "password-1234").await;
    let theirs = insert_wallet(&h, other, "EVM", EVM_ADDRESS).await;

    assert_eq!(h.wallets().await, json!([]));
    let sync = h
        .client
        .post(&format!("/api/crypto/wallet/{theirs}/sync"))
        .send()
        .await;
    assert_eq!(sync.status, StatusCode::NOT_FOUND);
    assert_eq!(sync.json()["detail"], "Wallet not found");
    let delete = h
        .client
        .delete(&format!("/api/crypto/wallet/{theirs}"))
        .send()
        .await;
    assert_eq!(delete.status, StatusCode::NOT_FOUND);

    let sam = h.app.login("sam", "password-1234").await;
    let listed = sam.get("/api/crypto/wallet").send().await.json();
    assert_eq!(listed[0]["id"], json!(theirs));
    assert!(listed[0].get("label").is_none() && listed[0].get("lastSyncedAt").is_none());
}

#[tokio::test]
async fn removing_a_wallet_soft_deletes_its_account_and_keeps_its_history() {
    let h = Harness::new().await;
    h.evm(&[("ethereum", &wei(1_000_000_000_000_000_000))], &[])
        .await;
    h.prices(&[("ETH", "2000")]);
    let id = h.add("EVM", EVM_ADDRESS, None).await.json()["id"]
        .as_i64()
        .unwrap();
    let wallet = h.wallets().await[0]["id"].as_i64().unwrap();

    let response = h
        .client
        .delete(&format!("/api/crypto/wallet/{wallet}"))
        .send()
        .await;
    assert_eq!(response.status, StatusCode::NO_CONTENT);
    assert!(response.body.is_empty());
    assert_eq!(h.wallets().await, json!([]));
    let deleted: bool =
        picsou::db::query_scalar("SELECT deleted_at IS NOT NULL FROM account WHERE id = $1")
            .bind(id)
            .fetch_one(&h.app.state.db)
            .await
            .unwrap();
    assert!(deleted);
    assert_eq!(h.snapshot(id).await, Some(d("2000.00")), "history survives");
}

#[tokio::test]
async fn a_wallet_whose_account_was_deleted_is_never_resurrected() {
    let h = Harness::new().await;
    h.evm(&[("ethereum", &wei(1_000_000_000_000_000_000))], &[])
        .await;
    h.prices(&[("ETH", "2000")]);
    let wallet = insert_wallet(&h, h.member_id, "EVM", EVM_ADDRESS).await;
    picsou::db::query(
        "INSERT INTO account (member_id, name, type, currency, current_balance, external_account_id, is_manual, color, \
         hidden, deleted_at, created_at, updated_at) \
         VALUES ($1, 'Old', 'CRYPTO', 'EUR', 0, $2, false, '#f59e0b', false, now(), now(), now())",
    )
    .bind(h.member_id)
    .bind(format!("wallet_evm_{wallet}"))
    .execute(&h.app.state.db)
    .await
    .unwrap();

    let response = h
        .client
        .post(&format!("/api/crypto/wallet/{wallet}/sync"))
        .send()
        .await;
    assert_eq!(response.status, StatusCode::UNPROCESSABLE_ENTITY);
    assert_eq!(
        response.json()["detail"],
        "This wallet's account was deleted. Remove the wallet from Sync -> Wallets to stop tracking it."
    );
    let summary = wallets::resync_all(&h.app.state, h.member_id).await;
    assert_eq!(
        (summary.total, summary.succeeded, summary.failed.len()),
        (1, 0, 0),
        "a skip, not a failure"
    );
    assert_eq!(h.account_count().await, 1, "no new account");
}

#[tokio::test]
async fn resync_all_reports_the_failed_chain_and_syncs_the_others() {
    let h = Harness::new().await;
    h.evm(&[("ethereum", &wei(1_000_000_000_000_000_000))], &[])
        .await;
    h.prices(&[("ETH", "2000")]);
    insert_wallet(&h, h.member_id, "EVM", EVM_ADDRESS).await;
    insert_wallet(&h, h.member_id, "BITCOIN", "xpub-not-a-real-key").await;

    let summary = wallets::resync_all(&h.app.state, h.member_id).await;
    assert_eq!(summary.total, 2);
    assert_eq!(summary.succeeded, 1);
    assert_eq!(summary.failed, vec![Chain::Bitcoin]);
    let empty = wallets::resync_all(&h.app.state, h.member_id + 1000).await;
    assert_eq!((empty.total, empty.succeeded), (0, 0));
}

#[tokio::test]
async fn a_resync_keeps_the_account_name_and_the_logo_the_user_picked() {
    let h = Harness::new().await;
    h.evm(&[("ethereum", &wei(1_000_000_000_000_000_000))], &[])
        .await;
    h.prices(&[("ETH", "2000")]);
    let id = h.add("EVM", EVM_ADDRESS, Some("Hot")).await.json()["id"]
        .as_i64()
        .unwrap();
    picsou::db::query(
        "UPDATE account SET name = 'Renamed', logo_key = 'ledger', ticker = 'ETH' WHERE id = $1",
    )
    .bind(id)
    .execute(&h.app.state.db)
    .await
    .unwrap();
    let wallet = h.wallets().await[0]["id"].as_i64().unwrap();

    h.prices(&[("ETH", "3000")]);
    let account = h
        .client
        .post(&format!("/api/crypto/wallet/{wallet}/sync"))
        .send()
        .await
        .json();
    assert_eq!(account["id"], json!(id));
    assert_eq!(account["name"], "Renamed");
    assert_eq!(account["logoKey"], "ledger");
    assert!(account.get("ticker").is_none(), "a sync clears the ticker");
    assert_eq!(account["currentBalance"], num("3000.00"));
}

//! AccountServiceTest: the valuation, snapshot and holding cases, on real rows with a stubbed
//! PriceRead, plus the track's golden cases (first/second sync, snapshot upsert, FX, Debt).

use std::collections::{BTreeSet, HashMap};
use std::sync::Mutex;

use async_trait::async_trait;
use chrono::NaiveDate;
use picsou::error::AppError;
use picsou::ledger::accounts::{self, NewAccount};
use picsou::ledger::model::{Account, AccountType, HoldingDraft, SnapshotSource};
use picsou::ledger::prices::{PriceRead, Quote};
use picsou::ledger::snapshots::{self, NewSnapshot};
use picsou::ledger::valuation::{self, SnapshotContext, Valuation};
use picsou::ledger::{holdings, providers};
use picsou::testing::TestApp;
use rust_decimal::Decimal;

use super::support::{d, day, member, now, today};

/// PriceService as the Java tests stub it: quotes by ticker, each marked crypto or not, and FX
/// rates. A crypto-only call sees the crypto entries only, as getCryptoQuotes never falls
/// through to an equity source.
#[derive(Default)]
struct StubPrices {
    quotes: HashMap<String, (Quote, bool)>,
    fx: HashMap<String, Decimal>,
    /// The crypto_only flag of every quotes call.
    calls: Mutex<Vec<bool>>,
}

impl StubPrices {
    fn new() -> Self {
        Self::default()
    }

    fn equity(mut self, ticker: &str, price: &str) -> Self {
        self.quotes.insert(ticker.to_owned(), (live(price), false));
        self
    }

    fn crypto(mut self, ticker: &str, price: &str) -> Self {
        self.quotes.insert(ticker.to_owned(), (live(price), true));
        self
    }

    fn quote(mut self, ticker: &str, quote: Quote) -> Self {
        self.quotes.insert(ticker.to_owned(), (quote, false));
        self
    }

    fn fx(mut self, currency: &str, rate: &str) -> Self {
        self.fx.insert(currency.to_owned(), d(rate));
        self
    }

    fn calls(&self) -> Vec<bool> {
        self.calls.lock().unwrap().clone()
    }
}

#[async_trait]
impl PriceRead for StubPrices {
    async fn quotes(
        &self,
        tickers: &BTreeSet<String>,
        crypto_only: bool,
        _today: NaiveDate,
    ) -> HashMap<String, Quote> {
        self.calls.lock().unwrap().push(crypto_only);
        tickers
            .iter()
            .filter_map(|t| {
                let (quote, is_crypto) = self.quotes.get(t)?;
                (!crypto_only || *is_crypto).then(|| (t.clone(), *quote))
            })
            .collect()
    }

    async fn fx_rate_eur(&self, currency: &str) -> Option<Decimal> {
        self.fx.get(currency).copied()
    }
}

fn live(price: &str) -> Quote {
    Quote {
        price: d(price),
        as_of: today(),
        live: true,
    }
}

fn line(ticker: &str, quantity: &str) -> HoldingDraft {
    HoldingDraft {
        ticker: ticker.to_owned(),
        quantity: d(quantity),
        ..HoldingDraft::default()
    }
}

struct Fixture {
    app: TestApp,
    member_id: i64,
}

impl Fixture {
    async fn new() -> Self {
        let app = TestApp::spawn().await;
        let member_id = member(&app, "alice").await;
        Self { app, member_id }
    }

    async fn account(
        &self,
        account_type: AccountType,
        balance: &str,
        edit: impl FnOnce(&mut NewAccount),
    ) -> Account {
        let mut draft = NewAccount::new(self.member_id, "Account", account_type);
        draft.current_balance = d(balance);
        edit(&mut draft);
        accounts::insert(&self.app.state.db, &draft, now())
            .await
            .expect("account")
    }

    /// The contract cost a CORUM Life sync stores, which NewAccount does not carry.
    async fn with_provider_invested(&self, account: &Account, invested: &str) -> Account {
        sqlx::query("UPDATE account SET provider_invested_eur = $2 WHERE id = $1")
            .bind(account.id)
            .bind(d(invested))
            .execute(&self.app.state.db)
            .await
            .expect("provider_invested_eur");
        accounts::find_by_id(&self.app.state.db, account.id)
            .await
            .unwrap()
            .expect("account")
    }

    async fn hold(&self, account: &Account, holding: HoldingDraft) {
        holdings::insert(&self.app.state.db, account.id, &holding, now())
            .await
            .expect("holding");
    }

    async fn valuation(&self, prices: &dyn PriceRead, account: &Account) -> Valuation {
        let mut conn = self.app.state.db.acquire().await.unwrap();
        valuation::valuation(&mut conn, prices, account, today())
            .await
            .unwrap()
    }

    async fn live_balance(&self, prices: &dyn PriceRead, account: &Account) -> Decimal {
        let mut conn = self.app.state.db.acquire().await.unwrap();
        valuation::live_balance_eur(&mut conn, prices, account, today())
            .await
            .unwrap()
    }

    async fn signed_live_balance(&self, prices: &dyn PriceRead, account: &Account) -> Decimal {
        let mut conn = self.app.state.db.acquire().await.unwrap();
        valuation::signed_live_balance_eur(&mut conn, prices, account, today())
            .await
            .unwrap()
    }

    async fn debt(
        &self,
        account: &Account,
        borrowed: &str,
        start: Option<&str>,
        end: Option<&str>,
    ) {
        sqlx::query(
            "INSERT INTO debt (account_id, member_id, borrowed_amount, interest_rate, start_date, end_date) \
             VALUES ($1, $2, $3, 0, $4, $5)",
        )
        .bind(account.id)
        .bind(self.member_id)
        .bind(d(borrowed))
        .bind(start.map(day))
        .bind(end.map(day))
        .execute(&self.app.state.db)
        .await
        .expect("debt");
    }
}

fn eur() -> impl FnOnce(&mut NewAccount) {
    |_| {}
}

fn provider(name: &'static str) -> impl FnOnce(&mut NewAccount) {
    move |a| {
        a.provider = Some(name.to_owned());
        a.is_manual = false;
    }
}

fn corum_life(a: &mut NewAccount) {
    a.provider = Some("CORUM".to_owned());
    a.is_manual = false;
    a.external_account_id = Some("corum_life_1001".to_owned());
    a.cash_balance = Some(Decimal::ZERO);
}

fn insurer(cash: &'static str) -> impl FnOnce(&mut NewAccount) {
    move |a| {
        a.provider = Some(providers::CAISSE_EPARGNE.to_owned());
        a.is_manual = false;
        a.cash_balance = Some(d(cash));
    }
}

// ─── LOAN and PEER_LOAN ─────────────────────────────────────────────────────

#[tokio::test]
async fn live_balance_eur_loan_with_debt_returns_positive_remaining_balance() {
    let f = Fixture::new().await;
    let loan = f.account(AccountType::Loan, "12000", eur()).await;
    // 120 interest-free installments of 100 from a year before today: twelve are paid.
    f.debt(&loan, "12000", Some("2025-03-15"), Some("2035-03-15"))
        .await;

    let result = f.live_balance(&StubPrices::new(), &loan).await;

    assert_eq!(result.to_string(), "10800.00");
}

#[tokio::test]
async fn live_balance_eur_loan_with_debt_pairs_the_outstanding_with_the_stored_balance() {
    let f = Fixture::new().await;
    let loan = f.account(AccountType::Loan, "12000", eur()).await;
    f.debt(&loan, "12000", Some("2025-03-15"), Some("2035-03-15"))
        .await;

    let valuation = f.valuation(&StubPrices::new(), &loan).await;

    assert_eq!(valuation.invested_eur, d("12000"));
    assert!(valuation.all_priced && valuation.any_priced && !valuation.any_stale);
}

#[tokio::test]
async fn live_balance_eur_loan_without_debt_falls_back_to_stored_balance() {
    let f = Fixture::new().await;
    let loan = f.account(AccountType::Loan, "12000", eur()).await;

    assert_eq!(f.live_balance(&StubPrices::new(), &loan).await, d("12000"));
}

#[tokio::test]
async fn live_balance_eur_loan_with_debt_without_dates_falls_back_to_stored_balance() {
    let f = Fixture::new().await;
    let loan = f.account(AccountType::Loan, "12000", eur()).await;
    f.debt(&loan, "200000", None, None).await;

    assert_eq!(f.live_balance(&StubPrices::new(), &loan).await, d("12000"));
}

#[tokio::test]
async fn signed_live_balance_eur_loan_returns_negative_outstanding() {
    let f = Fixture::new().await;
    let loan = f.account(AccountType::Loan, "12000", eur()).await;
    f.debt(&loan, "12000", Some("2025-03-15"), Some("2035-03-15"))
        .await;

    let result = f.signed_live_balance(&StubPrices::new(), &loan).await;

    assert_eq!(result.to_string(), "-10800.00");
}

#[tokio::test]
async fn signed_live_balance_eur_checking_returns_balance_unchanged() {
    let f = Fixture::new().await;
    let cash = f.account(AccountType::Checking, "2500", eur()).await;

    assert_eq!(
        f.signed_live_balance(&StubPrices::new(), &cash).await,
        d("2500")
    );
}

#[tokio::test]
async fn peer_loan_is_valued_at_the_principal_still_owed_on_both_sides() {
    let f = Fixture::new().await;
    let receivable = f.account(AccountType::PeerLoan, "1000", eur()).await;
    let db = &f.app.state.db;
    let counterparty: i64 = sqlx::query_scalar(
        "INSERT INTO peer_counterparty (member_id, name) VALUES ($1, 'Marie') RETURNING id",
    )
    .bind(f.member_id)
    .fetch_one(db)
    .await
    .unwrap();
    sqlx::query(
        "INSERT INTO peer_loan_terms (account_id, counterparty_id, principal, lent_at) \
         VALUES ($1, $2, 1000, '2026-01-10')",
    )
    .bind(receivable.id)
    .bind(counterparty)
    .execute(db)
    .await
    .unwrap();
    sqlx::query(
        "INSERT INTO peer_loan_repayment (account_id, paid_on, amount, kind) \
         VALUES ($1, '2026-02-10', 300, 'PRINCIPAL')",
    )
    .bind(receivable.id)
    .execute(db)
    .await
    .unwrap();

    let valuation = f.valuation(&StubPrices::new(), &receivable).await;

    assert_eq!(valuation.live_eur, d("700"));
    assert_eq!(valuation.invested_eur, d("700"));
}

#[tokio::test]
async fn peer_loan_without_terms_falls_back_to_the_stored_balance() {
    let f = Fixture::new().await;
    let receivable = f.account(AccountType::PeerLoan, "450", eur()).await;

    let valuation = f.valuation(&StubPrices::new(), &receivable).await;

    assert_eq!(
        (valuation.live_eur, valuation.invested_eur),
        (d("450"), d("450"))
    );
}

// ─── cash accounts ──────────────────────────────────────────────────────────

#[tokio::test]
async fn live_balance_eur_cash_account_converts_stored_balance() {
    let f = Fixture::new().await;
    let cash = f
        .account(AccountType::Checking, "2500", |a| {
            a.currency = "USD".to_owned()
        })
        .await;
    let prices = StubPrices::new().fx("USD", "0.92");

    assert_eq!(f.live_balance(&prices, &cash).await, d("2300"));
}

#[tokio::test]
async fn valuation_cash_account_costs_what_it_is_worth_in_eur() {
    let f = Fixture::new().await;
    let cash = f
        .account(AccountType::Checking, "2500", |a| {
            a.currency = "USD".to_owned()
        })
        .await;
    let prices = StubPrices::new().fx("USD", "0.92");

    let valuation = f.valuation(&prices, &cash).await;

    assert_eq!(valuation.live_eur, d("2300"));
    assert_eq!(valuation.invested_eur, d("2300"));
}

#[tokio::test]
async fn caisse_epargne_per_euro_pocket_without_holdings_uses_its_stored_balance() {
    let f = Fixture::new().await;
    let account = f.account(AccountType::Per, "1000", insurer("1000")).await;

    let valuation = f.valuation(&StubPrices::new(), &account).await;

    assert_eq!(valuation.live_eur, d("1000"));
    assert_eq!(valuation.invested_eur, d("1000"));
    assert!(valuation.any_priced);
    assert!(!valuation.any_stale);
}

// ─── holdings ───────────────────────────────────────────────────────────────

#[tokio::test]
async fn live_balance_eur_skips_holdings_without_live_price() {
    let f = Fixture::new().await;
    let account = f.account(AccountType::CompteTitres, "0", eur()).await;
    f.hold(&account, line("AAPL", "5")).await;
    f.hold(&account, line("PHYMF", "10")).await;
    let prices = StubPrices::new().equity("AAPL", "200");

    assert_eq!(f.live_balance(&prices, &account).await, d("1000"));
}

#[tokio::test]
async fn live_balance_eur_uses_broker_position_value_when_live_price_is_unavailable() {
    let f = Fixture::new().await;
    let account = f.account(AccountType::CompteTitres, "0", eur()).await;
    f.hold(&account, line("AAPL", "5")).await;
    f.hold(
        &account,
        HoldingDraft {
            provider_value_eur: Some(d("840")),
            ..line("PHYMF", "10")
        },
    )
    .await;
    let prices = StubPrices::new().equity("AAPL", "200");

    assert_eq!(f.live_balance(&prices, &account).await, d("1840"));
}

#[tokio::test]
async fn valuation_counts_the_cost_basis_of_a_broker_valued_line_not_just_its_value() {
    let f = Fixture::new().await;
    let account = f.account(AccountType::CompteTitres, "0", eur()).await;
    f.hold(
        &account,
        HoldingDraft {
            provider_value_eur: Some(d("840")),
            provider_pnl_eur: Some(d("40")),
            ..line("PHYMF", "10")
        },
    )
    .await;

    let valuation = f.valuation(&StubPrices::new(), &account).await;

    assert_eq!(valuation.live_eur, d("840"));
    assert_eq!(valuation.invested_eur, d("800"));
}

#[tokio::test]
async fn valuation_converts_a_native_balance_used_as_the_baseline_for_an_unknown_cost() {
    let f = Fixture::new().await;
    let account = f
        .account(AccountType::Crypto, "1000", |a| {
            a.currency = "USD".to_owned()
        })
        .await;
    f.hold(
        &account,
        HoldingDraft {
            provider_value_eur: Some(d("900")),
            ..line("XYZ", "1")
        },
    )
    .await;
    let prices = StubPrices::new().fx("USD", "0.9");

    let valuation = f.valuation(&prices, &account).await;

    assert_eq!(valuation.live_eur, d("900"));
    assert_eq!(valuation.invested_eur, d("900"));
}

#[tokio::test]
async fn valuation_falls_back_to_the_live_value_as_the_baseline_when_the_balance_has_no_eur_rate() {
    let f = Fixture::new().await;
    let account = f
        .account(AccountType::Crypto, "1000", |a| {
            a.currency = "USD".to_owned()
        })
        .await;
    f.hold(
        &account,
        HoldingDraft {
            provider_value_eur: Some(d("900")),
            ..line("XYZ", "1")
        },
    )
    .await;

    let valuation = f.valuation(&StubPrices::new(), &account).await;

    assert_eq!(valuation.live_eur, d("900"));
    assert_eq!(valuation.invested_eur, d("900"));
}

fn simplefin_brokerage(a: &mut NewAccount) {
    a.provider = Some("SimpleFIN".to_owned());
    a.currency = "USD".to_owned();
}

#[tokio::test]
async fn valuation_simplefin_position_yahoo_cannot_price_is_valued_at_the_bridges_figure_not_dropped()
 {
    let f = Fixture::new().await;
    let account = f
        .account(AccountType::Brokerage, "10000", simplefin_brokerage)
        .await;
    f.hold(&account, line("AAA", "20")).await;
    f.hold(
        &account,
        HoldingDraft {
            provider_value_eur: Some(d("7000")),
            provider_price_date: Some(day("2026-01-02")),
            ..line("BBB", "50")
        },
    )
    .await;
    let prices = StubPrices::new().equity("AAA", "100").fx("USD", "0.9");

    let valuation = f.valuation(&prices, &account).await;

    assert_eq!(valuation.live_eur, d("9000"));
    assert_eq!(valuation.invested_eur, d("9000"));
}

#[tokio::test]
async fn valuation_simplefin_portfolio_no_price_feed_knows_is_the_sum_of_the_bridges_figures_not_zero()
 {
    let f = Fixture::new().await;
    let account = f
        .account(AccountType::Brokerage, "10000", simplefin_brokerage)
        .await;
    f.hold(
        &account,
        HoldingDraft {
            provider_value_eur: Some(d("6000")),
            ..line("31617H102", "12")
        },
    )
    .await;
    f.hold(
        &account,
        HoldingDraft {
            provider_value_eur: Some(d("3000")),
            ..line("922908553", "3")
        },
    )
    .await;
    let prices = StubPrices::new().fx("USD", "0.9");

    let valuation = f.valuation(&prices, &account).await;

    assert_eq!(valuation.live_eur, d("9000"));
    assert_eq!(valuation.invested_eur, d("9000"));
    assert!(valuation.any_priced);
}

fn bourse_direct(cash: Option<&'static str>) -> impl FnOnce(&mut NewAccount) {
    move |a| {
        a.provider = Some(providers::BOURSE_DIRECT.to_owned());
        a.is_manual = false;
        a.cash_balance = cash.map(d);
    }
}

#[tokio::test]
async fn live_balance_eur_bourse_direct_adds_cash_when_all_positions_are_priced() {
    let f = Fixture::new().await;
    let account = f
        .account(AccountType::Pea, "1250", bourse_direct(Some("250")))
        .await;
    f.hold(&account, line("ACME", "10")).await;
    let prices = StubPrices::new().equity("ACME", "100");

    assert_eq!(f.live_balance(&prices, &account).await, d("1250"));
}

#[tokio::test]
async fn live_balance_eur_bourse_direct_uses_broker_total_when_any_position_is_unpriced() {
    let f = Fixture::new().await;
    let account = f
        .account(AccountType::Pea, "1250", bourse_direct(Some("250")))
        .await;
    f.hold(&account, line("UNKNOWN", "10")).await;

    assert_eq!(
        f.live_balance(&StubPrices::new(), &account).await,
        d("1250")
    );
}

#[tokio::test]
async fn live_balance_eur_amundi_uses_the_provider_total_when_no_fcpe_can_be_priced() {
    let f = Fixture::new().await;
    let account = f
        .account(
            AccountType::EmployeeSavings,
            "1234.56",
            provider(providers::AMUNDI),
        )
        .await;
    f.hold(&account, line("FR0010405035", "12.3456")).await;

    assert_eq!(
        f.live_balance(&StubPrices::new(), &account).await,
        d("1234.56")
    );
}

#[tokio::test]
async fn live_balance_eur_amundi_still_prefers_live_prices_when_every_fcpe_resolves() {
    let f = Fixture::new().await;
    let account = f
        .account(
            AccountType::EmployeeSavings,
            "1000",
            provider(providers::AMUNDI),
        )
        .await;
    f.hold(&account, line("FR0010405035", "10")).await;
    let prices = StubPrices::new().equity("FR0010405035", "123.456");

    assert_eq!(f.live_balance(&prices, &account).await, d("1234.56"));
}

#[tokio::test]
async fn natixis_keeps_the_provider_values_even_when_every_ticker_would_resolve() {
    let f = Fixture::new().await;
    let account = f
        .account(
            AccountType::EmployeeSavings,
            "1500.50",
            provider(providers::NATIXIS),
        )
        .await;
    f.hold(
        &account,
        HoldingDraft {
            provider_value_eur: Some(d("1500.50")),
            provider_pnl_eur: Some(d("100.50")),
            ..line("FR0010405035", "10")
        },
    )
    .await;
    // An unrelated instrument's price that the old code would have used: 10 x 999.
    let prices = StubPrices::new().equity("FR0010405035", "999");

    assert_eq!(f.live_balance(&prices, &account).await, d("1500.50"));
    assert!(prices.calls().is_empty());
}

#[tokio::test]
async fn live_balance_eur_bourso_bank_uses_the_provider_total_when_a_line_kept_its_bourso_symbol() {
    let f = Fixture::new().await;
    let account = f
        .account(AccountType::Pea, "143088.89", provider(providers::BOURSO))
        .await;
    f.hold(
        &account,
        HoldingDraft {
            provider_value_eur: Some(d("140000.00")),
            ..line("1rTCW8", "1000")
        },
    )
    .await;

    assert_eq!(
        f.live_balance(&StubPrices::new(), &account).await,
        d("143088.89")
    );
}

fn fortuneo(cash: &'static str) -> impl FnOnce(&mut NewAccount) {
    move |a| {
        a.provider = Some(providers::FORTUNEO.to_owned());
        a.is_manual = false;
        a.cash_balance = Some(d(cash));
    }
}

#[tokio::test]
async fn live_balance_eur_fortuneo_uses_broker_total_when_an_unlisted_holding_cannot_be_priced() {
    let f = Fixture::new().await;
    let account = f.account(AccountType::Pea, "5000", fortuneo("500")).await;
    f.hold(&account, line("ACME.PA", "10")).await;
    f.hold(&account, line("XX0000000001", "100")).await;
    let prices = StubPrices::new().equity("ACME.PA", "100");

    assert_eq!(f.live_balance(&prices, &account).await, d("5000"));
}

#[tokio::test]
async fn live_balance_eur_fortuneo_recomputes_live_when_every_holding_is_priced() {
    let f = Fixture::new().await;
    let account = f.account(AccountType::Pea, "999999", fortuneo("250")).await;
    f.hold(&account, line("ACME.PA", "10")).await;
    let prices = StubPrices::new().equity("ACME.PA", "100");

    assert_eq!(f.live_balance(&prices, &account).await, d("1250"));
}

// ─── CORUM Life ─────────────────────────────────────────────────────────────

#[tokio::test]
async fn valuation_corum_life_uses_corums_own_line_values_when_no_feed_quotes_its_units() {
    let f = Fixture::new().await;
    let account = f
        .account(AccountType::AssuranceVie, "1534.37", corum_life)
        .await;
    f.hold(
        &account,
        HoldingDraft {
            current_price: Some(d("195")),
            average_buy_in: Some(d("180")),
            provider_value_eur: Some(d("518.61")),
            ..line("CORUM-LIFE-XL", "2.65955")
        },
    )
    .await;
    f.hold(
        &account,
        HoldingDraft {
            current_price: Some(Decimal::ONE),
            average_buy_in: Some(Decimal::ONE),
            provider_value_eur: Some(d("1015.76")),
            ..line("CORUM-LIFE-FGCORUM", "1015.76")
        },
    )
    .await;

    let valuation = f.valuation(&StubPrices::new(), &account).await;

    assert_eq!(valuation.live_eur, d("1534.37"));
    // 2.65955 x 180 + 1015.76 x 1
    assert_eq!(valuation.invested_eur, d("1494.479"));
    assert!(valuation.any_priced);
}

#[tokio::test]
async fn valuation_manual_account_typed_corum_does_not_take_the_corum_life_fallback() {
    let f = Fixture::new().await;
    let account = f
        .account(AccountType::AssuranceVie, "5000", |a| {
            a.provider = Some("CORUM".to_owned());
            a.cash_balance = Some(Decimal::ZERO);
        })
        .await;
    f.hold(
        &account,
        HoldingDraft {
            average_buy_in: Some(d("100")),
            ..line("NOQUOTE", "1")
        },
    )
    .await;

    let valuation = f.valuation(&StubPrices::new(), &account).await;

    assert_eq!(valuation.live_eur, d("0"));
    assert!(!valuation.any_priced);
}

#[tokio::test]
async fn valuation_corum_life_pairs_the_contract_value_with_the_contract_cost() {
    let f = Fixture::new().await;
    let account = f
        .account(AccountType::AssuranceVie, "1534.37", corum_life)
        .await;
    let account = f.with_provider_invested(&account, "1580").await;
    f.hold(
        &account,
        HoldingDraft {
            current_price: Some(d("195")),
            provider_value_eur: Some(d("518.61")),
            ..line("CORUM-LIFE-XL", "2.65955")
        },
    )
    .await;
    f.hold(
        &account,
        HoldingDraft {
            current_price: Some(Decimal::ONE),
            provider_value_eur: Some(d("1015.76")),
            ..line("CORUM-LIFE-FGCORUM", "1015.76")
        },
    )
    .await;

    let valuation = f.valuation(&StubPrices::new(), &account).await;

    assert_eq!(valuation.live_eur, d("1534.37"));
    assert_eq!(valuation.invested_eur, d("1580"));
    assert!(valuation.any_priced);
}

#[tokio::test]
async fn valuation_corum_life_keeps_the_contract_cost_when_the_provider_is_renamed() {
    let f = Fixture::new().await;
    let account = f
        .account(AccountType::AssuranceVie, "1534.37", |a| {
            corum_life(a);
            a.provider = Some("My insurer".to_owned());
        })
        .await;
    let account = f.with_provider_invested(&account, "1580").await;
    f.hold(
        &account,
        HoldingDraft {
            provider_value_eur: Some(d("518.61")),
            ..line("CORUM-LIFE-XL", "2.65955")
        },
    )
    .await;

    let valuation = f.valuation(&StubPrices::new(), &account).await;

    assert_eq!(valuation.live_eur, d("1534.37"));
    assert_eq!(valuation.invested_eur, d("1580"));
    assert!(valuation.any_priced);
}

#[tokio::test]
async fn valuation_corum_life_without_holdings_keeps_the_contract_cost() {
    let f = Fixture::new().await;
    let account = f
        .account(AccountType::AssuranceVie, "1534.37", corum_life)
        .await;
    let account = f.with_provider_invested(&account, "1580").await;

    let valuation = f.valuation(&StubPrices::new(), &account).await;

    assert_eq!(valuation.live_eur, d("1534.37"));
    assert_eq!(valuation.invested_eur, d("1580"));
}

#[tokio::test]
async fn valuation_corum_life_without_holdings_ignores_an_edited_currency() {
    let f = Fixture::new().await;
    let account = f
        .account(AccountType::AssuranceVie, "1534.37", |a| {
            corum_life(a);
            a.currency = "USD".to_owned();
        })
        .await;
    let account = f.with_provider_invested(&account, "1580").await;
    // A rate that would show if the balance were converted.
    let prices = StubPrices::new().fx("USD", "0.5");

    let valuation = f.valuation(&prices, &account).await;

    assert_eq!(valuation.live_eur, d("1534.37"));
    assert_eq!(valuation.invested_eur, d("1580"));
}

#[tokio::test]
async fn valuation_non_manual_corum_without_the_life_external_id_is_not_provider_valued() {
    let f = Fixture::new().await;
    let account = f
        .account(AccountType::AssuranceVie, "5000", |a| {
            a.provider = Some("CORUM".to_owned());
            a.is_manual = false;
            a.external_account_id = Some("other_1".to_owned());
            a.cash_balance = Some(Decimal::ZERO);
        })
        .await;
    let account = f.with_provider_invested(&account, "1").await;
    f.hold(
        &account,
        HoldingDraft {
            average_buy_in: Some(d("400")),
            ..line("CW8.PA", "10")
        },
    )
    .await;
    let prices = StubPrices::new().equity("CW8.PA", "500");

    let valuation = f.valuation(&prices, &account).await;

    assert_eq!(valuation.live_eur, d("5000"));
    assert_eq!(valuation.invested_eur, d("4000"));
}

#[tokio::test]
async fn valuation_manual_account_with_a_provider_cost_ignores_it() {
    let f = Fixture::new().await;
    let account = f
        .account(AccountType::AssuranceVie, "5000", |a| {
            a.provider = Some("CORUM".to_owned());
            a.cash_balance = Some(Decimal::ZERO);
        })
        .await;
    let account = f.with_provider_invested(&account, "1").await;
    f.hold(
        &account,
        HoldingDraft {
            average_buy_in: Some(d("400")),
            ..line("CW8.PA", "10")
        },
    )
    .await;
    let prices = StubPrices::new().equity("CW8.PA", "500");

    let valuation = f.valuation(&prices, &account).await;

    assert_eq!(valuation.live_eur, d("5000"));
    assert_eq!(valuation.invested_eur, d("4000"));
}

// ─── insurer contracts (Caisse d'Epargne, Banque Populaire) ─────────────────

fn uc(ticker: &str, quantity: &str, price: &str, value: &str) -> HoldingDraft {
    HoldingDraft {
        current_price: Some(d(price)),
        quote_currency: Some("USD".to_owned()),
        provider_value_eur: Some(d(value)),
        ..line(ticker, quantity)
    }
}

#[tokio::test]
async fn caisse_epargne_assurance_vie_is_valued_from_insurer_figures_like_a_per_and_a_manual_one_is_not()
 {
    let f = Fixture::new().await;
    let synced = f
        .account(AccountType::AssuranceVie, "1500", insurer("1000"))
        .await;
    f.hold(&synced, uc("UC_A", "10", "50", "500")).await;
    let prices = StubPrices::new().equity("UC_A", "60");

    let valuation = f.valuation(&prices, &synced).await;

    // 1000 euro fund + 10 units at the live 60, repriced between syncs.
    assert_eq!(valuation.live_eur, d("1600"));
    // The insurer baseline is the live value itself.
    assert_eq!(valuation.invested_eur, d("1600"));

    let manual = f
        .account(AccountType::AssuranceVie, "1500", |a| {
            insurer("1000")(a);
            a.is_manual = true;
        })
        .await;
    f.hold(&manual, uc("UC_A", "10", "50", "500")).await;

    let valuation = f.valuation(&prices, &manual).await;

    assert_eq!(valuation.live_eur, d("1600"));
    // Not an insurer contract: the neutral baseline is the stored balance.
    assert_eq!(valuation.invested_eur, d("1500"));
}

#[tokio::test]
async fn caisse_epargne_per_uses_live_quotes_and_keeps_unresolved_bank_valuations_stale() {
    let f = Fixture::new().await;
    let account = f.account(AccountType::Per, "1700", insurer("1000")).await;
    f.hold(&account, uc("UC_A", "10", "50", "500")).await;
    f.hold(
        &account,
        HoldingDraft {
            provider_price_date: Some(day("2026-10-06")),
            ..uc("UC_B", "2", "999", "200")
        },
    )
    .await;

    let at_old_price = f
        .valuation(&StubPrices::new().equity("UC_A", "50"), &account)
        .await;
    let at_new_price = f
        .valuation(&StubPrices::new().equity("UC_A", "55"), &account)
        .await;

    assert_eq!(at_old_price.live_eur, d("1700"));
    assert_eq!(at_new_price.live_eur, d("1750"));
    assert_eq!(at_old_price.invested_eur, d("1700"));
    assert_eq!(at_new_price.invested_eur, d("1750"));
    assert!(at_new_price.any_priced);
    assert!(at_new_price.any_stale);
    let lines = holdings::find_by_account(&f.app.state.db, account.id)
        .await
        .unwrap();
    assert_eq!(lines[0].quantity, d("10"));
    assert_eq!(lines[1].quantity, d("2"));
}

#[tokio::test]
async fn banque_populaire_per_uses_live_quotes_and_keeps_unresolved_bank_valuations_stale() {
    let f = Fixture::new().await;
    let account = f
        .account(AccountType::Per, "1700", |a| {
            a.provider = Some("Banque Populaire Rives de Paris".to_owned());
            a.external_account_id = Some("bp_contract:BP1".to_owned());
            a.is_manual = false;
            a.cash_balance = Some(d("1000"));
        })
        .await;
    f.hold(&account, uc("UC_A", "10", "50", "500")).await;
    f.hold(
        &account,
        HoldingDraft {
            provider_price_date: Some(day("2026-10-06")),
            ..uc("UC_B", "2", "999", "200")
        },
    )
    .await;
    let prices = StubPrices::new().equity("UC_A", "55");

    let valuation = f.valuation(&prices, &account).await;

    assert_eq!(valuation.live_eur, d("1750"));
    assert_eq!(valuation.invested_eur, d("1750"));
    assert!(valuation.any_priced);
    assert!(valuation.any_stale);
}

#[tokio::test]
async fn caisse_epargne_per_ignores_nonpositive_quotes_and_bank_values() {
    let f = Fixture::new().await;
    let account = f.account(AccountType::Per, "1700", insurer("1000")).await;
    f.hold(
        &account,
        HoldingDraft {
            provider_value_eur: Some(d("500")),
            ..line("UC_A", "10")
        },
    )
    .await;
    f.hold(
        &account,
        HoldingDraft {
            provider_value_eur: Some(Decimal::ZERO),
            ..line("UC_B", "2")
        },
    )
    .await;
    let prices = StubPrices::new().equity("UC_A", "0").equity("UC_B", "-1");

    let valuation = f.valuation(&prices, &account).await;

    assert_eq!(valuation.live_eur, d("1500"));
    assert!(valuation.any_priced);
    assert!(valuation.any_stale);
}

#[tokio::test]
async fn caisse_epargne_per_live_quote_stays_fresh_when_bank_fallback_is_invalid() {
    let f = Fixture::new().await;
    let account = f.account(AccountType::Per, "500", insurer("0")).await;
    f.hold(
        &account,
        HoldingDraft {
            provider_value_eur: Some(Decimal::ZERO),
            ..line("UC_A", "10")
        },
    )
    .await;
    let prices = StubPrices::new().equity("UC_A", "50");

    let valuation = f.valuation(&prices, &account).await;

    assert_eq!(valuation.live_eur, d("500"));
    assert!(valuation.any_priced);
    assert!(!valuation.any_stale);
}

#[tokio::test]
async fn caisse_epargne_per_all_missing_quotes_keeps_the_complete_bank_total_as_stale_and_priced() {
    let f = Fixture::new().await;
    let account = f.account(AccountType::Per, "1700", insurer("1000")).await;
    f.hold(
        &account,
        HoldingDraft {
            provider_value_eur: Some(d("500")),
            ..line("UC_A", "10")
        },
    )
    .await;
    f.hold(
        &account,
        HoldingDraft {
            provider_value_eur: Some(d("200")),
            ..line("UC_B", "2")
        },
    )
    .await;

    let valuation = f.valuation(&StubPrices::new(), &account).await;

    assert_eq!(valuation.live_eur, d("1700"));
    assert!(valuation.any_priced);
    assert!(!valuation.all_priced);
    assert!(valuation.any_stale);
}

// ─── cost basis ─────────────────────────────────────────────────────────────

#[tokio::test]
async fn calculate_invested_amount_includes_cash_and_prefers_broker_eur_cost_basis() {
    let f = Fixture::new().await;
    let account = f
        .account(AccountType::CompteTitres, "1250", |a| {
            a.cash_balance = Some(d("250"))
        })
        .await;
    // Blank ticker: the column is NOT NULL, and Java treats blank as no ticker.
    f.hold(
        &account,
        HoldingDraft {
            average_buy_in: Some(d("90")),
            provider_value_eur: Some(d("1000")),
            provider_pnl_eur: Some(d("200")),
            ..line("", "10")
        },
    )
    .await;

    let mut conn = f.app.state.db.acquire().await.unwrap();
    let invested = valuation::invested_amount(&mut conn, &StubPrices::new(), &account, today())
        .await
        .unwrap();

    assert_eq!(invested, d("1050"));
}

#[tokio::test]
async fn an_unpriced_holding_leaves_the_cost_basis_too_not_just_the_value() {
    let f = Fixture::new().await;
    let account = f.account(AccountType::Crypto, "448.24", eur()).await;
    f.hold(
        &account,
        HoldingDraft {
            average_buy_in: Some(d("1.06")),
            ..line("ATOM", "33.15")
        },
    )
    .await;
    f.hold(
        &account,
        HoldingDraft {
            average_buy_in: Some(d("54570")),
            ..line("BTC", "0.00487")
        },
    )
    .await;
    let prices = StubPrices::new().crypto("ATOM", "1.06");

    let valuation = f.valuation(&prices, &account).await;

    assert_eq!(valuation.live_eur, d("35.139"));
    assert_eq!(valuation.invested_eur, d("35.139"));
    assert!(!valuation.all_priced);
}

#[tokio::test]
async fn an_imported_token_sharing_a_stock_symbol_keeps_its_provider_value_not_the_share_price() {
    let f = Fixture::new().await;
    let account = f.account(AccountType::Crypto, "10", eur()).await;
    f.hold(
        &account,
        HoldingDraft {
            average_buy_in: Some(Decimal::ONE),
            provider_value_eur: Some(Decimal::TEN),
            ..line("REG", "10")
        },
    )
    .await;
    // Regency Centers on the equity route; no crypto source maps the token.
    let prices = StubPrices::new().equity("REG", "60");

    let valuation = f.valuation(&prices, &account).await;

    assert_eq!(valuation.live_eur, d("10"));
}

#[tokio::test]
async fn bourse_direct_broker_total_is_paired_with_the_cost_of_every_position() {
    let f = Fixture::new().await;
    let account = f
        .account(AccountType::Pea, "5000", bourse_direct(None))
        .await;
    f.hold(
        &account,
        HoldingDraft {
            average_buy_in: Some(d("90")),
            ..line("ACME", "10")
        },
    )
    .await;
    f.hold(
        &account,
        HoldingDraft {
            average_buy_in: Some(d("400")),
            ..line("PHYMF", "10")
        },
    )
    .await;
    let prices = StubPrices::new().equity("ACME", "100");

    let valuation = f.valuation(&prices, &account).await;

    assert_eq!(valuation.live_eur, d("5000"));
    assert_eq!(valuation.invested_eur, d("4900"));
}

#[tokio::test]
async fn idle_cash_on_a_priced_account_is_on_both_sides_so_it_does_not_show_up_as_gain() {
    let f = Fixture::new().await;
    let account = f
        .account(AccountType::Pea, "1250", bourse_direct(Some("250")))
        .await;
    f.hold(
        &account,
        HoldingDraft {
            average_buy_in: Some(d("90")),
            ..line("ACME", "10")
        },
    )
    .await;
    let prices = StubPrices::new().equity("ACME", "100");

    let valuation = f.valuation(&prices, &account).await;

    assert_eq!(valuation.live_eur, d("1250"));
    assert_eq!(valuation.invested_eur, d("1150"));
    assert_eq!(valuation.live_eur - valuation.invested_eur, d("100"));
}

#[tokio::test]
async fn idle_cash_folded_into_the_broker_total_is_on_both_sides_too() {
    let f = Fixture::new().await;
    let account = f
        .account(AccountType::Pea, "1250", bourse_direct(None))
        .await;
    f.hold(
        &account,
        HoldingDraft {
            provider_value_eur: Some(d("1000")),
            provider_pnl_eur: Some(d("100")),
            ..line("PHYMF", "10")
        },
    )
    .await;

    let valuation = f.valuation(&StubPrices::new(), &account).await;

    assert_eq!(valuation.live_eur, d("1250"));
    assert_eq!(valuation.invested_eur, d("1150"));
    assert_eq!(valuation.live_eur - valuation.invested_eur, d("100"));
}

#[tokio::test]
async fn an_account_where_nothing_can_be_priced_reports_it_rather_than_returning_zero() {
    let f = Fixture::new().await;
    let account = f.account(AccountType::Crypto, "448.24", eur()).await;
    f.hold(
        &account,
        HoldingDraft {
            average_buy_in: Some(d("54570")),
            ..line("BTC", "0.00487")
        },
    )
    .await;

    let valuation = f.valuation(&StubPrices::new(), &account).await;

    assert!(!valuation.any_priced);
    assert_eq!(valuation.live_eur, d("0"));
}

#[tokio::test]
async fn a_holding_the_provider_valued_itself_counts_as_priced() {
    let f = Fixture::new().await;
    let account = f.account(AccountType::CompteTitres, "0", eur()).await;
    f.hold(
        &account,
        HoldingDraft {
            provider_value_eur: Some(d("500")),
            provider_pnl_eur: Some(d("100")),
            ..line("", "3")
        },
    )
    .await;
    let prices = StubPrices::new();

    let valuation = f.valuation(&prices, &account).await;

    assert!(valuation.any_priced);
    assert!(valuation.all_priced);
    assert_eq!(valuation.live_eur, d("500"));
    assert_eq!(valuation.invested_eur, d("400"));
    // Nothing to look up, so no provider call.
    assert!(prices.calls().is_empty());
}

#[tokio::test]
async fn a_holding_with_no_ticker_and_no_provider_value_leaves_both_sides_alone() {
    let f = Fixture::new().await;
    let account = f.account(AccountType::CompteTitres, "0", eur()).await;
    f.hold(
        &account,
        HoldingDraft {
            average_buy_in: Some(d("120")),
            ..line("", "3")
        },
    )
    .await;

    let valuation = f.valuation(&StubPrices::new(), &account).await;

    assert!(!valuation.any_priced);
    assert!(!valuation.all_priced);
    assert_eq!(valuation.live_eur, d("0"));
    assert_eq!(valuation.invested_eur, d("0"));
}

#[tokio::test]
async fn a_recorded_price_still_values_the_account_and_is_reported_as_stale() {
    let f = Fixture::new().await;
    let account = f.account(AccountType::CompteTitres, "0", eur()).await;
    f.hold(
        &account,
        HoldingDraft {
            average_buy_in: Some(d("150")),
            ..line("AAPL", "5")
        },
    )
    .await;
    let prices = StubPrices::new().quote(
        "AAPL",
        Quote {
            price: d("200"),
            as_of: day("2026-03-14"),
            live: false,
        },
    );

    let valuation = f.valuation(&prices, &account).await;

    assert_eq!(valuation.live_eur, d("1000"));
    assert!(valuation.all_priced);
    assert!(valuation.any_stale);
}

#[tokio::test]
async fn crypto_accounts_are_never_priced_through_the_stock_route() {
    let f = Fixture::new().await;
    let account = f.account(AccountType::Crypto, "0", eur()).await;
    f.hold(&account, line("ATOM", "10")).await;
    let prices = StubPrices::new().equity("ATOM", "7");

    let value = f.live_balance(&prices, &account).await;

    assert_eq!(prices.calls(), vec![true]);
    assert_eq!(value, d("0"));
}

// ─── snapshots ──────────────────────────────────────────────────────────────

fn ctx(prices: &dyn PriceRead) -> SnapshotContext<'_> {
    SnapshotContext {
        prices,
        today: today(),
        now: now(),
    }
}

#[tokio::test]
async fn upsert_snapshot_from_native_refreshes_native_on_a_manual_row() {
    let f = Fixture::new().await;
    let account = f
        .account(AccountType::Checking, "1200", |a| {
            a.currency = "USD".to_owned()
        })
        .await;
    let earlier = now() - chrono::Duration::hours(3);
    snapshots::insert(
        &f.app.state.db,
        &NewSnapshot {
            source: SnapshotSource::Manual,
            native_balance: Some(d("1200")),
            statement_entered_at: Some(earlier),
            ..NewSnapshot::derived(account.id, today(), d("1080"), d("1080"))
        },
        earlier,
    )
    .await
    .unwrap();
    let prices = StubPrices::new().fx("USD", "0.9");

    let mut conn = f.app.state.db.acquire().await.unwrap();
    let result = valuation::upsert_snapshot_from_native(
        &mut conn,
        &ctx(&prices),
        &account,
        d("1300"),
        today(),
    )
    .await
    .unwrap();

    assert_eq!(result.balance, d("1170"));
    assert_eq!(result.source, SnapshotSource::Manual);
    assert_eq!(result.native_balance, Some(d("1300")));
    assert_eq!(result.statement_entered_at, Some(now()));
    // The cash account's cost basis is its stored balance converted: 1200 x 0.9.
    assert_eq!(result.invested_amount, d("1080"));
}

#[tokio::test]
async fn upsert_snapshot_from_native_leaves_a_derived_row_without_a_native_figure() {
    let f = Fixture::new().await;
    let account = f
        .account(AccountType::Checking, "1200", |a| {
            a.currency = "USD".to_owned()
        })
        .await;
    let prices = StubPrices::new().fx("USD", "0.9");

    let mut conn = f.app.state.db.acquire().await.unwrap();
    let result = valuation::upsert_snapshot_from_native(
        &mut conn,
        &ctx(&prices),
        &account,
        d("1300"),
        today(),
    )
    .await
    .unwrap();

    assert_eq!(result.balance, d("1170"));
    assert_eq!(result.source, SnapshotSource::Derived);
    assert_eq!(result.native_balance, None);
    assert_eq!(result.statement_entered_at, None);
}

#[tokio::test]
async fn upsert_snapshot_updates_the_existing_days_row_in_place_keeping_its_source() {
    let f = Fixture::new().await;
    let account = f.account(AccountType::CompteTitres, "0", eur()).await;
    f.hold(
        &account,
        HoldingDraft {
            average_buy_in: Some(d("80")),
            ..line("ACME", "10")
        },
    )
    .await;
    let existing = snapshots::insert(
        &f.app.state.db,
        &NewSnapshot {
            source: SnapshotSource::Manual,
            native_balance: Some(d("500")),
            ..NewSnapshot::derived(account.id, today(), d("500"), d("500"))
        },
        now(),
    )
    .await
    .unwrap();
    let prices = StubPrices::new().equity("ACME", "100");

    let mut conn = f.app.state.db.acquire().await.unwrap();
    let result = valuation::upsert_snapshot(&mut conn, &ctx(&prices), &account, d("1000"), today())
        .await
        .unwrap();

    assert_eq!(result.id, existing.id);
    assert_eq!(result.source, SnapshotSource::Manual);
    assert_eq!(result.native_balance, Some(d("500")));
    assert_eq!(result.balance, d("1000"));
    assert_eq!(result.invested_amount, d("800"));
    let rows = snapshots::find_by_account(&f.app.state.db, account.id)
        .await
        .unwrap();
    assert_eq!(rows.len(), 1);
}

#[tokio::test]
async fn upsert_snapshot_creates_a_derived_row_with_the_valuations_invested_amount() {
    let f = Fixture::new().await;
    let account = f
        .account(AccountType::Pea, "1250", bourse_direct(Some("250")))
        .await;
    f.hold(
        &account,
        HoldingDraft {
            average_buy_in: Some(d("90")),
            ..line("ACME", "10")
        },
    )
    .await;
    let prices = StubPrices::new().equity("ACME", "100");
    let date = day("2026-03-14");

    let mut conn = f.app.state.db.acquire().await.unwrap();
    let result = valuation::upsert_snapshot(&mut conn, &ctx(&prices), &account, d("1250"), date)
        .await
        .unwrap();

    assert_eq!(result.date, date);
    assert_eq!(result.source, SnapshotSource::Derived);
    assert_eq!(result.balance, d("1250"));
    assert_eq!(result.invested_amount, d("1150"));
    assert_eq!(result.native_balance, None);
    assert_eq!(result.created_at, now());
}

// ─── holdings ───────────────────────────────────────────────────────────────

#[tokio::test]
async fn upsert_holding_first_sync_sets_the_cost_basis_to_the_price_and_a_second_sync_keeps_it() {
    let f = Fixture::new().await;
    let account = f.account(AccountType::Crypto, "0", eur()).await;
    let mut conn = f.app.state.db.acquire().await.unwrap();

    let first = holdings::upsert_holding(
        &mut conn,
        account.id,
        f.member_id,
        "BTC",
        Some("Bitcoin"),
        d("0.5"),
        Some(d("60000")),
        now(),
    )
    .await
    .unwrap();

    assert_eq!(first.average_buy_in, Some(d("60000")));
    assert_eq!(first.current_price, Some(d("60000")));
    assert_eq!(first.quantity, d("0.5"));
    assert_eq!(first.name.as_deref(), Some("Bitcoin"));
    assert_eq!(first.last_synced_at, Some(now()));

    let later = now() + chrono::Duration::days(1);
    let second = holdings::upsert_holding(
        &mut conn,
        account.id,
        f.member_id,
        "BTC",
        Some("Renamed"),
        d("0.75"),
        Some(d("70000")),
        later,
    )
    .await
    .unwrap();

    assert_eq!(second.id, first.id);
    assert_eq!(second.average_buy_in, Some(d("60000")));
    assert_eq!(second.current_price, Some(d("70000")));
    assert_eq!(second.quantity, d("0.75"));
    assert_eq!(second.name.as_deref(), Some("Bitcoin"));
    assert_eq!(second.last_synced_at, Some(later));
}

#[tokio::test]
async fn upsert_holding_on_another_members_account_is_not_found() {
    let f = Fixture::new().await;
    let account = f.account(AccountType::Crypto, "0", eur()).await;
    let stranger = member(&f.app, "bob").await;
    let mut conn = f.app.state.db.acquire().await.unwrap();

    let result = holdings::upsert_holding(
        &mut conn,
        account.id,
        stranger,
        "BTC",
        None,
        d("1"),
        Some(d("1")),
        now(),
    )
    .await;

    assert!(matches!(result, Err(AppError::NotFound(_))));
    assert!(
        holdings::find_by_account(&mut *conn, account.id)
            .await
            .unwrap()
            .is_empty()
    );
}

async fn tickers_held(f: &Fixture, account: &Account) -> Vec<String> {
    holdings::find_by_account(&f.app.state.db, account.id)
        .await
        .unwrap()
        .into_iter()
        .map(|h| h.ticker)
        .collect()
}

#[tokio::test]
async fn prune_holdings_deletes_only_tickers_not_kept() {
    let f = Fixture::new().await;
    let account = f.account(AccountType::Crypto, "0", eur()).await;
    for ticker in ["BTC", "ETH", "SOL"] {
        f.hold(&account, line(ticker, "1")).await;
    }

    let deleted = holdings::prune_holdings(
        &f.app.state.db,
        account.id,
        &["BTC".to_owned(), "ETH".to_owned()],
    )
    .await
    .unwrap();

    assert_eq!(deleted, 1);
    assert_eq!(tickers_held(&f, &account).await, ["BTC", "ETH"]);
}

#[tokio::test]
async fn prune_holdings_empty_keep_set_clears_all_holdings() {
    let f = Fixture::new().await;
    let account = f.account(AccountType::Crypto, "0", eur()).await;
    let other = f.account(AccountType::Crypto, "0", eur()).await;
    for ticker in ["BTC", "ETH"] {
        f.hold(&account, line(ticker, "1")).await;
    }
    f.hold(&other, line("BTC", "1")).await;

    holdings::prune_holdings(&f.app.state.db, account.id, &[])
        .await
        .unwrap();

    assert!(tickers_held(&f, &account).await.is_empty());
    assert_eq!(tickers_held(&f, &other).await, ["BTC"]);
}

//! Prices, FX, metals and instrument data providers (track T4).

pub mod boursorama;
pub mod cerema;
pub mod coingecko;
pub mod cooldown;
pub mod geocoding;
pub mod insee;
pub mod justetf;
pub mod metals;
pub mod nasdaq;
pub mod num;
pub mod openfigi;
pub mod prices;
pub mod runners;
pub mod yahoo;

use std::collections::BTreeSet;
use std::sync::Arc;

use axum::extract::State;
use chrono::Months;
use rust_decimal::Decimal;
use serde_json::{Map, Value, json};
use sqlx::PgPool;

use self::boursorama::Boursorama;
use self::cerema::CeremaDv3f;
use self::coingecko::CoinGecko;
use self::cooldown::YahooCooldown;
use self::insee::InseeBdm;
use self::justetf::JustEtf;
use self::metals::PreciousMetals;
use self::openfigi::OpenFigi;
use self::prices::PriceService;
use self::yahoo::YahooFinance;
use crate::AppState;
use crate::auth::Auth;
use crate::clock::AppClock;
use crate::config::Config;
use crate::error::AppResult;
use crate::http::json::Path;
use crate::ledger::prices::{PriceRead, price_history};
use crate::routes::Routes;
use crate::wealth::params::{self, Query};

pub fn register(routes: &mut Routes) {
    routes
        .get("/api/prices", get_prices)
        .get("/api/prices/precious-metals", precious_metals)
        .get("/api/prices/{ticker}/history", price_history_of)
        .get("/api/prices/{ticker}/intraday", intraday);
}

/// The market domain's process state (provider clients, caches, cooldowns), held by AppState as
/// state.market.
pub struct Market {
    prices: Arc<PriceService>,
    openfigi: Arc<OpenFigi>,
    boursorama: Arc<Boursorama>,
    justetf: Arc<JustEtf>,
    cerema: Arc<CeremaDv3f>,
    insee: Arc<InseeBdm>,
    cooldown: Arc<YahooCooldown>,
    yahoo: Arc<YahooFinance>,
    coingecko: Arc<CoinGecko>,
}

impl Market {
    pub fn new(config: &Config, db: &PgPool, http: &reqwest::Client, clock: &AppClock) -> Self {
        let cooldown = Arc::new(YahooCooldown::new(clock.clone()));
        let yahoo = Arc::new(YahooFinance::new(
            http.clone(),
            &config.yahoo_finance_base_url,
            cooldown.clone(),
            clock.clone(),
        ));
        let coingecko = Arc::new(
            CoinGecko::new(
                http.clone(),
                &config.coingecko_base_url,
                db.clone(),
                clock.clone(),
                Arc::new(YahooCooldown::new(clock.clone())),
            )
            .with_api_key(config.coingecko_api_key.clone()),
        );
        let openfigi = Arc::new(OpenFigi::new(
            http.clone(),
            &config.openfigi_base_url,
            yahoo.clone(),
            coingecko.clone(),
        ));
        let boursorama = if config.justetf_base_url.contains("justetf.com") {
            Arc::new(Boursorama::new(http.clone()))
        } else {
            // In offline wiremock tests (where justetf_base_url is overridden to loopback),
            // avoid reaching out to live www.boursorama.com.
            Arc::new(Boursorama::with_base_url(http.clone(), ""))
        };
        let justetf = Arc::new(JustEtf::new(http.clone(), &config.justetf_base_url));
        let cerema = Arc::new(CeremaDv3f::new(http.clone(), &config.cerema_api_url));
        let insee = Arc::new(InseeBdm::new(
            http.clone(),
            &config.insee_api_url,
            clock.clone(),
        ));
        Self {
            prices: Arc::new(PriceService::new(
                db.clone(),
                clock.clone(),
                yahoo.clone(),
                coingecko.clone(),
                Arc::new(PreciousMetals::new(
                    http.clone(),
                    &config.precious_metals_base_url,
                    yahoo.clone(),
                    clock.clone(),
                )),
            )),
            openfigi,
            boursorama,
            justetf,
            cerema,
            insee,
            cooldown,
            yahoo,
            coingecko,
        }
    }

    /// The Yahoo Finance client: fetch_dividend_history(ticker) -> Result<DividendHistory,
    /// YahooError>, fx_rate_to_eur(Some(currency)) -> Option<Decimal>, historical_prices_eur,
    /// intraday_prices_eur, probe_quote, search_symbols, equity_sector, instrument_type.
    pub fn yahoo(&self) -> &Arc<YahooFinance> {
        &self.yahoo
    }

    /// CoinGecko client: prices_eur, historical_prices_eur, intraday_prices_eur, logo_urls,
    /// resolve_by_symbol, learn.
    pub fn coingecko(&self) -> &Arc<CoinGecko> {
        &self.coingecko
    }

    /// YahooCooldown: active(), until(), arm(retry_after).
    pub fn cooldown(&self) -> &Arc<YahooCooldown> {
        &self.cooldown
    }

    /// The price service: refresh_prices, backfill_historical_prices, intraday_prices_eur.
    pub fn prices(&self) -> &Arc<PriceService> {
        &self.prices
    }

    /// OpenFIGI ISIN to ticker: resolve(isin), resolve_isin_or_symbol.
    pub fn openfigi(&self) -> &Arc<OpenFigi> {
        &self.openfigi
    }

    /// Boursorama public ETF composition provider (first in line, up to 10 slices per axis).
    pub fn boursorama(&self) -> &Arc<Boursorama> {
        &self.boursorama
    }

    /// justETF ETF composition & facts by ISIN.
    pub fn justetf(&self) -> &Arc<JustEtf> {
        &self.justetf
    }

    /// Cerema DV3F open-data property valuation provider.
    pub fn cerema(&self) -> &Arc<CeremaDv3f> {
        &self.cerema
    }

    /// INSEE BDM housing price index provider.
    pub fn insee(&self) -> &Arc<InseeBdm> {
        &self.insee
    }

    /// The price service the ledger's valuation reads through.
    pub fn price_read(&self) -> Arc<dyn PriceRead> {
        self.prices.clone()
    }
}

/// A BigDecimal as Jackson writes it: a JSON number with its scale.
fn number(value: Decimal) -> Value {
    serde_json::from_str(&value.to_string()).unwrap_or(Value::Null)
}

/// Live EUR prices of the comma-separated tickers, each fetched one recorded as today's price.
async fn get_prices(
    State(state): State<AppState>,
    _auth: Auth,
    query: Query,
) -> AppResult<axum::Json<Map<String, Value>>> {
    let tickers: BTreeSet<String> = params::required(&query, "tickers")?
        .split(',')
        .map(str::trim)
        .filter(|t| !t.is_empty())
        .map(str::to_owned)
        .collect();
    let prices = state.market.prices.refresh_prices(&tickers).await;
    Ok(axum::Json(
        prices.into_iter().map(|(k, v)| (k, number(v))).collect(),
    ))
}

async fn precious_metals(_auth: Auth) -> axum::Json<Vec<metals::ProductResponse>> {
    axum::Json(metals::products())
}

/// The recorded daily prices of one ticker over the last months.
async fn price_history_of(
    State(state): State<AppState>,
    _auth: Auth,
    Path(ticker): Path<String>,
    query: Query,
) -> AppResult<axum::Json<Vec<Value>>> {
    let months: i32 = params::or_default(&query, "months", 12)?;
    let to = state.clock.today();
    let from = if months >= 0 {
        to.checked_sub_months(Months::new(months.unsigned_abs()))
    } else {
        to.checked_add_months(Months::new(months.unsigned_abs()))
    }
    .unwrap_or(to);
    let rows = price_history(&state.db, &[ticker.to_uppercase()], from, to).await?;
    Ok(axum::Json(
        rows.into_iter()
            .map(|r| json!({"date": r.date.to_string(), "priceEur": number(r.price_eur)}))
            .collect(),
    ))
}

/// The provider's hourly prices of one ticker over the last 24 hours.
async fn intraday(
    State(state): State<AppState>,
    _auth: Auth,
    Path(ticker): Path<String>,
) -> axum::Json<Vec<Value>> {
    let to = state.clock.local_now();
    let from = to - chrono::Duration::hours(24);
    let series = state
        .market
        .prices
        .intraday_prices_eur(&ticker, from, to)
        .await;
    axum::Json(
        series
            .into_iter()
            .map(|(at, price)| json!({"timestamp": num::java_local_date_time(at), "priceEur": number(price)}))
            .collect(),
    )
}

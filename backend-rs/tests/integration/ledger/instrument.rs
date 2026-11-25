use picsou::ledger::instrument::{TickerResult, is_isin, resolve};

async fn never_called(isin: &str) -> TickerResult {
    panic!("OpenFIGI must not be called for {isin}")
}

fn figi(ticker: &'static str, name: &'static str) -> impl AsyncFnOnce(&str) -> TickerResult {
    async move |isin: &str| {
        assert_eq!(isin, "IE00B4L5Y983");
        TickerResult {
            ticker: ticker.to_owned(),
            name: Some(name.to_owned()),
        }
    }
}

#[tokio::test]
async fn blank_input_returns_null() {
    assert_eq!(resolve(None, None, never_called).await, None);
    assert_eq!(resolve(Some("   "), Some("X"), never_called).await, None);
}

#[tokio::test]
async fn isin_resolves_ticker_name_and_description() {
    let r = resolve(
        Some("IE00B4L5Y983"),
        None,
        figi("IWDA.AS", "iShares Core MSCI World UCITS ETF"),
    )
    .await
    .unwrap();
    assert_eq!(r.ticker, "IWDA.AS");
    assert_eq!(r.name.as_deref(), Some("iShares Core MSCI World UCITS ETF"));
    // The raw ISIN never surfaces in the description.
    assert_eq!(r.description, "iShares Core MSCI World UCITS ETF");
}

#[tokio::test]
async fn plain_ticker_uppercased_no_resolve_and_neutral_description() {
    let r = resolve(Some("iwda.as"), None, never_called).await.unwrap();
    assert_eq!(r.ticker, "IWDA.AS");
    assert_eq!(r.name, None);
    assert_eq!(r.description, "IWDA.AS");
}

#[tokio::test]
async fn user_name_wins_over_resolved_name() {
    let r = resolve(
        Some("IE00B4L5Y983"),
        Some(" My World ETF "),
        figi("IWDA.AS", "resolved name"),
    )
    .await
    .unwrap();
    assert_eq!(r.ticker, "IWDA.AS");
    assert_eq!(r.name.as_deref(), Some("My World ETF"));
    assert_eq!(r.description, "My World ETF");
}

#[test]
fn is_isin_is_case_insensitive_and_ignores_surrounding_whitespace() {
    assert!(is_isin(" ie00b4l5y983 "));
    assert!(!is_isin("IE00B4L5Y98"));
    assert!(!is_isin("1E00B4L5Y983"));
    assert!(!is_isin("IE00B4L5Y98."));
}

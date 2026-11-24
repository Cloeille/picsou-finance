//! The write side of InstrumentLogoService, with YahooQuotePageLogoProvider: the marks of shares
//! and funds, downloaded from Yahoo Finance's quote page and stored, so Picsou serves them itself
//! (accounts::instrument_logo is the read side).
//!
//! Two requests per ticker on a hit: the quote page (around 1 MB of HTML), then the 50px rendition
//! of the mark the page links to on s.yimg.com (a few KB), plus the dark-background variant when
//! it is a different file. A ticker is asked once: its answer, a mark or the lack of one, is
//! stored, so the page weight is paid once per installation.
//!
//! Every answer the page gives is untrusted: the logo URL must be https on s.yimg.com
//! (quote_page::image_url), and the downloaded bytes must be a PNG, JPEG or WebP by their own
//! signature, whatever the header says, and under MAX_IMAGE_BYTES. SVG is refused outright: it is
//! a document that can carry script, and these bytes are served from Picsou's own origin.

use std::collections::BTreeSet;
use std::sync::Arc;
use std::time::Duration;

use percent_encoding::utf8_percent_encode;
use reqwest::StatusCode;
use reqwest::header::{ACCEPT, CONTENT_TYPE, RETRY_AFTER, USER_AGENT};
use url::{Position, Url};

use crate::clock::AppClock;
use crate::db::{self, Db};
use crate::ledger::holdings;
use crate::ledger::model::AccountType;

use super::coingecko::CoinGecko;
use super::cooldown::YahooCooldown;
use super::quote_page::{self, QuotePage};
use super::yahoo::{PATH_SEGMENT, YahooFinance, transport_message};

/// Lookups per pass: the price path shares Yahoo's rate budget.
pub const BATCH: usize = 10;
pub const MAX_IMAGE_BYTES: usize = 256 * 1024;
/// The page is about 1 MB today; this is a memory bound, not an expectation.
pub const MAX_PAGE_BYTES: usize = 4 * 1024 * 1024;
const RETRY_FAILED_AFTER: chrono::Duration = chrono::Duration::days(7);
const PACING: Duration = Duration::from_millis(500);
const PAGE_TIMEOUT: Duration = Duration::from_secs(10);
const IMAGE_TIMEOUT: Duration = Duration::from_secs(5);

/// A downloaded mark, typed by its own signature.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Image {
    pub bytes: Vec<u8>,
    pub content_type: &'static str,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Mark {
    pub light: Image,
    pub dark: Option<Image>,
}

/// Why a lookup brought no mark back.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum Miss {
    /// Yahoo has no mark for the ticker, or one Picsou will not store. Asking again gets the
    /// same answer, so the ticker is settled.
    Absent(&'static str),
    /// Yahoo did not answer usably, which says nothing about the mark: asked again later.
    Unavailable(String),
    /// A 429, or a pause still in force: the ticker was not really asked.
    RateLimited,
}

/// The instance's logo resolver (state.market.instrument_logos()).
pub struct InstrumentLogos {
    db: Db,
    clock: AppClock,
    http: reqwest::Client,
    page_base_url: String,
    image_base_url: String,
    cooldown: Arc<YahooCooldown>,
    coingecko: Arc<CoinGecko>,
}

impl InstrumentLogos {
    /// `http` must not follow redirects, so a page can never steer a request off the hosts asked
    /// for.
    pub fn new(
        db: Db,
        clock: AppClock,
        http: reqwest::Client,
        page_base_url: &str,
        image_base_url: &str,
        cooldown: Arc<YahooCooldown>,
        coingecko: Arc<CoinGecko>,
    ) -> Self {
        Self {
            db,
            clock,
            http,
            page_base_url: page_base_url.trim_end_matches('/').to_owned(),
            image_base_url: image_base_url.trim_end_matches('/').to_owned(),
            cooldown,
            coingecko,
        }
    }

    /// One capped pass over the tickers that are due. Returns how many marks were stored.
    ///
    /// Stops at the first sign that Yahoo is not answering: a 429 or a cooldown records nothing
    /// (the ticker was not really asked, so it stays first in line), a 5xx or timeout records the
    /// ticker as FAILED. Either way the rest of the batch waits for the next pass rather than
    /// spending more of the budget the price path depends on.
    ///
    /// Every write is one statement after the requests it follows: no transaction is open while
    /// Yahoo is asked.
    pub async fn resolve_pending(&self) -> sqlx::Result<usize> {
        let due = self.due_tickers().await?;
        let mut stored = 0;
        for (i, ticker) in due.iter().enumerate() {
            if i > 0 {
                tokio::time::sleep(PACING).await;
            }
            match self.lookup(ticker).await {
                Ok(mark) => {
                    self.record(ticker, "STORED", Some(&mark)).await?;
                    stored += 1;
                }
                Err(Miss::Absent(_)) => self.record(ticker, "ABSENT", None).await?,
                Err(Miss::Unavailable(reason)) => {
                    self.record(ticker, "FAILED", None).await?;
                    tracing::info!(
                        "Instrument logos: stopping this pass after {ticker} ({reason}); {} left for later",
                        due.len() - i - 1
                    );
                    return Ok(stored);
                }
                Err(Miss::RateLimited) => {
                    tracing::info!(
                        "Instrument logos: Yahoo is rate-limiting, {} lookups left for a later pass",
                        due.len() - i
                    );
                    return Ok(stored);
                }
            }
        }
        if !due.is_empty() {
            tracing::info!(
                "Instrument logos: stored {stored} of {} looked up",
                due.len()
            );
        }
        Ok(stored)
    }

    /// Held, quoted, not crypto, and never answered for (or answered with a failure long enough
    /// ago), capped at BATCH. Sorted so a capped pass is deterministic.
    ///
    /// A recorded price is the proof that a ticker is a real quoted symbol, before a quote-page
    /// request is spent on it: a fund code or a cash line no provider can price never has one.
    pub async fn due_tickers(&self) -> sqlx::Result<Vec<String>> {
        let in_crypto_accounts =
            upper(holdings::distinct_tickers(&self.db, Some((AccountType::Crypto, false))).await?);
        let mut candidates = upper(holdings::distinct_tickers(&self.db, None).await?);
        candidates.retain(|t| !in_crypto_accounts.contains(t) && YahooFinance::supports(t));
        // The same split the price path makes: a coin never reaches the equity page.
        let coins = self.coingecko.coin_ids(&candidates).await;
        candidates.retain(|t| !coins.contains_key(t));
        if candidates.is_empty() {
            return Ok(Vec::new());
        }
        let keys: Vec<&str> = candidates.iter().map(String::as_str).collect();
        db::query_scalar(&format!(
            "SELECT DISTINCT p.ticker FROM price_snapshot p WHERE p.ticker = ANY($1) \
             AND NOT EXISTS (SELECT 1 FROM instrument_logo l WHERE l.ticker = p.ticker \
             AND (l.status <> 'FAILED' OR l.attempted_at >= $2)) \
             ORDER BY p.ticker LIMIT {BATCH}"
        ))
        .bind(keys.as_slice())
        .bind(self.clock.now() - RETRY_FAILED_AFTER)
        .fetch_all(&self.db)
        .await
    }

    /// The mark Yahoo's quote page links for the ticker.
    pub async fn lookup(&self, ticker: &str) -> Result<Mark, Miss> {
        if !YahooFinance::supports(ticker) {
            return Err(Miss::Absent("not a quoted symbol"));
        }
        if self.cooldown.active() {
            return Err(Miss::RateLimited);
        }
        let symbol = ticker.to_uppercase();
        let page_url = format!(
            "{}/quote/{}/",
            self.page_base_url,
            utf8_percent_encode(&symbol, PATH_SEGMENT)
        );
        let (page, _) = self
            .get(
                "quote page",
                &symbol,
                &page_url,
                "text/html",
                PAGE_TIMEOUT,
                MAX_PAGE_BYTES,
            )
            .await?;
        if page.len() > MAX_PAGE_BYTES {
            // The page's size is Yahoo's layout, the same for every ticker: recording it as
            // absent would settle the whole portfolio on one page redesign.
            tracing::warn!("Yahoo's quote page for {symbol} exceeds the size cap");
            return Err(Miss::Unavailable("quote page too large".to_owned()));
        }
        let urls = match quote_page::read(&String::from_utf8_lossy(&page), &symbol) {
            QuotePage::Marked(urls) => urls,
            QuotePage::Unmarked => {
                tracing::info!("Yahoo quote page for {symbol} carries no logo for that symbol");
                return Err(Miss::Absent("no logo on the quote page"));
            }
            QuotePage::RefusedMark => {
                tracing::warn!(
                    "Yahoo quote page for {symbol} links its logo off {} (CDN change?)",
                    quote_page::IMAGE_HOST
                );
                return Err(Miss::Unavailable("logo URL off the image host".to_owned()));
            }
            QuotePage::NotQuoted => {
                tracing::warn!(
                    "Yahoo quote page for {symbol} does not quote that symbol (layout change or consent page?)"
                );
                return Err(Miss::Unavailable(
                    "symbol not quoted on the page".to_owned(),
                ));
            }
        };
        let light = self.download("logo", &urls.light, &symbol).await?;
        let dark = match urls.dark.filter(|dark| *dark != urls.light) {
            // A dark variant Yahoo does not have is no reason to lose the light mark. An outage
            // is: storing the light one alone would settle the ticker without its dark variant.
            Some(url) => match self.download("dark logo", &url, &symbol).await {
                Ok(image) => Some(image),
                Err(Miss::Absent(_)) => None,
                Err(miss) => return Err(miss),
            },
            None => None,
        };
        Ok(Mark { light, dark })
    }

    async fn download(&self, what: &str, url: &Url, symbol: &str) -> Result<Image, Miss> {
        // `url` passed quote_page::image_url, so in production this is the URL itself; the base
        // exists for the tests, which stand in for the image host.
        let target = format!(
            "{}{}",
            self.image_base_url,
            &url[Position::BeforePath..Position::AfterQuery]
        );
        let (bytes, declared) = self
            .get(what, symbol, &target, "*/*", IMAGE_TIMEOUT, MAX_IMAGE_BYTES)
            .await?;
        let size = bytes.len();
        validate(bytes, declared.as_deref()).inspect_err(|miss| {
            tracing::info!(
                "Rejected the {what} Yahoo serves for {symbol} (type {declared:?}, {size} bytes): {miss:?}"
            );
        })
    }

    /// The body of a 2xx answer, read no further than one byte past `cap`, with its declared
    /// content type.
    async fn get(
        &self,
        what: &str,
        symbol: &str,
        url: &str,
        accept: &str,
        timeout: Duration,
        cap: usize,
    ) -> Result<(Vec<u8>, Option<String>), Miss> {
        // A connection cut mid-body is the upstream misbehaving, like a timeout.
        let unanswered = |error: reqwest::Error| {
            let reason = transport_message(&error);
            tracing::warn!("Yahoo {what} request for {symbol} did not complete: {reason}");
            Miss::Unavailable(reason)
        };
        let mut response = self
            .http
            .get(url)
            .header(USER_AGENT, "Mozilla/5.0")
            .header(ACCEPT, accept)
            .timeout(timeout)
            .send()
            .await
            .map_err(unanswered)?;
        if !response.status().is_success() {
            return Err(self.refused(what, symbol, &response));
        }
        let content_type = response
            .headers()
            .get(CONTENT_TYPE)
            .and_then(|value| value.to_str().ok())
            .map(str::to_owned);
        let mut body = Vec::new();
        while body.len() <= cap
            && let Some(chunk) = response.chunk().await.map_err(unanswered)?
        {
            let room = cap + 1 - body.len();
            body.extend_from_slice(&chunk[..chunk.len().min(room)]);
        }
        Ok((body, content_type))
    }

    /// What a refused request means. Only a 404 is permanent; every other status is asked again
    /// later, a 3xx included: redirects are not followed, and one to a consent page or a
    /// normalised URL says nothing about the mark. A 429 or a 5xx also arms the shared cooldown,
    /// so the rest of the batch and the next pass stand down too.
    fn refused(&self, what: &str, symbol: &str, response: &reqwest::Response) -> Miss {
        let status = response.status();
        if status == StatusCode::TOO_MANY_REQUESTS {
            let retry_after = response
                .headers()
                .get(RETRY_AFTER)
                .and_then(|value| value.to_str().ok());
            let pause = self.cooldown.arm(retry_after);
            tracing::warn!(
                "Yahoo rate-limited (429) the {what} request for {symbol} -- pausing logo lookups for {}s",
                pause.as_secs()
            );
            return Miss::RateLimited;
        }
        if status == StatusCode::NOT_FOUND {
            tracing::info!("Yahoo has no {what} for {symbol} (HTTP 404)");
            return Miss::Absent("HTTP 404");
        }
        if status.is_server_error() {
            self.cooldown.arm(None);
        }
        tracing::warn!("Yahoo answered the {what} request for {symbol} with HTTP {status}");
        Miss::Unavailable(format!("HTTP {}", status.as_u16()))
    }

    /// One row per ticker. A ticker that is looked up has no row or a FAILED one, which holds no
    /// image, so every column is written.
    async fn record(&self, ticker: &str, status: &str, mark: Option<&Mark>) -> sqlx::Result<()> {
        let now = self.clock.now();
        let dark = mark.and_then(|mark| mark.dark.as_ref());
        db::query(
            "INSERT INTO instrument_logo (ticker, status, image, content_type, image_dark, \
             content_type_dark, attempted_at, fetched_at) VALUES ($1, $2, $3, $4, $5, $6, $7, $8) \
             ON CONFLICT (ticker) DO UPDATE SET status = EXCLUDED.status, image = EXCLUDED.image, \
             content_type = EXCLUDED.content_type, image_dark = EXCLUDED.image_dark, \
             content_type_dark = EXCLUDED.content_type_dark, \
             attempted_at = EXCLUDED.attempted_at, fetched_at = EXCLUDED.fetched_at",
        )
        .bind(ticker)
        .bind(status)
        .bind(mark.map(|mark| mark.light.bytes.as_slice()))
        .bind(mark.map(|mark| mark.light.content_type))
        .bind(dark.map(|image| image.bytes.as_slice()))
        .bind(dark.map(|image| image.content_type))
        .bind(now)
        .bind(mark.map(|_| now))
        .execute(&self.db)
        .await?;
        Ok(())
    }
}

/// The image, if the bytes carry a PNG, JPEG or WebP signature and fit under MAX_IMAGE_BYTES,
/// whatever the header says, unless it says SVG.
///
/// The bytes decide, not the label: a CDN that serves the same files as application/octet-stream,
/// without a type, or under the wrong image type changes nothing about the mark, and judging by
/// the header would settle every ticker at once. Since the stored type is the canonical one for
/// the signature, the label never reaches the browser either.
///
/// Absent only when the refusal is about the mark itself: an image over the cap, or an SVG. A
/// body that is not an image at all (an error or anti-bot page served with a 200, whatever its
/// size, or an empty body) says nothing about the mark and is Unavailable.
pub fn validate(bytes: Vec<u8>, declared_content_type: Option<&str>) -> Result<Image, Miss> {
    if bytes.is_empty() {
        return Err(Miss::Unavailable("empty image body".to_owned()));
    }
    if declared_content_type.map(media_type).as_deref() == Some("image/svg+xml") {
        return Err(Miss::Absent("SVG"));
    }
    let Some(content_type) = sniff(&bytes) else {
        return Err(Miss::Unavailable(format!(
            "not an image: {declared_content_type:?}"
        )));
    };
    if bytes.len() > MAX_IMAGE_BYTES {
        return Err(Miss::Absent("too large"));
    }
    Ok(Image {
        bytes,
        content_type,
    })
}

/// Type and subtype, lower-cased, without parameters.
fn media_type(content_type: &str) -> String {
    content_type
        .split(';')
        .next()
        .unwrap_or_default()
        .trim()
        .to_lowercase()
}

fn sniff(bytes: &[u8]) -> Option<&'static str> {
    if bytes.starts_with(&[0x89, b'P', b'N', b'G', 0x0D, 0x0A, 0x1A, 0x0A]) {
        Some("image/png")
    } else if bytes.starts_with(&[0xFF, 0xD8, 0xFF]) {
        Some("image/jpeg")
    } else if bytes.starts_with(b"RIFF") && bytes.get(8..12) == Some(b"WEBP".as_slice()) {
        Some("image/webp")
    } else {
        None
    }
}

fn upper(tickers: Vec<String>) -> BTreeSet<String> {
    tickers.iter().map(|t| t.to_uppercase()).collect()
}

#[cfg(test)]
mod tests {
    use super::*;

    const PNG: [u8; 16] = [
        0x89, b'P', b'N', b'G', 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 13, b'I', b'H', b'D', b'R',
    ];
    const JPEG: [u8; 10] = [0xFF, 0xD8, 0xFF, 0xE0, 0, 16, b'J', b'F', b'I', b'F'];
    const WEBP: [u8; 16] = [
        b'R', b'I', b'F', b'F', 0, 0, 0, 0, b'W', b'E', b'B', b'P', b'V', b'P', b'8', b' ',
    ];
    const SVG: &[u8] = br#"<svg xmlns="http://www.w3.org/2000/svg" onload="alert(1)"/>"#;

    fn padded(prefix: &[u8], len: usize) -> Vec<u8> {
        let mut bytes = prefix.to_vec();
        bytes.resize(len, 0);
        bytes
    }

    fn stored_type(bytes: &[u8], declared: Option<&str>) -> &'static str {
        validate(bytes.to_vec(), declared)
            .unwrap_or_else(|miss| panic!("{declared:?} was refused: {miss:?}"))
            .content_type
    }

    #[test]
    fn validation_trusts_the_signature_not_the_header() {
        assert_eq!(stored_type(&PNG, Some("image/png")), "image/png");
        assert_eq!(stored_type(&JPEG, Some("image/jpeg")), "image/jpeg");
        assert_eq!(stored_type(&JPEG, Some("image/jpg")), "image/jpeg");
        assert_eq!(stored_type(&WEBP, Some("image/webp")), "image/webp");
        assert_eq!(
            stored_type(&PNG, Some("IMAGE/PNG; charset=binary")),
            "image/png"
        );
        assert!(validate(padded(&PNG, MAX_IMAGE_BYTES), Some("image/png")).is_ok());
        // A label the CDN got wrong or left generic: the bytes decide, and the stored type is
        // theirs.
        for label in [
            None,
            Some("application/octet-stream"),
            Some("binary/octet-stream"),
            Some("text/html"),
            Some("image/jpeg"),
            Some("png"),
        ] {
            assert_eq!(stored_type(&PNG, label), "image/png");
        }
        assert_eq!(stored_type(&JPEG, Some("text/plain")), "image/jpeg");
    }

    #[test]
    fn a_refusal_about_the_mark_itself_is_permanent() {
        // Asking again gets the same file.
        assert_eq!(
            validate(SVG.to_vec(), Some("image/svg+xml")),
            Err(Miss::Absent("SVG"))
        );
        assert_eq!(
            validate(PNG.to_vec(), Some("Image/SVG+XML")),
            Err(Miss::Absent("SVG"))
        );
        assert_eq!(
            validate(padded(&PNG, MAX_IMAGE_BYTES + 1), Some("image/png")),
            Err(Miss::Absent("too large"))
        );
        assert_eq!(
            validate(padded(&PNG, 300 * 1024), Some("application/octet-stream")),
            Err(Miss::Absent("too large"))
        );
    }

    #[test]
    fn a_body_that_is_not_an_image_says_nothing_about_the_mark() {
        // An error page or a CDN hiccup, whatever its size.
        for (bytes, declared) in [
            (b"<html>".to_vec(), Some("text/html")),
            (vec![0; 300 * 1024], Some("text/html")),
            (vec![0; 300 * 1024], Some("image/png")),
            (SVG.to_vec(), Some("image/png")),
            (SVG.to_vec(), None),
            (Vec::new(), Some("image/png")),
        ] {
            let size = bytes.len();
            assert!(
                matches!(validate(bytes, declared), Err(Miss::Unavailable(_))),
                "{size} bytes declared {declared:?}"
            );
        }
    }
}

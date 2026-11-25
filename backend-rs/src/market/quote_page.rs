//! YahooQuotePageParser: the logo URLs Yahoo's quote page carries for the symbol it is about.
//!
//! The page embeds its API responses as `<script type="application/json">` blocks, each a JSON
//! envelope whose `body` is the response as a JSON string. A quote object in there has `symbol`,
//! `logoUrl` (a mark for a light background) and `logoUrlDarkMode`.
//!
//! The page also lists dozens of other companies (trending tickers, "people also watch"), each
//! with its own logo URL, and the first `s.yimg.com/lg/logos/` URL in the page belongs to one of
//! them. So this never scans the page for a logo URL: it parses the JSON and only accepts the
//! object whose `symbol` is the one asked for. A layout change can make it find nothing, which it
//! reports as NotQuoted or RefusedMark, never as a missing mark; it cannot make it find the wrong
//! company.

use std::sync::LazyLock;

use regex::Regex;
use serde_json::Value;
use url::Url;

/// The only host a logo may be downloaded from. Anything else in the page is ignored.
pub const IMAGE_HOST: &str = "s.yimg.com";

/// The tag boundaries only; what is inside is handed to a JSON parser.
static JSON_SCRIPT: LazyLock<Regex> = LazyLock::new(|| {
    Regex::new(r#"(?is)<script\b[^>]*\btype="application/json"[^>]*>(.*?)</script>"#)
        .expect("regex")
});

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct LogoUrls {
    pub light: Url,
    pub dark: Option<Url>,
}

/// What the page says about one symbol.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum QuotePage {
    /// The page quotes the symbol and carries a usable mark for it.
    Marked(LogoUrls),
    /// The page quotes the symbol without a `logoUrl`, and carries other companies' marks, so it
    /// is the layout this parser reads: Yahoo has none.
    Unmarked,
    /// The quote has a `logoUrl` this parser will not download (another host, not https): a CDN
    /// move, not a missing mark.
    RefusedMark,
    /// No quote object for the symbol, or one on a page carrying no usable mark at all: a layout
    /// this parser no longer reads, or a consent or anti-bot page served with a 200. Says nothing
    /// about whether a mark exists.
    NotQuoted,
}

pub fn read(html: &str, symbol: &str) -> QuotePage {
    let mut scan = Scan {
        symbol,
        mark: None,
        mark_refused: false,
        quoted_without_mark: false,
        page_has_a_mark: false,
    };
    for block in JSON_SCRIPT.captures_iter(html) {
        let Some(root) = parse(&block[1]) else {
            continue;
        };
        scan.visit(&root);
        if scan.mark.is_none()
            && let Some(body) = root.get("body").and_then(Value::as_str).and_then(parse)
        {
            scan.visit(&body);
        }
        if let Some(mark) = scan.mark.take() {
            return QuotePage::Marked(mark);
        }
    }
    scan.verdict()
}

/// What the whole page has said so far about the symbol, and whether it carries any mark.
struct Scan<'a> {
    symbol: &'a str,
    mark: Option<LogoUrls>,
    mark_refused: bool,
    quoted_without_mark: bool,
    page_has_a_mark: bool,
}

impl Scan<'_> {
    fn visit(&mut self, root: &Value) {
        let mut stack = vec![root];
        while let Some(node) = stack.pop() {
            match node {
                Value::Object(fields) => {
                    let raw = fields.get("logoUrl").and_then(Value::as_str);
                    let light = raw.and_then(image_url);
                    self.page_has_a_mark |= light.is_some();
                    let about_the_symbol = fields
                        .get("symbol")
                        .and_then(Value::as_str)
                        .is_some_and(|symbol| symbol.eq_ignore_ascii_case(self.symbol));
                    if about_the_symbol {
                        if let Some(light) = light {
                            let dark = fields
                                .get("logoUrlDarkMode")
                                .and_then(Value::as_str)
                                .and_then(image_url);
                            self.mark = Some(LogoUrls { light, dark });
                            return;
                        }
                        // Only a quote object counts: the page also has other objects keyed by
                        // the symbol (recommendations, news) that never carry a logo.
                        if raw.is_some_and(|raw| !raw.trim().is_empty()) {
                            self.mark_refused = true;
                        } else if fields.contains_key("quoteType") {
                            self.quoted_without_mark = true;
                        }
                    }
                    stack.extend(fields.values());
                }
                Value::Array(items) => stack.extend(items),
                _ => {}
            }
        }
    }

    /// A missing mark is only believed on a page that carries other companies' marks, which
    /// Yahoo's quote pages always do: without one, the field was renamed or moved and every
    /// symbol would look unmarked.
    fn verdict(&self) -> QuotePage {
        if self.mark_refused {
            QuotePage::RefusedMark
        } else if self.quoted_without_mark && self.page_has_a_mark {
            QuotePage::Unmarked
        } else {
            QuotePage::NotQuoted
        }
    }
}

/// An https URL on IMAGE_HOST, or None. The page is untrusted input.
pub fn image_url(raw: &str) -> Option<Url> {
    let url = Url::parse(raw.trim()).ok()?;
    let on_the_image_host = url.scheme() == "https"
        && url.host_str() == Some(IMAGE_HOST)
        && url.username().is_empty()
        && url.password().is_none()
        && url.port().is_none();
    on_the_image_host.then_some(url)
}

fn parse(text: &str) -> Option<Value> {
    serde_json::from_str(text).ok()
}

#[cfg(test)]
mod tests {
    use super::*;

    const AAPL_PAGE: &str =
        include_str!("../../tests/integration/market/fixtures/yahoo/quote-page-aapl.html");
    const IWDA_PAGE: &str =
        include_str!("../../tests/integration/market/fixtures/yahoo/quote-page-iwda.html");
    const AAPL_LIGHT: &str = "https://s.yimg.com/lo/mysterio/api/5dee/finance/resizefill_w50_h50/https://s.yimg.com/lg/logos/US0378331005/light/2e23b039.png";
    const AAPL_DARK: &str = "https://s.yimg.com/lo/mysterio/api/402d/finance/resizefill_w50_h50/https://s.yimg.com/lg/logos/US0378331005/dark/fabb0b30.png";

    fn marked(html: &str, symbol: &str) -> LogoUrls {
        match read(html, symbol) {
            QuotePage::Marked(urls) => urls,
            other => panic!("expected a mark for {symbol}, got {other:?}"),
        }
    }

    fn page(quote_json: &str) -> String {
        format!(
            r#"<html><script type="application/json">{{"quoteResponse":{{"result":[{quote_json}]}}}}</script></html>"#
        )
    }

    #[test]
    fn reads_the_mark_of_the_symbol_the_page_is_about_not_the_first_logo_on_the_page() {
        // The page's first logo is VST's, in the trending block, and MSFT's sits in the same quote
        // response just before AAPL's. A scan for the first s.yimg.com/lg/logos URL would store
        // another company's mark under AAPL.
        let urls = marked(AAPL_PAGE, "AAPL");
        assert_eq!(urls.light.as_str(), AAPL_LIGHT);
        assert_eq!(urls.dark.expect("a dark variant").as_str(), AAPL_DARK);
    }

    #[test]
    fn reads_an_etf_with_an_exchange_suffix() {
        let urls = marked(IWDA_PAGE, "IWDA.AS");
        assert!(
            urls.light
                .as_str()
                .ends_with("/IE00B4L5Y983/light/22532c01.png")
        );
        assert!(
            urls.dark
                .expect("a dark variant")
                .as_str()
                .ends_with("/IE00B4L5Y983/dark/22532c01.png")
        );
    }

    #[test]
    fn matches_the_symbol_case_insensitively() {
        assert!(matches!(read(IWDA_PAGE, "iwda.as"), QuotePage::Marked(_)));
    }

    #[test]
    fn a_symbol_the_page_does_not_quote_is_not_quoted_rather_than_unmarked() {
        // GOOGL is nowhere in the page; the other companies' logos must not stand in for it.
        assert_eq!(read(AAPL_PAGE, "GOOGL"), QuotePage::NotQuoted);
    }

    #[test]
    fn a_layout_without_json_blocks_quotes_nothing_rather_than_reporting_no_mark() {
        let html = r#"<html><body><img src="https://s.yimg.com/lg/logos/US0378331005/light/2e23b039.png"><div data-symbol="AAPL"></div></body></html>"#;
        assert_eq!(read(html, "AAPL"), QuotePage::NotQuoted);
    }

    #[test]
    fn refuses_a_logo_url_off_the_image_host_without_calling_the_symbol_unmarked() {
        // A CDN move would put every mark off the host at once; that is not "Yahoo has none".
        let html = page(
            r#"{"symbol":"MSFT","logoUrl":"https://s.yimg.com/lg/m.png"},{"symbol":"AAPL","quoteType":"EQUITY","logoUrl":"https://evil.example/logo.png"}"#,
        );
        assert_eq!(read(&html, "AAPL"), QuotePage::RefusedMark);
    }

    #[test]
    fn an_object_keyed_by_the_symbol_that_is_not_a_quote_is_not_quoted() {
        // The real page has one: recommendationsbysymbol, which never carries a logo.
        let html = page(
            r#"{"symbol":"MSFT","logoUrl":"https://s.yimg.com/lg/m.png"},{"symbol":"AAPL","recommendedSymbols":[{"symbol":"MSFT","score":0.25}]}"#,
        );
        assert_eq!(read(&html, "AAPL"), QuotePage::NotQuoted);
    }

    #[test]
    fn a_quote_without_a_logo_on_a_page_with_no_usable_mark_at_all_is_not_quoted() {
        let html = page(
            r#"{"symbol":"MSFT","quoteType":"EQUITY"},{"symbol":"AAPL","quoteType":"EQUITY"}"#,
        );
        assert_eq!(read(&html, "AAPL"), QuotePage::NotQuoted);
    }

    #[test]
    fn the_real_page_without_the_symbols_own_mark_is_unmarked() {
        let html = AAPL_PAGE.replace(AAPL_LIGHT, "");
        assert_eq!(read(&html, "AAPL"), QuotePage::Unmarked);
    }

    #[test]
    fn the_real_page_with_its_logo_field_renamed_is_not_quoted_rather_than_unmarked() {
        let html = AAPL_PAGE.replace("logoUrl", "brandLogo");
        assert_eq!(read(&html, "AAPL"), QuotePage::NotQuoted);
    }

    #[test]
    fn refuses_plain_http_and_lookalike_hosts() {
        for refused in [
            "http://s.yimg.com/lg/logos/X/light/a.png",
            "https://s.yimg.com.evil.example/a.png",
            "https://user@s.yimg.com/a.png",
            "https://s.yimg.com:8443/a.png",
            "javascript:alert(1)",
            " ",
        ] {
            assert_eq!(image_url(refused), None, "{refused}");
        }
        assert!(image_url("https://s.yimg.com/lg/logos/X/light/a.png").is_some());
    }

    #[test]
    fn keeps_the_light_mark_when_the_dark_one_is_unusable() {
        let html = page(
            r#"{"symbol":"AAPL","logoUrl":"https://s.yimg.com/lg/a.png","logoUrlDarkMode":"https://evil.example/b.png"}"#,
        );
        let urls = marked(&html, "AAPL");
        assert_eq!(urls.light.as_str(), "https://s.yimg.com/lg/a.png");
        assert_eq!(urls.dark, None);
    }

    #[test]
    fn a_quote_object_for_the_symbol_without_a_logo_is_unmarked() {
        let html = page(
            r#"{"symbol":"MSFT","logoUrl":"https://s.yimg.com/lg/m.png"},{"symbol":"AAPL","quoteType":"EQUITY"}"#,
        );
        assert_eq!(read(&html, "AAPL"), QuotePage::Unmarked);
    }

    #[test]
    fn json_that_only_quotes_other_symbols_is_not_quoted() {
        let html = page(r#"{"symbol":"MSFT","logoUrl":"https://s.yimg.com/lg/m.png"}"#);
        assert_eq!(read(&html, "AAPL"), QuotePage::NotQuoted);
    }

    #[test]
    fn a_mark_in_a_later_block_wins_over_an_unmarked_quote_in_an_earlier_one() {
        let html = page(r#"{"symbol":"AAPL","quoteType":"EQUITY"}"#)
            + &page(r#"{"symbol":"AAPL","logoUrl":"https://s.yimg.com/lg/a.png"}"#);
        assert_eq!(
            marked(&html, "AAPL").light.as_str(),
            "https://s.yimg.com/lg/a.png"
        );
    }
}

//! SimplefinUrls: a setup token becomes a claim URL, an access URL a credential-free accounts
//! request. Both must point at SimpleFIN Bridge, so a pasted token or a claim response cannot
//! make Picsou call any other host.

use base64::Engine;
use base64::engine::general_purpose::{GeneralPurpose, GeneralPurposeConfig};
use base64::engine::{DecodePaddingMode, general_purpose};
use chrono::NaiveDate;
use url::Url;

use super::SyncError;
use super::java_uri::{self, JavaUri, percent_decode_lossy};
use crate::ledger::sync_values::{java_is_blank, java_trim};

pub const BRIDGE_HOST: &str = "beta-bridge.simplefin.org";
pub const MAX_TOKEN_CHARS: usize = 4096;
const MAX_ACCESS_URL_CHARS: usize = 8192;

const NOT_A_TOKEN: &str = "That does not look like a SimpleFIN setup token.";
const MISSING_CREDENTIALS: &str = "The SimpleFIN access URL is missing its credentials.";

/// Java's Base64 decoders: padding optional, trailing bits not checked.
const LENIENT: GeneralPurposeConfig = GeneralPurposeConfig::new()
    .with_decode_allow_trailing_bits(true)
    .with_decode_padding_mode(DecodePaddingMode::Indifferent);
const STANDARD: GeneralPurpose = GeneralPurpose::new(&base64::alphabet::STANDARD, LENIENT);
const URL_SAFE: GeneralPurpose = GeneralPurpose::new(&base64::alphabet::URL_SAFE, LENIENT);

/// The accounts URL without user info, and the Basic header that carries it.
#[derive(Debug, Clone)]
pub struct AccountsRequest {
    pub url: Url,
    pub authorization: String,
}

pub fn utf16_len(value: &str) -> usize {
    value.encode_utf16().count()
}

/// The decoded claim URL. Accepts a raw https:// URL as well as Base64.
pub fn claim_url(setup_token: &str) -> Result<Url, SyncError> {
    if java_is_blank(setup_token) {
        return Err(SyncError::new("A SimpleFIN setup token is required."));
    }
    let trimmed = java_trim(setup_token);
    if utf16_len(trimmed) > MAX_TOKEN_CHARS {
        return Err(SyncError::new("That SimpleFIN setup token is too long."));
    }
    let raw = trimmed
        .get(..8)
        .is_some_and(|scheme| scheme.eq_ignore_ascii_case("https://"));
    let decoded = if raw {
        trimmed.to_owned()
    } else {
        decode_token(trimmed)?
    };
    let uri = parse(&decoded)?;
    assert_https(&uri, false)?;
    dispatch_url(&uri, uri.raw_path.as_deref(), uri.raw_query.as_deref())
}

/// Validates an access URL and returns it trimmed when it is safe to store.
pub fn require_access_url(access_url: &str) -> Result<String, SyncError> {
    access_target(access_url, None)?;
    Ok(java_trim(access_url).to_owned())
}

/// The accounts request for a stored access URL, from the given date (inclusive).
pub fn accounts_request(access_url: &str, start: NaiveDate) -> Result<AccountsRequest, SyncError> {
    access_target(access_url, Some(start))
}

fn access_target(access_url: &str, start: Option<NaiveDate>) -> Result<AccountsRequest, SyncError> {
    if java_is_blank(access_url) {
        return Err(SyncError::new(
            "The SimpleFIN access URL is missing. Connect again with a new setup token.",
        ));
    }
    let trimmed = java_trim(access_url);
    if utf16_len(trimmed) > MAX_ACCESS_URL_CHARS {
        return Err(SyncError::new("The SimpleFIN access URL is too long."));
    }
    let uri = parse(trimmed)?;
    assert_https(&uri, true)?;
    let user_info = uri.raw_user_info.as_deref().unwrap_or_default();
    let (username, password) = match user_info.split_once(':') {
        Some((user, pass)) if !user.is_empty() && !pass.is_empty() => (user, pass),
        _ => return Err(SyncError::new(MISSING_CREDENTIALS)),
    };
    let credentials = format!("{}:{}", url_decode(username), url_decode(password));
    let token = general_purpose::STANDARD.encode(credentials.as_bytes());

    let mut path = match uri.raw_path.as_deref() {
        None | Some("") => "/".to_owned(),
        Some(path) => path.to_owned(),
    };
    if !path.ends_with('/') {
        path.push('/');
    }
    path.push_str("accounts");
    let query = start.map(|date| {
        let epoch = date
            .and_hms_opt(0, 0, 0)
            .map_or(0, |t| t.and_utc().timestamp());
        format!("version=2&start-date={epoch}")
    });
    Ok(AccountsRequest {
        url: dispatch_url(&uri, Some(&path), query.as_deref())?,
        authorization: format!("Basic {token}"),
    })
}

fn decode_token(compact: &str) -> Result<String, SyncError> {
    let stripped: String = compact
        .chars()
        .filter(|c| !matches!(c, ' ' | '\t' | '\n' | '\u{0B}' | '\u{0C}' | '\r'))
        .collect();
    let missing = (4 - stripped.len() % 4) % 4;
    let padded = format!("{stripped}{}", "=".repeat(missing));
    let bytes = STANDARD
        .decode(&padded)
        .or_else(|_| URL_SAFE.decode(&padded))
        .map_err(|_| SyncError::new(NOT_A_TOKEN))?;
    Ok(java_trim(&String::from_utf8_lossy(&bytes)).to_owned())
}

fn parse(value: &str) -> Result<JavaUri, SyncError> {
    java_uri::parse(value).map_err(|_| SyncError::new(NOT_A_TOKEN))
}

fn bridge_refused() -> SyncError {
    SyncError::new(format!(
        "Picsou only connects to SimpleFIN Bridge ({BRIDGE_HOST})."
    ))
}

fn assert_https(uri: &JavaUri, require_user_info: bool) -> Result<(), SyncError> {
    let https = uri
        .scheme
        .as_deref()
        .is_some_and(|s| s.eq_ignore_ascii_case("https"));
    let Some(host) = uri.host.as_deref().filter(|h| https && !java_is_blank(h)) else {
        return Err(SyncError::new("A SimpleFIN URL must be an https address."));
    };
    if !host.eq_ignore_ascii_case(BRIDGE_HOST) || (uri.port != -1 && uri.port != 443) {
        return Err(bridge_refused());
    }
    let has_user_info = uri.raw_user_info.as_deref().is_some_and(|u| !u.is_empty());
    if require_user_info && !has_user_info {
        return Err(SyncError::new(MISSING_CREDENTIALS));
    }
    if !require_user_info && has_user_info {
        return Err(SyncError::new(
            "A SimpleFIN setup token must not contain credentials.",
        ));
    }
    Ok(())
}

/// The URL the request goes to: the checked host, never the user info, and a second check on
/// what the HTTP client will actually connect to.
fn dispatch_url(uri: &JavaUri, path: Option<&str>, query: Option<&str>) -> Result<Url, SyncError> {
    let mut text = format!("https://{BRIDGE_HOST}{}", path.unwrap_or_default());
    if let Some(query) = query {
        text.push('?');
        text.push_str(query);
    }
    let url = Url::parse(&text).map_err(|_| SyncError::new(NOT_A_TOKEN))?;
    let same_host = uri
        .host
        .as_deref()
        .is_some_and(|h| h.eq_ignore_ascii_case(BRIDGE_HOST));
    if !same_host
        || url.host_str() != Some(BRIDGE_HOST)
        || url.port().is_some()
        || !url.username().is_empty()
        || url.password().is_some()
    {
        return Err(bridge_refused());
    }
    Ok(url)
}

/// URLDecoder.decode after a literal '+' became %2B: a plus stays a plus.
fn url_decode(value: &str) -> String {
    percent_decode_lossy(value)
}

/// The access URL's username as a hint: its last four characters, else a fixed mask.
pub fn mask(access_url: &str) -> String {
    const MASK: &str = "\u{2022}\u{2022}\u{2022}\u{2022}";
    let Ok(uri) = java_uri::parse(access_url) else {
        return MASK.to_owned();
    };
    let Some(user_info) = uri.raw_user_info.map(|u| percent_decode_lossy(&u)) else {
        return MASK.to_owned();
    };
    if java_is_blank(&user_info) {
        return MASK.to_owned();
    }
    let username: Vec<u16> = user_info
        .split(':')
        .next()
        .unwrap_or_default()
        .encode_utf16()
        .collect();
    if username.len() <= 4 {
        return MASK.to_owned();
    }
    format!(
        "{MASK}{}",
        String::from_utf16_lossy(&username[username.len() - 4..])
    )
}

#[cfg(test)]
mod tests {
    use super::*;

    const USERNAME: &str = "usr98765";
    const PASSWORD: &str = "s3cr3tPassw0rd";
    const SECRET: &str = "SECRET-CLAIM-VALUE-12345";

    fn token(url: &str) -> String {
        general_purpose::STANDARD.encode(url.as_bytes())
    }

    fn access_url(authority: &str) -> String {
        format!("https://{USERNAME}:{PASSWORD}@{authority}/simplefin")
    }

    fn foreign_hosts() -> Vec<String> {
        let b = BRIDGE_HOST;
        [
            "evil.example".to_owned(),
            format!("{b}."),
            format!("{b}.evil.example"),
            format!("evil{b}"),
            format!("evil.{b}"),
            "bridge.simplefin.org".to_owned(),
            "simplefin.org".to_owned(),
            "beta%2Dbridge.simplefin.org".to_owned(),
            "beta-bridge%2Esimplefin.org".to_owned(),
            format!("{b}%2Eevil.example"),
            format!("{b}%00.evil.example"),
            "%62eta-bridge.simplefin.org".to_owned(),
            "b\u{0435}ta-bridge.simplefin.org".to_owned(),
            "xn--bta-bridge-3hd.simplefin.org".to_owned(),
            "\u{ff42}eta-bridge.simplefin.org".to_owned(),
            "[::1]".to_owned(),
            "[::ffff:10.0.0.5]".to_owned(),
            "[::ffff:a00:5]".to_owned(),
            "10.0.0.5".to_owned(),
            "10.0.0.5:8443".to_owned(),
            "127.0.0.1".to_owned(),
            "169.254.169.254".to_owned(),
            "2130706433".to_owned(),
            "0x7f000001".to_owned(),
            "localhost".to_owned(),
            "localhost.".to_owned(),
            format!("{b}@evil.example"),
            format!("{b}:pw@evil.example"),
            format!("{b}:443@evil.example"),
            format!("u:p@{b}@evil.example"),
            format!("evil.example#@{b}"),
            format!("evil.example?@{b}"),
            format!("evil.example/@{b}"),
            format!("evil.example\\@{b}"),
            format!("{b}\\@evil.example"),
            format!("{b}\\.evil.example"),
        ]
        .into()
    }

    #[test]
    fn accepted_claim_tokens_point_at_the_bridge() {
        let url = format!("https://{BRIDGE_HOST}/simplefin/claim/abc");
        let encoded = token(&url);
        let mut padded = format!("https://{BRIDGE_HOST}/simplefin/claim/a");
        while padded.len() % 3 != 2 {
            padded.push('a');
        }
        padded.push_str("?x=1");
        let standard = general_purpose::STANDARD.encode(padded.as_bytes());
        assert!(standard.contains('/') || standard.contains('+'));
        let forms = [
            encoded.clone(),
            url.clone(),
            format!("HTTPS://{BRIDGE_HOST}/simplefin/claim/abc"),
            token("https://BETA-Bridge.SimpleFIN.org/simplefin/claim/abc"),
            token(&format!("https://{BRIDGE_HOST}:443/simplefin/claim/abc")),
            format!("  \n\t{}\r\n{} \n", &encoded[..10], &encoded[10..]),
            standard,
            general_purpose::STANDARD_NO_PAD.encode(padded.as_bytes()),
            general_purpose::URL_SAFE.encode(padded.as_bytes()),
            general_purpose::URL_SAFE_NO_PAD.encode(padded.as_bytes()),
        ];
        for form in forms {
            let claim = claim_url(&form).unwrap_or_else(|e| panic!("{form}: {}", e.message));
            assert_eq!(claim.host_str(), Some(BRIDGE_HOST));
            assert_eq!(claim.scheme(), "https");
        }
    }

    #[test]
    fn foreign_hosts_are_refused_without_echoing_the_token() {
        for host in foreign_hosts() {
            let url = format!("https://{host}/claim/{SECRET}");
            for input in [url.clone(), token(&url)] {
                let error = claim_url(&input).expect_err(&host);
                assert!(!error.message.contains(SECRET), "{host}");
                assert!(!error.message.contains(&input), "{host}");
            }
            let error = require_access_url(&access_url(&host)).expect_err(&host);
            assert!(!error.message.contains(PASSWORD) && !error.message.contains(USERNAME));
        }
    }

    #[test]
    fn non_https_claim_urls_are_refused() {
        let b = BRIDGE_HOST;
        for url in [
            format!("http://{b}/x"),
            format!("HTTP://{b}/x"),
            format!("ftp://{b}/x"),
            "file:///etc/passwd".to_owned(),
            format!("file://{b}/etc/passwd"),
            format!("jar:https://{b}/x!/"),
            "jar:file:/tmp/x.jar!/".to_owned(),
            format!("//{b}/x"),
            format!("/{b}/x"),
            format!("{b}/x"),
            format!("https:{b}/x"),
            format!("https:/{b}/x"),
            format!("https:///{b}/x"),
            "javascript:alert(1)".to_owned(),
            "data:text/plain;base64,aHR0cHM6Ly9iZXRhLWJyaWRnZS5zaW1wbGVmaW4ub3Jn".to_owned(),
        ] {
            assert!(claim_url(&token(&url)).is_err(), "{url}");
        }
    }

    #[test]
    fn messages_follow_the_refusal() {
        let b = BRIDGE_HOST;
        let refused = format!("Picsou only connects to SimpleFIN Bridge ({b}).");
        for port in [":8443", ":80", ":444"] {
            let claim = claim_url(&token(&format!("https://{b}{port}/simplefin/claim/abc")));
            assert_eq!(claim.unwrap_err().message, refused);
            let access = require_access_url(&access_url(&format!("{b}{port}")));
            assert_eq!(access.unwrap_err().message, refused);
        }
        assert_eq!(
            claim_url(&token(&format!("https://u:p@{b}/claim/abc")))
                .unwrap_err()
                .message,
            "A SimpleFIN setup token must not contain credentials."
        );
        for blank in ["", " ", "\n", "\t \r\n"] {
            assert_eq!(
                claim_url(blank).unwrap_err().message,
                "A SimpleFIN setup token is required."
            );
        }
        assert_eq!(
            claim_url(&format!("http://{b}/x")).unwrap_err().message,
            NOT_A_TOKEN,
            "a raw http URL is read as Base64"
        );
        assert_eq!(
            claim_url(&token(&format!("http://{b}/x")))
                .unwrap_err()
                .message,
            "A SimpleFIN URL must be an https address."
        );
    }

    #[test]
    fn tokens_that_are_not_claim_urls_are_refused() {
        for input in [
            "not base64!!!",
            "%%%%",
            "aGVsbG8gd29ybGQ=",
            "////",
            "AAAA",
            "aHR0cHM6Ly9iZXRhLWJyaWRnZS5zaW1wbGVmaW4ub3Jn$",
        ] {
            assert!(claim_url(input).is_err(), "{input}");
        }
    }

    #[test]
    fn the_token_cap_is_judged_on_the_trimmed_length() {
        let over = "A".repeat(MAX_TOKEN_CHARS + 1);
        assert!(claim_url(&over).unwrap_err().message.contains("too long"));
        let at_cap = format!("   {}   ", "A".repeat(MAX_TOKEN_CHARS));
        assert!(!claim_url(&at_cap).unwrap_err().message.contains("too long"));
        let raw = format!("https://{BRIDGE_HOST}/{}", "a".repeat(MAX_TOKEN_CHARS));
        assert!(claim_url(&raw).unwrap_err().message.contains("too long"));
    }

    #[test]
    fn accepted_access_urls_are_returned_trimmed() {
        for url in [
            access_url(BRIDGE_HOST),
            access_url("BETA-BRIDGE.SIMPLEFIN.ORG"),
            format!("HTTPS://{USERNAME}:{PASSWORD}@{BRIDGE_HOST}/simplefin"),
            access_url(&format!("{BRIDGE_HOST}:443")),
        ] {
            assert_eq!(require_access_url(&url).unwrap(), url);
        }
        let padded = format!("  \n{}\n  ", access_url(BRIDGE_HOST));
        assert_eq!(
            require_access_url(&padded).unwrap(),
            access_url(BRIDGE_HOST)
        );
    }

    fn basic(header: &str) -> String {
        let encoded = header.strip_prefix("Basic ").unwrap();
        String::from_utf8(general_purpose::STANDARD.decode(encoded).unwrap()).unwrap()
    }

    #[test]
    fn the_accounts_request_carries_the_credentials_only_in_the_header() {
        let start = NaiveDate::from_ymd_opt(2026, 1, 1).unwrap();
        let request = accounts_request(&access_url(BRIDGE_HOST), start).unwrap();
        assert_eq!(request.url.host_str(), Some(BRIDGE_HOST));
        assert_eq!(request.url.path(), "/simplefin/accounts");
        assert_eq!(request.url.query(), Some("version=2&start-date=1767225600"));
        assert!(!request.url.as_str().contains(USERNAME) && !request.url.as_str().contains('@'));
        assert_eq!(
            basic(&request.authorization),
            format!("{USERNAME}:{PASSWORD}")
        );

        let tricked = format!(
            "{}?redirect=https://evil.example#frag",
            access_url(BRIDGE_HOST)
        );
        let request = accounts_request(&tricked, start).unwrap();
        assert!(!request.url.as_str().contains("evil") && request.url.fragment().is_none());

        let plus = format!("https://user:p+ss%2Bword@{BRIDGE_HOST}/simplefin");
        let request = accounts_request(&plus, start).unwrap();
        assert_eq!(basic(&request.authorization), "user:p+ss+word");
    }

    #[test]
    fn access_urls_without_usable_credentials_are_refused() {
        let b = BRIDGE_HOST;
        for url in [
            format!("https://{b}/simplefin"),
            format!("https://@{b}/simplefin"),
            format!("https://:@{b}/simplefin"),
            format!("https://{USERNAME}@{b}/simplefin"),
            format!("https://{USERNAME}:@{b}/simplefin"),
            format!("https://:{PASSWORD}@{b}/simplefin"),
            format!("https://{USERNAME}:{PASSWORD}%zz@{b}/simplefin"),
            format!(
                "https://{USERNAME}:{}@{b}/simplefin",
                "p".repeat(MAX_ACCESS_URL_CHARS)
            ),
            String::new(),
            " ".to_owned(),
        ] {
            let error = require_access_url(&url).expect_err(&url);
            assert!(!error.message.contains(PASSWORD) && !error.message.contains("ppppp"));
        }
    }

    #[test]
    fn mask_keeps_the_last_four_characters_of_the_username() {
        let b = BRIDGE_HOST;
        for (url, expected) in [
            (
                format!("https://user1234:pw@{b}/simplefin"),
                "\u{2022}\u{2022}\u{2022}\u{2022}1234",
            ),
            (
                format!("https://abcd:pw@{b}/simplefin"),
                "\u{2022}\u{2022}\u{2022}\u{2022}",
            ),
            (
                format!("https://{b}/simplefin"),
                "\u{2022}\u{2022}\u{2022}\u{2022}",
            ),
            ("not a url".to_owned(), "\u{2022}\u{2022}\u{2022}\u{2022}"),
        ] {
            assert_eq!(mask(&url), expected, "{url}");
        }
    }
}

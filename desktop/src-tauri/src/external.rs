//! Links that leave the app. The main window never opens a second browser window: a link the page
//! asks to open in a new one (`target="_blank"`, `window.open`) goes to the system browser.

use tauri::Url;

/// True for a link the system browser may open: https, a host, no embedded credentials.
pub fn is_openable(url: &Url) -> bool {
    url.scheme() == "https"
        && url.host_str().is_some_and(|host| !host.is_empty())
        && url.username().is_empty()
        && url.password().is_none()
}

/// Hands an allowed link to the system browser and says whether it did. Anything else is
/// dropped. Only the scheme or the host is logged, never the path or the query.
pub fn open_in_browser(url: &Url) -> bool {
    if !is_openable(url) {
        tracing::warn!(
            scheme = url.scheme(),
            "refused to open a link outside the app"
        );
        return false;
    }
    match open::that_detached(url.as_str()) {
        Ok(()) => true,
        Err(e) => {
            tracing::warn!(
                host = url.host_str().unwrap_or_default(),
                "the system browser did not open: {e}"
            );
            false
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn only_plain_https_links_reach_the_system_browser() {
        for allowed in [
            "https://wise.com/your-account/integrations-and-tools/api-tokens",
            "https://bridge.simplefin.org/simplefin/create",
            "https://github.com/Cloeille/picsou-finance/releases/tag/1.3.0",
            "https://enablebanking.com:8443/sign-in/",
        ] {
            assert!(
                is_openable(&Url::parse(allowed).unwrap()),
                "expected allowed: {allowed}"
            );
        }
        for rejected in [
            "http://example.com/",
            "http://127.0.0.1:8080/api/accounts",
            "file:///etc/passwd",
            "javascript:alert(1)",
            "data:text/html,hi",
            "about:blank",
            "blob:https://example.com/abc",
            "ms-settings:privacy",
            "https://user:pass@example.com/",
            "https://user@example.com/",
        ] {
            assert!(
                !is_openable(&Url::parse(rejected).unwrap()),
                "expected rejected: {rejected}"
            );
        }
    }

    #[test]
    fn a_refused_link_opens_nothing() {
        assert!(!open_in_browser(&Url::parse("file:///etc/passwd").unwrap()));
        assert!(!open_in_browser(
            &Url::parse("http://127.0.0.1:8080/").unwrap()
        ));
    }
}

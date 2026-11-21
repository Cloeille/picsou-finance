//! SidecarBaseUrl: plaintext HTTP only to a sidecar that is demonstrably co-located (loopback or a
//! single-label Compose service name), since every request body carries a bank login.

use url::Url;

/// The trimmed URL once validated, or the reason startup must fail.
pub fn validate(sidecar_name: &str, url: &str) -> Result<String, String> {
    let trimmed = url.trim();
    if trimmed.is_empty() {
        return Err(invalid(sidecar_name, url, "it is not set"));
    }
    if !trimmed.contains(':') {
        return Err(invalid(
            sidecar_name,
            url,
            "it has no scheme (expected http:// or https://)",
        ));
    }
    let parsed =
        Url::parse(trimmed).map_err(|_| invalid(sidecar_name, url, "it is not a valid URL"))?;
    let scheme = parsed.scheme().to_ascii_lowercase();
    if scheme != "http" && scheme != "https" {
        return Err(invalid(
            sidecar_name,
            url,
            &format!("the scheme '{scheme}' is not http or https"),
        ));
    }
    let host = match parsed.host_str() {
        Some(host) if !host.trim().is_empty() => host.to_owned(),
        _ => return Err(invalid(sidecar_name, url, "it has no host")),
    };
    if !parsed.username().is_empty() || parsed.password().is_some() {
        return Err(invalid(
            sidecar_name,
            url,
            "it embeds credentials in the URL",
        ));
    }
    if parsed.query().is_some() || parsed.fragment().is_some() {
        return Err(invalid(
            sidecar_name,
            url,
            "it must not carry a query string or fragment",
        ));
    }
    if scheme == "http" && !is_co_located(&host) {
        return Err(invalid(
            sidecar_name,
            url,
            &format!(
                "plain HTTP is only allowed for a sidecar co-located with the backend (loopback, or a Docker \
                 Compose service name). Host '{host}' is reachable over a network, so it must use https://"
            ),
        ));
    }
    Ok(trimmed.to_owned())
}

fn is_co_located(host: &str) -> bool {
    let lower = host.to_ascii_lowercase();
    let normalized = lower
        .strip_prefix('[')
        .and_then(|h| h.strip_suffix(']'))
        .unwrap_or(&lower);
    if matches!(normalized, "localhost" | "::1" | "0:0:0:0:0:0:0:1")
        || normalized.starts_with("127.")
    {
        return true;
    }
    !normalized.contains('.') && !normalized.contains(':')
}

fn invalid(sidecar_name: &str, url: &str, reason: &str) -> String {
    let configured = if url.trim().is_empty() {
        "<empty>"
    } else {
        url
    };
    format!(
        "The {sidecar_name} sidecar URL is unusable because {reason} (configured: {configured})."
    )
}

#[cfg(test)]
mod tests {
    use super::validate;

    #[test]
    fn accepts_loopback_and_compose_names_over_http() {
        for url in [
            "http://127.0.0.1:8001",
            "http://127.4.5.6:8001",
            "http://localhost:8001",
            "http://[::1]:8001",
            "http://tr-auth:8001",
            " https://bank.example.com ",
        ] {
            assert!(validate("x", url).is_ok(), "{url}");
        }
    }

    #[test]
    fn rejects_cleartext_over_a_network_and_unusable_urls() {
        for (url, reason) in [
            ("", "it is not set"),
            ("http://sidecar.example.com:8001", "plain HTTP"),
            ("http://10.0.0.4:8001", "plain HTTP"),
            ("ftp://tr-auth", "the scheme 'ftp'"),
            ("http://user:pw@tr-auth:8001", "credentials"),
            ("http://tr-auth:8001/?a=b", "query string"),
            ("tr-auth", "no scheme"),
        ] {
            let error = validate("Trade Republic", url).expect_err(url);
            assert!(error.contains(reason), "{url}: {error}");
        }
    }
}

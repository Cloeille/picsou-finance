//! EvmWalletAdapter: one 0x address across every enabled EVM network, queried concurrently.
//!
//! A native-balance failure on any network fails the whole sync (dropping a chain would
//! understate net worth while marking the wallet synced); a token failure skips that token only.

use std::time::Duration;

use futures::future::{join_all, try_join_all};
use rust_decimal::Decimal;
use serde_json::{Value, json};

use super::rpc::{self, WalletBalance, WalletError, as_text};
use crate::AppState;

const BALANCE_OF_SELECTOR: &str = "0x70a08231";
const MESSAGE_ADDRESS_LIMIT: usize = 64;
const MAX_RETRIES: u32 = 2;
const RETRY_BACKOFF: Duration = Duration::from_millis(200);
/// Every enabled network's native coin has 18 decimals (ETH, BNB, POL, AVAX).
const NATIVE_DECIMALS: u32 = 18;

struct Erc20Token {
    contract: &'static str,
    symbol: &'static str,
    decimals: u32,
}

struct EvmNetwork {
    display_name: &'static str,
    native_symbol: &'static str,
    /// The config.sync_url name of its RPC endpoint.
    url_key: &'static str,
    tokens: &'static [Erc20Token],
}

const fn token(contract: &'static str, symbol: &'static str, decimals: u32) -> Erc20Token {
    Erc20Token {
        contract,
        symbol,
        decimals,
    }
}

/// Curated tokens only: ones the price service can price, at contracts verified by hand. BSC
/// stablecoins use 18 decimals, unlike the 6-decimal versions elsewhere.
const NETWORKS: &[EvmNetwork] = &[
    EvmNetwork {
        display_name: "Ethereum",
        native_symbol: "ETH",
        url_key: "evm-ethereum",
        tokens: &[
            token("0xdAC17F958D2ee523a2206206994597C13D831ec7", "USDT", 6),
            token("0xA0b86991c6218b36c1d19D4a2e9Eb0cE3606eB48", "USDC", 6),
            token("0x6B175474E89094C44Da98b954EedeAC495271d0F", "DAI", 18),
        ],
    },
    EvmNetwork {
        display_name: "BNB Chain",
        native_symbol: "BNB",
        url_key: "evm-bnb",
        tokens: &[
            token("0x55d398326f99059fF775485246999027B3197955", "USDT", 18),
            token("0x8AC76a51cc950d9822D68b83fE1Ad97B32Cd580d", "USDC", 18),
        ],
    },
    EvmNetwork {
        display_name: "Polygon",
        native_symbol: "POL",
        url_key: "evm-polygon",
        tokens: &[
            token("0xc2132D05D31c914a87C6611C10748AEb04B58e8F", "USDT", 6),
            token("0x3c499c542cEF5E3811e1192ce70d8cC03d5c3359", "USDC", 6),
        ],
    },
    EvmNetwork {
        display_name: "Arbitrum",
        native_symbol: "ETH",
        url_key: "evm-arbitrum",
        tokens: &[
            token("0xFd086bC7CD5C481DCC9C85ebE478A1C0b69FCbb9", "USDT", 6),
            token("0xaf88d065e77c8cC2239327C5EDb3A432268e5831", "USDC", 6),
        ],
    },
    EvmNetwork {
        display_name: "Optimism",
        native_symbol: "ETH",
        url_key: "evm-optimism",
        tokens: &[
            token("0x94b008aA00579c1307B0EF2c499aD98a8ce58e58", "USDT", 6),
            token("0x0b2C639c533813f4Aa9D7837CAf62653d097Ff85", "USDC", 6),
        ],
    },
    EvmNetwork {
        display_name: "Base",
        native_symbol: "ETH",
        url_key: "evm-base",
        tokens: &[token(
            "0x833589fCD6eDb6E08f4c7C32D4f71b54bdA02913",
            "USDC",
            6,
        )],
    },
    EvmNetwork {
        display_name: "Avalanche C-Chain",
        native_symbol: "AVAX",
        url_key: "evm-avalanche",
        tokens: &[
            token("0x9702230A8Ea53601f5cD2dc00fDBc13d4dF4A8c7", "USDT", 6),
            token("0xB97EF9Ef8734C71904D8002F8b6Bc66Dd9c48a6E", "USDC", 6),
        ],
    },
];

fn is_evm_address(address: &str) -> bool {
    address.len() == 42
        && address.starts_with("0x")
        && address[2..].bytes().all(|b| b.is_ascii_hexdigit())
}

/// The add-wallet gate: a typo is a 400 naming the expected format.
pub fn validate_address(address: &str) -> Result<(), String> {
    if is_evm_address(address) {
        return Ok(());
    }
    Err(format!(
        "Invalid EVM address '{}' (expected 0x followed by 40 hex characters)",
        for_message(address)
    ))
}

/// Bounds and flattens a rejected address before it is echoed or logged: truncated first (to
/// 64 UTF-16 units, as Java's substring), then every \s becomes a space.
fn for_message(address: &str) -> String {
    let mut bounded = String::new();
    let mut units = 0;
    let mut truncated = false;
    for c in address.chars() {
        units += c.len_utf16();
        if units > MESSAGE_ADDRESS_LIMIT {
            truncated = true;
            break;
        }
        bounded.push(c);
    }
    if truncated {
        bounded.push_str("...");
    }
    bounded
        .chars()
        .map(|c| {
            if matches!(c, ' ' | '\t' | '\n' | '\u{0B}' | '\u{0C}' | '\r') {
                ' '
            } else {
                c
            }
        })
        .collect()
}

pub async fn fetch_balances(
    state: &AppState,
    address: &str,
) -> Result<Vec<WalletBalance>, WalletError> {
    // A stored address is re-checked: one written before the add-time gate is a sync failure.
    if !is_evm_address(address) {
        return Err(WalletError::rpc(format!(
            "Malformed EVM address '{}' (expected 0x + 40 hex characters)",
            for_message(address)
        )));
    }
    let per_network = try_join_all(
        NETWORKS
            .iter()
            .map(|net| fetch_network(state, net, address)),
    )
    .await?;

    // The primary native leads whatever the completion order: the caller reads the first entry
    // as the account's display ticker. Its zero seed never becomes a holding.
    let mut by_symbol: Vec<(String, Decimal)> =
        vec![(NETWORKS[0].native_symbol.to_owned(), Decimal::ZERO)];
    for balance in per_network.into_iter().flatten() {
        if balance.amount <= Decimal::ZERO {
            continue;
        }
        match by_symbol.iter_mut().find(|(s, _)| *s == balance.symbol) {
            Some((_, amount)) => *amount += balance.amount,
            None => by_symbol.push((balance.symbol, balance.amount)),
        }
    }
    Ok(by_symbol
        .into_iter()
        .map(|(symbol, amount)| WalletBalance { symbol, amount })
        .collect())
}

async fn fetch_network(
    state: &AppState,
    net: &EvmNetwork,
    address: &str,
) -> Result<Vec<WalletBalance>, WalletError> {
    let url = state.config.sync_url(net.url_key);
    let native = fetch_native(state, url, net, address).await?;
    let tokens = join_all(
        net.tokens
            .iter()
            .map(|t| fetch_token(state, url, net, t, address)),
    )
    .await;
    let mut balances = vec![native];
    let mut failures = 0;
    for (token, outcome) in net.tokens.iter().zip(tokens) {
        match outcome {
            Ok(balance) => balances.push(balance),
            Err(e) => {
                failures += 1;
                tracing::warn!(
                    "EVM token {} on {} failed: {} -- skipping token",
                    token.symbol,
                    net.display_name,
                    e.message
                );
            }
        }
    }
    if failures > 0 {
        tracing::warn!(
            "EVM {}: {failures} of {} tokens failed -- balance reported without them",
            net.display_name,
            net.tokens.len()
        );
    }
    Ok(balances)
}

async fn fetch_native(
    state: &AppState,
    url: &str,
    net: &EvmNetwork,
    address: &str,
) -> Result<WalletBalance, WalletError> {
    let context = format!("{} eth_getBalance", net.display_name);
    let request = json!({
        "jsonrpc": "2.0",
        "id": 1,
        "method": "eth_getBalance",
        "params": [address, "latest"],
    });
    let result = call(state, url, &request, &context).await?;
    let wei = parse_hex(&as_text(&result), &context)?;
    Ok(WalletBalance::new(
        net.native_symbol,
        scaled(wei, NATIVE_DECIMALS, &context)?,
    ))
}

async fn fetch_token(
    state: &AppState,
    url: &str,
    net: &EvmNetwork,
    token: &Erc20Token,
    address: &str,
) -> Result<WalletBalance, WalletError> {
    let context = format!("{} balanceOf {}", net.display_name, token.symbol);
    let data = format!(
        "{BALANCE_OF_SELECTOR}{:0>64}",
        address[2..].to_ascii_lowercase()
    );
    let request = json!({
        "jsonrpc": "2.0",
        "id": 1,
        "method": "eth_call",
        "params": [{"to": token.contract, "data": data}, "latest"],
    });
    let result = call(state, url, &request, &context).await?;
    let hex = as_text(&result);
    // A call to a non-contract address answers "0x": no balance.
    if hex.chars().count() <= 2 {
        return Ok(WalletBalance::new(token.symbol, Decimal::ZERO));
    }
    let raw = parse_hex(&hex, &context)?;
    Ok(WalletBalance::new(
        token.symbol,
        scaled(raw, token.decimals, &context)?,
    ))
}

/// One RPC call with a per-attempt timeout and two retries on transport failures; a JSON-RPC
/// error payload is an answer, never retried, and an empty body is a failure.
async fn call(
    state: &AppState,
    url: &str,
    request: &Value,
    context: &str,
) -> Result<Value, WalletError> {
    let mut attempt = 0;
    loop {
        match rpc::send(rpc::post_json(state, url, request)).await {
            Ok(Some(body)) => return rpc::require_result(&body, context).cloned(),
            Ok(None) => {
                return Err(WalletError::rpc(format!(
                    "{context}: RPC returned no response"
                )));
            }
            Err(e) if attempt >= MAX_RETRIES => {
                return Err(WalletError::rpc(format!("{context}: RPC failed - {}", e.0)));
            }
            Err(_) => {
                tokio::time::sleep(RETRY_BACKOFF * 2u32.pow(attempt)).await;
                attempt += 1;
            }
        }
    }
}

/// new BigInteger(hex.substring(2), 16) after the 0x check.
fn parse_hex(hex: &str, context: &str) -> Result<i128, WalletError> {
    let malformed = || WalletError::rpc(format!("{context}: malformed hex '{hex}'"));
    let digits = hex.strip_prefix("0x").ok_or_else(malformed)?;
    let (negative, digits) = match digits.as_bytes().first() {
        Some(b'-') => (true, &digits[1..]),
        Some(b'+') => (false, &digits[1..]),
        _ => (false, digits),
    };
    if digits.is_empty() || !digits.bytes().all(|b| b.is_ascii_hexdigit()) {
        return Err(malformed());
    }
    let significant = digits.trim_start_matches('0');
    let magnitude = if significant.is_empty() {
        0
    } else {
        i128::from_str_radix(significant, 16).map_err(|_| out_of_range(context))?
    };
    Ok(if negative { -magnitude } else { magnitude })
}

fn out_of_range(context: &str) -> WalletError {
    WalletError::rpc(format!("{context}: balance out of range"))
}

/// raw / 10^decimals at that scale, exact.
fn scaled(raw: i128, decimals: u32, context: &str) -> Result<Decimal, WalletError> {
    Decimal::try_from_i128_with_scale(raw, decimals).map_err(|_| out_of_range(context))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn validates_the_address_format() {
        assert!(validate_address("0x52908400098527886E0F7030069857D2E4169EE7").is_ok());
        assert!(validate_address("0x52908400098527886e0f7030069857d2e4169ee7").is_ok());
        assert!(validate_address("0xnope").is_err());
        assert!(validate_address("52908400098527886E0F7030069857D2E4169EE7ab").is_err());
    }

    #[test]
    fn bounds_and_flattens_the_echoed_address() {
        let long = format!("0x{}\n{}", "a".repeat(70), "b".repeat(10));
        assert_eq!(for_message(&long), format!("0x{}...", "a".repeat(62)));
        assert_eq!(for_message("0x\tno\npe"), "0x no pe");
    }

    #[test]
    fn parses_hex_like_big_integer() {
        assert_eq!(parse_hex("0x0", "c").unwrap(), 0);
        assert_eq!(
            parse_hex("0xde0b6b3a7640000", "c").unwrap(),
            1_000_000_000_000_000_000
        );
        assert_eq!(
            parse_hex(&format!("0x{}{}", "0".repeat(60), "0f42"), "c").unwrap(),
            3906
        );
        assert!(parse_hex("123", "c").is_err());
        assert!(parse_hex("0x", "c").is_err());
        assert!(parse_hex("0xzz", "c").is_err());
        assert_eq!(scaled(1_500_000, 6, "c").unwrap().to_string(), "1.500000");
    }
}

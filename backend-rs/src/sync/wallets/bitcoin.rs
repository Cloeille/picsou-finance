//! BitcoinWalletAdapter over the Esplora API: a plain address, or an extended key (xpub, ypub,
//! zpub, or an output descriptor) whose BIP84 addresses are scanned on the external and change
//! chains until GAP_LIMIT consecutive unused ones.

use percent_encoding::{AsciiSet, CONTROLS, utf8_percent_encode};
use rust_decimal::Decimal;

use super::bitcoin_keys::{self, GAP_LIMIT, Xpub};
use super::rpc::{self, WalletBalance, WalletError, as_long};
use crate::AppState;

const SATS_SCALE: u32 = 8;

/// What a URI template encodes in one path segment.
const PATH_SEGMENT: &AsciiSet = &CONTROLS
    .add(b' ')
    .add(b'"')
    .add(b'#')
    .add(b'%')
    .add(b'/')
    .add(b'<')
    .add(b'>')
    .add(b'?')
    .add(b'[')
    .add(b'\\')
    .add(b']')
    .add(b'^')
    .add(b'`')
    .add(b'{')
    .add(b'|')
    .add(b'}');

pub async fn fetch_balances(
    state: &AppState,
    address: &str,
) -> Result<Vec<WalletBalance>, WalletError> {
    let sats = if bitcoin_keys::is_extended_key(address) {
        // An invalid key is an error, never a 0 BTC balance.
        let xpub = bitcoin_keys::normalize_to_xpub(address).map_err(WalletError::unexpected)?;
        let root = bitcoin_keys::parse_xpub(&xpub).map_err(WalletError::unexpected)?;
        let mut total = 0i64;
        for (chain, name) in [(0, "external"), (1, "change")] {
            let chain_key =
                bitcoin_keys::derive_child(&root, chain).map_err(WalletError::unexpected)?;
            total += scan_chain(state, &chain_key, name).await?;
        }
        tracing::info!("Bitcoin HD wallet balance: {total} sats");
        total
    } else {
        fetch_address_stats(state, address).await?.balance_sats
    };
    Ok(vec![WalletBalance::new(
        "BTC",
        Decimal::from_i128_with_scale(i128::from(sats), SATS_SCALE),
    )])
}

async fn scan_chain(state: &AppState, chain_key: &Xpub, name: &str) -> Result<i64, WalletError> {
    let mut total = 0i64;
    let mut consecutive_unused = 0;
    let mut index = 0;
    while consecutive_unused < GAP_LIMIT {
        let child =
            bitcoin_keys::derive_child(chain_key, index).map_err(WalletError::unexpected)?;
        let address = bitcoin_keys::to_p2wpkh_address(&child.pub_key);
        let stats = fetch_address_stats(state, &address).await?;
        if stats.tx_count > 0 {
            consecutive_unused = 0;
            total += stats.balance_sats;
        } else {
            consecutive_unused += 1;
        }
        index += 1;
    }
    tracing::info!(
        "Bitcoin {name} chain: scanned {index} addresses, {} with history",
        index - consecutive_unused
    );
    Ok(total)
}

struct AddressStats {
    balance_sats: i64,
    tx_count: i64,
}

async fn fetch_address_stats(state: &AppState, address: &str) -> Result<AddressStats, WalletError> {
    let base = state.config.sync_url("bitcoin").trim_end_matches('/');
    let url = format!(
        "{base}/api/address/{}",
        utf8_percent_encode(address, PATH_SEGMENT)
    );
    let response = rpc::send(state.http.get(url).header("Accept", "application/json"))
        .await
        .map_err(|e| WalletError::unexpected(format!("Esplora call failed - {}", e.0)))?;
    // An empty body reads as an unused address, as the Java adapter does.
    let Some(response) = response else {
        return Ok(AddressStats {
            balance_sats: 0,
            tx_count: 0,
        });
    };
    let stats = response.get("chain_stats");
    let field = |name: &str| as_long(stats.and_then(|s| s.get(name)));
    Ok(AddressStats {
        balance_sats: field("funded_txo_sum").wrapping_sub(field("spent_txo_sum")),
        tx_count: field("tx_count"),
    })
}

//! SolanaWalletAdapter: SOL from getBalance plus the known SPL stablecoins.

use rust_decimal::Decimal;
use serde_json::{Value, json};

use super::rpc::{self, WalletBalance, WalletError, as_long, parse_decimal, text_or};
use crate::AppState;

const SPL_TOKEN_PROGRAM_ID: &str = "TokenkegQfeZyiNwAJbNbGKPFXCWuBvf9Ss623VQ5DA";
const LAMPORTS_SCALE: u32 = 9;

/// Mints we can price; an unknown mint has no EUR value and is skipped.
fn known_mint(mint: &str) -> Option<&'static str> {
    match mint {
        "EPjFWdd5AufqSSqeM2qN1xzybapC8G4wEGGkZwyTDt1v" => Some("USDC"),
        "HzwqbKZw8HxMN6bF2yFZNrht3c2iXXzpKcFu7uBEDKtr" => Some("EURC"),
        "Es9vMFrzaCERmJfrF4H2FYD4KCoNkY11McCe8BenwNYB" => Some("USDT"),
        _ => None,
    }
}

pub async fn fetch_balances(
    state: &AppState,
    address: &str,
) -> Result<Vec<WalletBalance>, WalletError> {
    let mut balances = vec![fetch_sol(state, address).await?];
    balances.extend(fetch_spl_tokens(state, address).await?);
    Ok(balances)
}

async fn call(state: &AppState, request: &Value) -> Result<Option<Value>, WalletError> {
    rpc::send(rpc::post_json(
        state,
        state.config.sync_url("solana"),
        request,
    ))
    .await
    .map_err(|e| WalletError::unexpected(format!("Solana RPC failed - {}", e.0)))
}

async fn fetch_sol(state: &AppState, address: &str) -> Result<WalletBalance, WalletError> {
    let request = json!({"jsonrpc": "2.0", "id": 1, "method": "getBalance", "params": [address]});
    let response = call(state, &request).await?.unwrap_or(Value::Null);
    let context = "Solana getBalance";
    if response.is_null() {
        return Err(WalletError::rpc(format!(
            "{context}: RPC returned no response"
        )));
    }
    let result = rpc::require_result(&response, context)?;
    let lamports = as_long(result.get("value"));
    Ok(WalletBalance::new(
        "SOL",
        Decimal::from_i128_with_scale(i128::from(lamports), LAMPORTS_SCALE),
    ))
}

/// One balance per non-zero, recognised SPL token account. A corrupt amount skips that token
/// rather than hiding SOL and every other token.
async fn fetch_spl_tokens(
    state: &AppState,
    address: &str,
) -> Result<Vec<WalletBalance>, WalletError> {
    let request = json!({
        "jsonrpc": "2.0",
        "id": 2,
        "method": "getTokenAccountsByOwner",
        "params": [address, {"programId": SPL_TOKEN_PROGRAM_ID}, {"encoding": "jsonParsed"}],
    });
    let response = call(state, &request).await?.unwrap_or(Value::Null);
    let context = "Solana getTokenAccountsByOwner";
    if response.is_null() {
        return Err(WalletError::rpc(format!(
            "{context}: RPC returned no response"
        )));
    }
    let Some(accounts) = rpc::require_result(&response, context)?
        .get("value")
        .and_then(Value::as_array)
    else {
        tracing::warn!("Solana getTokenAccountsByOwner returned a non-array 'value'");
        return Ok(Vec::new());
    };
    let mut tokens = Vec::new();
    for account in accounts {
        let info = account
            .get("account")
            .and_then(|a| a.get("data"))
            .and_then(|d| d.get("parsed"))
            .and_then(|p| p.get("info"));
        let mint = text_or(info.and_then(|i| i.get("mint")), "");
        let Some(symbol) = known_mint(&mint) else {
            continue;
        };
        let ui_amount = text_or(
            info.and_then(|i| i.get("tokenAmount"))
                .and_then(|t| t.get("uiAmountString")),
            "0",
        );
        let Some(amount) = parse_decimal(&ui_amount) else {
            tracing::error!("Failed to parse SPL token balance for mint {mint}: '{ui_amount}'");
            continue;
        };
        if amount <= Decimal::ZERO {
            continue;
        }
        tokens.push(WalletBalance::new(symbol, amount));
    }
    Ok(tokens)
}

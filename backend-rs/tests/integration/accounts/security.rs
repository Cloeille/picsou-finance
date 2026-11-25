//! The security insight endpoint without market sources: the crypto registry alone decides.

use picsou::testing::TestApp;
use serde_json::json;

use super::analysis_support::{get_json, signed_in};

#[tokio::test]
async fn a_registered_coin_is_crypto_and_anything_else_unknown_without_a_quote_source() {
    let app = TestApp::with_admin().await;
    let (_, client) = signed_in(&app, "alice").await;
    assert_eq!(
        get_json(&client, "/api/securities/eth/insight").await,
        json!({"ticker": "ETH", "assetType": "CRYPTO"})
    );
    assert_eq!(
        get_json(&client, "/api/securities/PSUNKNOWN/insight?name=Amundi").await,
        json!({"ticker": "PSUNKNOWN", "assetType": "UNKNOWN"})
    );
    assert_eq!(
        app.client()
            .get("/api/securities/ETH/insight")
            .send()
            .await
            .status,
        401
    );
}

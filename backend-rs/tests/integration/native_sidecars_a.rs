//! Integration & parser contract tests for Phase 3A native Rust sidecars:
//! DEGIRO, CMES, Sofidy, and CIC (+ shared Euro-Information client).
//!
//! All upstream bank calls are stubbed with `wiremock` (synthetic fixtures only, zero internet).

use std::sync::Arc;
use std::sync::atomic::{AtomicUsize, Ordering};
use std::time::{Duration, Instant};

use http::{Method, StatusCode};
use picsou::connectors::sidecar::base_url;
use picsou::ledger::sidecar::SidecarBank;
use picsou::native_sidecars::NativeSidecar;
use picsou::native_sidecars::cic::{self, NativeCic};
use picsou::native_sidecars::cmes::{self, NativeCmes};
use picsou::native_sidecars::degiro::{self, NativeDegiro, RawPosition};
use picsou::native_sidecars::euro_info;
use picsou::native_sidecars::sofidy::{self, NativeSofidy};
use picsou::testing::{ADMIN_USERNAME, Client, TestApp};
use rust_decimal::Decimal;
use serde_json::{Map, Value, json};
use wiremock::matchers::{method, path, query_param};
use wiremock::{Mock, MockServer, Request, Respond, ResponseTemplate};

fn d(s: &str) -> Decimal {
    s.parse().expect("valid decimal literal")
}

fn n(s: &str) -> Value {
    serde_json::from_str(s).expect("valid JSON number")
}

async fn admin_client(app: &TestApp) -> (Client, i64) {
    let client = app.login_admin().await;
    let member_id = picsou::db::query_scalar("SELECT member_id FROM app_user WHERE username = $1")
        .bind(ADMIN_USERNAME)
        .fetch_one(&app.state.db)
        .await
        .expect("admin member");
    (client, member_id)
}

async fn settle(app: &TestApp) {
    app.state.connectors.jobs.wait_idle().await;
}

// ─── Base URL validation for native:// ──────────────────────────────────────

#[test]
fn base_url_accepts_native_schemes_and_rejects_malformed_ones() {
    for valid in [
        "native",
        "NATIVE",
        "native://degiro",
        "native://degiro-auth",
        "native://cmes-auth",
        "native://sofidy-auth",
        "native://cic-auth",
    ] {
        assert!(base_url::validate("x", valid).is_ok(), "{valid}");
    }
    for bad in [
        "native://",
        "native://user:pass@degiro-auth",
        "native://degiro-auth?x=1",
        "native://degiro-auth#frag",
        "native://degiro.example.com",
    ] {
        assert!(base_url::validate("x", bad).is_err(), "{bad}");
    }
}

// ============================================================================
// 1. DEGIRO
// ============================================================================

#[test]
fn degiro_portfolio_parser_handles_live_quirks_and_enrichment() {
    // is_real_product_id
    assert!(degiro::is_real_product_id(Some(&json!("15690087"))));
    assert!(degiro::is_real_product_id(Some(&json!(15690087))));
    assert!(!degiro::is_real_product_id(Some(&json!("FLATEX_EUR"))));
    assert!(!degiro::is_real_product_id(None));
    assert!(!degiro::is_real_product_id(Some(&Value::Null)));
    assert!(!degiro::is_real_product_id(Some(&json!("NULL"))));

    // sanitize_product_info
    let sanitized = degiro::sanitize_product_info(&json!({
        "isin": "NULL",
        "symbol": "  ",
        "name": "Real Name",
        "closePrice": "NULL"
    }));
    assert_eq!(sanitized.isin, None);
    assert_eq!(sanitized.symbol, None);
    assert_eq!(sanitized.name.as_deref(), Some("Real Name"));
    assert_eq!(sanitized.close_price, None);

    let sanitized_ci = degiro::sanitize_product_info(&json!({
        "isin": "null",
        "symbol": "Null",
        "name": Value::Null,
        "closePrice": n("38.0")
    }));
    assert_eq!(sanitized_ci.isin, None);
    assert_eq!(sanitized_ci.symbol, None);
    assert_eq!(sanitized_ci.close_price, Some(d("38.0")));

    // value_pairs_to_dict
    let pairs = degiro::value_pairs_to_dict(&json!({
        "id": "1",
        "value": [
            {"name": "size", "value": 10},
            {"name": "price", "value": n("42.5")},
            "garbage"
        ]
    }));
    assert_eq!(pairs.get("size"), Some(&json!(10)));
    assert_eq!(pairs.get("price"), Some(&n("42.5")));

    // parse_cash_eur
    assert_eq!(
        degiro::parse_cash_eur(&[
            json!({"value": [{"name": "currencyCode", "value": "USD"}, {"name": "value", "value": n("100.0")}]}),
            json!({"value": [{"name": "currencyCode", "value": "EUR"}, {"name": "value", "value": n("250.5")}]}),
        ]),
        d("250.5")
    );
    assert_eq!(degiro::parse_cash_eur(&[]), Decimal::ZERO);

    // parse_raw_positions: filters zero size and FLATEX_EUR
    let raw = degiro::parse_raw_positions(&[
        json!({"id": "FLATEX_EUR", "value": [{"name": "size", "value": n("250.0")}, {"name": "price", "value": 1}]}),
        json!({"id": "1", "value": [{"name": "size", "value": 0}, {"name": "price", "value": 10}]}),
        json!({"id": "15690087", "value": [
            {"name": "size", "value": n("290.67")},
            {"name": "price", "value": n("121.36")},
            {"name": "breakEvenPrice", "value": n("69.48")}
        ]}),
    ]);
    assert_eq!(raw.len(), 1);
    assert_eq!(raw[0].product_id.as_deref(), Some("15690087"));
    assert_eq!(raw[0].size, d("290.67"));
    assert_eq!(raw[0].break_even_price, d("69.48"));

    // build_product_info_map & build_positions end-to-end
    let mut info_map = Map::new();
    info_map.insert(
        "FLATEX_EUR".into(),
        json!({"isin": Value::Null, "symbol": Value::Null, "name": "Cash"}),
    );
    info_map.insert(
        "15690087".into(),
        json!({
            "isin": "IE00BGSF1X88",
            "symbol": "IB01",
            "name": "iShares Treasury Bond",
            "closePrice": n("125.00")
        }),
    );
    let products = degiro::build_product_info_map(&info_map);
    assert!(!products.contains_key("FLATEX_EUR"));
    assert!(products.contains_key("15690087"));

    let built = degiro::build_positions(&raw, &products);
    assert_eq!(built.len(), 1);
    assert_eq!(built[0].isin.as_deref(), Some("IE00BGSF1X88"));
    assert_eq!(built[0].symbol, "IB01");
    assert_eq!(built[0].current_price, d("125.00"));
    assert_eq!(built[0].buying_price, d("69.48"));

    // Fallback when closePrice is "NULL" or product info missing
    let mut null_close = Map::new();
    null_close.insert(
        "123".into(),
        json!({"isin": "IE00B4L5Y983", "symbol": "IWDA", "name": "iShares", "closePrice": "NULL"}),
    );
    let built_null = degiro::build_positions(
        &[RawPosition {
            product_id: Some("123".into()),
            size: d("10"),
            price: d("42"),
            break_even_price: d("38"),
        }],
        &degiro::build_product_info_map(&null_close),
    );
    assert_eq!(built_null[0].current_price, d("42"));

    let built_missing = degiro::build_positions(
        &[RawPosition {
            product_id: Some("999".into()),
            size: d("1"),
            price: d("5"),
            break_even_price: d("4.5"),
        }],
        &std::collections::HashMap::new(),
    );
    assert_eq!(built_missing[0].isin, None);
    assert_eq!(built_missing[0].symbol, "999");
    assert_eq!(built_missing[0].name, "999");
}

#[tokio::test]
async fn degiro_portfolio_url_rejects_malicious_session_blobs() {
    let bank = MockServer::start().await;
    let degiro = NativeDegiro::with_base_url(&bank.uri());
    let valid_sid = "8F1A2B3C4D5E6F708192A3B4C5D6E7F8.prod_b_112_1";

    for bad_acct in [
        json!("1/../../pa/secure/client"),
        json!("1?x=1"),
        json!("1#f"),
        json!("abc"),
        Value::Null,
        json!([1]),
        json!({"a": 1}),
    ] {
        let blob = json!({"sessionId": valid_sid, "intAccount": bad_acct}).to_string();
        let err = degiro
            .call(Method::POST, "/portfolio", json!({"sessionBlob": blob}))
            .await
            .expect_err("malicious intAccount must fail");
        assert_eq!(err.status, StatusCode::BAD_REQUEST);
    }

    let long_sid = "x".repeat(257);
    for bad_sid in [
        json!("a/b"),
        json!("a?b=1"),
        json!("a#b"),
        json!("a%2Fb"),
        json!("a;b"),
        json!("a@b"),
        json!("a b"),
        json!("a\nb"),
        json!(""),
        json!(123),
        Value::Null,
        json!(long_sid),
    ] {
        let blob = json!({"sessionId": bad_sid, "intAccount": 31415926}).to_string();
        let err = degiro
            .call(Method::POST, "/portfolio", json!({"sessionBlob": blob}))
            .await
            .expect_err("malicious sessionId must fail");
        assert_eq!(err.status, StatusCode::BAD_REQUEST);
    }

    // Zero requests reached upstream bank
    assert!(bank.received_requests().await.unwrap().is_empty());

    // Valid numeric string intAccount reaches the exact expected path
    Mock::given(method("GET"))
        .and(path(format!(
            "/trading/secure/v5/update/31415926;jsessionid={valid_sid}"
        )))
        .respond_with(ResponseTemplate::new(401))
        .expect(1)
        .mount(&bank)
        .await;

    let blob = json!({"sessionId": valid_sid, "intAccount": "31415926"}).to_string();
    let err = degiro
        .call(Method::POST, "/portfolio", json!({"sessionBlob": blob}))
        .await
        .expect_err("401 from bank");
    assert_eq!(err.status, StatusCode::UNAUTHORIZED);
}

#[tokio::test]
async fn degiro_end_to_end_native_through_test_app() {
    let bank = MockServer::start().await;
    let sid = "ABC123.prod_1";

    // Step 1: initial login asks for TOTP
    Mock::given(method("POST"))
        .and(path("/login/secure/login"))
        .respond_with(ResponseTemplate::new(400).set_body_json(json!({
            "status": 6,
            "statusText": "totpNeeded"
        })))
        .mount(&bank)
        .await;

    // Step 2: TOTP login sets JSESSIONID cookie
    Mock::given(method("POST"))
        .and(path("/login/secure/login/totp"))
        .respond_with(
            ResponseTemplate::new(200)
                .insert_header("Set-Cookie", format!("JSESSIONID={sid}; Path=/"))
                .set_body_json(json!({"status": 0, "statusText": "success"})),
        )
        .mount(&bank)
        .await;

    // Step 3: /pa/secure/client resolves intAccount
    Mock::given(method("GET"))
        .and(path("/pa/secure/client"))
        .and(query_param("sessionId", sid))
        .respond_with(
            ResponseTemplate::new(200).set_body_json(json!({"data": {"intAccount": 998877}})),
        )
        .mount(&bank)
        .await;

    // Step 4: portfolio update + product info
    Mock::given(method("GET"))
        .and(path(format!(
            "/trading/secure/v5/update/998877;jsessionid={sid}"
        )))
        .respond_with(ResponseTemplate::new(200).set_body_json(json!({
            "cashFunds": {
                "value": [
                    {"value": [{"name": "currencyCode", "value": "EUR"}, {"name": "value", "value": n("125.50")}]}
                ]
            },
            "portfolio": {
                "value": [
                    {"id": "FLATEX_EUR", "value": [{"name": "size", "value": n("125.50")}, {"name": "price", "value": 1}]},
                    {"id": "15690087", "value": [
                        {"name": "size", "value": n("10")},
                        {"name": "price", "value": n("40.00")},
                        {"name": "breakEvenPrice", "value": n("35.00")}
                    ]}
                ]
            }
        })))
        .mount(&bank)
        .await;

    Mock::given(method("POST"))
        .and(path("/product_search/secure/v5/products/info"))
        .respond_with(ResponseTemplate::new(200).set_body_json(json!({
            "data": {
                "15690087": {
                    "isin": "IE00B4L5Y983",
                    "symbol": "IWDA",
                    "name": "iShares Core MSCI World",
                    "closePrice": n("45.00")
                }
            }
        })))
        .mount(&bank)
        .await;

    let app = TestApp::builder()
        .with_admin()
        .env("DEGIRO_AUTH_URL", "native://degiro-auth")
        .env("DEGIRO_BASE_URL", bank.uri())
        .spawn()
        .await;
    let (client, _) = admin_client(&app).await;

    let init = client
        .post("/api/degiro/auth/initiate")
        .json(&json!({"username": "demo", "password": "secret"}))
        .send()
        .await;
    assert_eq!(init.status, 200, "{}", init.text());
    let init_json = init.json();
    assert_eq!(init_json["totpRequired"], true);
    let process_id = init_json["processId"].as_str().unwrap().to_owned();

    let comp = client
        .post("/api/degiro/auth/complete")
        .json(&json!({"processId": process_id, "code": "123456"}))
        .send()
        .await;
    assert_eq!(comp.status, 200, "{}", comp.text());
    assert_eq!(comp.json()["isActive"], true);

    // Account balance = 125.50 cash + 10 * 45.00 = 575.50
    let (balance, cash): (Decimal, Decimal) = picsou::db::query_as(
        "SELECT current_balance, cash_balance FROM account WHERE external_account_id = 'degiro-portfolio'",
    )
    .fetch_one(&app.state.db)
    .await
    .unwrap();
    assert_eq!((balance, cash), (d("575.50"), d("125.50")));

    let (qty, buy_in, price): (Decimal, Decimal, Decimal) = picsou::db::query_as(
        "SELECT quantity, average_buy_in, current_price FROM account_holding LIMIT 1",
    )
    .fetch_one(&app.state.db)
    .await
    .unwrap();
    assert_eq!((qty, buy_in, price), (d("10"), d("35.00"), d("45.00")));
}

// ============================================================================
// 2. CMES
// ============================================================================

const CMES_LOGOUT: &str =
    r#"<a href="/epargne-salariale/fr/epargnants/deconnexion/deconnexion.html">Déconnexion</a>"#;

const CMES_LOGIN_PAGE: &str = r#"
<html><body>
<form method="post" autocomplete="off" name="bloc_ident" id="bloc_ident">
  <input type="text" id="_userid" name="_cm_user" value="" />
  <input type="hidden" name="flag" value="password">
  <input type="hidden" name="_charset_">
  <input type="password" name="_cm_pwd" id="_pwduser" />
</form>
</body></html>"#;

fn cmes_wrong_password_page() -> String {
    CMES_LOGIN_PAGE.replace(
        "<body>",
        r#"<body><div class="blocmsg err"><p>Votre identifiant ou mot de passe est incorrect.</p></div>"#,
    )
}

fn cmes_fund_row(index: usize, label: &str, value: &str, gain: &str, sign: &str) -> String {
    format!(
        r##"
<tr id="I0:P3:F_{index}.L10:root">
  <td class="i eir_tblcaption">
    <a id="I0:P3:F_{index}.L0" href="#" class="_c1 es_fund-clickable _c1 needscript">{label}</a>
    <input type="submit" value="{label}" name="_FID_GoFundSummary_selectedFundIndex:{index}" class="hideifscript" />
  </td>
  <td class="i eir_tblline d mntt"><div class="eir_tblcnt">
    <span class="eir_tblcnt_header"><span aria-hidden="true">Montant total estim&#233;</span></span>
    <span class="_c1 nowrap _c1">{value} &#8364; <a href="#1" class="renvoi"><span class="ei_sronly">Note :</span> 1</a></span>
  </div></td>
  <td class="i eir_tblline d mntt"><div class="eir_tblcnt">
    <span class="eir_tblcnt_header"><span aria-hidden="true">Plus ou moins values</span></span>
    <span class="ei_hdet_itemtext ei_iblock"><span class="_c1 {sign} nowrap es_pvl _c1">{gain} %</span></span>
  </div></td>
  <td class="i a_actions nowrap c"><div class="ei_actionsblock"></div></td>
</tr>"##
    )
}

fn cmes_plan_table(label: &str, total: &str, rows: &str) -> String {
    format!(
        r#"
<table class="_c1 tb_accordeon _c1 liste ei_cellspacing" style="width:100%;">
<tbody>
<tr id="I0:P3:T0" class="_c1 ei_row_show pointer _c1 ___TrExpander">
  <th class="rupture _c1 nowrap _c1"><div> {label} </div></th>
  <td class="datarupture i"><div><span>Disponible</span><br /><span class="_c1 em nowrap _c1">0,00 &#8364;</span></div></td>
  <td class="datarupture i"><div><span>Bloqu&#233;</span><br /><span class="_c1 em nowrap _c1">{total} &#8364;</span></div></td>
  <td class="datarupture i"><span>Montant total estim&#233;</span><br /><span class="_c1 em nowrap _c1">{total} &#8364;</span></td>
</tr>
<tr id="I0:P3:T1"><td colspan="5">
<table id="I0:P3:L:root" class="eir_xs_listtable liste ei_cellspacing" style="width:100%;">
<tbody>{rows}</tbody>
</table>
</td></tr>
</tbody>
</table>"#
    )
}

fn cmes_situation_page(plans: &str) -> String {
    format!(
        r#"
<html><body>
<div class="ei_tpl_profil_content">
  <p class="_c1 ei_profil_entrep _c1">ACME INDUSTRIES <span>12345</span></p>
</div>
{CMES_LOGOUT}
<form id="I0:P3:F" action="/epargne-salariale/fr/epargnants/mon-epargne/situation-financiere-detaillee/index.html?_tabi=I0&amp;_pid=FinancialSituation" method="post">
  <input name="$CPT" type="hidden" value="opaque-page-state" />
  {plans}
</form>
</body></html>"#
    )
}

fn cmes_default_situation() -> String {
    let rows = format!(
        "{}{}",
        cmes_fund_row(
            0,
            "FONDS EQUILIBRE SOLIDAIRE",
            "1&nbsp;000,00",
            "+ 10,00",
            "pos"
        ),
        cmes_fund_row(1, "FONDS ACTIONS MONDE", "500,50", "-2,50", "neg"),
    );
    cmes_situation_page(&cmes_plan_table(
        "Plan d&#39;&#233;pargne groupe",
        "1&nbsp;500,50",
        &rows,
    ))
}

fn cmes_fund_summary(index: usize, code: &str) -> String {
    let base = format!(
        "/epargne-salariale/fr/epargnants/supports/synthese-du-support.html?_tabi=C&amp;_pid=FundSummary&amp;k_fundIndex={index}"
    );
    format!(
        r#"<html><body>
<nav><a href="/epargne-salariale/fr/epargnants/mon-epargne/index.html">Mon &#233;pargne</a></nav>
{CMES_LOGOUT}
<ul id="C:T3:tabslist" class="ei_newtabs">
  <li class="sel"><span>Synth&#232;se</span></li>
  <li><a href="{base}&amp;_fid=GoMyAsset">Mon &#233;pargne</a></li>
</ul>
<table class="fiche"><tbody>
<tr><th scope="row">Code du support</th><td>{code}</td></tr>
</tbody></table>
</body></html>"#
    )
}

fn cmes_fund_savings(value: &str, gain: &str, units: &str, price: &str) -> String {
    format!(
        r#"<html><body>{CMES_LOGOUT}
<table class="saisie fiche"><tbody>
<tr><th scope="row">Montant estim&#233;</th><td>{value} &#160; &#8364;</td></tr>
<tr><th scope="row">Dont plus ou moins value</th><td><em><span class="_c1 es_pvl pos nowrap _c1">{gain} &#160; &#8364;</span></em></td></tr>
<tr><th scope="row">Nombre de parts</th><td><em>{units}</em></td></tr>
<tr><th scope="row">Valeur de la part au 22/10/2026</th><td><em>{price} &#160; &#8364;</em></td></tr>
</tbody></table></body></html>"#
    )
}

const CMES_MOBILE_MFA: &str = r#"<html><body>
<form id="C:P:F" action="/epargne-salariale/fr/epargnants/premiers-pas/authentification-forte/index.html?_tabi=C" method="post">
  <input name="$CPT" type="hidden" value="opaque-page-state" />
  <input type="submit" name="_FID_DoValidate" value="Continuer" />
</form>
<script>
  $dv.O('C:P:polling', 'OtpPolling', {transactionId: '0123456789abcdef', getTransactionStateUrl: '/epargne-salariale/fr/async/otp/SOSD_OTP_GetTransactionState.htm', cancelUrl: '/epargne-salariale/fr/async/otp/SOSD_OTP_CancelTransaction.htm'});
</script></body></html>"#;

const CMES_SMS_MFA: &str = r#"<html><body>
<form id="C:P:F" action="/epargne-salariale/fr/epargnants/premiers-pas/authentification-forte/index.html?_tabi=C" method="post">
  <input type="hidden" name="otp_hidden" value="opaque-otp-state" />
  <input type="text" name="otp_password" maxlength="6" />
  <input type="submit" name="_FID_CheckOrDeliverBackup" value="Valider" />
  <input name="$CPT" type="hidden" value="opaque-page-state" />
</form></body></html>"#;

const CMES_SMS_WRONG: &str = r#"<html><body>
<form id="C:P:F" action="/epargne-salariale/fr/epargnants/premiers-pas/authentification-forte/index.html?_tabi=C" method="post">
  <div class="bloctxt err">Le code de confirmation saisi est erroné.</div>
  <input type="hidden" name="otp_hidden" value="opaque-otp-state" />
  <input type="text" name="otp_password" maxlength="6" />
  <input type="submit" name="_FID_CheckOrDeliverBackup" value="Valider" />
  <input name="$CPT" type="hidden" value="opaque-page-state" />
</form></body></html>"#;

#[test]
fn cmes_parsers_match_python_contract() {
    assert_eq!(
        cmes::parse_money("22&nbsp;427,72 &#160; &#8364;").unwrap(),
        d("22427.72")
    );
    assert_eq!(
        cmes::parse_money("+4\u{202f}171,84 €").unwrap(),
        d("4171.84")
    );
    assert_eq!(cmes::parse_money("-12,83 €").unwrap(), d("-12.83"));
    assert_eq!(cmes::parse_percent("+ 22,85 %").unwrap(), d("22.85"));
    assert_eq!(cmes::parse_percent("-1,43 %").unwrap(), d("-1.43"));
    for bad in ["", "N/A", "1 234,56", "1 234,56 USD"] {
        assert!(cmes::parse_money(bad).is_err(), "{bad}");
    }

    let sit = cmes_default_situation();
    assert_eq!(
        cmes::parse_profile(&sit).unwrap(),
        (Some("ACME INDUSTRIES".into()), "12345".into())
    );
    let plans = cmes::parse_situation(&sit).unwrap();
    assert_eq!(plans.len(), 1);
    assert_eq!(plans[0].name, "Plan d'épargne groupe");
    assert_eq!(plans[0].plan_kind.as_deref(), Some("PEG"));
    assert_eq!(plans[0].balance_eur, d("1500.50"));
    assert_eq!(plans[0].funds.len(), 2);
    assert_eq!(plans[0].funds[0].value_eur, d("1000.00"));
    assert_eq!(plans[0].funds[1].gain_percent, d("-2.50"));

    // Plan external IDs & legacy IDs
    let id_a = cmes::plan_external_id("12345", "Plan d'épargne entreprise ACME+A");
    let id_b = cmes::plan_external_id("12345", "Plan d'épargne entreprise ACME A");
    assert_ne!(id_a, id_b);
    assert!(
        cmes::plan_external_id("12345", "Plan d'épargne entreprise")
            .starts_with("cmes_12345_plan-d-pargne-entreprise-")
    );
    assert_eq!(
        cmes::legacy_external_ids("12345", "Plan d'épargne groupe ACME", Some("PEG")),
        vec!["cmes_12345_PEG", "cmes_12345_PEG-plan-d-pargne-groupe-acme"]
    );

    // Mismatched total -> PORTFOLIO_INCOMPLETE
    let bad_total = cmes_situation_page(&cmes_plan_table(
        "Plan d&#39;&#233;pargne groupe",
        "9&nbsp;999,99",
        &cmes_fund_row(0, "FONDS", "100,00", "+1,00", "pos"),
    ));
    assert_eq!(
        cmes::parse_situation(&bad_total).unwrap_err().code,
        cmes::INCOMPLETE
    );

    // Empty plan kept with 0 balance
    let empty_plan = cmes_situation_page(&cmes_plan_table(
        "Plan d&#39;&#233;pargne entreprise",
        "0,00",
        "",
    ));
    let empty_parsed = cmes::parse_situation(&empty_plan).unwrap();
    assert_eq!(empty_parsed[0].balance_eur, d("0.00"));
    assert!(empty_parsed[0].funds.is_empty());

    // Fund parsers
    let savings_html = cmes_fund_savings("1&nbsp;000,00", "+90,91", "50,0000", "20,0000");
    let details = cmes::parse_my_savings(&savings_html).unwrap();
    assert_eq!(details.quantity, d("50.0000"));
    assert_eq!(details.unit_value, d("20.0000"));
    assert_eq!(details.pnl_eur, d("90.91"));
    assert_eq!(details.price_date.unwrap().to_string(), "2026-10-22");

    // Units mismatch refused
    let mismatch = cmes_fund_savings("1&nbsp;000,00", "+90,91", "5,0000", "20,0000");
    assert!(cmes::parse_my_savings(&mismatch).is_err());

    // Tab link vs header link + fund identity
    let summary = cmes_fund_summary(1, "1613");
    let tab = cmes::my_savings_tab(&summary).unwrap();
    assert!(tab.contains("k_fundIndex=1&_fid=GoMyAsset"));
    assert_eq!(
        cmes::parse_fund_identity(&summary),
        (None, Some("1613".into()))
    );

    // Non-finite units refused
    for bad in ["NaN", "Infinity", "-inf"] {
        assert_eq!(
            cmes::parse_units(bad).unwrap_err().code,
            cmes::FORMAT_CHANGED
        );
    }

    // Euro-Information HTML forms: only checked checkboxes/radios sent; field_names never leaks values
    let forms = euro_info::parse_forms(
        r#"<form action="/a">
             <input type="checkbox" name="consent" value="unchecked" />
             <input type="checkbox" name="remember" checked />
             <input type="radio" name="channel" value="sms" checked="checked" />
           </form>"#,
    );
    assert_eq!(
        forms[0].fields.get("remember").map(String::as_str),
        Some("on")
    );
    assert_eq!(
        forms[0].fields.get("channel").map(String::as_str),
        Some("sms")
    );
    assert!(!forms[0].fields.contains_key("consent"));
    assert!(euro_info::field_names(&sit).contains(&"$CPT".to_owned()));
    assert!(
        !euro_info::field_names(&sit)
            .join(" ")
            .contains("opaque-page-state")
    );
}

struct CmesFakeSite {
    second_factor: &'static str,
    poll_calls: AtomicUsize,
    poll_states: Vec<&'static str>,
    cancelled: AtomicUsize,
}

impl Respond for CmesFakeSite {
    fn respond(&self, request: &Request) -> ResponseTemplate {
        let p = request.url.path();
        let m = request.method.as_str();
        let cookies = request
            .headers
            .get("cookie")
            .and_then(|v| v.to_str().ok())
            .unwrap_or("");
        let logged = cookies.contains("IdSes=");
        let body = String::from_utf8_lossy(&request.body);

        if p == cmes::LOGIN_PATH && m == "GET" {
            return ResponseTemplate::new(200).set_body_string(CMES_LOGIN_PAGE);
        }
        if p == cmes::LOGIN_PATH && m == "POST" {
            if !body.contains("_cm_pwd=secret") {
                return ResponseTemplate::new(200).set_body_string(cmes_wrong_password_page());
            }
            let trusted = cookies.contains("auth_client_state=trusted-device");
            if !self.second_factor.is_empty() && !trusted {
                return ResponseTemplate::new(302)
                    .insert_header(
                        "location",
                        "/epargne-salariale/fr/epargnants/premiers-pas/authentification-forte/index.html",
                    )
                    .insert_header("set-cookie", "IdSes=pending; Path=/");
            }
            return ResponseTemplate::new(302)
                .insert_header("location", cmes::SITUATION_PATH)
                .insert_header("set-cookie", "IdSes=live; Path=/");
        }
        if p.ends_with("authentification-forte/index.html") && m == "GET" {
            let html = match self.second_factor {
                "sms" => CMES_SMS_MFA,
                _ => CMES_MOBILE_MFA,
            };
            return ResponseTemplate::new(200).set_body_string(html);
        }
        if p.ends_with("SOSD_OTP_GetTransactionState.htm") {
            let idx = self.poll_calls.fetch_add(1, Ordering::SeqCst);
            let state = self.poll_states.get(idx).copied().unwrap_or("PENDING");
            return ResponseTemplate::new(200).set_body_string(format!(
                r#"<?xml version="1.0" encoding="utf-8"?><root><transactionState>{state}</transactionState></root>"#
            ));
        }
        if p.ends_with("SOSD_OTP_CancelTransaction.htm") {
            self.cancelled.fetch_add(1, Ordering::SeqCst);
            return ResponseTemplate::new(200);
        }
        if p.ends_with("authentification-forte/index.html") && m == "POST" {
            if self.second_factor == "sms"
                && (!body.contains("otp_password=123456")
                    || !body.contains("_FID_CheckOrDeliverBackup"))
            {
                return ResponseTemplate::new(200).set_body_string(CMES_SMS_WRONG);
            }
            if self.second_factor == "mobile" && !body.contains("_FID_DoValidate") {
                return ResponseTemplate::new(200).set_body_string(CMES_MOBILE_MFA);
            }
            return ResponseTemplate::new(302)
                .insert_header("location", cmes::SITUATION_PATH)
                .append_header("set-cookie", "IdSes=live; Path=/")
                .append_header("set-cookie", "auth_client_state=trusted-device; Path=/");
        }
        if !logged {
            return ResponseTemplate::new(302).insert_header("location", cmes::LOGIN_PATH);
        }
        if p == cmes::SITUATION_PATH && m == "GET" {
            return ResponseTemplate::new(200).set_body_string(cmes_default_situation());
        }
        if p == cmes::SITUATION_PATH && m == "POST" {
            let idx =
                if body.contains("selectedFundIndex%3A1") || body.contains("selectedFundIndex:1") {
                    1
                } else {
                    0
                };
            return ResponseTemplate::new(302).insert_header(
                "location",
                format!("/epargne-salariale/fr/epargnants/supports/synthese-du-support.html?k_fundIndex={idx}"),
            );
        }
        if p.ends_with("synthese-du-support.html") {
            let q = request.url.query().unwrap_or("");
            let idx: usize = if q.contains("k_fundIndex=1") { 1 } else { 0 };
            if q.contains("_fid=GoMyAsset") {
                return ResponseTemplate::new(302).insert_header(
                    "location",
                    format!("/epargne-salariale/fr/epargnants/supports/epargne-sur-le-support.html?k_fundIndex={idx}"),
                );
            }
            return ResponseTemplate::new(200)
                .set_body_string(cmes_fund_summary(idx, &format!("16{idx}")));
        }
        if p.ends_with("epargne-sur-le-support.html") {
            let q = request.url.query().unwrap_or("");
            let html = if q.contains("k_fundIndex=1") {
                cmes_fund_savings("500,50", "-12,83", "25,0250", "20,0000")
            } else {
                cmes_fund_savings("1&nbsp;000,00", "+90,91", "50,0000", "20,0000")
            };
            return ResponseTemplate::new(200).set_body_string(html);
        }
        ResponseTemplate::new(404)
    }
}

#[tokio::test]
async fn cmes_mobile_confirmation_remembered_device_and_unattended_withdrawal() {
    let bank = MockServer::start().await;
    let fake = Arc::new(CmesFakeSite {
        second_factor: "mobile",
        poll_calls: AtomicUsize::new(0),
        poll_states: vec!["PENDING", "VALIDATED"],
        cancelled: AtomicUsize::new(0),
    });
    Mock::given(wiremock::matchers::any())
        .respond_with(CmesResponder(fake.clone()))
        .mount(&bank)
        .await;

    let mut site = cmes::default_site(&bank.uri());
    site.app_validation_poll = Duration::ZERO;
    let cmes_sidecar = NativeCmes::with_site(site);

    // Wrong password -> 401 INVALID_CREDENTIALS
    let bad = cmes_sidecar
        .call(
            Method::POST,
            "/initiate",
            json!({"login": "12345678", "password": "wrong"}),
        )
        .await
        .unwrap_err();
    assert_eq!(
        (bad.status, bad.detail.as_str()),
        (StatusCode::UNAUTHORIZED, "INVALID_CREDENTIALS")
    );

    // Unattended login withdraws confirmation from phone -> 401 SESSION_EXPIRED
    let unattended = cmes_sidecar
        .call(
            Method::POST,
            "/initiate",
            json!({"login": "12345678", "password": "secret", "unattended": true}),
        )
        .await
        .unwrap_err();
    assert_eq!(
        (unattended.status, unattended.detail.as_str()),
        (StatusCode::UNAUTHORIZED, "SESSION_EXPIRED")
    );
    assert_eq!(fake.cancelled.load(Ordering::SeqCst), 1);

    // Normal mobile confirmation
    let init = cmes_sidecar
        .call(
            Method::POST,
            "/initiate",
            json!({"login": "12345678", "password": "secret"}),
        )
        .await
        .unwrap();
    assert_eq!(init["mfaRequired"], true);
    assert_eq!(init["mfaType"], "APP_PUSH");
    let pid = init["processId"].as_str().unwrap().to_owned();

    // Complete -> polls PENDING then VALIDATED
    let done = cmes_sidecar
        .call(
            Method::POST,
            "/complete",
            json!({"processId": pid, "code": Value::Null}),
        )
        .await
        .unwrap();
    let session = done["sessionState"].as_str().unwrap().to_owned();
    assert!(session.contains("auth_client_state"));

    // Positions
    let plans = cmes_sidecar
        .call(Method::POST, "/positions", json!({"sessionState": session}))
        .await
        .unwrap();
    let arr = plans.as_array().unwrap();
    assert_eq!(arr.len(), 1);
    assert_eq!(arr[0]["employer"], "ACME INDUSTRIES");
    assert_eq!(arr[0]["positions"].as_array().unwrap().len(), 2);

    // Remembered device spares MFA on next initiate
    let relogin = cmes_sidecar
        .call(
            Method::POST,
            "/initiate",
            json!({"login": "12345678", "password": "secret", "sessionState": session}),
        )
        .await
        .unwrap();
    assert_eq!(relogin["mfaRequired"], false);
    assert!(relogin["sessionState"].is_string());

    // Malformed session & unknown processId
    let bad_sess = cmes_sidecar
        .call(Method::POST, "/positions", json!({"sessionState": "{]"}))
        .await
        .unwrap_err();
    assert_eq!(
        (bad_sess.status, bad_sess.detail.as_str()),
        (StatusCode::BAD_REQUEST, "INVALID_DATA")
    );

    let unknown = cmes_sidecar
        .call(Method::POST, "/complete", json!({"processId": "nope"}))
        .await
        .unwrap_err();
    assert_eq!(
        (unknown.status, unknown.detail.as_str()),
        (StatusCode::GONE, "AUTH_ATTEMPT_EXPIRED")
    );

    let bad_code = cmes_sidecar
        .call(
            Method::POST,
            "/complete",
            json!({"processId": "p", "code": "12ab"}),
        )
        .await
        .unwrap_err();
    assert_eq!(
        (bad_code.status, bad_code.detail.as_str()),
        (StatusCode::BAD_REQUEST, "INVALID_OTP")
    );
}

#[tokio::test]
async fn cmes_sms_retry_and_end_to_end_test_app() {
    let bank = MockServer::start().await;
    let fake = Arc::new(CmesFakeSite {
        second_factor: "sms",
        poll_calls: AtomicUsize::new(0),
        poll_states: vec![],
        cancelled: AtomicUsize::new(0),
    });
    Mock::given(wiremock::matchers::any())
        .respond_with(CmesResponder(fake))
        .mount(&bank)
        .await;

    let app = TestApp::builder()
        .with_admin()
        .env("CMES_AUTH_URL", "native://cmes-auth")
        .env("CMES_BASE_URL", bank.uri())
        .spawn()
        .await;
    let (client, _) = admin_client(&app).await;

    let init = client
        .post("/api/cmes/auth/initiate")
        .json(&json!({"login": "12345678", "password": "secret"}))
        .send()
        .await;
    assert_eq!(init.status, 200, "{}", init.text());
    let init_json = init.json();
    assert_eq!(init_json["mfaType"], "SMS");
    let pid = init_json["processId"].as_str().unwrap().to_owned();

    // Wrong SMS code -> 422 INVALID_OTP, attempt stays open!
    let wrong = client
        .post("/api/cmes/auth/complete")
        .json(&json!({"processId": pid, "code": "000000"}))
        .send()
        .await;
    assert_eq!(wrong.status, 422);
    assert_eq!(wrong.json()["code"], "INVALID_OTP");

    // Retry with right SMS code -> 200 and queues background sync
    let right = client
        .post("/api/cmes/auth/complete")
        .json(&json!({"processId": pid, "code": "123456"}))
        .send()
        .await;
    assert_eq!(right.status, 200, "{}", right.text());
    settle(&app).await;

    let status = client.get("/api/cmes/status").send().await.json();
    assert_eq!(status["isActive"], true);
    assert_eq!(status["syncStatus"], "SUCCESS");

    let balance: Decimal =
        picsou::db::query_scalar("SELECT current_balance FROM account WHERE provider = $1 LIMIT 1")
            .bind(picsou::ledger::providers::CMES)
            .fetch_one(&app.state.db)
            .await
            .unwrap();
    assert_eq!(balance, d("1500.50"));

    let holdings_count: i64 = picsou::db::query_scalar(
        "SELECT count(*) FROM account_holding h JOIN account a ON a.id = h.account_id WHERE a.provider = $1",
    )
    .bind(picsou::ledger::providers::CMES)
    .fetch_one(&app.state.db)
    .await
    .unwrap();
    assert_eq!(holdings_count, 2);
}

struct CmesResponder(Arc<CmesFakeSite>);
impl Respond for CmesResponder {
    fn respond(&self, request: &Request) -> ResponseTemplate {
        self.0.respond(request)
    }
}

// ============================================================================
// 3. SOFIDY
// ============================================================================

fn sofidy_page(rows: &str, total: &str) -> String {
    format!(
        "<html><body><span>Portefeuille SCPI au 27/09/2026</span>\
         <table><thead><tr><th>Nombre de parts</th><th>Valeur unitaire*</th></tr></thead>\
         <tbody>{rows}{total}</tbody></table></body></html>"
    )
}

fn sofidy_fund_row(label: &str, parts: &str, unit: &str, total: &str, code: &str) -> String {
    format!(
        r#"<tr class="line_marron">
          <td>{label}</td>
          <td class="sepmillier">{parts}</td>
          <td>Pleine propriété</td>
          <td><span class="sepmillier">{unit}</span> &euro;</td>
          <td><span class="sepmillier">{total}</span> &euro;</td>
          <td><a href="scripts/ajax/rapportdetail.php?Code_Produit={code}">Rapport</a></td>
        </tr>"#
    )
}

fn sofidy_total_row(parts: &str, total: &str) -> String {
    format!(
        r#"<tr class="titre_table">
          <td>Total</td><td class="sepmillier">{parts}</td><td></td><td></td>
          <td><span class="sepmillier">{total}</span> &euro;</td><td></td>
        </tr>"#
    )
}

#[test]
fn sofidy_positions_parser_covers_all_validation_rules() {
    let row = sofidy_fund_row("FONDS-EXEMPLE", "3.00000", "100.00", "300.00", "XY");
    let tot = sofidy_total_row("3.00000", "300.00");
    let decoy = "<table><tr><td>Menu</td></tr></table>";

    let snap = sofidy::parse_portfolio(&format!("{decoy}{}", sofidy_page(&row, &tot))).unwrap();
    assert_eq!(snap.valuation_date.as_deref(), Some("2026-09-27"));
    assert_eq!(snap.total_eur, d("300.00"));
    assert_eq!(snap.holdings.len(), 1);
    assert_eq!(snap.holdings[0].fund_code, "XY");
    assert_eq!(snap.holdings[0].quantity, d("3.00000"));
    assert_eq!(snap.holdings[0].withdrawal_price, Some(d("100.00")));
    assert!(snap.snapshot_complete);

    // Missing withdrawal price -> None; "0.00" -> Some(0.00)
    let unpriced = sofidy::parse_portfolio(&sofidy_page(
        &sofidy_fund_row("FONDS-EXEMPLE", "3.00000", "", "300.00", "XY"),
        &tot,
    ))
    .unwrap();
    assert_eq!(unpriced.holdings[0].withdrawal_price, None);

    let zero_priced = sofidy::parse_portfolio(&sofidy_page(
        &sofidy_fund_row("FONDS-EXEMPLE", "3.00000", "0.00", "0.00", "XY"),
        &sofidy_total_row("3.00000", "0.00"),
    ))
    .unwrap();
    assert_eq!(zero_priced.holdings[0].withdrawal_price, Some(d("0.00")));

    // Empty portfolio with exact zero total is valid
    let empty =
        sofidy::parse_portfolio(&sofidy_page("", &sofidy_total_row("0.00000", "0.00"))).unwrap();
    assert!(empty.holdings.is_empty());
    assert_eq!(empty.total_eur, Decimal::ZERO);

    // Empty portfolio with nonzero or unreadable total refused
    for (total, code) in [
        ("300.00", "PORTFOLIO_INCOMPLETE"),
        ("0.01", "PORTFOLIO_INCOMPLETE"),
        ("n/c", "INVALID_DATA"),
        ("NaN", "INVALID_DATA"),
    ] {
        let err =
            sofidy::parse_portfolio(&sofidy_page("", &sofidy_total_row("0", total))).unwrap_err();
        assert_eq!(err.code, code);
    }

    // Login form -> SESSION_EXPIRED
    assert_eq!(
        sofidy::parse_portfolio(r#"<form id="connexion_espace_partenaire"></form>"#)
            .unwrap_err()
            .code,
        "SESSION_EXPIRED"
    );

    // Mismatched total -> PORTFOLIO_INCOMPLETE; 1 cent drift tolerated
    assert_eq!(
        sofidy::parse_portfolio(&sofidy_page(&row, &sofidy_total_row("3.00000", "5000.00")))
            .unwrap_err()
            .code,
        "PORTFOLIO_INCOMPLETE"
    );
    assert!(
        sofidy::parse_portfolio(&sofidy_page(&row, &sofidy_total_row("3.00000", "300.01"))).is_ok()
    );

    // Duplicate fund codes, comma-formatted numbers, truncated row -> UPSTREAM_FORMAT_CHANGED
    assert_eq!(
        sofidy::parse_portfolio(&sofidy_page(&format!("{row}{row}"), &tot))
            .unwrap_err()
            .code,
        "UPSTREAM_FORMAT_CHANGED"
    );
    assert_eq!(
        sofidy::parse_portfolio(&sofidy_page(
            &sofidy_fund_row("F", "1,5", "100.00", "150.00", "XY"),
            &sofidy_total_row("1,5", "150.00"),
        ))
        .unwrap_err()
        .code,
        "UPSTREAM_FORMAT_CHANGED"
    );
    assert_eq!(
        sofidy::parse_portfolio(&sofidy_page(
            "<tr><td>FONDS</td><td>3.00000</td><td>PP</td></tr>",
            &sofidy_total_row("0", "0.00"),
        ))
        .unwrap_err()
        .code,
        "UPSTREAM_FORMAT_CHANGED"
    );
}

#[tokio::test]
async fn sofidy_contract_outcomes_retry_and_redirect_security() {
    let bank = MockServer::start().await;
    let sofidy_sidecar = NativeSofidy::with_base_url(&bank.uri());

    // Validation errors -> 400 INVALID_REQUEST
    let bad_code = sofidy_sidecar
        .call(
            Method::POST,
            "/initiate",
            json!({"associateCode": "12345", "password": "secret"}),
        )
        .await
        .unwrap_err();
    assert_eq!(
        (bad_code.status, bad_code.detail.as_str()),
        (StatusCode::BAD_REQUEST, "INVALID_REQUEST")
    );

    // Cross-origin 307 redirect blocked BEFORE sending request
    Mock::given(method("GET"))
        .and(path(sofidy::HOME_PATH))
        .respond_with(
            ResponseTemplate::new(200)
                .insert_header("set-cookie", "PHPSESSID=abc; Path=/")
                .set_body_string("<html>accueil</html>"),
        )
        .mount(&bank)
        .await;
    Mock::given(method("POST"))
        .and(path(sofidy::LOGIN_PATH))
        .respond_with(
            ResponseTemplate::new(307)
                .insert_header("location", "https://attacker.example/steal?token=secret"),
        )
        .mount(&bank)
        .await;

    let redir_err = sofidy_sidecar
        .call(
            Method::POST,
            "/initiate",
            json!({"associateCode": "123456", "password": "secret"}),
        )
        .await
        .unwrap_err();
    assert_eq!(
        (redir_err.status, redir_err.detail.as_str()),
        (StatusCode::BAD_GATEWAY, "UPSTREAM_UNAVAILABLE")
    );

    // Test portal outcomes: faux, compteur_4, inactif, firstyes
    for (portal_body, expected_status, expected_detail) in [
        ("faux", StatusCode::UNAUTHORIZED, "INVALID_CREDENTIALS"),
        ("compteur_4", StatusCode::TOO_MANY_REQUESTS, "RATE_LIMITED"),
        ("inactif", StatusCode::FORBIDDEN, "ACCOUNT_INACTIVE"),
        ("firstyes", StatusCode::CONFLICT, "FIRST_VISIT_PENDING"),
    ] {
        bank.reset().await;
        Mock::given(method("GET"))
            .and(path(sofidy::HOME_PATH))
            .respond_with(ResponseTemplate::new(200).set_body_string("<html>ok</html>"))
            .mount(&bank)
            .await;
        Mock::given(method("POST"))
            .and(path(sofidy::LOGIN_PATH))
            .respond_with(ResponseTemplate::new(200).set_body_string(portal_body))
            .mount(&bank)
            .await;
        let err = sofidy_sidecar
            .call(
                Method::POST,
                "/initiate",
                json!({"associateCode": "123456", "password": "secret"}),
            )
            .await
            .unwrap_err();
        assert_eq!(
            (err.status, err.detail.as_str()),
            (expected_status, expected_detail)
        );
    }

    // Wrong 2FA code stays retryable, then succeeds, then second complete -> 410
    bank.reset().await;
    Mock::given(method("GET"))
        .and(path(sofidy::HOME_PATH))
        .respond_with(
            ResponseTemplate::new(200)
                .insert_header("set-cookie", "PHPSESSID=sess1; Path=/")
                .set_body_string("<html>connecte</html>"),
        )
        .mount(&bank)
        .await;
    Mock::given(method("POST"))
        .and(path(sofidy::LOGIN_PATH))
        .respond_with(ResponseTemplate::new(200).set_body_string("2FA|a***@example.com"))
        .mount(&bank)
        .await;
    let verify_calls = Arc::new(AtomicUsize::new(0));
    let vc = verify_calls.clone();
    Mock::given(method("POST"))
        .and(path(sofidy::VERIFY_2FA_PATH))
        .respond_with(move |_req: &Request| {
            if vc.fetch_add(1, Ordering::SeqCst) == 0 {
                ResponseTemplate::new(200).set_body_string("faux")
            } else {
                ResponseTemplate::new(200).set_body_string("fauxyes")
            }
        })
        .mount(&bank)
        .await;

    let init = sofidy_sidecar
        .call(
            Method::POST,
            "/initiate",
            json!({"associateCode": "123456", "password": "secret"}),
        )
        .await
        .unwrap();
    let pid = init["processId"].as_str().unwrap().to_owned();
    assert_eq!(init["mfaType"], "EMAIL_CODE");

    let first_try = sofidy_sidecar
        .call(
            Method::POST,
            "/complete",
            json!({"processId": pid, "code": "000000"}),
        )
        .await
        .unwrap_err();
    assert_eq!(
        (first_try.status, first_try.detail.as_str()),
        (StatusCode::UNAUTHORIZED, "MFA_INVALID")
    );

    let second_try = sofidy_sidecar
        .call(
            Method::POST,
            "/complete",
            json!({"processId": pid, "code": "480921"}),
        )
        .await
        .unwrap();
    assert!(
        second_try["sessionState"]
            .as_str()
            .unwrap()
            .contains("PHPSESSID")
    );

    let replay = sofidy_sidecar
        .call(
            Method::POST,
            "/complete",
            json!({"processId": pid, "code": "480921"}),
        )
        .await
        .unwrap_err();
    assert_eq!(
        (replay.status, replay.detail.as_str()),
        (StatusCode::GONE, "AUTH_ATTEMPT_EXPIRED")
    );
}

#[tokio::test]
async fn sofidy_end_to_end_native_through_test_app() {
    let bank = MockServer::start().await;
    let row = sofidy_fund_row("IMMORENTE", "3.00000", "100.00", "300.00", "IM");
    let tot = sofidy_total_row("3.00000", "300.00");

    Mock::given(method("GET"))
        .and(path(sofidy::HOME_PATH))
        .respond_with(
            ResponseTemplate::new(200)
                .insert_header("set-cookie", "PHPSESSID=live123; Path=/")
                .set_body_string("<html>accueil connecte</html>"),
        )
        .mount(&bank)
        .await;
    Mock::given(method("POST"))
        .and(path(sofidy::LOGIN_PATH))
        .respond_with(ResponseTemplate::new(200).set_body_string("2FA|u***@example.com"))
        .mount(&bank)
        .await;
    Mock::given(method("POST"))
        .and(path(sofidy::VERIFY_2FA_PATH))
        .respond_with(ResponseTemplate::new(200).set_body_string("fauxyes"))
        .mount(&bank)
        .await;
    Mock::given(method("GET"))
        .and(path(sofidy::PORTFOLIO_PATH))
        .respond_with(ResponseTemplate::new(200).set_body_string(sofidy_page(&row, &tot)))
        .mount(&bank)
        .await;

    let app = TestApp::builder()
        .with_admin()
        .env("SOFIDY_AUTH_URL", "native://sofidy-auth")
        .env("SOFIDY_BASE_URL", bank.uri())
        .spawn()
        .await;
    let (client, _) = admin_client(&app).await;

    let init = client
        .post("/api/sofidy/auth/initiate")
        .json(&json!({"associateCode": "123456", "password": "secret"}))
        .send()
        .await;
    assert_eq!(init.status, 200, "{}", init.text());
    let pid = init.json()["processId"].as_str().unwrap().to_owned();

    let comp = client
        .post("/api/sofidy/auth/complete")
        .json(&json!({"processId": pid, "code": "480921"}))
        .send()
        .await;
    assert_eq!(comp.status, 200, "{}", comp.text());
    settle(&app).await;

    let status = client.get("/api/sofidy/status").send().await.json();
    assert_eq!(status["isActive"], true);
    assert_eq!(status["syncStatus"], "SUCCESS");
}

// ============================================================================
// 4. CIC
// ============================================================================

const CIC_LOGOUT: &str =
    r#"<a href="/fr/identification/deconnexion/deconnexion.cgi">Déconnexion</a>"#;

const CIC_LOGIN_PAGE: &str = r#"
<html><body>
<form method="post" autocomplete="off" name="bloc_ident" id="bloc_ident">
  <input type="text" id="_userid" name="_cm_user" value="" />
  <input type="hidden" name="flag" value="password">
  <input type="hidden" name="_charset_">
  <input type="password" name="_cm_pwd" id="_pwduser" />
</form>
</body></html>"#;

fn cic_account_row(index: usize, title: &str, number: &str, amount: &str) -> String {
    format!(
        r#"
<tr id="I0:ac.s1.A1:al.A:F_{index}.A1:T0" class="_c1 _c1">
<td colspan="1" class="_c1 decal1 _c1"><a href="https://www.cic.fr/fr/banque/mouvements.html?webid=0a{index}" target="_self">
<span><span class="_c1 ei_sdsf_title _c1">{title}</span> <span class="_c1 nowrap _c1">M JOHN DOE</span> <span>-</span>
<span class="_c1 nowrap doux _c1">{number}</span></span></a></td>
<td class="d nowrap "></td><td class="d nowrap ei_sdsf_solde"><span class="ei_sdsf_montant pos">{amount}</span></td>
</tr>"#
    )
}

fn cic_accounts_table(category: &str, rows: &str) -> String {
    format!(
        r#"
<table id="I0:ac.T" class="_c1 ei_comptescontrats _c1 liste ei_cellspacing">
<caption class="invisible"><span class="invisible">Liste des comptes li&#233;s &#224; la cat&#233;gorie : {category}</span></caption>
<tbody>{rows}</tbody></table>"#
    )
}

fn cic_accounts_page() -> String {
    let checking = cic_accounts_table(
        "Comptes courants",
        &cic_account_row(
            0,
            "COMPTE COURANT",
            "10000 000123456 01",
            "+1&nbsp;234,56 EUR",
        ),
    );
    let savings = cic_accounts_table(
        "Livrets et comptes d&#39;épargne",
        &format!(
            "{}{}{}",
            cic_account_row(1, "LIVRET A", "10000 000123456 02", "+5&nbsp;000,00 EUR"),
            cic_account_row(
                2,
                "LIVRET DE DEVELOPPEMENT DURABLE SOLIDAIRE",
                "10000 000123456 03",
                "+800,00 EUR"
            ),
            cic_account_row(
                3,
                "PLAN EPARGNE LOGEMENT",
                "10000 000123456 04",
                "+3&nbsp;100,50 EUR"
            ),
        ),
    );
    let other = r#"<table class="_c1 ei_comptescontrats _c1 liste">
<caption class="invisible"><span class="invisible">Liste des comptes li&#233;s &#224; la cat&#233;gorie : Autres contrats li&#233;s</span></caption>
<tbody><tr><td><a href="/lien">XXXX1234 Épargne Retraite</a></td><td></td></tr></tbody></table>"#;
    format!(
        r#"<html><body>{CIC_LOGOUT}<form id="I0:P:F" method="post">{checking}{savings}{other}</form></body></html>"#
    )
}

const CIC_MOBILE_MFA: &str = r#"<html><body>
<form id="C:P:F" action="/fr/banque/validation.aspx?_tabi=C" method="post">
  <input name="$CPT" type="hidden" value="opaque-page-state" />
  <input type="submit" name="_FID_DoValidate" value="Continuer" />
</form>
<script>
  $dv.O('C:P:polling', 'OtpPolling', {transactionId: '0123456789abcdef', getTransactionState: true});
</script></body></html>"#;

const CIC_SMS_MFA: &str = r#"<html><body>
<form id="C:P:F" action="/fr/banque/validation.aspx?_tabi=C" method="post">
  <input type="hidden" name="otp_hidden" value="opaque-otp-state" />
  <input type="text" name="otp_password" maxlength="6" />
  <input type="submit" name="_FID_CheckOrDeliverBackup" value="Valider" />
  <input name="$CPT" type="hidden" value="opaque-page-state" />
</form></body></html>"#;

const CIC_AUTHORITY_PAGE: &str = r#"<html><body>
<form id="C:P:F" action="/fr/banque/migr_gestion_pouvoirs.html?_tabi=C" method="post">
  <input name="$CPT" type="hidden" value="opaque-page-state" />
  <input type="submit" name="_FID_DoStopPaciAndRemind" value="Plus tard" />
</form></body></html>"#;

#[test]
fn cic_accounts_parser_matches_python_security_and_edge_cases() {
    // Amounts
    assert_eq!(
        cic::parse_amount("+1&nbsp;234,56 EUR").unwrap(),
        d("1234.56")
    );
    assert_eq!(cic::parse_amount("-42,10 EUR").unwrap(), d("-42.10"));
    assert_eq!(cic::parse_amount("+0,00 €").unwrap(), d("0.00"));
    for bad in ["+12,00 USD", "", "N/A"] {
        assert!(cic::parse_amount(bad).is_err(), "{bad}");
    }

    // Linear-time scanning on long digit runs (no quadratic backtracking)
    let long_run = format!("<td>{}</td>", "1 ".repeat(20_000));
    let started = Instant::now();
    assert!(!cic::has_balance(&long_run));
    assert!(cic::has_balance(&format!("{long_run}<td>12,34 EUR</td>")));
    assert!(started.elapsed() < Duration::from_secs(1));

    // Account types & diacritic folding
    assert_eq!(
        cic::account_type(cic::SAVINGS_CATEGORY, "LIVRET A SUP"),
        "LIVRET_A"
    );
    assert_eq!(
        cic::account_type(cic::SAVINGS_CATEGORY, "Plan d\u{2019}épargne logement"),
        "PEL"
    );
    assert_eq!(
        cic::account_type(cic::SAVINGS_CATEGORY, "COMPTE ÉPARGNE LOGEMENT"),
        "CEL"
    );
    assert_eq!(
        cic::account_type(cic::SAVINGS_CATEGORY, "LIVRET D'ÉPARGNE POPULAIRE"),
        "LEP"
    );
    assert_eq!(
        cic::account_type(cic::SAVINGS_CATEGORY, "LIVRET JEUNE"),
        "LIVRET_JEUNE"
    );
    assert_eq!(
        cic::account_type(cic::SAVINGS_CATEGORY, "LIVRET PREMIERE EPARGNE"),
        "SAVINGS"
    );
    assert_eq!(
        cic::account_type(cic::CHECKING_CATEGORY, "COMPTE COURANT"),
        "CHECKING"
    );

    // Standard full page
    let page = cic_accounts_page();
    let (accounts, skipped, categories) = cic::parse_accounts_listing(&page).unwrap();
    assert_eq!(accounts.len(), 4);
    assert_eq!(accounts[0].id, "1000000012345601");
    assert_eq!(accounts[0].name, "Compte Courant");
    assert_eq!(accounts[0].account_type, "CHECKING");
    assert_eq!(accounts[0].balance_eur, d("1234.56"));
    assert_eq!(accounts[1].account_type, "LIVRET_A");
    assert_eq!(accounts[2].account_type, "LDDS");
    assert_eq!(accounts[3].account_type, "PEL");
    assert_eq!(skipped, vec!["autres contrats liés"]);
    assert_eq!(categories, vec!["CHECKING", "SAVINGS"]);

    // Typographic apostrophe in category caption
    let typo = page.replace("d&#39;épargne", "d’épargne");
    assert_eq!(cic::parse_accounts(&typo).unwrap().0.len(), 4);

    // Incidental prose, ambiguous words, percentage offers, scripts/styles/comments keep all accounts
    for extra in [
        "<p>Total : 10 135,06 EUR</p>",
        r#"<table class="layout"><tr><td>Depuis 2026 ils proposent. SOS 24h/24. Top 10</td></tr></table>"#,
        r#"<table class="layout"><tr><td>TOP 10 des offres</td></tr></table>"#,
        r#"<table class="layout"><tr><td>ALL 3 offres</td></tr></table>"#,
        r#"<table class="layout"><tr><td>SOS 112</td></tr></table>"#,
        r#"<table class="ei_comptescontrats"><tr><td><span class="ei_sdsf_title">Contrat 2024 USD</span></td><td class="ei_sdsf_solde"></td></tr></table>"#,
        r#"<table class="ei_comptescontrats"><tr><td><span class="ei_sdsf_title">Nos offres</span><span>À partir de 2,99 % TAEG</span></td><td class="ei_sdsf_solde"></td></tr></table>"#,
        r#"<table><tr><td><script>s.replace(/x/, "$1");</script></td></tr></table>"#,
        r#"<!-- <script> --><div title="<style>">Aide</div><script>ignored</script>"#,
        r#"<!help><!-- > <script> --><!--->"#,
    ] {
        let with_extra = page.replace("</body>", &format!("{extra}</body>"));
        assert_eq!(
            cic::parse_accounts(&with_extra).unwrap().0.len(),
            4,
            "should succeed for: {extra}"
        );
    }

    // Truncated page, unterminated comment, content after </html>, unknown balances fail closed
    assert!(cic::parse_accounts(&page[..page.find("</html>").unwrap()]).is_err());
    assert!(cic::parse_accounts(&format!("{page}<p>trailing</p>")).is_err());
    assert!(cic::parse_accounts(&page.replace("<body>", "<body><!-- help")).is_err());

    for bad_row in [
        r#"<td>EUR 0</td>"#,
        r#"<td>eur 12</td>"#,
        r#"<td>USD 500</td>"#,
        r#"<td>1 234,56</td><td>3,50 %</td>"#,
        r#"<td>3,50 %</td><td>1 234,56</td>"#,
        r#"<td>1 EURO</td>"#,
        r#"<td>1 234,56 euros</td>"#,
        r#"<td>TOP 10,50</td>"#,
        r#"<td>10 TOP</td>"#,
        r#"<td><span class="ei_sdsf_title">X<td>1 234 EUR</td>"#,
        r#"<td><span class="ei_sdsf_title">LIVRET X <span>1 234 EUR</span></span></td>"#,
        r#"<td><span class="ei_sdsf_title">LIVRET X</span><span class=doux>0001 12345678</span></td>"#,
    ] {
        let tbl = cic_accounts_table("Nouveaux comptes", &format!("<tr>{bad_row}</tr>"));
        let p = page.replace("</body>", &format!("{tbl}</body>"));
        let err = cic::parse_accounts(&p).unwrap_err();
        assert_eq!(err.code, cic::FORMAT_CHANGED, "must fail for: {bad_row}");
    }

    // Error omits private product text and truncates category name to 60 chars
    let private_title = "Assurance 12 500,00 EUR M EXAMPLE";
    let long_cat = "C".repeat(100);
    let tbl = cic_accounts_table(
        &long_cat,
        &format!(r#"<tr><td><span class="ei_sdsf_title">{private_title}</span></td></tr>"#),
    );
    let err = cic::parse_accounts(&format!("<html><body>{tbl}</body></html>")).unwrap_err();
    assert!(!err.message.contains("EXAMPLE"));
    assert!(!err.message.contains("12 500"));
    assert!(err.message.contains(&"c".repeat(60)));
    assert!(!err.message.contains(&"c".repeat(61)));
}

struct CicFakeBank {
    second_factor: &'static str,
    authority_notice: AtomicUsize,
    poll_calls: AtomicUsize,
    poll_status: AtomicUsize,
    cancelled: AtomicUsize,
}

impl Respond for CicFakeBank {
    fn respond(&self, request: &Request) -> ResponseTemplate {
        let p = request.url.path();
        let m = request.method.as_str();
        let cookies = request
            .headers
            .get("cookie")
            .and_then(|v| v.to_str().ok())
            .unwrap_or("");
        let logged = cookies.contains("IdSes=live");
        let body = String::from_utf8_lossy(&request.body);

        if p == cic::LOGIN_PATH && m == "GET" {
            return ResponseTemplate::new(200).set_body_string(CIC_LOGIN_PAGE);
        }
        if p == cic::LOGIN_PATH && m == "POST" {
            if !body.contains("_cm_pwd=secret") {
                return ResponseTemplate::new(200).set_body_string(
                    CIC_LOGIN_PAGE.replace(
                        "<body>",
                        r#"<body><div class="blocmsg err"><p>Votre identifiant ou mot de passe est incorrect.</p></div>"#,
                    ),
                );
            }
            let trusted = cookies.contains("auth_client_state=trusted-device")
                && cookies.contains("device_guid=dev1");
            if !self.second_factor.is_empty() && !trusted {
                return ResponseTemplate::new(302)
                    .insert_header("location", "/fr/banque/validation.aspx")
                    .insert_header("set-cookie", "IdSes=pending; Path=/");
            }
            let target = if self.authority_notice.load(Ordering::SeqCst) > 0 {
                "/fr/banque/migr_gestion_pouvoirs.html"
            } else {
                "/fr/banque/pageaccueil.html"
            };
            return ResponseTemplate::new(302)
                .insert_header("location", target)
                .insert_header("set-cookie", "IdSes=live; Path=/");
        }
        if p == "/fr/banque/validation.aspx" && m == "GET" {
            let html = if self.second_factor == "sms" {
                CIC_SMS_MFA
            } else {
                CIC_MOBILE_MFA
            };
            return ResponseTemplate::new(200).set_body_string(html);
        }
        if p == cic::DEFAULT_STATE_PATH {
            let override_status = self.poll_status.load(Ordering::SeqCst);
            if override_status > 0 {
                return ResponseTemplate::new(override_status as u16)
                    .set_body_string("Polling failed");
            }
            let idx = self.poll_calls.fetch_add(1, Ordering::SeqCst);
            let st = if idx == 0 { "PENDING" } else { "VALIDATED" };
            return ResponseTemplate::new(200).set_body_string(format!(
                r#"<?xml version="1.0" encoding="utf-8"?><root><transactionState>{st}</transactionState></root>"#
            ));
        }
        if p == cic::DEFAULT_CANCEL_PATH {
            self.cancelled.fetch_add(1, Ordering::SeqCst);
            return ResponseTemplate::new(200);
        }
        if p == "/fr/banque/validation.aspx" && m == "POST" {
            if self.second_factor == "sms"
                && (!body.contains("otp_password=123456")
                    || !body.contains("_FID_CheckOrDeliverBackup"))
            {
                return ResponseTemplate::new(200).set_body_string(CIC_SMS_MFA);
            }
            let target = if self.authority_notice.load(Ordering::SeqCst) > 0 {
                "/fr/banque/migr_gestion_pouvoirs.html"
            } else {
                "/fr/banque/pageaccueil.html"
            };
            return ResponseTemplate::new(302)
                .insert_header("location", target)
                .append_header("set-cookie", "IdSes=live; Path=/")
                .append_header("set-cookie", "auth_client_state=trusted-device; Path=/")
                .append_header("set-cookie", "device_guid=dev1; Path=/");
        }
        if !logged {
            return ResponseTemplate::new(302).insert_header("location", cic::LOGIN_PATH);
        }
        if p == "/fr/banque/migr_gestion_pouvoirs.html" && m == "GET" {
            return ResponseTemplate::new(200).set_body_string(CIC_AUTHORITY_PAGE);
        }
        if p == "/fr/banque/migr_gestion_pouvoirs.html" && m == "POST" {
            assert!(body.contains("_FID_DoStopPaciAndRemind"));
            self.authority_notice.store(0, Ordering::SeqCst);
            return ResponseTemplate::new(302)
                .insert_header("location", "/fr/banque/pageaccueil.html");
        }
        if p == "/fr/banque/pageaccueil.html" || p == cic::ACCOUNTS_PATH {
            return ResponseTemplate::new(200).set_body_string(cic_accounts_page());
        }
        ResponseTemplate::new(404)
    }
}

struct CicResponder(Arc<CicFakeBank>);
impl Respond for CicResponder {
    fn respond(&self, request: &Request) -> ResponseTemplate {
        self.0.respond(request)
    }
}

#[tokio::test]
async fn cic_mobile_confirmation_authority_notice_poll_errors_and_remembered_device() {
    let bank = MockServer::start().await;
    let fake = Arc::new(CicFakeBank {
        second_factor: "mobile",
        authority_notice: AtomicUsize::new(1),
        poll_calls: AtomicUsize::new(0),
        poll_status: AtomicUsize::new(0),
        cancelled: AtomicUsize::new(0),
    });
    Mock::given(wiremock::matchers::any())
        .respond_with(CicResponder(fake.clone()))
        .mount(&bank)
        .await;

    let mut site = cic::default_site(&bank.uri());
    site.app_validation_poll = Duration::ZERO;
    let cic_sidecar = NativeCic::with_site(site);

    // Complete mobile confirmation + postpone authority notice
    let init = cic_sidecar
        .call(
            Method::POST,
            "/initiate",
            json!({"login": "12345678", "password": "secret"}),
        )
        .await
        .unwrap();
    let pid = init["processId"].as_str().unwrap().to_owned();
    let done = cic_sidecar
        .call(Method::POST, "/complete", json!({"processId": pid}))
        .await
        .unwrap();
    let session = done["sessionState"].as_str().unwrap().to_owned();
    assert_eq!(fake.authority_notice.load(Ordering::SeqCst), 0);

    // /accounts/listing returns accounts + categoriesRead
    let listing = cic_sidecar
        .call(
            Method::POST,
            "/accounts/listing",
            json!({"sessionState": session}),
        )
        .await
        .unwrap();
    assert_eq!(listing["accounts"].as_array().unwrap().len(), 4);
    assert_eq!(listing["categoriesRead"], json!(["CHECKING", "SAVINGS"]));

    // Remembered device (replaying ALL cookies) spares MFA
    let relogin = cic_sidecar
        .call(
            Method::POST,
            "/initiate",
            json!({"login": "12345678", "password": "secret", "sessionState": session}),
        )
        .await
        .unwrap();
    assert_eq!(relogin["mfaRequired"], false);

    // Polling 403 -> 410 AUTH_ATTEMPT_EXPIRED + cancels; polling 404 -> 502 UPSTREAM_FORMAT_CHANGED + cancels
    for (http_status, expected_status, expected_detail) in [
        (403usize, StatusCode::GONE, "AUTH_ATTEMPT_EXPIRED"),
        (404usize, StatusCode::BAD_GATEWAY, "UPSTREAM_FORMAT_CHANGED"),
        (503usize, StatusCode::BAD_GATEWAY, "UPSTREAM_UNAVAILABLE"),
    ] {
        fake.poll_status.store(http_status, Ordering::SeqCst);
        fake.cancelled.store(0, Ordering::SeqCst);
        let started = cic_sidecar
            .call(
                Method::POST,
                "/initiate",
                json!({"login": "12345678", "password": "secret"}),
            )
            .await
            .unwrap();
        let err = cic_sidecar
            .call(
                Method::POST,
                "/complete",
                json!({"processId": started["processId"].as_str().unwrap()}),
            )
            .await
            .unwrap_err();
        assert_eq!(
            (err.status, err.detail.as_str()),
            (expected_status, expected_detail)
        );
        assert_eq!(fake.cancelled.load(Ordering::SeqCst), 1);
    }
}

#[tokio::test]
async fn cic_end_to_end_native_through_test_app() {
    let bank = MockServer::start().await;
    let fake = Arc::new(CicFakeBank {
        second_factor: "sms",
        authority_notice: AtomicUsize::new(0),
        poll_calls: AtomicUsize::new(0),
        poll_status: AtomicUsize::new(0),
        cancelled: AtomicUsize::new(0),
    });
    Mock::given(wiremock::matchers::any())
        .respond_with(CicResponder(fake))
        .mount(&bank)
        .await;

    let app = TestApp::builder()
        .with_admin()
        .env("CIC_AUTH_URL", "native://cic-auth")
        .env("CIC_BASE_URL", bank.uri())
        .spawn()
        .await;
    let (client, member_id) = admin_client(&app).await;

    let init = client
        .post("/api/cic/auth/initiate")
        .json(&json!({"login": "12345678", "password": "secret"}))
        .send()
        .await;
    assert_eq!(init.status, 200, "{}", init.text());
    let pid = init.json()["processId"].as_str().unwrap().to_owned();

    let comp = client
        .post("/api/cic/auth/complete")
        .json(&json!({"processId": pid, "code": "123456"}))
        .send()
        .await;
    assert_eq!(comp.status, 200, "{}", comp.text());
    settle(&app).await;

    let status = client.get("/api/cic/status").send().await.json();
    assert_eq!(status["isActive"], true);
    assert_eq!(status["syncStatus"], "SUCCESS");
    assert_eq!(status["canReconnect"], true);

    let rows: Vec<(String, String, Decimal)> = picsou::db::query_as(
        "SELECT external_account_id, type::text, current_balance FROM account \
         WHERE member_id = $1 AND provider = 'CIC' ORDER BY external_account_id",
    )
    .bind(member_id)
    .fetch_all(&app.state.db)
    .await
    .unwrap();
    assert_eq!(
        rows,
        vec![
            (
                "cic_1000000012345601".into(),
                "CHECKING".into(),
                d("1234.56")
            ),
            (
                "cic_1000000012345602".into(),
                "LIVRET_A".into(),
                d("5000.00")
            ),
            ("cic_1000000012345603".into(), "LDDS".into(), d("800.00")),
            ("cic_1000000012345604".into(), "PEL".into(), d("3100.50")),
        ]
    );

    let _ = SidecarBank::Cic; // verify bank enum linkage
}

// ---------------------------------------------------------------------------------------------
// Robustness: a mangled page is an error or an empty answer, never a panic. A bank changes its
// markup without notice, and a panic in a parser would abort the request instead of reporting
// UPSTREAM_FORMAT_CHANGED. Synthetic pages only.
// ---------------------------------------------------------------------------------------------

/// The page cut short at every character, cut from the start at every character, with one
/// character removed, and with a multi-byte character pushed in, at a stride that keeps the run
/// short. Byte offsets that split a UTF-8 sequence are exactly what a hand-written scanner trips on.
pub(crate) fn mangled(page: &str) -> Vec<String> {
    let boundaries: Vec<usize> = page.char_indices().map(|(i, _)| i).collect();
    let stride = (boundaries.len() / 1500).max(1);
    let mut out = Vec::new();
    for &at in boundaries.iter().step_by(stride) {
        out.push(page[..at].to_owned());
        out.push(page[at..].to_owned());
        let next = page[at..].chars().next().map_or(at, |c| at + c.len_utf8());
        out.push(format!("{}{}", &page[..at], &page[next..]));
        for inserted in ["é", "€", "𝄞", "<", ">", "\"", "&"] {
            out.push(format!("{}{inserted}{}", &page[..at], &page[at..]));
        }
    }
    out.push(String::new());
    out.push(page.to_owned());
    out
}

/// Runs `parse` over every mangled page on a worker thread. A panic fails the test with the
/// page that caused it; so does a parse that does not come back, which a scanner that stops
/// advancing would otherwise turn into a hung sync.
pub(crate) fn assert_never_panics(
    parser: &'static str,
    pages: &[String],
    parse: impl Fn(&str) + Send + 'static,
) {
    use std::sync::{Arc, Mutex, mpsc};

    let pages: Vec<String> = pages.iter().flat_map(|seed| mangled(seed)).collect();
    let current = Arc::new(Mutex::new(String::new()));
    let (tick, ticks) = mpsc::channel::<Result<(), String>>();
    let worker_current = Arc::clone(&current);
    std::thread::spawn(move || {
        for page in pages {
            *worker_current.lock().unwrap() = page.clone();
            let outcome = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| parse(&page)));
            if tick.send(outcome.map_err(|_| page)).is_err() {
                return;
            }
        }
    });
    let excerpt = |page: &str| page.chars().take(240).collect::<String>();
    loop {
        match ticks.recv_timeout(Duration::from_secs(10)) {
            Ok(Ok(())) => {}
            Ok(Err(page)) => panic!(
                "{parser} panicked on a mangled page of {} bytes: {:?}",
                page.len(),
                excerpt(&page)
            ),
            Err(mpsc::RecvTimeoutError::Disconnected) => break,
            Err(mpsc::RecvTimeoutError::Timeout) => {
                let page = current.lock().unwrap().clone();
                panic!(
                    "{parser} did not return within 10 s on a mangled page of {} bytes: {:?}",
                    page.len(),
                    excerpt(&page)
                );
            }
        }
    }
}

#[test]
fn cic_parsers_never_panic_on_a_mangled_page() {
    let pages = [
        cic_accounts_page(),
        CIC_LOGIN_PAGE.to_owned(),
        CIC_MOBILE_MFA.to_owned(),
        CIC_SMS_MFA.to_owned(),
        CIC_AUTHORITY_PAGE.to_owned(),
        "<!-- é --><script>var a = '</scr';</script><style>é</style><!DOCTYPE é><td>1 234,56 €</td>"
            .to_owned(),
    ];
    assert_never_panics("cic", &pages, |page| {
        let _ = cic::visible_html(page);
        let _ = cic::has_balance(page);
        let _ = cic::categories_read(page);
        let _ = cic::parse_accounts_listing(page);
        let _ = cic::parse_amount(page);
        let _ = euro_info::parse_forms(page);
        let _ = euro_info::field_names(page);
        let _ = euro_info::find_form_by_name(page, "é");
        let _ = euro_info::link_with(page, &["déconnexion", "é"]);
    });
}

#[test]
fn cmes_parsers_never_panic_on_a_mangled_page() {
    let pages = [
        cmes_default_situation(),
        cmes_fund_savings("1 234,56", "12,34", "10,0000", "123,456"),
        cmes_fund_summary(0, "QS0000000001"),
        CMES_LOGIN_PAGE.to_owned(),
        CMES_MOBILE_MFA.to_owned(),
        CMES_SMS_MFA.to_owned(),
    ];
    assert_never_panics("cmes", &pages, |page| {
        let _ = cmes::parse_profile(page);
        let _ = cmes::parse_situation(page);
        let _ = cmes::my_savings_tab(page);
        let _ = cmes::parse_fund_identity(page);
        let _ = cmes::parse_my_savings(page);
        let _ = cmes::parse_money(page);
        let _ = cmes::parse_percent(page);
        let _ = cmes::parse_units(page);
        let _ = euro_info::parse_forms(page);
        let _ = euro_info::field_names(page);
    });
}

#[test]
fn sofidy_parser_never_panics_on_a_mangled_page() {
    let row = sofidy_fund_row(
        "SOFIDY PIERRE EUROPE é",
        "10.00000",
        "235.00",
        "2350.00",
        "SPE",
    );
    let pages = [sofidy_page(&row, &sofidy_total_row("10.00000", "2350.00"))];
    assert_never_panics("sofidy", &pages, |page| {
        let _ = sofidy::parse_portfolio(page);
    });
}

#[test]
fn cmes_and_sofidy_parsers_report_errors_instead_of_panicking_on_decimal_overflow() {
    let huge = "50000000000000000000000000000";

    // CMES situation: two funds whose values overflow Decimal on sum.
    let cmes_rows = format!(
        "{}{}",
        cmes_fund_row(0, "FONDS A", huge, "+ 1,00", "pos"),
        cmes_fund_row(1, "FONDS B", huge, "+ 1,00", "pos"),
    );
    let cmes_page = cmes_situation_page(&cmes_plan_table(
        "Plan d&#39;&#233;pargne groupe",
        huge,
        &cmes_rows,
    ));
    let err = cmes::parse_situation(&cmes_page).unwrap_err();
    assert_eq!(err.code, cmes::FORMAT_CHANGED);

    // CMES fund savings: quantity * unit_value overflows Decimal.
    let savings_page =
        cmes_fund_savings("1 000,00", "0,00", "1000000000000000", "1000000000000000");
    let err = cmes::parse_my_savings(&savings_page).unwrap_err();
    assert_eq!(err.code, cmes::FORMAT_CHANGED);

    // CMES money_close: extreme opposite signs must not overflow.
    assert!(!cmes::money_close(Decimal::MAX, -Decimal::MAX));

    // Sofidy: two holdings whose totals overflow Decimal on sum.
    let sofidy_rows = format!(
        "{}{}",
        sofidy_fund_row("SCPI A", "10.00", "100.00", huge, "A"),
        sofidy_fund_row("SCPI B", "10.00", "100.00", huge, "B"),
    );
    let sofidy_html = sofidy_page(&sofidy_rows, &sofidy_total_row("20.00", huge));
    let err = sofidy::parse_portfolio(&sofidy_html).unwrap_err();
    assert_eq!(err.code, "INVALID_DATA");

    // Sofidy: computed - declared overflows Decimal -> PORTFOLIO_INCOMPLETE.
    let max_str = Decimal::MAX.to_string();
    let neg_max_str = (-Decimal::MAX).to_string();
    let sofidy_diff_html = sofidy_page(
        &sofidy_fund_row("SCPI A", "10.00", "100.00", &max_str, "A"),
        &sofidy_total_row("10.00", &neg_max_str),
    );
    let err = sofidy::parse_portfolio(&sofidy_diff_html).unwrap_err();
    assert_eq!(err.code, "PORTFOLIO_INCOMPLETE");
}

#[tokio::test]
async fn sofidy_wrong_otp_does_not_extend_pending_ttl() {
    let bank = MockServer::start().await;
    Mock::given(method("GET"))
        .and(path(sofidy::HOME_PATH))
        .respond_with(
            ResponseTemplate::new(200)
                .insert_header("set-cookie", "PHPSESSID=sid1; Path=/")
                .set_body_string("<html>accueil</html>"),
        )
        .mount(&bank)
        .await;
    Mock::given(method("POST"))
        .and(path(sofidy::LOGIN_PATH))
        .respond_with(ResponseTemplate::new(200).set_body_string("2FA|email"))
        .mount(&bank)
        .await;
    Mock::given(method("POST"))
        .and(path(sofidy::VERIFY_2FA_PATH))
        .respond_with(ResponseTemplate::new(200).set_body_string("faux"))
        .mount(&bank)
        .await;

    let clock = Arc::new(picsou::clock::FixedClock::new(picsou::testing::epoch()));
    let sidecar = NativeSofidy::with_base_url_and_clock(&bank.uri(), clock.clone());
    let init = sidecar
        .call(
            Method::POST,
            "/initiate",
            json!({"associateCode": "123456", "password": "secret"}),
        )
        .await
        .expect("initiate ok");
    let pid = init["processId"].as_str().unwrap().to_owned();

    // Wrong OTP 200s later must NOT reset the 300s TTL.
    clock.advance(Duration::from_secs(200));
    let wrong = sidecar
        .call(
            Method::POST,
            "/complete",
            json!({"processId": pid, "code": "000000"}),
        )
        .await
        .unwrap_err();
    assert_eq!(
        (wrong.status, wrong.detail.as_str()),
        (StatusCode::UNAUTHORIZED, "MFA_INVALID")
    );

    // 110s later (310s total > 300s TTL, but < 300s since the wrong OTP), attempt must be expired.
    clock.advance(Duration::from_secs(110));
    let expired = sidecar
        .call(
            Method::POST,
            "/complete",
            json!({"processId": pid, "code": "123456"}),
        )
        .await
        .unwrap_err();
    assert_eq!(
        (expired.status, expired.detail.as_str()),
        (StatusCode::GONE, "AUTH_ATTEMPT_EXPIRED")
    );
}

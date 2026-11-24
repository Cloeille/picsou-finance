//! Natixis Interépargne native sidecar tests (parsers, operations, wiremock end-to-end, log hygiene).
//!
//! All upstream fixtures are synthetic, ported directly from `services/natixis-auth/`.

use std::collections::{HashMap, HashSet};
use std::io::Write;
use std::str::FromStr;
use std::sync::{Arc, Mutex};
use std::time::Duration;

use base64::Engine;
use base64::engine::general_purpose::URL_SAFE_NO_PAD;
use chrono::{TimeZone, Utc};
use http::{Method, StatusCode};
use picsou::clock::FixedClock;
use picsou::native_sidecars::natixis::operations_parser::{
    EMPLOYER_TOP_UP, external_id, finished_demands, investment_operations, operation_fund_ids,
    parse_operations, payment_operations,
};
use picsou::native_sidecars::natixis::savings_parser::{
    FORMAT_CHANGED, PORTFOLIO_INCOMPLETE, fund_code, fund_ids, money_close, parse_plans,
};
use picsou::native_sidecars::natixis::{
    DEFAULT_REDIRECT_URI, MAX_CODE_ATTEMPTS, NativeNatixis, NatixisConfig, parse_session,
};
use rust_decimal::Decimal;
use serde_json::{Value, json};
use sha2::{Digest, Sha256};
use tracing_subscriber::fmt::MakeWriter;
use wiremock::matchers::any;
use wiremock::{Mock, MockServer, Request, Respond, ResponseTemplate};

const EMPLOYEE_ID: &str = "10000-1000000000001";
const EMAIL: &str = "saver@example.org";
const PASSWORD: &str = "pw-12345678";
const CODE: &str = "424242";
const CSRF: &str = "csrf-value-0123456789";
const SAML_VALUE: &str = "saml-response-value";
const AUTH_CODE: &str = "authorization-code-value";
const ACCESS_TOKEN: &str = "access-token-value";
const REFRESH_TOKEN: &str = "refresh-token-value";
const VERIFICATION_ID: &str = "verification-id-value";

fn dec(s: &str) -> Decimal {
    Decimal::from_str(s).unwrap()
}

// ─── Synthetic Fixtures ─────────────────────────────────────────────────────

fn fixture_users_me() -> Value {
    json!({
        "customTheme": null,
        "features": null,
        "hasSeveralAccounts": false,
        "id": EMPLOYEE_ID,
        "lastConnectionDateTime": null,
        "locale": "fr",
        "role": "SAVER"
    })
}

fn fixture_auth_modes() -> Value {
    json!({ "items": [{ "mode": "PASSWORD" }, { "mode": "WEBAUTHN" }] })
}

fn fixture_strong_auth_required() -> Value {
    json!({
        "errors": [{
            "code": "strong_authentication_required",
            "message": "A strong authentication is required",
            "additionalInformation": { "requestId": "REQUEST-ID" }
        }]
    })
}

fn fixture_strong_auth_modes() -> Value {
    json!({
        "items": [
            { "id": "SMS", "status": "VALID", "additionalInformation": { "phoneNumber": "(+33)*****0000" } },
            { "id": "VOICE", "status": "VALID", "additionalInformation": { "phoneNumber": "(+33)*****0000" } }
        ]
    })
}

fn fixture_fund_detail(fund_id: i64) -> Value {
    json!({
        "id": fund_id,
        "identifiers": [{ "type": "QS", "value": format!("QS{fund_id:010}") }],
        "label": "FONDS ACTIONS EUROPE I",
        "lastValue": { "date": "2024-06-14", "amount": { "value": 25.5, "currencyCode": "EUR" } },
        "assetClass": "ACTION",
        "state": "ACTIVE"
    })
}

fn fixture_savings() -> Value {
    json!({
        "items": [
            {
                "id": "PEE-4242",
                "family": "PEE",
                "code": "PEE",
                "conventionDenomination": "UES ACME",
                "displayableProductCode": "PEE",
                "displayableProductLabel": "Plan d'Épargne Entreprise",
                "amount": { "value": 3625.8, "currencyCode": "EUR" },
                "gainsOrLossesAmount": { "value": 47.6, "currencyCode": "EUR" },
                "compartments": [
                    {
                        "type": "EMPLOYEE_SAVINGS",
                        "label": "EMPLOYEE_SAVINGS",
                        "amount": { "value": 3625.8, "currencyCode": "EUR" },
                        "investmentOptions": [
                            {
                                "id": "FUND--1001", "fundId": 1001, "type": "FUND",
                                "label": "FONDS ACTIONS EUROPE ISR R",
                                "lastValue": { "date": "2024-06-14", "amount": { "value": 10.0, "currencyCode": "EUR" } },
                                "units": 60.0,
                                "amount": { "value": 600.0, "currencyCode": "EUR" },
                                "gainsOrLossesAmount": { "value": 10.5, "currencyCode": "EUR" }
                            },
                            {
                                "id": "FUND--1002", "fundId": 1002, "type": "FUND",
                                "label": "FONDS ACTIONS EUROPE I",
                                "lastValue": { "date": "2024-06-14", "amount": { "value": 25.5, "currencyCode": "EUR" } },
                                "units": 24.0,
                                "amount": { "value": 612.0, "currencyCode": "EUR" },
                                "gainsOrLossesAmount": { "value": 10.8, "currencyCode": "EUR" }
                            },
                            {
                                "id": "FUND--1003", "fundId": 1003, "type": "FUND",
                                "label": "FONDS OBLIGATIONS EURO ISR R",
                                "lastValue": { "date": "2024-06-14", "amount": { "value": 102.3, "currencyCode": "EUR" } },
                                "units": 6.0,
                                "amount": { "value": 613.8, "currencyCode": "EUR" },
                                "gainsOrLossesAmount": { "value": -3.45, "currencyCode": "EUR" }
                            },
                            {
                                "id": "FUND--1004", "fundId": 1004, "type": "FUND",
                                "label": "FONDS ACTIONS MONDE I",
                                "lastValue": { "date": "2024-06-14", "amount": { "value": 120.0, "currencyCode": "EUR" } },
                                "units": 5.0,
                                "amount": { "value": 600.0, "currencyCode": "EUR" },
                                "gainsOrLossesAmount": { "value": 8.75, "currencyCode": "EUR" }
                            },
                            {
                                "id": "FUND--1005", "fundId": 1005, "type": "FUND",
                                "label": "FONDS MIXTE SOLIDAIRE R",
                                "lastValue": { "date": "2024-06-14", "amount": { "value": 150.0, "currencyCode": "EUR" } },
                                "units": 4.0,
                                "amount": { "value": 600.0, "currencyCode": "EUR" },
                                "gainsOrLossesAmount": { "value": 10.5, "currencyCode": "EUR" }
                            },
                            {
                                "id": "FUND--1006", "fundId": 1006, "type": "FUND",
                                "label": "FONDS CONVICTIONS MONDE I",
                                "lastValue": { "date": "2024-06-14", "amount": { "value": 15.0, "currencyCode": "EUR" } },
                                "units": 40.0,
                                "amount": { "value": 600.0, "currencyCode": "EUR" },
                                "gainsOrLossesAmount": { "value": 10.5, "currencyCode": "EUR" }
                            }
                        ]
                    },
                    {
                        "type": "VOLUNTARY_INVESTMENT",
                        "label": "VOLUNTARY_INVESTMENT",
                        "amount": { "value": 0.0, "currencyCode": "EUR" },
                        "subCompartments": [
                            {
                                "type": "PEE_INVESTMENTS",
                                "label": "PEE_INVESTMENTS",
                                "amount": { "value": 0.0, "currencyCode": "EUR" }
                            }
                        ]
                    }
                ]
            },
            {
                "id": "PERCO-4242",
                "family": "PER",
                "code": "PERCO",
                "conventionDenomination": "UES ACME",
                "displayableProductCode": "PERCOL",
                "displayableProductLabel": "PER Collectif",
                "amount": { "value": 0.0, "currencyCode": "EUR" },
                "gainsOrLossesAmount": { "value": 0.0, "currencyCode": "EUR" },
                "compartments": [
                    {
                        "type": "EMPLOYEE_SAVINGS",
                        "label": "EMPLOYEE_SAVINGS",
                        "amount": { "value": 0.0, "currencyCode": "EUR" }
                    },
                    {
                        "type": "VOLUNTARY_INVESTMENT",
                        "label": "VOLUNTARY_INVESTMENT",
                        "amount": { "value": 0.0, "currencyCode": "EUR" },
                        "subCompartments": [
                            { "type": "PER_INVESTMENTS", "label": "PER_INVESTMENTS", "amount": { "value": 0.0, "currencyCode": "EUR" } },
                            { "type": "ALLOWABLE_PAYMENTS", "label": "ALLOWABLE_PAYMENTS", "amount": { "value": 0.0, "currencyCode": "EUR" } }
                        ]
                    }
                ]
            }
        ],
        "additionalInformation": {
            "date": "2024-06-15",
            "amount": { "value": 3625.8, "currencyCode": "EUR" }
        }
    })
}

fn fixture_products() -> Value {
    json!({
        "items": [
            {
                "id": "TklFLTEtTklF",
                "code": "PEE",
                "internalAccount": true,
                "amounts": { "total": { "value": 3625.8, "currencyCode": "EUR" } }
            },
            {
                "id": "TklFLTctTklF",
                "code": "PERCOL",
                "internalAccount": true,
                "amounts": { "total": { "value": 0.0, "currencyCode": "EUR" } }
            }
        ],
        "additionalInformation": {
            "globalAmounts": { "total": { "value": 3625.8, "currencyCode": "EUR" } },
            "internalAmounts": { "total": { "value": 3625.8, "currencyCode": "EUR" } },
            "internalAmountsManagedByNIE": { "total": { "value": 3625.8, "currencyCode": "EUR" } }
        }
    })
}

fn fixture_transactions() -> Value {
    json!({
        "items": [
            {
                "_links": { "self": { "href": format!("/transaction/v1/employees/{EMPLOYEE_ID}/investmentDemands/2024-03-15-INT--10000001-") } },
                "date": "2024-03-15",
                "finished": true,
                "id": format!("{EMPLOYEE_ID}-INVESTMENT_DEMANDS-2024-03-15-INT--10000001-"),
                "status": "INVESTED",
                "type": "INVESTMENT_DEMANDS"
            },
            {
                "_links": { "self": { "href": format!("/transaction/v1/employees/{EMPLOYEE_ID}/paymentDemands/2023-11-20-IND-1") } },
                "date": "2023-11-20",
                "finished": true,
                "id": format!("{EMPLOYEE_ID}-PAYMENT_DEMANDS-2023-11-20-IND-1"),
                "status": "PAID",
                "type": "PAYMENT_DEMANDS"
            },
            {
                "_links": { "self": { "href": format!("/transaction/v1/employees/{EMPLOYEE_ID}/investmentDemands/2023-03-15-INT--10000001-") } },
                "date": "2023-03-15",
                "finished": true,
                "id": format!("{EMPLOYEE_ID}-INVESTMENT_DEMANDS-2023-03-15-INT--10000001-"),
                "status": "INVESTED",
                "type": "INVESTMENT_DEMANDS"
            }
        ],
        "additionalInformation": { "historyDepthMaxYears": 5 }
    })
}

#[allow(clippy::too_many_arguments)]
fn investment_line(
    fund_id: i64,
    fund_label: &str,
    own_date: &str,
    own_units: &str,
    own_unit_value: &str,
    own_net: &str,
    top_date: &str,
    top_units: &str,
    top_unit_value: &str,
    top_net: &str,
) -> Value {
    let product = json!({ "code": "PEE", "id": "PEE-4242" });
    json!({
        "id": format!("100000001-{fund_id}"),
        "fundId": fund_id,
        "fundLabel": fund_label,
        "type": "FUND",
        "status": "INVESTED",
        "investmentDate": own_date,
        "units": Decimal::from_str(own_units).unwrap(),
        "unitValue": Decimal::from_str(own_unit_value).unwrap(),
        "fees": { "value": 0.0, "currencyCode": "EUR" },
        "net": { "value": Decimal::from_str(own_net).unwrap(), "currencyCode": "EUR" },
        "netInvested": { "value": Decimal::from_str(own_net).unwrap(), "currencyCode": "EUR" },
        "product": product,
        "additionalInvestment": {
            "fundId": fund_id,
            "fundLabel": fund_label,
            "type": "FUND",
            "status": "INVESTED",
            "investmentDate": top_date,
            "units": Decimal::from_str(top_units).unwrap(),
            "unitValue": Decimal::from_str(top_unit_value).unwrap(),
            "fees": { "value": 0.0, "currencyCode": "EUR" },
            "net": { "value": Decimal::from_str(top_net).unwrap(), "currencyCode": "EUR" },
            "netInvested": { "value": Decimal::from_str(top_net).unwrap(), "currencyCode": "EUR" },
            "product": { "code": "PEE", "id": "PEE-4242" }
        }
    })
}

fn fixture_investment_demand() -> Value {
    json!({
        "id": "2024-03-15-INT--10000001-",
        "date": "2024-03-15",
        "originId": "INT",
        "originLabel": "Investissement de votre intéressement",
        "status": "INVESTED",
        "investments": [
            investment_line(1001, "FONDS ACTIONS EUROPE ISR R", "2024-03-28", "45.0", "9.8", "441.0", "2024-04-11", "15.0", "9.9", "148.5"),
            investment_line(1006, "FONDS CONVICTIONS MONDE I", "2024-03-29", "30.0", "14.7", "441.0", "2024-04-12", "10.0", "14.85", "148.5"),
            investment_line(1003, "FONDS OBLIGATIONS EURO ISR R", "2024-03-28", "4.5", "103.0", "463.5", "2024-04-11", "1.5", "102.5", "153.75"),
            investment_line(1005, "FONDS MIXTE SOLIDAIRE R", "2024-03-28", "3.0", "147.0", "441.0", "2024-04-11", "1.0", "148.5", "148.5"),
            investment_line(1004, "FONDS ACTIONS MONDE I", "2024-03-28", "3.75", "118.0", "442.5", "2024-04-11", "1.25", "119.0", "148.75"),
            investment_line(1002, "FONDS ACTIONS EUROPE I", "2024-03-28", "18.0", "25.0", "450.0", "2024-04-11", "6.0", "25.2", "151.2")
        ]
    })
}

fn fixture_payment_demand() -> Value {
    json!({
        "id": "2023-11-20-IND-1",
        "date": "2023-11-20",
        "status": "PAID",
        "type": "EARLY_RELEASE",
        "payments": [
            {
                "date": "2023-11-22",
                "id": "2023-11-20-IND-1-NATIXIS_INTEREPARGNE-1000001",
                "products": [{
                    "id": "PEE-4242",
                    "code": "PEE",
                    "investmentOptions": [
                        {
                            "id": "FUND--1001", "fundId": 1001, "type": "FUND",
                            "label": "FONDS ACTIONS EUROPE ISR R", "units": 40.0,
                            "gross": { "value": 380.0, "currencyCode": "EUR" },
                            "disvestmentValue": { "date": "2023-11-21", "amount": { "value": 9.5, "currencyCode": "EUR" } }
                        },
                        {
                            "id": "FUND--1005", "fundId": 1005, "type": "FUND",
                            "label": "FONDS MIXTE SOLIDAIRE R", "units": 2.5,
                            "gross": { "value": 370.0, "currencyCode": "EUR" },
                            "disvestmentValue": { "date": "2023-11-21", "amount": { "value": 148.0, "currencyCode": "EUR" } }
                        },
                        {
                            "id": "FUND--1007", "fundId": 1007, "type": "FUND",
                            "label": "FONDS ANCIEN 1 R", "units": 70.0,
                            "gross": { "value": 364.0, "currencyCode": "EUR" },
                            "disvestmentValue": { "date": "2023-11-21", "amount": { "value": 5.2, "currencyCode": "EUR" } }
                        }
                    ]
                }]
            },
            {
                "date": "2023-11-23",
                "id": "2023-11-20-IND-1-NATIXIS_INTEREPARGNE-1000001",
                "products": [{
                    "id": "PEE-4242",
                    "code": "PEE",
                    "investmentOptions": [
                        {
                            "id": "FUND--1006", "fundId": 1006, "type": "FUND",
                            "label": "FONDS CONVICTIONS MONDE I", "units": 25.0,
                            "gross": { "value": 370.0, "currencyCode": "EUR" },
                            "disvestmentValue": { "date": "2023-11-22", "amount": { "value": 14.8, "currencyCode": "EUR" } }
                        }
                    ]
                }]
            }
        ]
    })
}

// ─── Savings Parser Tests (test_parsers.py) ─────────────────────────────────

#[test]
fn savings_captured_plans_reconcile() {
    let result = parse_plans(
        &fixture_savings(),
        &fixture_products(),
        EMPLOYEE_ID,
        &HashMap::new(),
    )
    .unwrap();
    let kinds: Vec<_> = result.iter().map(|p| p.plan_kind.as_deref()).collect();
    assert_eq!(kinds, vec![Some("PEE"), Some("PER")]);
    let pee = &result[0];
    let perco = &result[1];
    assert_eq!(pee.external_id, format!("natixis_{EMPLOYEE_ID}_PEE-4242"));
    assert_eq!(pee.name, "Plan d'Épargne Entreprise");
    assert_eq!(pee.employer.as_deref(), Some("UES ACME"));
    assert_eq!(pee.positions.len(), 6);
    let total: Decimal = pee.positions.iter().map(|p| p.value_eur).sum();
    assert!(money_close(total, pee.balance_eur));
    assert_eq!(perco.balance_eur, Decimal::ZERO);
    assert!(perco.positions.is_empty());
}

#[test]
fn savings_position_fields_and_qs_code() {
    let mut codes = HashMap::new();
    codes.insert(1002, Some("QS0000001002".to_owned()));
    let plans = parse_plans(&fixture_savings(), &fixture_products(), EMPLOYEE_ID, &codes).unwrap();
    let line = plans[0]
        .positions
        .iter()
        .find(|p| p.label == "FONDS ACTIONS EUROPE I")
        .unwrap();
    assert_eq!(line.isin, None);
    assert_eq!(line.fund_code.as_deref(), Some("QS0000001002"));
    assert!(line.quantity > Decimal::ZERO);
    assert_eq!(line.unit_value, Some(dec("25.5")));
    assert!(line.pnl_eur.is_some());
    assert!(money_close(
        line.quantity * line.unit_value.unwrap(),
        line.value_eur
    ));
}

#[test]
fn savings_fund_held_in_two_compartments_is_merged_into_one_line() {
    let mut savings = fixture_savings();
    let pee = &mut savings["items"][0];
    let mut moved = pee["compartments"][0]["investmentOptions"][0].clone();
    pee["compartments"][0]["investmentOptions"][0]["amount"]["value"] = json!(300.0);
    pee["compartments"][0]["investmentOptions"][0]["units"] = json!(30.0);
    moved["amount"]["value"] = json!(300.0);
    moved["units"] = json!(30.0);
    pee["compartments"][1]["investmentOptions"] = json!([moved]);

    let result = parse_plans(&savings, &fixture_products(), EMPLOYEE_ID, &HashMap::new()).unwrap();
    assert_eq!(result[0].positions.len(), 6);
    let merged = result[0]
        .positions
        .iter()
        .find(|p| p.label == "FONDS ACTIONS EUROPE ISR R")
        .unwrap();
    assert!(money_close(merged.value_eur, dec("600.0")));
    assert_eq!(merged.quantity, dec("60.0"));
}

#[test]
fn savings_missing_line_or_plan_is_portfolio_incomplete() {
    let mut savings = fixture_savings();
    savings["items"][0]["compartments"][0]["investmentOptions"]
        .as_array_mut()
        .unwrap()
        .pop();
    let err = parse_plans(&savings, &fixture_products(), EMPLOYEE_ID, &HashMap::new()).unwrap_err();
    assert_eq!(err.code, PORTFOLIO_INCOMPLETE);

    let mut savings2 = fixture_savings();
    savings2["items"].as_array_mut().unwrap().remove(0);
    let err2 =
        parse_plans(&savings2, &fixture_products(), EMPLOYEE_ID, &HashMap::new()).unwrap_err();
    assert_eq!(err2.code, PORTFOLIO_INCOMPLETE);

    let mut products = fixture_products();
    let extra = products["items"][0].clone();
    products["items"].as_array_mut().unwrap().push(extra);
    let err3 =
        parse_plans(&fixture_savings(), &products, EMPLOYEE_ID, &HashMap::new()).unwrap_err();
    assert_eq!(err3.code, PORTFOLIO_INCOMPLETE);
}

#[test]
fn savings_drift_within_tolerance_is_accepted() {
    let mut savings = fixture_savings();
    savings["items"][0]["compartments"][0]["investmentOptions"][0]["amount"]["value"] =
        json!(600.04);
    let plans = parse_plans(&savings, &fixture_products(), EMPLOYEE_ID, &HashMap::new()).unwrap();
    assert_eq!(plans.len(), 2);
}

#[test]
fn savings_unexpected_shapes_are_format_changed() {
    type JsonMutator = Box<dyn Fn(&mut Value)>;
    let mutations: Vec<JsonMutator> = vec![
        Box::new(|s| {
            s.as_object_mut().unwrap().remove("items");
        }),
        Box::new(|s| {
            s["items"][0].as_object_mut().unwrap().remove("amount");
        }),
        Box::new(|s| {
            s["items"][0]["compartments"][0]["investmentOptions"][0]["units"] = json!("many");
        }),
        Box::new(|s| {
            s["items"][0]["compartments"][0]["investmentOptions"][0]["type"] = json!("CASH");
        }),
        Box::new(|s| {
            s["items"][0].as_object_mut().unwrap().remove("id");
        }),
    ];
    for mutate in mutations {
        let mut savings = fixture_savings();
        mutate(&mut savings);
        let err =
            parse_plans(&savings, &fixture_products(), EMPLOYEE_ID, &HashMap::new()).unwrap_err();
        assert_eq!(err.code, FORMAT_CHANGED);
    }
}

#[test]
fn savings_non_eur_or_missing_currency_is_format_changed() {
    fn set_currency(node: &mut Value, code: Option<&str>) {
        match node {
            Value::Object(map) => {
                if map.contains_key("currencyCode") {
                    match code {
                        Some(c) => {
                            map.insert("currencyCode".to_owned(), json!(c));
                        }
                        None => {
                            map.remove("currencyCode");
                        }
                    }
                }
                for v in map.values_mut() {
                    set_currency(v, code);
                }
            }
            Value::Array(arr) => {
                for v in arr {
                    set_currency(v, code);
                }
            }
            _ => {}
        }
    }

    for code in [Some("USD"), None] {
        let mut savings = fixture_savings();
        set_currency(&mut savings, code);
        let err =
            parse_plans(&savings, &fixture_products(), EMPLOYEE_ID, &HashMap::new()).unwrap_err();
        assert_eq!(err.code, FORMAT_CHANGED);
        assert!(err.message.contains("not in EUR"));

        let mut products = fixture_products();
        set_currency(&mut products, code);
        let err =
            parse_plans(&fixture_savings(), &products, EMPLOYEE_ID, &HashMap::new()).unwrap_err();
        assert_eq!(err.code, FORMAT_CHANGED);
        assert!(err.message.contains("not in EUR"));
    }
}

#[test]
fn savings_optional_amount_without_value_is_none_and_sub_compartments_work() {
    let mut savings = fixture_savings();
    let label = savings["items"][0]["compartments"][0]["investmentOptions"][0]["label"]
        .as_str()
        .unwrap()
        .to_owned();
    savings["items"][0]["compartments"][0]["investmentOptions"][0]["gainsOrLossesAmount"] =
        json!({ "value": null });
    let plans = parse_plans(&savings, &fixture_products(), EMPLOYEE_ID, &HashMap::new()).unwrap();
    let line = plans[0]
        .positions
        .iter()
        .find(|p| p.label == label)
        .unwrap();
    assert_eq!(line.pnl_eur, None);

    // Move one fund into subCompartments
    let mut savings2 = fixture_savings();
    let moved = savings2["items"][0]["compartments"][0]["investmentOptions"]
        .as_array_mut()
        .unwrap()
        .pop()
        .unwrap();
    let moved_label = moved["label"].as_str().unwrap().to_owned();
    savings2["items"][0]["compartments"][1]["subCompartments"][0]["investmentOptions"] =
        json!([moved]);
    let plans2 = parse_plans(&savings2, &fixture_products(), EMPLOYEE_ID, &HashMap::new()).unwrap();
    assert!(plans2[0].positions.iter().any(|p| p.label == moved_label));
}

#[test]
fn savings_sub_compartments_nesting_past_depth_five_is_rejected() {
    let mut savings = fixture_savings();
    let mut deep = json!({ "type": "DEEP" });
    for _ in 0..6 {
        deep = json!({ "type": "NESTED", "subCompartments": [deep] });
    }
    savings["items"][0]["compartments"]
        .as_array_mut()
        .unwrap()
        .push(deep);
    let err = parse_plans(&savings, &fixture_products(), EMPLOYEE_ID, &HashMap::new()).unwrap_err();
    assert_eq!(err.code, FORMAT_CHANGED);
}

#[test]
fn savings_edge_cases_duplicate_labels_long_id_empty_portfolio_fund_helpers() {
    // Same label without fundId rejected
    let mut savings = fixture_savings();
    for i in 0..2 {
        let opt = savings["items"][0]["compartments"][0]["investmentOptions"][i]
            .as_object_mut()
            .unwrap();
        opt.remove("fundId");
        opt.insert("label".to_owned(), json!("SAME FUND"));
    }
    assert_eq!(
        parse_plans(&savings, &fixture_products(), EMPLOYEE_ID, &HashMap::new())
            .unwrap_err()
            .code,
        FORMAT_CHANGED
    );

    // Fund without QS code keeps NIE-<id>
    let plans = parse_plans(
        &fixture_savings(),
        &fixture_products(),
        EMPLOYEE_ID,
        &HashMap::new(),
    )
    .unwrap();
    assert!(
        plans[0].positions[0]
            .fund_code
            .as_deref()
            .unwrap()
            .starts_with("NIE-")
    );

    // Too-long plan id rejected
    let mut long_savings = fixture_savings();
    long_savings["items"][0]["id"] = json!("P".repeat(120));
    assert_eq!(
        parse_plans(
            &long_savings,
            &fixture_products(),
            EMPLOYEE_ID,
            &HashMap::new()
        )
        .unwrap_err()
        .code,
        FORMAT_CHANGED
    );

    // Empty portfolio is valid
    let mut empty_products = fixture_products();
    empty_products["items"] = json!([]);
    for v in empty_products["additionalInformation"]
        .as_object_mut()
        .unwrap()
        .values_mut()
    {
        v["total"]["value"] = json!(0);
    }
    assert!(
        parse_plans(
            &json!({ "items": [] }),
            &empty_products,
            EMPLOYEE_ID,
            &HashMap::new()
        )
        .unwrap()
        .is_empty()
    );

    // Products without totals rejected
    assert_eq!(
        parse_plans(
            &fixture_savings(),
            &json!({ "items": [] }),
            EMPLOYEE_ID,
            &HashMap::new()
        )
        .unwrap_err()
        .code,
        FORMAT_CHANGED
    );

    // fund_ids & fund_code helpers
    let ids = fund_ids(&fixture_savings()).unwrap();
    assert_eq!(ids, vec![1001, 1002, 1003, 1004, 1005, 1006]);
    assert_eq!(
        fund_code(&fixture_fund_detail(1002)).as_deref(),
        Some("QS0000001002")
    );
    assert_eq!(
        fund_code(&json!({ "identifiers": [{ "type": "ISIN", "value": "FR0000000000" }] })),
        None
    );
    assert_eq!(fund_code(&json!({})), None);
}

// ─── Operations Parser Tests (test_operations.py) ───────────────────────────

#[test]
fn operations_finished_demands_and_link_validation() {
    let demands = finished_demands(&fixture_transactions(), EMPLOYEE_ID).unwrap();
    let types: Vec<_> = demands.iter().map(|d| d.kind.as_str()).collect();
    assert_eq!(
        types,
        vec![
            "INVESTMENT_DEMANDS",
            "PAYMENT_DEMANDS",
            "INVESTMENT_DEMANDS"
        ]
    );
    assert!(demands.iter().all(|d| {
        d.path
            .starts_with(&format!("/transaction/v1/employees/{EMPLOYEE_ID}/"))
    }));

    // Unfinished and unknown skipped
    let mut tx = fixture_transactions();
    tx["items"][0]["finished"] = json!(false);
    tx["items"][1]["type"] = json!("ARBITRATION_DEMANDS");
    assert_eq!(finished_demands(&tx, EMPLOYEE_ID).unwrap().len(), 1);

    // Listing without items fails
    assert!(finished_demands(&json!({}), EMPLOYEE_ID).is_err());

    // Absolute API link rebuilt to relative path
    let mut abs_tx = fixture_transactions();
    let orig_href = abs_tx["items"][0]["_links"]["self"]["href"]
        .as_str()
        .unwrap()
        .to_owned();
    abs_tx["items"][0]["_links"]["self"]["href"] =
        json!(format!("https://nie.api.natixis.com{orig_href}"));
    assert_eq!(
        finished_demands(&abs_tx, EMPLOYEE_ID).unwrap()[0].path,
        format!(
            "/transaction/v1/employees/{EMPLOYEE_ID}/investmentDemands/2024-03-15-INT--10000001-"
        )
    );

    // Malicious / out-of-space links rejected
    let base = format!("/transaction/v1/employees/{EMPLOYEE_ID}");
    let bad_hrefs = [
        format!("{base}/investmentDemands/../../../../auth/v3/users/me"),
        format!("{base}/investmentDemands/.."),
        format!("{base}/investmentDemands/%2e%2e"),
        format!("{base}/investmentDemands/a%2Fb"),
        format!("{base}/investmentDemands//x"),
        format!("{base}/investmentDemands/x?y=1"),
        format!("{base}/investmentDemands/x#y"),
        format!("{base}/investmentDemands/"),
        format!("{base}/paymentDemands/2024-03-15-INT--10000001-"),
        "/transaction/v1/employees/99999-1/investmentDemands/x".to_owned(),
        format!("http://nie.api.natixis.com{base}/investmentDemands/x"),
        format!("https://user@nie.api.natixis.com{base}/investmentDemands/x"),
        format!("https://nie.api.natixis.com:8443{base}/investmentDemands/x"),
        format!("https://evil.example{base}/investmentDemands/x"),
        format!("//evil.example{base}/investmentDemands/x"),
        format!("{base}/investmentDemands/x\n"),
        format!("{base}/investmentDemands/x\ty"),
        format!("{base}/investmentDemands/x y"),
        format!("{base}/investmentDemands/x\u{00e9}"),
    ];
    for href in bad_hrefs {
        let mut bad = fixture_transactions();
        bad["items"][0]["_links"]["self"]["href"] = json!(href);
        assert!(
            finished_demands(&bad, EMPLOYEE_ID).is_err(),
            "href should be rejected: {href:?}"
        );
    }
}

#[test]
fn operations_investments_payments_and_hashing() {
    let ops =
        investment_operations(&fixture_investment_demand(), EMPLOYEE_ID, &HashMap::new()).unwrap();
    assert_eq!(ops.len(), 12);
    let own: Vec<_> = ops.iter().filter(|o| o.label != EMPLOYER_TOP_UP).collect();
    let top_up: Vec<_> = ops.iter().filter(|o| o.label == EMPLOYER_TOP_UP).collect();
    assert_eq!(own.len(), 6);
    assert_eq!(top_up.len(), 6);
    assert_eq!(own[0].kind, "BUY");
    assert_eq!(
        own[0].plan_external_id,
        format!("natixis_{EMPLOYEE_ID}_PEE-4242")
    );
    assert_eq!(own[0].quantity, dec("45.0"));
    assert_eq!(own[0].date, "2024-03-28");
    assert_eq!(own[0].fund_code.as_deref(), Some("NIE-1001"));

    // Zero-unit parts skipped
    let mut zero_top = fixture_investment_demand();
    for line in zero_top["investments"].as_array_mut().unwrap() {
        line["additionalInvestment"]["units"] = json!(0);
    }
    assert_eq!(
        investment_operations(&zero_top, EMPLOYEE_ID, &HashMap::new())
            .unwrap()
            .len(),
        6
    );

    // Payments -> SELL
    let mut codes = HashMap::new();
    codes.insert(1001, Some("QS0000001001".to_owned()));
    let sells = payment_operations(&fixture_payment_demand(), EMPLOYEE_ID, &codes).unwrap();
    assert_eq!(sells.len(), 4);
    assert!(sells.iter().all(|s| s.kind == "SELL"));
    assert!(sells.iter().all(|s| s.label == "Déblocage anticipé"));
    assert_eq!(sells[0].fund_code.as_deref(), Some("QS0000001001"));
    assert_eq!(sells[0].amount_eur, dec("380.0"));
    assert_eq!(sells[0].date, "2023-11-21");

    // Non-EUR sold amount rejected
    let mut usd_payment = fixture_payment_demand();
    usd_payment["payments"][0]["products"][0]["investmentOptions"][0]["gross"]["currencyCode"] =
        json!("USD");
    let err = payment_operations(&usd_payment, EMPLOYEE_ID, &HashMap::new()).unwrap_err();
    assert_eq!(err.code, FORMAT_CHANGED);
    assert!(err.message.contains("not in EUR"));

    // Missing currency on invested amount rejected
    let mut no_cur_inv = fixture_investment_demand();
    for line in no_cur_inv["investments"].as_array_mut().unwrap() {
        for key in ["netInvested", "net"] {
            line[key].as_object_mut().unwrap().remove("currencyCode");
            line["additionalInvestment"][key]
                .as_object_mut()
                .unwrap()
                .remove("currencyCode");
        }
    }
    assert_eq!(
        investment_operations(&no_cur_inv, EMPLOYEE_ID, &HashMap::new())
            .unwrap_err()
            .code,
        FORMAT_CHANGED
    );

    // Sold-out fund included in operation_fund_ids
    assert!(operation_fund_ids(&[fixture_payment_demand()]).contains(&1007));

    // Unique bounded ids & duplicate rejection
    let demands = finished_demands(&fixture_transactions(), EMPLOYEE_ID).unwrap();
    let details: Vec<Value> = demands
        .iter()
        .map(|d| {
            let mut v = if d.kind == "INVESTMENT_DEMANDS" {
                fixture_investment_demand()
            } else {
                fixture_payment_demand()
            };
            v["id"] = json!(d.id);
            v
        })
        .collect();
    let all_ops = parse_operations(&demands, &details, EMPLOYEE_ID, &HashMap::new()).unwrap();
    let unique: HashSet<_> = all_ops.iter().map(|o| o.external_id.as_str()).collect();
    assert_eq!(unique.len(), all_ops.len());
    assert!(all_ops.iter().all(|o| o.external_id.chars().count() <= 100));

    let dup_demands = vec![demands[0].clone(), demands[0].clone()];
    assert!(
        parse_operations(
            &dup_demands,
            &[fixture_investment_demand(), fixture_investment_demand()],
            EMPLOYEE_ID,
            &HashMap::new()
        )
        .is_err()
    );

    // Long external_id hashed, not truncated
    let x120 = "X".repeat(120);
    let x121 = "X".repeat(121);
    let id1 = external_id(EMPLOYEE_ID, &[&x120, "line"]);
    let id2 = external_id(EMPLOYEE_ID, &[&x121, "line"]);
    assert!(id1.chars().count() <= 100);
    assert_ne!(id1, id2);
}

// ─── Wiremock End-to-End Harness (test_main.py) ─────────────────────────────

struct FakeState {
    base_url: String,
    login_modes: Value,
    strong_modes: Value,
    authorize_html: Option<String>,
    password_status: u16,
    code_status: u16,
    token_status: u16,
    api_status: u16,
    fund_status: u16,
    operations_status: u16,
    detail_status: u16,
    detail_delay: Duration,
    fund_delay: Duration,
    password_delay: Duration,
    code_challenge: Option<String>,
    exchanged_verifier: Option<String>,
    service_provider_url: Option<String>,
    savings: Value,
    products: Value,
    transactions: Value,
    investment_demand: Value,
    returned_refresh_token: String,
    oauth_state: Option<String>,
    requests: Vec<(String, String)>,
    require_sso_cookie: bool,
    cookie_counter: usize,
    seen_complete_cookies: Vec<String>,
}

impl FakeState {
    fn new(base_url: String) -> Self {
        Self {
            base_url,
            login_modes: fixture_auth_modes(),
            strong_modes: fixture_strong_auth_modes(),
            authorize_html: None,
            password_status: 201,
            code_status: 201,
            token_status: 200,
            api_status: 200,
            fund_status: 200,
            operations_status: 200,
            detail_status: 200,
            detail_delay: Duration::ZERO,
            fund_delay: Duration::ZERO,
            password_delay: Duration::ZERO,
            code_challenge: None,
            exchanged_verifier: None,
            service_provider_url: None,
            savings: fixture_savings(),
            products: fixture_products(),
            transactions: fixture_transactions(),
            investment_demand: fixture_investment_demand(),
            returned_refresh_token: REFRESH_TOKEN.to_owned(),
            oauth_state: None,
            requests: Vec::new(),
            require_sso_cookie: false,
            cookie_counter: 0,
            seen_complete_cookies: Vec::new(),
        }
    }
}

struct FakeResponder {
    state: Arc<Mutex<FakeState>>,
}

fn parse_form(bytes: &[u8]) -> HashMap<String, String> {
    url::form_urlencoded::parse(bytes)
        .map(|(k, v)| (k.into_owned(), v.into_owned()))
        .collect()
}

impl Respond for FakeResponder {
    fn respond(&self, request: &Request) -> ResponseTemplate {
        let method = request.method.as_str().to_owned();
        let path = request.url.path().to_owned();
        let mut st = self.state.lock().unwrap();
        st.requests.push((method.clone(), path.clone()));

        if method == "GET" && path == "/api/oauth/authorize" {
            for (k, v) in request.url.query_pairs() {
                if k == "state" {
                    st.oauth_state = Some(v.into_owned());
                } else if k == "code_challenge" {
                    st.code_challenge = Some(v.into_owned());
                }
            }
            let html = st.authorize_html.clone().unwrap_or_else(|| {
                format!(
                    r#"<html><body><form method="post" action="{}/auth/nie-gateway/saml2/idp/sso"><input type="hidden" name="SAMLRequest" value="request&#x3d;"/></form></body></html>"#,
                    st.base_url
                )
            });
            return ResponseTemplate::new(200).set_body_raw(html, "text/html");
        }

        if method == "POST" && path == "/auth/nie-gateway/saml2/idp/sso" {
            let form = parse_form(&request.body);
            if form.get("SAMLRequest").map(String::as_str) != Some("request=") {
                return ResponseTemplate::new(400);
            }
            st.cookie_counter += 1;
            let cookie_val = format!("SSO_SESSION=sess-{}", st.cookie_counter);
            let html = format!(
                r#"<html><head><meta name="_csrf" content="{CSRF}"/></head><body></body></html>"#
            );
            return ResponseTemplate::new(200)
                .insert_header("set-cookie", format!("{cookie_val}; Path=/"))
                .set_body_raw(html, "text/html");
        }

        if path.starts_with("/auth/v1/") {
            let csrf = request
                .headers
                .get("x-csrf-token")
                .and_then(|v| v.to_str().ok());
            if csrf != Some(CSRF) {
                return ResponseTemplate::new(403)
                    .set_body_json(json!({ "errors": [{ "code": "csrf" }] }));
            }
            if st.require_sso_cookie {
                let has_cookie = request
                    .headers
                    .get("cookie")
                    .and_then(|v| v.to_str().ok())
                    .is_some_and(|c| c.contains("SSO_SESSION=sess-"));
                if !has_cookie {
                    return ResponseTemplate::new(401)
                        .set_body_json(json!({ "errors": [{ "code": "missing_cookie" }] }));
                }
            }
            if path == "/auth/v1/authenticationModes/search" {
                return ResponseTemplate::new(200).set_body_json(st.login_modes.clone());
            }
            if path == "/auth/v1/authenticate" {
                let mut resp = ResponseTemplate::new(st.password_status)
                    .set_body_json(json!({ "link": { "href": "/x", "rel": "MFA" } }));
                if !st.password_delay.is_zero() {
                    resp = resp.set_delay(st.password_delay);
                }
                return resp;
            }
            if path == "/auth/v1/multiFactorsAuthenticate" {
                let strong = request
                    .headers
                    .get("strong-authentication")
                    .and_then(|v| v.to_str().ok());
                if strong == Some(VERIFICATION_ID) {
                    return ResponseTemplate::new(201).set_body_json(
                        json!({ "link": { "href": "/y", "rel": "PROFILE_VERIFICATION" } }),
                    );
                }
                return ResponseTemplate::new(403).set_body_json(fixture_strong_auth_required());
            }
            if path == "/auth/v1/strongAuthenticationModes" {
                return ResponseTemplate::new(200).set_body_json(st.strong_modes.clone());
            }
            if path == "/auth/v1/strongAuthentications" {
                return ResponseTemplate::new(201).set_body_json(json!({
                    "id": "attempt-1",
                    "mode": "SMS",
                    "expiresIn": 299000,
                    "canRetryIn": 239000,
                    "_links": { "verification": { "href": "/auth/v1/strongAuthentications/attempt-1/verifications" } }
                }));
            }
            if path == "/auth/v1/strongAuthentications/attempt-1/verifications" {
                if let Some(cookie) = request.headers.get("cookie").and_then(|v| v.to_str().ok()) {
                    st.seen_complete_cookies.push(cookie.to_owned());
                }
                if st.code_status != 201 {
                    return ResponseTemplate::new(st.code_status)
                        .set_body_json(json!({ "errors": [{ "code": "invalid_code" }] }));
                }
                return ResponseTemplate::new(201).set_body_json(json!({ "id": VERIFICATION_ID }));
            }
            if path == "/auth/v1/profiles/me/verifications" {
                let sp = st
                    .service_provider_url
                    .clone()
                    .unwrap_or_else(|| format!("{}/api/oauth/authorize", st.base_url));
                return ResponseTemplate::new(201).set_body_json(json!({
                    "saml": {
                        "encodedValue": SAML_VALUE,
                        "serviceProviderUrl": sp
                    }
                }));
            }
            return ResponseTemplate::new(404);
        }

        if method == "POST" && path == "/api/oauth/authorize" {
            let state_val = st.oauth_state.clone().unwrap_or_default();
            let location = format!("{DEFAULT_REDIRECT_URI}?code={AUTH_CODE}&state={state_val}");
            return ResponseTemplate::new(302).insert_header("location", location);
        }

        if method == "POST" && path == "/api/oauth/token" {
            let form = parse_form(&request.body);
            if form.get("grant_type").map(String::as_str) == Some("authorization_code") {
                let verifier = form.get("code_verifier").cloned().unwrap_or_default();
                let digest = URL_SAFE_NO_PAD.encode(Sha256::digest(verifier.as_bytes()));
                if st.code_challenge.as_deref() != Some(digest.as_str()) {
                    return ResponseTemplate::new(400)
                        .set_body_json(json!({ "error": "invalid_grant" }));
                }
                st.exchanged_verifier = Some(verifier);
            }
            if st.token_status != 200 {
                return ResponseTemplate::new(st.token_status)
                    .set_body_json(json!({ "error": "invalid_grant" }));
            }
            return ResponseTemplate::new(200).set_body_json(json!({
                "access_token": ACCESS_TOKEN,
                "refresh_token": st.returned_refresh_token,
                "token_type": "Bearer",
                "expires_in": 1800
            }));
        }

        // API endpoints
        if st.api_status != 200 {
            return ResponseTemplate::new(st.api_status).set_body_json(json!({}));
        }
        if path == "/auth/v3/users/me" {
            return ResponseTemplate::new(200).set_body_json(fixture_users_me());
        }
        if path.ends_with("/savings") {
            return ResponseTemplate::new(200).set_body_json(st.savings.clone());
        }
        if path.ends_with("/products") {
            return ResponseTemplate::new(200).set_body_json(st.products.clone());
        }
        if path.starts_with("/transaction/v1/employees/") {
            if st.operations_status != 200 {
                return ResponseTemplate::new(st.operations_status).set_body_json(json!({}));
            }
            if path.ends_with("/transactions") {
                return ResponseTemplate::new(200).set_body_json(st.transactions.clone());
            }
            if st.detail_status != 200 {
                return ResponseTemplate::new(st.detail_status).set_body_json(json!({}));
            }
            let demand_id = path.rsplit('/').next().unwrap_or("");
            let mut body = if path.contains("/investmentDemands/") {
                st.investment_demand.clone()
            } else if path.contains("/paymentDemands/") {
                fixture_payment_demand()
            } else {
                return ResponseTemplate::new(404);
            };
            body["id"] = json!(demand_id);
            let mut resp = ResponseTemplate::new(200).set_body_json(body);
            if !st.detail_delay.is_zero() {
                resp = resp.set_delay(st.detail_delay);
            }
            return resp;
        }
        if let Some(id_str) = path.strip_prefix("/fund/v1/funds/") {
            if st.fund_status != 200 {
                return ResponseTemplate::new(st.fund_status).set_body_json(json!({}));
            }
            let fund_id: i64 = id_str.parse().unwrap_or(0);
            let mut resp = ResponseTemplate::new(200).set_body_json(fixture_fund_detail(fund_id));
            if !st.fund_delay.is_zero() {
                resp = resp.set_delay(st.fund_delay);
            }
            return resp;
        }
        ResponseTemplate::new(404)
    }
}

struct Harness {
    _server: MockServer,
    state: Arc<Mutex<FakeState>>,
    clock: Arc<FixedClock>,
    sidecar: NativeNatixis,
}

impl Harness {
    async fn start() -> Self {
        Self::start_with(|c| c).await
    }

    async fn start_with(customize: impl FnOnce(NatixisConfig) -> NatixisConfig) -> Self {
        let server = MockServer::start().await;
        let state = Arc::new(Mutex::new(FakeState::new(server.uri())));
        Mock::given(any())
            .respond_with(FakeResponder {
                state: state.clone(),
            })
            .mount(&server)
            .await;
        let clock = Arc::new(FixedClock::new(
            Utc.with_ymd_and_hms(2025, 6, 15, 10, 0, 0).unwrap(),
        ));
        let config =
            customize(NatixisConfig::for_mock_server(&server.uri()).with_clock(clock.clone()));
        let sidecar = NativeNatixis::with_config(config);
        Self {
            _server: server,
            state,
            clock,
            sidecar,
        }
    }

    async fn initiate(&self) -> Result<Value, (StatusCode, String)> {
        self.sidecar
            .call(
                Method::POST,
                "/initiate",
                Some(json!({ "email": EMAIL, "password": PASSWORD })),
            )
            .await
    }

    async fn login(&self) -> Result<Value, (StatusCode, String)> {
        let init = self.initiate().await?;
        let pid = init["processId"].as_str().unwrap().to_owned();
        self.sidecar
            .call(
                Method::POST,
                "/complete",
                Some(json!({ "processId": pid, "code": CODE })),
            )
            .await
    }

    async fn positions(&self, session: &str) -> Result<Value, (StatusCode, String)> {
        self.sidecar
            .call(
                Method::POST,
                "/positions",
                Some(json!({ "sessionState": session })),
            )
            .await
    }
}

fn assert_plans_without_history(body: &Value) {
    assert_eq!(body["operationsComplete"], json!(false));
    assert_eq!(body["operationsFrom"], Value::Null);
    assert_eq!(body["operations"], json!([]));
    assert_eq!(body["plans"].as_array().unwrap().len(), 2);
}

// ─── Route & Contract Tests ─────────────────────────────────────────────────

#[tokio::test]
async fn health_routing_and_strict_body_validation() {
    let h = Harness::start().await;

    let health = h.sidecar.call(Method::GET, "/health", None).await.unwrap();
    assert_eq!(health, json!({ "status": "ok" }));

    assert_eq!(
        h.sidecar
            .call(Method::POST, "/health", None)
            .await
            .unwrap_err(),
        (
            StatusCode::METHOD_NOT_ALLOWED,
            "METHOD_NOT_ALLOWED".to_owned()
        )
    );
    assert_eq!(
        h.sidecar
            .call(Method::GET, "/unknown", None)
            .await
            .unwrap_err(),
        (StatusCode::NOT_FOUND, "NOT_FOUND".to_owned())
    );
    // Extra field forbidden on every POST endpoint
    assert_eq!(
        h.sidecar
            .call(
                Method::POST,
                "/initiate",
                Some(json!({ "email": EMAIL, "password": PASSWORD, "extra": 1 }))
            )
            .await
            .unwrap_err(),
        (StatusCode::BAD_REQUEST, "INVALID_DATA".to_owned())
    );
    assert_eq!(
        h.sidecar
            .call(
                Method::POST,
                "/cancel",
                Some(json!({ "processId": "p1", "extra": 1 }))
            )
            .await
            .unwrap_err(),
        (StatusCode::BAD_REQUEST, "INVALID_DATA".to_owned())
    );
    assert_eq!(
        h.sidecar
            .call(
                Method::POST,
                "/positions",
                Some(json!({ "sessionState": "{}", "extra": 1 }))
            )
            .await
            .unwrap_err(),
        (StatusCode::BAD_REQUEST, "INVALID_DATA".to_owned())
    );
}

#[tokio::test]
async fn happy_path_login_pkce_cookies_and_positions_with_operations() {
    let h = Harness::start().await;
    h.state.lock().unwrap().require_sso_cookie = true;

    let init = h.initiate().await.unwrap();
    assert_eq!(init["mfaRequired"], json!(true));
    assert_eq!(init["mfaType"], json!("SMS"));
    let pid = init["processId"].as_str().unwrap();
    assert!(!pid.is_empty());

    let completed = h
        .sidecar
        .call(
            Method::POST,
            "/complete",
            Some(json!({ "processId": pid, "code": CODE })),
        )
        .await
        .unwrap();
    let session_str = completed["sessionState"].as_str().unwrap();
    let parsed_state = parse_session(session_str).unwrap();
    assert_eq!(parsed_state.v, 1);
    assert_eq!(parsed_state.refresh_token, REFRESH_TOKEN);
    assert_eq!(parsed_state.employee_id, EMPLOYEE_ID);
    assert!(h.state.lock().unwrap().exchanged_verifier.is_some());

    let body = h.positions(session_str).await.unwrap();
    let plans = body["plans"].as_array().unwrap();
    assert_eq!(plans.len(), 2);
    assert_eq!(plans[0]["planKind"], json!("PEE"));
    assert_eq!(plans[1]["planKind"], json!("PER"));
    assert_eq!(plans[0]["positions"].as_array().unwrap().len(), 6);
    for pos in plans[0]["positions"].as_array().unwrap() {
        assert!(pos["fundCode"].as_str().unwrap().starts_with("QS"));
    }

    assert_eq!(body["operationsComplete"], json!(true));
    // FixedClock at 2025-06-15 minus (5*365 - 1 = 1824) days = 2020-06-17
    assert_eq!(body["operationsFrom"], json!("2020-06-17"));
    let ops = body["operations"].as_array().unwrap();
    let buys: Vec<_> = ops.iter().filter(|o| o["type"] == "BUY").collect();
    let sells: Vec<_> = ops.iter().filter(|o| o["type"] == "SELL").collect();
    assert_eq!(buys.len(), 24);
    assert_eq!(sells.len(), 4);
    assert_eq!(
        buys.iter()
            .filter(|o| o["label"] == EMPLOYER_TOP_UP)
            .count(),
        12
    );
    assert!(
        ops.iter()
            .all(|o| o["fundCode"].as_str().unwrap().starts_with("QS"))
    );
    let op_ids: HashSet<_> = ops
        .iter()
        .map(|o| o["externalId"].as_str().unwrap())
        .collect();
    assert_eq!(op_ids.len(), ops.len());
}

#[tokio::test]
async fn concurrent_logins_keep_isolated_cookie_jars() {
    let h = Harness::start().await;
    h.state.lock().unwrap().require_sso_cookie = true;

    let init1 = h.initiate().await.unwrap();
    let pid1 = init1["processId"].as_str().unwrap().to_owned();
    let init2 = h.initiate().await.unwrap();
    let pid2 = init2["processId"].as_str().unwrap().to_owned();

    // Complete pid1 (expected sess-1) and pid2 (expected sess-2) — each keeps its own jar.
    h.sidecar
        .call(
            Method::POST,
            "/complete",
            Some(json!({ "processId": pid2, "code": CODE })),
        )
        .await
        .unwrap();
    // Refresh oauth_state isn't needed for cookie check; let's check both cookies seen were distinct
    // Note: pid1 had an earlier oauth_state overwritten in the single fake state, so complete pid1 up to verify_code
    let _ = h
        .sidecar
        .call(
            Method::POST,
            "/complete",
            Some(json!({ "processId": pid1, "code": CODE })),
        )
        .await;
    let cookies = h.state.lock().unwrap().seen_complete_cookies.clone();
    assert_eq!(cookies.len(), 2);
    assert_ne!(cookies[0], cookies[1]);
}

#[tokio::test]
async fn positions_graceful_degradation_on_history_and_fund_failures() {
    // 503 on operations listing
    let h = Harness::start().await;
    let session = h.login().await.unwrap()["sessionState"]
        .as_str()
        .unwrap()
        .to_owned();
    h.state.lock().unwrap().operations_status = 503;
    assert_plans_without_history(&h.positions(&session).await.unwrap());

    // 401 on operations listing does not expire session
    h.state.lock().unwrap().operations_status = 401;
    assert_plans_without_history(&h.positions(&session).await.unwrap());
    h.state.lock().unwrap().operations_status = 200;

    // 401 and 503 on detail endpoints drop history only
    for status in [401, 503] {
        h.state.lock().unwrap().detail_status = status;
        assert_plans_without_history(&h.positions(&session).await.unwrap());
    }
    h.state.lock().unwrap().detail_status = 200;

    // Contract-refused operations (bad date, >200-char label) drop history only
    for (field, val) in [
        ("investmentDate", json!("2025-07-23T10:00:00Z")),
        ("fundLabel", json!("F".repeat(201))),
    ] {
        let mut inv = fixture_investment_demand();
        inv["investments"][0][field] = val;
        h.state.lock().unwrap().investment_demand = inv;
        assert_plans_without_history(&h.positions(&session).await.unwrap());
    }

    // Parser-unreadable history (`product: "PEE"`) drops history only
    let mut bad_prod = fixture_investment_demand();
    bad_prod["investments"][0]["product"] = json!("PEE");
    h.state.lock().unwrap().investment_demand = bad_prod;
    assert_plans_without_history(&h.positions(&session).await.unwrap());
    h.state.lock().unwrap().investment_demand = fixture_investment_demand();

    // Missing / 401 fund details fall back to NIE-* without blocking sync
    for status in [404, 401] {
        h.state.lock().unwrap().fund_status = status;
        let body = h.positions(&session).await.unwrap();
        assert!(
            body["plans"][0]["positions"]
                .as_array()
                .unwrap()
                .iter()
                .all(|p| p["fundCode"].as_str().unwrap().starts_with("NIE-"))
        );
    }
}

#[tokio::test]
async fn operations_and_fund_budgets_drop_slow_optional_calls_not_plans() {
    let h = Harness::start_with(|c| {
        c.with_operations_budget(Duration::from_millis(120))
            .with_fund_detail_budget(Duration::from_millis(80))
    })
    .await;
    let session = h.login().await.unwrap()["sessionState"]
        .as_str()
        .unwrap()
        .to_owned();

    // Slow operation details exceed operations_budget -> plans still returned
    h.state.lock().unwrap().detail_delay = Duration::from_millis(600);
    assert_plans_without_history(&h.positions(&session).await.unwrap());
    h.state.lock().unwrap().detail_delay = Duration::ZERO;

    // Slow fund details exceed fund_detail_budget -> plans still returned with NIE-* fallback
    h.state.lock().unwrap().fund_delay = Duration::from_millis(600);
    let body = h.positions(&session).await.unwrap();
    assert_eq!(body["plans"].as_array().unwrap().len(), 2);
}

#[tokio::test]
async fn detail_link_never_steers_token_elsewhere() {
    let h = Harness::start().await;
    let session = h.login().await.unwrap()["sessionState"]
        .as_str()
        .unwrap()
        .to_owned();
    let base = format!("/transaction/v1/employees/{EMPLOYEE_ID}");
    for href in [
        format!("{base}/investmentDemands/../../../../auth/v3/users/me"),
        format!("{base}/investmentDemands/%2e%2e/%2e%2e/x"),
        format!("https://evil.example{base}/investmentDemands/2024-03-15-INT--10000001-"),
    ] {
        let mut tx = fixture_transactions();
        tx["items"][0]["_links"]["self"]["href"] = json!(href);
        {
            let mut st = h.state.lock().unwrap();
            st.transactions = tx;
            st.requests.clear();
        }
        assert_plans_without_history(&h.positions(&session).await.unwrap());
        let reqs = h.state.lock().unwrap().requests.clone();
        assert!(!reqs.iter().any(|(_, p)| p == "/auth/v3/users/me"));
        assert!(!reqs.iter().any(|(_, p)| p.contains("/investmentDemands/")));
    }
}

#[tokio::test]
async fn refresh_token_rotation_voice_fallback_cancel_and_capacity_limit() {
    let h = Harness::start().await;
    let session = h.login().await.unwrap()["sessionState"]
        .as_str()
        .unwrap()
        .to_owned();
    h.state.lock().unwrap().returned_refresh_token = "rotated-refresh-token".to_owned();
    let body = h.positions(&session).await.unwrap();
    assert_eq!(
        parse_session(body["sessionState"].as_str().unwrap())
            .unwrap()
            .refresh_token,
        "rotated-refresh-token"
    );

    // VOICE chosen when SMS absent
    h.state.lock().unwrap().strong_modes = json!({
        "items": [{ "id": "VOICE", "status": "VALID" }]
    });
    assert_eq!(h.initiate().await.unwrap()["mfaType"], json!("VOICE"));

    // Cancel frees waiting login and is idempotent (204)
    let pid = h.initiate().await.unwrap()["processId"]
        .as_str()
        .unwrap()
        .to_owned();
    let r1 = h
        .sidecar
        .call_raw(Method::POST, "/cancel", Some(json!({ "processId": pid })))
        .await
        .unwrap();
    assert_eq!(r1.status, StatusCode::NO_CONTENT);
    assert_eq!(r1.body, Value::Null);
    let r2 = h
        .sidecar
        .call_raw(Method::POST, "/cancel", Some(json!({ "processId": pid })))
        .await
        .unwrap();
    assert_eq!(r2.status, StatusCode::NO_CONTENT);
    assert_eq!(
        h.sidecar
            .call(
                Method::POST,
                "/complete",
                Some(json!({ "processId": pid, "code": CODE }))
            )
            .await
            .unwrap_err(),
        (StatusCode::GONE, "AUTH_ATTEMPT_EXPIRED".to_owned())
    );

    // Full store refuses with 503 BEFORE requesting a code from Natixis
    let full = Harness::start_with(|c| c.with_max_pending(0)).await;
    assert_eq!(
        full.initiate().await.unwrap_err(),
        (
            StatusCode::SERVICE_UNAVAILABLE,
            "UPSTREAM_UNAVAILABLE".to_owned()
        )
    );
    assert!(
        !full
            .state
            .lock()
            .unwrap()
            .requests
            .iter()
            .any(|(_, p)| p == "/auth/v1/strongAuthentications")
    );
}

#[tokio::test]
async fn error_codes_credentials_mfa_otp_retries_expiry_and_upstream() {
    let h = Harness::start().await;

    // Wrong password -> 401 INVALID_CREDENTIALS
    h.state.lock().unwrap().password_status = 401;
    assert_eq!(
        h.initiate().await.unwrap_err(),
        (StatusCode::UNAUTHORIZED, "INVALID_CREDENTIALS".to_owned())
    );

    // Throttled / locked -> 429 RATE_LIMITED
    for status in [429, 423] {
        h.state.lock().unwrap().password_status = status;
        assert_eq!(
            h.initiate().await.unwrap_err(),
            (StatusCode::TOO_MANY_REQUESTS, "RATE_LIMITED".to_owned())
        );
    }
    h.state.lock().unwrap().password_status = 201;

    // Unknown email -> 401 INVALID_CREDENTIALS
    h.state.lock().unwrap().login_modes = json!({ "items": [] });
    assert_eq!(
        h.initiate().await.unwrap_err(),
        (StatusCode::UNAUTHORIZED, "INVALID_CREDENTIALS".to_owned())
    );

    // Passkey-only account -> 501 MFA_TYPE_UNSUPPORTED
    h.state.lock().unwrap().login_modes = json!({ "items": [{ "mode": "WEBAUTHN" }] });
    assert_eq!(
        h.initiate().await.unwrap_err(),
        (
            StatusCode::NOT_IMPLEMENTED,
            "MFA_TYPE_UNSUPPORTED".to_owned()
        )
    );
    h.state.lock().unwrap().login_modes = fixture_auth_modes();

    // No supported second factor -> 501 MFA_TYPE_UNSUPPORTED
    h.state.lock().unwrap().strong_modes = json!({ "items": [] });
    assert_eq!(
        h.initiate().await.unwrap_err(),
        (
            StatusCode::NOT_IMPLEMENTED,
            "MFA_TYPE_UNSUPPORTED".to_owned()
        )
    );
    h.state.lock().unwrap().strong_modes = fixture_strong_auth_modes();

    // Wrong code can be retried
    let pid = h.initiate().await.unwrap()["processId"]
        .as_str()
        .unwrap()
        .to_owned();
    h.state.lock().unwrap().code_status = 400;
    assert_eq!(
        h.sidecar
            .call(
                Method::POST,
                "/complete",
                Some(json!({ "processId": pid, "code": "111111" }))
            )
            .await
            .unwrap_err(),
        (StatusCode::BAD_REQUEST, "INVALID_OTP".to_owned())
    );
    h.state.lock().unwrap().code_status = 201;
    assert!(
        h.sidecar
            .call(
                Method::POST,
                "/complete",
                Some(json!({ "processId": pid, "code": CODE }))
            )
            .await
            .is_ok()
    );
    // Completed attempt cannot be reused
    assert_eq!(
        h.sidecar
            .call(
                Method::POST,
                "/complete",
                Some(json!({ "processId": pid, "code": CODE }))
            )
            .await
            .unwrap_err(),
        (StatusCode::GONE, "AUTH_ATTEMPT_EXPIRED".to_owned())
    );

    // Too many wrong codes (MAX_CODE_ATTEMPTS=5) drop the attempt
    let pid2 = h.initiate().await.unwrap()["processId"]
        .as_str()
        .unwrap()
        .to_owned();
    h.state.lock().unwrap().code_status = 400;
    for _ in 0..(MAX_CODE_ATTEMPTS - 1) {
        assert_eq!(
            h.sidecar
                .call(
                    Method::POST,
                    "/complete",
                    Some(json!({ "processId": pid2, "code": "111111" }))
                )
                .await
                .unwrap_err(),
            (StatusCode::BAD_REQUEST, "INVALID_OTP".to_owned())
        );
    }
    assert_eq!(
        h.sidecar
            .call(
                Method::POST,
                "/complete",
                Some(json!({ "processId": pid2, "code": "111111" }))
            )
            .await
            .unwrap_err(),
        (StatusCode::GONE, "AUTH_ATTEMPT_EXPIRED".to_owned())
    );
    h.state.lock().unwrap().code_status = 201;
    assert_eq!(
        h.sidecar
            .call(
                Method::POST,
                "/complete",
                Some(json!({ "processId": pid2, "code": CODE }))
            )
            .await
            .unwrap_err(),
        (StatusCode::GONE, "AUTH_ATTEMPT_EXPIRED".to_owned())
    );

    // Malformed code -> 400 INVALID_OTP; unknown attempt -> 410 AUTH_ATTEMPT_EXPIRED
    let pid3 = h.initiate().await.unwrap()["processId"]
        .as_str()
        .unwrap()
        .to_owned();
    assert_eq!(
        h.sidecar
            .call(
                Method::POST,
                "/complete",
                Some(json!({ "processId": pid3, "code": "abc" }))
            )
            .await
            .unwrap_err(),
        (StatusCode::BAD_REQUEST, "INVALID_OTP".to_owned())
    );
    assert_eq!(
        h.sidecar
            .call(
                Method::POST,
                "/complete",
                Some(json!({ "processId": "nope", "code": CODE }))
            )
            .await
            .unwrap_err(),
        (StatusCode::GONE, "AUTH_ATTEMPT_EXPIRED".to_owned())
    );

    // TTL expiry via injectable clock -> 410 AUTH_ATTEMPT_EXPIRED
    h.clock.advance(Duration::from_secs(601));
    assert_eq!(
        h.sidecar
            .call(
                Method::POST,
                "/complete",
                Some(json!({ "processId": pid3, "code": CODE }))
            )
            .await
            .unwrap_err(),
        (StatusCode::GONE, "AUTH_ATTEMPT_EXPIRED".to_owned())
    );
}

#[tokio::test]
async fn error_codes_sessions_format_deadlines_and_api_path_guard() {
    let h = Harness::start().await;
    let session = h.login().await.unwrap()["sessionState"]
        .as_str()
        .unwrap()
        .to_owned();

    // Refused refresh token -> 401 SESSION_EXPIRED
    h.state.lock().unwrap().token_status = 400;
    assert_eq!(
        h.positions(&session).await.unwrap_err(),
        (StatusCode::UNAUTHORIZED, "SESSION_EXPIRED".to_owned())
    );
    h.state.lock().unwrap().token_status = 200;

    // API 401 -> 401 SESSION_EXPIRED; API 403 -> 502 UPSTREAM_FORMAT_CHANGED; API 503 -> 502 UPSTREAM_UNAVAILABLE
    h.state.lock().unwrap().api_status = 401;
    assert_eq!(
        h.positions(&session).await.unwrap_err(),
        (StatusCode::UNAUTHORIZED, "SESSION_EXPIRED".to_owned())
    );
    h.state.lock().unwrap().api_status = 403;
    assert_eq!(
        h.positions(&session).await.unwrap_err(),
        (
            StatusCode::BAD_GATEWAY,
            "UPSTREAM_FORMAT_CHANGED".to_owned()
        )
    );
    h.state.lock().unwrap().api_status = 503;
    assert_eq!(
        h.positions(&session).await.unwrap_err(),
        (StatusCode::BAD_GATEWAY, "UPSTREAM_UNAVAILABLE".to_owned())
    );
    h.state.lock().unwrap().api_status = 200;

    // Incomplete portfolio -> 502 PORTFOLIO_INCOMPLETE
    h.state.lock().unwrap().savings["items"][0]["compartments"][0]["investmentOptions"]
        .as_array_mut()
        .unwrap()
        .pop();
    assert_eq!(
        h.positions(&session).await.unwrap_err(),
        (StatusCode::BAD_GATEWAY, "PORTFOLIO_INCOMPLETE".to_owned())
    );

    // Non-object JSON -> 502 UPSTREAM_FORMAT_CHANGED
    h.state.lock().unwrap().savings = json!(["not", "an", "object"]);
    assert_eq!(
        h.positions(&session).await.unwrap_err(),
        (
            StatusCode::BAD_GATEWAY,
            "UPSTREAM_FORMAT_CHANGED".to_owned()
        )
    );
    h.state.lock().unwrap().savings = fixture_savings();

    // Changed login page -> 502 UPSTREAM_FORMAT_CHANGED
    h.state.lock().unwrap().authorize_html =
        Some("<html><body>maintenance</body></html>".to_owned());
    assert_eq!(
        h.initiate().await.unwrap_err(),
        (
            StatusCode::BAD_GATEWAY,
            "UPSTREAM_FORMAT_CHANGED".to_owned()
        )
    );
    h.state.lock().unwrap().authorize_html = None;

    // Untrusted SAML destination -> 502 UPSTREAM_FORMAT_CHANGED
    h.state.lock().unwrap().service_provider_url = Some("https://example.org/collect".to_owned());
    let pid = h.initiate().await.unwrap()["processId"]
        .as_str()
        .unwrap()
        .to_owned();
    assert_eq!(
        h.sidecar
            .call(
                Method::POST,
                "/complete",
                Some(json!({ "processId": pid, "code": CODE }))
            )
            .await
            .unwrap_err(),
        (
            StatusCode::BAD_GATEWAY,
            "UPSTREAM_FORMAT_CHANGED".to_owned()
        )
    );
    h.state.lock().unwrap().service_provider_url = None;

    // Refused authorization code -> 410 AUTH_ATTEMPT_EXPIRED
    let pid = h.initiate().await.unwrap()["processId"]
        .as_str()
        .unwrap()
        .to_owned();
    h.state.lock().unwrap().token_status = 400;
    assert_eq!(
        h.sidecar
            .call(
                Method::POST,
                "/complete",
                Some(json!({ "processId": pid, "code": CODE }))
            )
            .await
            .unwrap_err(),
        (StatusCode::GONE, "AUTH_ATTEMPT_EXPIRED".to_owned())
    );
    h.state.lock().unwrap().token_status = 200;

    // Invalid sessionState -> 400 INVALID_DATA
    for bad in ["{not json", r#"{"v": 1}"#] {
        assert_eq!(
            h.positions(bad).await.unwrap_err(),
            (StatusCode::BAD_REQUEST, "INVALID_DATA".to_owned())
        );
    }

    // No API call leaves allowed paths (never hits wiremock)
    for bad_path in [
        format!("/saving/v1/employees/{EMPLOYEE_ID}/../../auth/v3/users/me"),
        format!("/saving/v1/employees/{EMPLOYEE_ID}/savings/x"),
        format!("/transaction/v1/employees/{EMPLOYEE_ID}/investmentDemands/a%2F.."),
        format!("/transaction/v1/employees/{EMPLOYEE_ID}/investmentDemands/x\n"),
        "/fund/v1/funds/1?x=1".to_owned(),
        "//evil.example/x".to_owned(),
        "@evil.example/x".to_owned(),
        "/auth/v3/users/me/".to_owned(),
    ] {
        h.state.lock().unwrap().requests.clear();
        let err = h
            .sidecar
            .api_get_checked(ACCESS_TOKEN, &bad_path, &[])
            .await
            .unwrap_err();
        assert_eq!(err.detail, "UPSTREAM_FORMAT_CHANGED");
        assert!(
            h.state.lock().unwrap().requests.is_empty(),
            "disallowed path must never hit network: {bad_path:?}"
        );
    }

    // Route deadline after failed detail -> 502 UPSTREAM_UNAVAILABLE
    let tight = Harness::start_with(|c| {
        c.with_positions_deadline(Duration::from_millis(250))
            .with_route_deadline(Duration::from_millis(80))
    })
    .await;
    // Slow login gives up before route deadline
    tight.state.lock().unwrap().password_delay = Duration::from_millis(400);
    assert_eq!(
        tight.initiate().await.unwrap_err(),
        (StatusCode::BAD_GATEWAY, "UPSTREAM_UNAVAILABLE".to_owned())
    );
    tight.state.lock().unwrap().password_delay = Duration::ZERO;

    // Positions deadline after failed detail
    let tight2 =
        Harness::start_with(|c| c.with_positions_deadline(Duration::from_millis(200))).await;
    let session2 = tight2.login().await.unwrap()["sessionState"]
        .as_str()
        .unwrap()
        .to_owned();
    {
        let mut st = tight2.state.lock().unwrap();
        st.detail_status = 503;
        st.fund_delay = Duration::from_millis(800);
    }
    assert_eq!(
        tight2.positions(&session2).await.unwrap_err(),
        (StatusCode::BAD_GATEWAY, "UPSTREAM_UNAVAILABLE".to_owned())
    );

    // Network error (unreachable port) -> 502 UPSTREAM_UNAVAILABLE
    let dead = NativeNatixis::with_config(
        NatixisConfig::for_mock_server("http://127.0.0.1:1")
            .with_request_timeout(Duration::from_secs(2)),
    );
    assert_eq!(
        dead.call(
            Method::POST,
            "/initiate",
            Some(json!({ "email": EMAIL, "password": PASSWORD }))
        )
        .await
        .unwrap_err(),
        (StatusCode::BAD_GATEWAY, "UPSTREAM_UNAVAILABLE".to_owned())
    );
}

// ─── Log Hygiene Test (test_main.py::LogHygieneTest) ────────────────────────

#[derive(Clone, Default)]
struct CaptureWriter {
    buf: Arc<Mutex<Vec<u8>>>,
}

impl Write for CaptureWriter {
    fn write(&mut self, data: &[u8]) -> std::io::Result<usize> {
        self.buf.lock().unwrap().extend_from_slice(data);
        Ok(data.len())
    }

    fn flush(&mut self) -> std::io::Result<()> {
        Ok(())
    }
}

impl<'a> MakeWriter<'a> for CaptureWriter {
    type Writer = CaptureWriter;
    fn make_writer(&'a self) -> Self::Writer {
        self.clone()
    }
}

#[tokio::test]
async fn log_hygiene_never_logs_sensitive_values() {
    let writer = CaptureWriter::default();
    let subscriber = tracing_subscriber::fmt()
        .with_max_level(tracing::Level::DEBUG)
        .with_ansi(false)
        .with_writer(writer.clone())
        .finish();
    let _guard = tracing::subscriber::set_default(subscriber);

    let h = Harness::start().await;
    let session = h.login().await.unwrap()["sessionState"]
        .as_str()
        .unwrap()
        .to_owned();
    h.positions(&session).await.unwrap();

    let pid = h.initiate().await.unwrap()["processId"]
        .as_str()
        .unwrap()
        .to_owned();
    h.state.lock().unwrap().code_status = 400;
    let _ = h
        .sidecar
        .call(
            Method::POST,
            "/complete",
            Some(json!({ "processId": pid, "code": CODE })),
        )
        .await;

    h.state.lock().unwrap().token_status = 400;
    let _ = h.positions(&session).await;

    let logged = String::from_utf8(writer.buf.lock().unwrap().clone()).unwrap();
    assert!(
        !logged.is_empty(),
        "test expected warning logs from error scenarios"
    );
    for secret in [
        EMAIL,
        PASSWORD,
        CODE,
        CSRF,
        SAML_VALUE,
        AUTH_CODE,
        ACCESS_TOKEN,
        REFRESH_TOKEN,
        VERIFICATION_ID,
    ] {
        assert!(
            !logged.contains(secret),
            "sensitive value leaked into logs: {secret}"
        );
    }
}

#[test]
fn natixis_savings_parser_reports_format_changed_instead_of_panicking_on_decimal_overflow() {
    let huge = "50000000000000000000000000000";
    let products_one = json!({
        "items": [{ "id": "P1", "code": "PEE", "internalAccount": true }],
        "additionalInformation": {
            "internalAmountsManagedByNIE": { "total": { "value": huge, "currencyCode": "EUR" } }
        }
    });

    // Merging the same fundId across compartments overflows units/amount.
    let merged_overflow = json!({
        "items": [{
            "id": "PEE-1",
            "family": "PEE",
            "displayableProductLabel": "PEE",
            "amount": { "value": huge, "currencyCode": "EUR" },
            "compartments": [
                {
                    "investmentOptions": [{
                        "fundId": 1001, "type": "FUND", "label": "FONDS A",
                        "units": huge,
                        "amount": { "value": huge, "currencyCode": "EUR" },
                        "gainsOrLossesAmount": { "value": huge, "currencyCode": "EUR" }
                    }]
                },
                {
                    "investmentOptions": [{
                        "fundId": 1001, "type": "FUND", "label": "FONDS A",
                        "units": huge,
                        "amount": { "value": huge, "currencyCode": "EUR" },
                        "gainsOrLossesAmount": { "value": huge, "currencyCode": "EUR" }
                    }]
                }
            ]
        }]
    });
    let err = parse_plans(&merged_overflow, &products_one, EMPLOYEE_ID, &HashMap::new())
        .unwrap_err();
    assert_eq!(err.code, FORMAT_CHANGED);

    // Two distinct funds in one plan whose amounts sum past Decimal::MAX.
    let lines_overflow = json!({
        "items": [{
            "id": "PEE-1",
            "family": "PEE",
            "displayableProductLabel": "PEE",
            "amount": { "value": huge, "currencyCode": "EUR" },
            "compartments": [{
                "investmentOptions": [
                    { "fundId": 1001, "type": "FUND", "label": "FONDS A", "units": "1", "amount": { "value": huge, "currencyCode": "EUR" } },
                    { "fundId": 1002, "type": "FUND", "label": "FONDS B", "units": "1", "amount": { "value": huge, "currencyCode": "EUR" } }
                ]
            }]
        }]
    });
    let err =
        parse_plans(&lines_overflow, &products_one, EMPLOYEE_ID, &HashMap::new()).unwrap_err();
    assert_eq!(err.code, FORMAT_CHANGED);

    // Two plans whose balances sum past Decimal::MAX.
    let plans_overflow = json!({
        "items": [
            {
                "id": "PEE-1", "family": "PEE", "displayableProductLabel": "PEE 1",
                "amount": { "value": huge, "currencyCode": "EUR" },
                "compartments": [{ "investmentOptions": [{ "fundId": 1001, "type": "FUND", "label": "A", "units": "1", "amount": { "value": huge, "currencyCode": "EUR" } }] }]
            },
            {
                "id": "PEE-2", "family": "PEE", "displayableProductLabel": "PEE 2",
                "amount": { "value": huge, "currencyCode": "EUR" },
                "compartments": [{ "investmentOptions": [{ "fundId": 1002, "type": "FUND", "label": "B", "units": "1", "amount": { "value": huge, "currencyCode": "EUR" } }] }]
            }
        ]
    });
    let products_two = json!({
        "items": [
            { "id": "P1", "code": "PEE", "internalAccount": true },
            { "id": "P2", "code": "PER", "internalAccount": true }
        ],
        "additionalInformation": {
            "internalAmountsManagedByNIE": { "total": { "value": huge, "currencyCode": "EUR" } }
        }
    });
    let err =
        parse_plans(&plans_overflow, &products_two, EMPLOYEE_ID, &HashMap::new()).unwrap_err();
    assert_eq!(err.code, FORMAT_CHANGED);

    // money_close with opposite extremes must return false without panicking.
    assert!(!money_close(Decimal::MAX, -Decimal::MAX));
}

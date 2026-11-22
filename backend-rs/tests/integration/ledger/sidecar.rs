use std::collections::{BTreeMap, BTreeSet};

use picsou::db::Db;
use picsou::ledger::sidecar::{SidecarBank, SidecarErrorCode, SourceSyncResult, SyncStatus};
use picsou::testing::TestApp;
use regex::Regex;

async fn constraint_definition(db: &Db, name: &str) -> String {
    if db.is_sqlite() {
        let ddls: Vec<String> = picsou::db::query_scalar(
            "SELECT sql FROM sqlite_master WHERE type = 'table' AND sql LIKE $1",
        )
        .bind(format!("%CONSTRAINT {name} %"))
        .fetch_all(db)
        .await
        .unwrap();
        let prefix = format!("CONSTRAINT {name} ");
        for ddl in ddls {
            for line in ddl.lines() {
                if let Some(pos) = line.find(&prefix) {
                    return line[pos + prefix.len()..]
                        .trim()
                        .trim_end_matches(',')
                        .to_owned();
                }
            }
        }
        panic!("{name} exists in sqlite_master");
    }
    picsou::db::query_scalar(
        "SELECT pg_get_constraintdef(c.oid) FROM pg_constraint c WHERE c.conname = $1",
    )
    .bind(name)
    .fetch_optional(db)
    .await
    .unwrap()
    .unwrap_or_else(|| panic!("{name} exists"))
}

fn quoted(text: &str) -> BTreeSet<String> {
    Regex::new(r"'([A-Z_]+)'")
        .unwrap()
        .captures_iter(text)
        .map(|c| c[1].to_owned())
        .collect()
}

/// The codes each bank branch of ck_sidecar_session_last_sync_error quotes.
async fn allowed_by_check(db: &Db) -> BTreeMap<String, BTreeSet<String>> {
    let definition = constraint_definition(db, "ck_sidecar_session_last_sync_error").await;
    let branch = if db.is_sqlite() {
        Regex::new(r"\(bank = '([A-Z_]+)'\) AND \(last_sync_error IN \(([^)]+)\)\)").unwrap()
    } else {
        Regex::new(r"bank\)::text = '([A-Z_]+)'::text\) AND .*?ARRAY\[(.*?)\]").unwrap()
    };
    let mut allowed = BTreeMap::new();
    for c in branch.captures_iter(&definition) {
        let previous = allowed.insert(c[1].to_owned(), quoted(&c[2]));
        assert!(previous.is_none(), "one branch for {}", &c[1]);
    }
    allowed
}

fn own_table_check(bank: SidecarBank) -> &'static str {
    match bank {
        SidecarBank::Lbp => "ck_lbp_session_last_sync_error",
        other => panic!("{other} is stored in sidecar_session"),
    }
}

#[tokio::test]
async fn the_declared_codes_are_exactly_the_banks_branch_of_the_session_check() {
    let app = TestApp::with_admin().await;
    let db = &app.state.db;
    let branches = allowed_by_check(db).await;
    for &bank in SidecarBank::ALL {
        let declared: BTreeSet<String> =
            bank.codes().iter().map(|c| c.as_str().to_owned()).collect();
        let allowed = if bank.stored_in_sidecar_session() {
            branches.get(bank.as_str()).cloned().unwrap_or_default()
        } else {
            quoted(&constraint_definition(db, own_table_check(bank)).await)
        };
        assert_eq!(allowed, declared, "{bank}");
    }
}

#[tokio::test]
async fn the_session_check_has_one_branch_per_bank_stored_there() {
    let app = TestApp::with_admin().await;
    let banks: BTreeSet<String> = SidecarBank::ALL
        .iter()
        .filter(|b| b.stored_in_sidecar_session())
        .map(|b| b.as_str().to_owned())
        .collect();
    let checked: BTreeSet<String> = allowed_by_check(&app.state.db).await.into_keys().collect();
    assert_eq!(checked, banks);
}

#[test]
fn every_constant_of_the_shared_enum_belongs_to_at_least_one_bank() {
    let used: BTreeSet<SidecarErrorCode> =
        SidecarBank::ALL.iter().flat_map(|b| b.codes()).collect();
    let all: BTreeSet<SidecarErrorCode> = SidecarErrorCode::ALL.iter().copied().collect();
    assert_eq!(used, all);
}

#[test]
fn every_declared_code_has_a_message_and_no_other_code_does() {
    for &bank in SidecarBank::ALL {
        for &code in SidecarErrorCode::ALL {
            if bank.declares(code) {
                assert!(!bank.message(code).unwrap().trim().is_empty());
                assert_eq!(bank.parse(Some(code.as_str())), Some(code));
                assert_eq!(bank.require_declared(code), Ok(code));
            } else {
                assert_eq!(bank.parse(Some(code.as_str())), None);
                let error = bank.message(code).unwrap_err();
                assert_eq!(
                    error.to_string(),
                    format!("{} does not declare {}", bank.as_str(), code.as_str())
                );
                assert!(bank.require_declared(code).is_err());
            }
        }
        assert_eq!(bank.parse(None), None);
        assert_eq!(bank.parse(Some("NOT_A_CODE")), None);
    }
}

#[test]
fn names_serialize_as_the_java_constants() {
    assert_eq!(
        serde_json::to_string(&SidecarBank::TradeRepublic).unwrap(),
        "\"TRADE_REPUBLIC\""
    );
    assert_eq!(
        serde_json::to_string(&SidecarErrorCode::IdentitySelectionUnsupported).unwrap(),
        "\"IDENTITY_SELECTION_UNSUPPORTED\""
    );
    assert_eq!(SidecarBank::BanquePopulaire.label(), "Banque Populaire");
    assert_eq!(SidecarBank::TradeRepublic.service_name(), "tr-auth");
    assert!(!SidecarBank::Lbp.stored_in_sidecar_session());
    for &code in SidecarErrorCode::ALL {
        assert_eq!(code.as_str().parse::<SidecarErrorCode>(), Ok(code));
        assert_eq!(serde_json::to_value(code).unwrap(), code.as_str());
    }
    let result = SourceSyncResult::from_sync_exception("amex", Some("SESSION_EXPIRED"), None);
    assert_eq!(
        serde_json::to_value(&result).unwrap(),
        serde_json::json!({"source": "amex", "status": "NEEDS_REAUTH", "message": "Reauthentication required"})
    );
}

fn status(source: &str, code: Option<&str>, message: &str) -> SyncStatus {
    SourceSyncResult::from_sync_exception(source, code, Some(message)).status
}

fn coded(source: &str, code: SidecarErrorCode) -> SyncStatus {
    status(source, Some(code.as_str()), "provider failure")
}

#[test]
fn sync_exception_status_recognizes_only_exact_connector_authentication_codes() {
    use SidecarErrorCode::{InvalidCredentials, SessionExpired};
    assert_eq!(
        status("trade-republic", Some("SESSION_EXPIRED"), "expired session"),
        SyncStatus::NeedsReauth
    );
    assert_eq!(
        status("trade-republic", None, "AUTHENTICATION_ERROR"),
        SyncStatus::NeedsReauth
    );
    assert_eq!(
        status(
            "revolut",
            Some("SESSION_EXPIRED"),
            "friendly expired-session message"
        ),
        SyncStatus::NeedsReauth
    );
    for (source, code) in [
        ("bourso", SessionExpired),
        ("bourso", InvalidCredentials),
        ("lbp", SessionExpired),
        ("lbp", InvalidCredentials),
        ("bourse-direct", SessionExpired),
        ("amundi", SessionExpired),
        ("fortuneo", SessionExpired),
        ("amex", SessionExpired),
    ] {
        assert_eq!(
            coded(source, code),
            SyncStatus::NeedsReauth,
            "{source} {code}"
        );
    }
    assert_eq!(
        status(
            "ibkr",
            None,
            "Interactive Brokers: Token has expired. (code 1012)"
        ),
        SyncStatus::NeedsReauth
    );
    assert_eq!(
        status(
            "ibkr",
            None,
            "Interactive Brokers: Token is invalid. (code 1015)"
        ),
        SyncStatus::NeedsReauth
    );
}

#[test]
fn sync_exception_status_keeps_transient_unknown_and_non_auth_errors_failed() {
    use SidecarErrorCode::{AuthAttemptExpired, UpstreamUnavailable};
    for source in [
        "trade-republic",
        "ibkr",
        "revolut",
        "bourso",
        "bourse-direct",
        "amundi",
        "fortuneo",
        "amex",
        "lbp",
    ] {
        assert_eq!(
            status(source, None, "temporary provider outage"),
            SyncStatus::Failed
        );
        assert_eq!(
            status(source, Some("INTERNAL_ERROR"), "INTERNAL_ERROR"),
            SyncStatus::Failed
        );
        assert_eq!(
            status(source, None, "failure with no code"),
            SyncStatus::Failed
        );
    }
    assert_eq!(
        status(
            "ibkr",
            None,
            "Interactive Brokers: Query is invalid. (code 1014)"
        ),
        SyncStatus::Failed
    );
    assert_eq!(
        status(
            "ibkr",
            None,
            "Interactive Brokers: expired token, invalid query id"
        ),
        SyncStatus::Failed
    );
    for source in [
        "bourso",
        "bourse-direct",
        "amundi",
        "fortuneo",
        "amex",
        "lbp",
    ] {
        assert_eq!(
            coded(source, UpstreamUnavailable),
            SyncStatus::Failed,
            "{source}"
        );
    }
    assert_eq!(coded("amex", AuthAttemptExpired), SyncStatus::Failed);
}

#[test]
fn sync_exception_result_never_exposes_provider_messages_or_unknown_codes() {
    let secret = "provider-secret-token-123";
    let reauth =
        SourceSyncResult::from_sync_exception("revolut", Some("SESSION_EXPIRED"), Some(secret));
    let failed_message = format!("{secret} (code 9999)");
    let failed = SourceSyncResult::from_sync_exception("ibkr", None, Some(&failed_message));
    let arbitrary_code =
        SourceSyncResult::from_sync_exception("bourso", Some(secret), Some(secret));

    assert_eq!(reauth.status, SyncStatus::NeedsReauth);
    assert_eq!(reauth.message, "Reauthentication required");
    assert_eq!(failed.status, SyncStatus::Failed);
    assert_eq!(failed.message, "Sync failed");
    assert_eq!(arbitrary_code.status, SyncStatus::Failed);
    assert_eq!(arbitrary_code.message, "Sync failed");
}

#[test]
fn from_sync_exception_simplefin_and_wise() {
    assert_eq!(
        status("simplefin", Some("SESSION_EXPIRED"), "provider failure"),
        SyncStatus::NeedsReauth
    );
    assert_eq!(
        status("simplefin", None, "Bridge unavailable"),
        SyncStatus::Failed
    );
    assert_eq!(
        status("wise", Some("SESSION_EXPIRED"), "provider failure"),
        SyncStatus::NeedsReauth
    );
    assert_eq!(
        status("wise", None, "Wise is limiting requests right now."),
        SyncStatus::Failed
    );
}

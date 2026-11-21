//! Parser tests (HomeBankFileParserTest, HomeBankQifParserTest).

use std::io::Write;

use aes_gcm::Aes256Gcm;
use aes_gcm::aead::{Aead, KeyInit};
use flate2::Compression;
use flate2::write::DeflateEncoder;

use super::java_hash::JavaHashSet;
use super::qif::{AMBIGUOUS_DATES, UNSUPPORTED_DATES};
use super::source::ParsedHomeBankData;
use super::{ios, qif};

const JSON: &str =
    include_str!("../../../tests/integration/transfer/fixtures/homebank/synthetic-v3.json");
const QIF: &str =
    include_str!("../../../tests/integration/transfer/fixtures/homebank/desktop-synthetic.qif");

fn deflate(bytes: &[u8]) -> Vec<u8> {
    let mut encoder = DeflateEncoder::new(Vec::new(), Compression::default());
    encoder.write_all(bytes).unwrap();
    encoder.finish().unwrap()
}

fn encrypt(compressed: &[u8], password: &str) -> Vec<u8> {
    let salt = [7u8; 32];
    let nonce = [9u8; 12];
    let mut key = [0u8; 32];
    pbkdf2::pbkdf2_hmac::<sha2::Sha256>(password.as_bytes(), &salt, 600_000, &mut key);
    let cipher = Aes256Gcm::new_from_slice(&key).unwrap();
    let sealed = cipher
        .encrypt(&aes_gcm::Nonce::try_from(&nonce[..]).unwrap(), compressed)
        .unwrap();
    [&[0u8, 0, 0, 1][..], &salt, &nonce, &sealed].concat()
}

fn hbk(json: &str) -> Result<ParsedHomeBankData, String> {
    ios::parse(&deflate(json.as_bytes()), false, None)
}

fn qif_eur(source: &str) -> Result<ParsedHomeBankData, String> {
    qif::parse(source.as_bytes(), Some("EUR"))
}

#[test]
fn parses_raw_deflate_and_preserves_exact_amounts_and_leap_date() {
    let parsed = hbk(JSON).unwrap();
    assert_eq!(parsed.accounts.len(), 1);
    assert_eq!(
        parsed.accounts[0].initial_balance.to_string(),
        "1234.56789012"
    );
    assert_eq!(
        parsed.accounts[0].institution.as_deref(),
        Some("Synthetic Bank")
    );
    assert_eq!(parsed.transactions.len(), 1);
    let t = &parsed.transactions[0];
    assert_eq!(t.amount.to_string(), "-12.34000001");
    assert_eq!(t.date.to_string(), "2024-02-29");
    assert_eq!(t.payee.as_deref(), Some("Synthetic Shop"));
    assert_eq!(t.notes, None);
}

#[test]
fn decrypts_version_one_export_and_rejects_wrong_or_missing_passwords() {
    let encrypted = encrypt(&deflate(JSON.as_bytes()), "sécurité🔐");
    assert_eq!(
        ios::parse(&encrypted, true, Some("sécurité🔐"))
            .unwrap()
            .transactions
            .len(),
        1
    );
    assert_eq!(
        ios::parse(&encrypted, true, None).unwrap_err(),
        "Password required for HomeBank export"
    );
    assert_eq!(
        ios::parse(&encrypted, true, Some("wrong-password")).unwrap_err(),
        "Incorrect password or corrupted HomeBank export"
    );
    let mut tampered = encrypted.clone();
    *tampered.last_mut().unwrap() ^= 1;
    assert_eq!(
        ios::parse(&tampered, true, Some("sécurité🔐")).unwrap_err(),
        "Incorrect password or corrupted HomeBank export"
    );
    let mut version = encrypted;
    version[3] = 2;
    assert_eq!(
        ios::parse(&version, true, Some("x")).unwrap_err(),
        "Unsupported HomeBank export encryption version"
    );
}

#[test]
fn rejects_duplicate_keys_invalid_references_dates_and_precision() {
    for (from, to) in [
        (
            "\"name\":\"Synthetic Main\"",
            "\"name\":\"Synthetic Main\",\"name\":\"Other\"",
        ),
        (
            "a12b3c4d-1111-4111-8111-111111111111\",\"amount\"",
            "e12b3c4d-1111-4111-8111-111111111111\",\"amount\"",
        ),
        ("\"day\":29", "\"day\":30"),
        ("-12.34000001", "-12.340000001"),
        ("\"schemaVersion\":3", "\"schemaVersion\":4"),
        (
            "\"isForecast\":false",
            "\"isForecast\":false,\"splits\":[{}]",
        ),
        (
            "\"currencyCode\":\"EUR\"},\"date\"",
            "\"currencyCode\":\"USD\"},\"date\"",
        ),
        (
            "\"name\":\"Synthetic Food\",\"isIncomeType\"",
            "\"name\":\"Synthetic Food\",\"parentID\":\"b12b3c4d-1111-4111-8111-111111111111\",\"isIncomeType\"",
        ),
    ] {
        assert!(hbk(&JSON.replace(from, to)).is_err(), "{to}");
    }
    assert_eq!(
        hbk(&format!("{JSON}{{}}")).unwrap_err(),
        "Trailing data after HomeBank JSON"
    );
    assert_eq!(
        hbk(&JSON.replace("\"day\":29", "\"day\":30")).unwrap_err(),
        "Invalid transaction date"
    );
    assert_eq!(
        hbk(&JSON.replace("-12.34000001", "-12.340000001")).unwrap_err(),
        "Monetary amount exceeds supported precision"
    );
    assert_eq!(
        hbk(&JSON.replace(
            "\"isForecast\":false",
            "\"isForecast\":false,\"splits\":[{}]"
        ))
        .unwrap_err(),
        "Split transactions are not supported"
    );
    assert!(
        hbk(&JSON.replace("\"isForecast\":false", "\"isForecast\":false,\"splits\":[]")).is_ok()
    );
}

fn with_rows(json: &str, field: &str, count: usize, first_id: &str) -> String {
    let array = format!("\"{field}\":[");
    let start = json.find(&array).unwrap() + array.len();
    let end = if field == "transactions" {
        start + json[start..].find("]}").unwrap()
    } else {
        start + json[start..].find("],\"").unwrap()
    };
    let template = &json[start..end];
    let rows: Vec<String> = (1..=count)
        .map(|i| {
            let id = format!("{i:08x}-1111-4111-8111-{i:012x}");
            template.replace(
                &format!("\"id\":\"{first_id}\""),
                &format!("\"id\":\"{id}\""),
            )
        })
        .collect();
    format!("{}{}{}", &json[..start], rows.join(","), &json[end..])
}

#[test]
fn rejects_source_arrays_above_their_limits() {
    for (field, count, id) in [
        ("accounts", 101, "a12b3c4d-1111-4111-8111-111111111111"),
        ("categories", 201, "b12b3c4d-1111-4111-8111-111111111111"),
        ("payees", 10_001, "c12b3c4d-1111-4111-8111-111111111111"),
        (
            "transactions",
            25_001,
            "d12b3c4d-1111-4111-8111-111111111111",
        ),
    ] {
        assert_eq!(
            hbk(&with_rows(JSON, field, count, id)).unwrap_err(),
            "HomeBank list exceeds the supported limit",
            "{field}"
        );
    }
}

#[test]
fn rejects_oversized_strings_and_deep_metadata_but_accepts_bounded_unused_metadata() {
    assert!(hbk(&JSON.replace("Synthetic Main", &"x".repeat(101))).is_err());
    assert!(hbk(&JSON.replace("Synthetic Bank", &"x".repeat(101))).is_err());
    assert!(hbk(&JSON.replace("Synthetic Shop", &"x".repeat(256))).is_err());
    let deep = format!(
        "\"metadata\":{{{}\"x\":true{}}},\"manifest\":",
        "\"nested\":{".repeat(64),
        "}".repeat(64)
    );
    assert_eq!(
        hbk(&JSON.replace("\"manifest\":", &deep)).unwrap_err(),
        ios::CORRUPT
    );
    assert!(
        hbk(&JSON.replace(
            "\"manifest\":",
            "\"metadata\":{\"x\":1,\"x\":2},\"manifest\":"
        ))
        .is_err()
    );
    let unused = format!(
        "\"unused\":[{{\"text\":\"{}\"}}],\"manifest\":",
        "x".repeat(4096)
    );
    assert_eq!(
        hbk(&JSON.replace("\"manifest\":", &unused))
            .unwrap()
            .transactions
            .len(),
        1
    );
    let ids = JSON.replace("a12b3c4d-1111", "A12B3C4D-1111");
    assert_eq!(
        hbk(&ids).unwrap().accounts[0].id,
        "a12b3c4d-1111-4111-8111-111111111111"
    );
}

#[test]
fn rejects_truncated_and_trailing_compressed_data() {
    let compressed = deflate(JSON.as_bytes());
    assert_eq!(
        ios::parse(&compressed[..compressed.len() - 1], false, None).unwrap_err(),
        ios::CORRUPT
    );
    let mut trailing = compressed;
    trailing.push(0);
    assert_eq!(
        ios::parse(&trailing, false, None).unwrap_err(),
        "Trailing data after HomeBank compressed payload"
    );
}

#[test]
fn qif_parses_accounts_categories_transfers_splits_and_stable_ids() {
    let parsed = qif_eur(QIF).unwrap();
    let names: Vec<&str> = parsed.accounts.iter().map(|a| a.name.as_str()).collect();
    assert_eq!(names, ["Compte courant", "Épargne"]);
    let groceries = parsed
        .categories
        .iter()
        .find(|c| c.name == "Groceries")
        .unwrap();
    assert!(!groceries.income && groceries.parent_id.is_some() && !groceries.kind_inferred);
    assert!(
        parsed
            .categories
            .iter()
            .find(|c| c.name == "Salary")
            .unwrap()
            .income
    );
    let t = &parsed.transactions;
    assert_eq!(t.len(), 7);
    assert_eq!(t[0].amount.to_string(), "-12.34000001");
    assert_eq!(t[0].payee.as_deref(), Some("Épicerie 🥖"));
    assert_eq!(t[0].notes.as_deref(), Some("achats du mois"));
    assert_eq!(
        t[3].transfer_account_id.as_ref(),
        Some(&parsed.accounts[1].id)
    );
    assert_eq!(t[4].amount.to_string(), "-4.25");
    assert_eq!(t[4].notes.as_deref(), Some("split memo"));
    assert_eq!(
        t[6].transfer_account_id.as_ref(),
        Some(&parsed.accounts[0].id)
    );
    let ids: Vec<&str> = t.iter().map(|t| t.id.as_str()).collect();
    assert_eq!(
        ids,
        [
            "4460717b-135d-399f-b94a-cd209584e08b",
            "ca113258-5dfc-31a4-9332-d2a0489ca04b",
            "4861a933-68fe-3d4f-9390-819050a2dcaf",
            "5ca12e73-e24b-3bf9-9fdd-4d54ba27fdbf",
            "2a6d22bd-ebfd-38cb-9967-654ebf8a27b1",
            "176ca3a3-e0cf-38b7-9deb-190c301368de",
            "fdf45f83-af71-390c-ba1c-f106612219ca",
        ]
    );
}

#[test]
fn qif_ids_are_stable_and_distinct_for_repeated_rows() {
    let one =
        "!Account\nNChecking\nTBank\n^\n!Type:Bank\nD2024/01/02\nT-1.00\nC*\nPSame\nLFood\n^\n";
    let first = qif_eur(one).unwrap();
    let again = qif_eur(&one.replace("C*\n", "")).unwrap();
    assert_eq!(first.transactions[0].id, again.transactions[0].id);
    let two = qif_eur(&format!("{one}D2024/01/02\nT-1.00\nPSame\nLFood\n^\n")).unwrap();
    assert_ne!(two.transactions[0].id, two.transactions[1].id);
    let shifted = qif_eur(&one.replace(
        "!Type:Bank\n",
        "!Type:Bank\nD2024/01/01\nT-9.00\nPOther\n^\n",
    ))
    .unwrap();
    assert_eq!(shifted.transactions[1].id, first.transactions[0].id);
}

#[test]
fn qif_rejects_malformed_input_and_bounds() {
    for bad in [
        "!Type:Bank\nD2024/01/01\nT1\n^".to_owned(),
        "!Account\nN\nTBank\n^\n!Type:Bank\nD2024/02/30\nT1\n^\n".to_owned(),
        "!Account\nNChecking\nTBank\n^\n!Type:Bank\nD2024/01/01\nT1\nT2\n^\n".to_owned(),
        "!Account\nNChecking\nTBank\n^\n!Type:Invst\n^".to_owned(),
        "!Account\nNChecking\nTBank\n^\n!Type:Bank\nD2024/01/01\nT1\nQunknown\n^\n".to_owned(),
        "!Account\nNChecking\nTBank\n^\n!Type:Bank\nD2024/01/01\nT-2\nSFood\n$-1\n^".to_owned(),
        "!Account\nNChecking\nTBank\n^\n!Account\nNChecking\nTBank\n^\n".to_owned(),
        format!(
            "!Account\nNChecking\nTBank\n^\n!Type:Bank\nD2024/01/01\nT1\nP{}\n^\n",
            "x".repeat(256)
        ),
        "!Account\nNChecking\nTBank\n^\n!Type:Bank\nD2024/01/01\nT1\n".to_owned(),
        format!(
            "!Account\nNChecking\nTBank\n^\n!Type:Bank\nD2024/01/01\nT-1\nM{}\nSFood\nE{}\n$-1\n^\n",
            "p".repeat(130),
            "s".repeat(130)
        ),
        format!(
            "!Account\nNChecking\nTBank\n^\n!Type:Bank\nD2024/01/01\nT-1\n^\n{}",
            "\n".repeat(500_001)
        ),
    ] {
        assert_eq!(
            qif_eur(&bad).unwrap_err(),
            "Invalid or unsupported QIF file",
            "{}",
            &bad[..bad.len().min(80)]
        );
    }
    assert!(qif::parse(QIF.as_bytes(), None).is_err());
    assert!(qif::parse(QIF.as_bytes(), Some("ZZZ")).is_err());
    assert!(qif::parse(QIF.as_bytes(), Some("FRF")).is_ok());
    assert!(qif::parse(&[0xc3, 0x28], Some("EUR")).is_err());
    let accounts: String = (0..101)
        .map(|i| format!("!Account\nNAccount {i}\nTBank\n^\n"))
        .collect();
    assert!(qif_eur(&accounts).is_err());
    let mut categories = "!Account\nNChecking\nTBank\n^\n".to_owned();
    for i in 0..201 {
        categories.push_str(&format!("!Type:Cat\nNCategory {i}\nE\n^\n"));
    }
    assert!(qif_eur(&categories).is_err());
    let mut rows = "!Account\nNChecking\nTBank\n^\n!Type:Bank\n".to_owned();
    rows.push_str(&"D2024/01/01\nT1\n^\n".repeat(25_001));
    assert!(qif_eur(&rows).is_err());
    let mut splits = "!Account\nNChecking\nTBank\n^\n!Type:Bank\nD2024/01/01\nT25001\n".to_owned();
    splits.push_str(&"SFood\n$1\n".repeat(25_001));
    splits.push_str("^\n");
    assert!(qif_eur(&splits).is_err());
}

#[test]
fn qif_category_kinds_memos_and_transfers_outside_the_file() {
    let refund = qif_eur("!Account\nNChecking\nTBank\n^\n!Type:Bank\nD2024/01/01\nT-12\nLFood\n^\nD2024/01/02\nT2\nLFood\n^\n").unwrap();
    assert!(!refund.categories[0].income && refund.categories[0].kind_inferred);
    let declared = qif_eur("!Account\nNChecking\nTBank\n^\n!Type:Cat\nNFood\nE\n^\nNSalary\nI\n^\n!Type:Bank\nD2024/01/01\nT-1\nLFood\n^\n").unwrap();
    assert!(declared.categories.iter().all(|c| !c.kind_inferred));
    let memos = qif_eur("!Account\nNChecking\nTBank\n^\n!Type:Bank\nD2024/01/01\nT-3\nMparent note\nSFood\nEsplit note\n$-3\n^\nD2024/01/02\nT-2\nMsame\nSFood\nEsame\n$-2\n^\nD2024/01/03\nT-1\nMparent only\nSFood\nE\n$-1\n^\n").unwrap();
    let notes: Vec<_> = memos
        .transactions
        .iter()
        .map(|t| t.notes.clone().unwrap())
        .collect();
    assert_eq!(notes, ["parent note — split note", "same", "parent only"]);
    let outside = qif_eur("!Account\nNChecking\nTBank\n^\n!Type:Bank\nD2024/01/05\nT-3.10\nPOut\nL[Elsewhere]\n^\nD2024/01/06\nT7.25\nMhello\nL[Elsewhere]\n^\n").unwrap();
    assert!(outside.categories.is_empty());
    assert_eq!(
        outside.transactions[0].notes.as_deref(),
        Some("Transfer: Elsewhere")
    );
    assert_eq!(
        outside.transactions[1].notes.as_deref(),
        Some("hello — Transfer: Elsewhere")
    );
    assert!(
        outside
            .transactions
            .iter()
            .all(|t| t.transfer_account_id.is_none())
    );
    let long = qif_eur(&format!(
        "!Account\nNChecking\nTBank\n^\n!Type:Bank\nD2024/01/05\nT-1\nL[{}]\n^\n",
        "n".repeat(250)
    ))
    .unwrap();
    assert_eq!(long.transactions[0].notes, None);
    let cash = qif_eur("!Account\nNCash\nTCash\n^\n!Type:Cash\nD2024/01/01\nT-1\n^\n!Account\nNCard\nTCCard\n^\n!Type:CCard\nD2024/01/02\nT-2\n^\n").unwrap();
    let types: Vec<_> = cash
        .accounts
        .iter()
        .map(|a| a.source_type.as_str())
        .collect();
    assert_eq!(types, ["cash", "creditcard"]);
}

fn dated(dates: &[&str]) -> String {
    let mut qif = "!Account\nNChecking\nTBank\n^\n!Type:Bank\n".to_owned();
    for date in dates {
        qif.push_str(&format!("D{date}\nT-1.00\nPSame\n^\n"));
    }
    qif
}

fn dates_of(source: &str) -> Vec<String> {
    qif_eur(source)
        .unwrap()
        .transactions
        .iter()
        .map(|t| t.date.to_string())
        .collect()
}

#[test]
fn qif_dates_pick_one_format_for_the_whole_file() {
    assert_eq!(
        dates_of(&dated(&["15/01/2024", "02/03/2024"])),
        ["2024-01-15", "2024-03-02"]
    );
    assert_eq!(
        dates_of(&dated(&["01/15/2024", "03/02/2024"])),
        ["2024-01-15", "2024-03-02"]
    );
    assert_eq!(
        dates_of(&dated(&["15-1-2024", "2.3.2024"])),
        ["2024-01-15", "2024-03-02"]
    );
    assert_eq!(
        dates_of(&dated(&["2024-01-15", "2024.03.02"])),
        ["2024-01-15", "2024-03-02"]
    );
    assert_eq!(
        dates_of(&dated(&["05/05/2024", "12/12/2024"])),
        ["2024-05-05", "2024-12-12"]
    );
    let ids = |d: &[&str]| {
        qif_eur(&dated(d))
            .unwrap()
            .transactions
            .iter()
            .map(|t| t.id.clone())
            .collect::<Vec<_>>()
    };
    assert_eq!(
        ids(&["2024/01/15", "2024/03/02"]),
        ids(&["15/01/2024", "02/03/2024"])
    );
    for ambiguous in [&["01/02/2024", "03/04/2024"][..], &["01/02/2024"]] {
        assert_eq!(qif_eur(&dated(ambiguous)).unwrap_err(), AMBIGUOUS_DATES);
    }
    for unsupported in [
        &["2024/01/15", "15/01/2024"][..],
        &["15/01/2024", "01/15/2024"],
        &["15/01/24"],
        &["1/15'24"],
        &["not a date"],
        &["2024/02/30"],
        &["31/02/2024"],
        &["02/31/2024"],
        &["2024/01-15"],
    ] {
        assert_eq!(
            qif_eur(&dated(unsupported)).unwrap_err(),
            UNSUPPORTED_DATES,
            "{unsupported:?}"
        );
    }
}

#[test]
fn java_hash_set_iterates_like_java() {
    let ins: Vec<String> = (0..40)
        .map(|i| format!("Cat{i}:Sub{}", i * 7 % 5))
        .collect();
    let mut explicit = JavaHashSet::default();
    for key in &ins[..5] {
        explicit.add(key);
    }
    assert_eq!(
        explicit.iter_java().join(","),
        "Cat3:Sub1,Cat1:Sub2,Cat2:Sub4,Cat4:Sub3,Cat0:Sub0"
    );
    let mut negative = JavaHashSet::default();
    for key in &ins[3..30] {
        negative.add(key);
    }
    let mut paths = JavaHashSet::with_initial(explicit.iter_java());
    for key in negative.iter_java() {
        paths.add(&key);
    }
    for key in paths.iter_java() {
        if let Some(at) = key.rfind(':') {
            paths.add(&key[..at]);
        }
    }
    assert_eq!(
        paths.iter_java().join(","),
        "Cat6:Sub2,Cat11:Sub2,Cat18,Cat17:Sub4,Cat25:Sub0,Cat17,Cat19,Cat14,Cat13,Cat16,Cat15,Cat10,Cat12,Cat11,Cat10:Sub0,Cat0:Sub0,Cat29,Cat5,Cat28,Cat4,Cat3,Cat2,Cat25,Cat1,Cat0,Cat24,Cat18:Sub1,Cat27,Cat26,Cat21,Cat20,Cat23,Cat2:Sub4,Cat22,Cat23:Sub1,Cat29:Sub3,Cat26:Sub2,Cat15:Sub0,Cat5:Sub0,Cat12:Sub4,Cat20:Sub0,Cat8:Sub1,Cat9:Sub3,Cat14:Sub3,Cat28:Sub1,Cat3:Sub1,Cat22:Sub4,Cat13:Sub1,Cat9,Cat8,Cat7,Cat6,Cat27:Sub4,Cat21:Sub2,Cat7:Sub4,Cat4:Sub3,Cat24:Sub3,Cat16:Sub2,Cat19:Sub3,Cat1:Sub2"
    );
}

//! Ports of MerchantNormalizerTest and MerchantKnowledgeBaseTest, plus the seeded knowledge base.

use picsou::ledger::merchants::{KnowledgeBase, match_key, normalize};
use picsou::ledger::model::{AliasMatchType, MerchantAlias, MerchantBrand};
use picsou::testing::TestApp;

#[test]
fn strips_payment_processor_wrapper_keeping_the_real_merchant() {
    assert_eq!(normalize(Some("PAYPAL *SPOTIFY 4357"), None), "Spotify");
    assert_eq!(normalize(Some("CB PAYPAL *NETFLIX"), None), "Netflix");
    assert_eq!(
        normalize(Some("SUMUP *BOULANGERIE DUPONT"), None),
        "Boulangerie Dupont"
    );
}

#[test]
fn strips_leading_transaction_noise_card_refs_and_dates() {
    assert_eq!(
        normalize(Some("CB CARREFOUR MARKET PARIS 12 01/02"), None),
        "Carrefour Market Paris 12"
    );
    assert_eq!(normalize(Some("CARTE 12/06 AUCHAN 4521"), None), "Auchan");
    assert_eq!(
        normalize(Some("MCDONALD'S 1234 PARIS"), None),
        "Mcdonalds Paris"
    );
}

#[test]
fn strips_sepa_transfer_prefix_from_description_when_no_counterparty() {
    assert_eq!(
        normalize(Some(""), Some("VIR SEPA SALAIRE ACME")),
        "Salaire Acme"
    );
    assert_eq!(
        normalize(None, Some("PRLV SEPA NETFLIX.COM")),
        "Netflix.com"
    );
}

#[test]
fn prefers_counterparty_over_description_and_falls_back_when_blank() {
    assert_eq!(
        normalize(Some("CARREFOUR"), Some("CB CARREFOUR PARIS")),
        "Carrefour"
    );
    assert_eq!(normalize(None, None), "");
    assert_eq!(normalize(Some("   "), Some("  ")), "");
}

#[test]
fn never_returns_empty_when_input_has_content_even_if_all_noise() {
    assert_eq!(normalize(Some("123456"), None), "123456");
}

#[test]
fn ascii_only_classes_match_java() {
    // Java's \b and \d are ASCII-only: a word boundary sits between "é" and "123", and
    // Arabic-Indic digits are not digits.
    assert_eq!(normalize(Some("CAFÉ123 X"), None), "Café X");
    assert_eq!(normalize(Some("CAFE ١٢٣٤"), None), "Cafe ١٢٣٤");
    // CASE_INSENSITIVE without UNICODE_CASE: the long s does not match "s" of "sepa".
    assert_eq!(normalize(Some("PRLV ſEPA X"), None), "Sepa X");
}

#[test]
fn match_key_is_lower_case_accent_free_and_apostrophe_stripped() {
    assert_eq!(match_key("Café Crème"), "cafe creme");
    assert_eq!(match_key("E.Leclerc"), "e.leclerc");
    assert_eq!(match_key("O'Tacos"), "otacos");
    assert_eq!(match_key("  Spotify  "), "spotify");
    assert_eq!(match_key(""), "");
}

#[test]
fn match_key_folds_latin_extended_a_and_keeps_undecomposable_letters() {
    assert_eq!(match_key("ŁÓDŹ Œuvre Straße"), "łodz œuvre straße");
    assert_eq!(match_key("Intermarché"), "intermarche");
    assert_eq!(match_key("Cafe\u{301}"), "cafe");
}

fn brand(id: i64, slug: &str, category_slug: &str) -> MerchantBrand {
    MerchantBrand {
        id,
        slug: slug.to_owned(),
        display_name: slug.to_owned(),
        default_category_slug: category_slug.to_owned(),
        color: None,
        monogram: None,
        logo_domain: None,
    }
}

fn alias(id: i64, brand_id: i64, pattern: &str, match_type: AliasMatchType) -> MerchantAlias {
    MerchantAlias {
        id,
        brand_id,
        pattern: pattern.to_owned(),
        match_type,
    }
}

fn kb() -> KnowledgeBase {
    KnowledgeBase::new(
        vec![
            brand(1, "carrefour", "courses"),
            brand(2, "uber", "transport"),
            brand(3, "uber-eats", "restaurants"),
            brand(4, "leclerc", "courses"),
        ],
        vec![
            alias(1, 1, "carrefour", AliasMatchType::Word),
            alias(2, 2, "uber", AliasMatchType::Word),
            alias(3, 3, "uber eats", AliasMatchType::Phrase),
            alias(4, 4, "leclerc", AliasMatchType::Word),
        ],
    )
}

fn slug(kb: &KnowledgeBase, key: &str) -> Option<String> {
    kb.find_match(key).map(|b| b.slug.clone())
}

#[test]
fn matches_single_word_against_any_token() {
    assert_eq!(
        slug(&kb(), "carrefour market paris 12").as_deref(),
        Some("carrefour")
    );
}

#[test]
fn splits_on_dots_so_dotted_brands_tokenize() {
    assert_eq!(slug(&kb(), "e.leclerc").as_deref(), Some("leclerc"));
}

#[test]
fn phrase_alias_outranks_parent_word_alias() {
    let kb = kb();
    assert_eq!(slug(&kb, "uber eats paris").as_deref(), Some("uber-eats"));
    assert_eq!(slug(&kb, "uber trip 12 34").as_deref(), Some("uber"));
}

#[test]
fn respects_word_boundaries() {
    let kb = kb();
    assert_eq!(slug(&kb, "ubered services"), None);
    assert_eq!(slug(&kb, "super uber eatsy").as_deref(), Some("uber"));
}

#[test]
fn returns_empty_for_unknown_or_blank() {
    let kb = kb();
    assert_eq!(slug(&kb, "boulangerie dupont"), None);
    assert_eq!(slug(&kb, ""), None);
    assert_eq!(slug(&kb, "   "), None);
}

#[test]
fn find_by_id_resolves_loaded_brands() {
    let kb = kb();
    assert_eq!(kb.find_by_id(2).map(|b| b.slug.as_str()), Some("uber"));
    assert!(kb.find_by_id(999).is_none());
    assert_eq!(kb.brand_count(), 4);
}

#[test]
fn longest_phrase_wins_and_first_word_alias_is_kept_and_orphans_skipped() {
    let kb = KnowledgeBase::new(
        vec![
            brand(1, "amazon", "shopping"),
            brand(2, "amazon-prime", "abonnements"),
        ],
        vec![
            alias(1, 1, "amazon", AliasMatchType::Phrase),
            alias(2, 2, "amazon prime", AliasMatchType::Phrase),
            alias(3, 1, "amzn", AliasMatchType::Word),
            alias(4, 2, "amzn", AliasMatchType::Word),
            alias(5, 99, "ghost", AliasMatchType::Word),
        ],
    );
    assert_eq!(
        slug(&kb, "amazon prime fr").as_deref(),
        Some("amazon-prime")
    );
    assert_eq!(slug(&kb, "amazon fr").as_deref(), Some("amazon"));
    assert_eq!(slug(&kb, "amzn mktp").as_deref(), Some("amazon"));
    assert_eq!(slug(&kb, "ghost"), None);
}

#[tokio::test]
async fn loads_the_seeded_knowledge_base() {
    let app = TestApp::with_admin().await;
    let kb = KnowledgeBase::load(&app.state.db).await.expect("load");
    assert!(kb.brand_count() >= 100);
    let resolve = |counterparty: &str| {
        let key = match_key(&normalize(Some(counterparty), None));
        kb.find_match(&key)
            .map(|b| (b.slug.clone(), b.default_category_slug.clone()))
    };
    assert_eq!(
        resolve("CB CARREFOUR MARKET PARIS 12 01/02"),
        Some(("carrefour".into(), "courses".into()))
    );
    assert_eq!(
        resolve("UBER EATS PARIS"),
        Some(("uber-eats".into(), "restaurants".into()))
    );
    assert_eq!(
        resolve("UBER TRIP 1234"),
        Some(("uber".into(), "transport".into()))
    );
    assert_eq!(
        resolve("CARTE 12/06 E.LECLERC 4521"),
        Some(("leclerc".into(), "courses".into()))
    );
    assert_eq!(
        resolve("PRLV SEPA NETFLIX.COM"),
        Some(("netflix".into(), "abonnements".into()))
    );
    assert_eq!(resolve("BOULANGERIE DUPONT"), None);
}

//! The ingestion subset of CategorizationService and CategoryService: default categories, the
//! rule and brand pipeline every import runs (enrich, rules, brand fallback). Rule CRUD, AI
//! categorization and retro-application belong to track T3.

use std::collections::HashMap;
use std::sync::Arc;

use chrono::{DateTime, Utc};
use rust_decimal::Decimal;
use sqlx::{PgConnection, PgExecutor, PgPool};

use super::merchants::{self, KnowledgeBase};
use super::model::{
    BudgetSettings, CATEGORY_COLUMNS, CategorizationRule, Category, CategoryKind, NewTransaction,
    RuleMatchType, Transaction,
};
use super::sync_values::java_is_blank;

struct DefaultCategory {
    slug: &'static str,
    name: &'static str,
    kind: CategoryKind,
    color: &'static str,
    icon: &'static str,
}

const fn default(
    slug: &'static str,
    name: &'static str,
    kind: CategoryKind,
    color: &'static str,
    icon: &'static str,
) -> DefaultCategory {
    DefaultCategory {
        slug,
        name,
        kind,
        color,
        icon,
    }
}

const DEFAULTS: &[DefaultCategory] = &[
    default(
        "courses",
        "Courses",
        CategoryKind::Expense,
        "#22c55e",
        "shopping-cart",
    ),
    default(
        "restaurants",
        "Restaurants",
        CategoryKind::Expense,
        "#f97316",
        "utensils",
    ),
    default(
        "transport",
        "Transport",
        CategoryKind::Expense,
        "#3b82f6",
        "car",
    ),
    default(
        "logement",
        "Logement",
        CategoryKind::Expense,
        "#8b5cf6",
        "house",
    ),
    default(
        "factures",
        "Factures & énergie",
        CategoryKind::Expense,
        "#eab308",
        "zap",
    ),
    default(
        "sante",
        "Santé",
        CategoryKind::Expense,
        "#ef4444",
        "heart-pulse",
    ),
    default(
        "loisirs",
        "Loisirs",
        CategoryKind::Expense,
        "#ec4899",
        "gamepad-2",
    ),
    default(
        "shopping",
        "Shopping",
        CategoryKind::Expense,
        "#14b8a6",
        "shopping-bag",
    ),
    default(
        "abonnements",
        "Abonnements",
        CategoryKind::Expense,
        "#6366f1",
        "repeat",
    ),
    default(
        "voyages",
        "Voyages",
        CategoryKind::Expense,
        "#06b6d4",
        "plane",
    ),
    default(
        "impots",
        "Impôts & taxes",
        CategoryKind::Expense,
        "#a16207",
        "landmark",
    ),
    default(
        "credit",
        "Crédit",
        CategoryKind::Expense,
        "#be123c",
        "hand-coins",
    ),
    default(
        "divers",
        "Divers",
        CategoryKind::Expense,
        "#94a3b8",
        "ellipsis",
    ),
    default(
        "salaire",
        "Salaire",
        CategoryKind::Income,
        "#16a34a",
        "wallet",
    ),
    default(
        "autres-revenus",
        "Autres revenus",
        CategoryKind::Income,
        "#65a30d",
        "hand-coins",
    ),
    default(
        "revenus-capital",
        "Revenus du capital",
        CategoryKind::Income,
        "#ca8a04",
        "coins",
    ),
    default(
        "remboursements",
        "Remboursements",
        CategoryKind::Income,
        "#0ea5e9",
        "undo-2",
    ),
    default(
        "epargne",
        "Épargne",
        CategoryKind::Transfer,
        "#0891b2",
        "piggy-bank",
    ),
    default(
        "investissement",
        "Investissement",
        CategoryKind::Transfer,
        "#7c3aed",
        "trending-up",
    ),
    default(
        "virement-interne",
        "Virement interne",
        CategoryKind::Transfer,
        "#64748b",
        "arrow-left-right",
    ),
];

/// The slug of the default internal-transfer category.
pub const TRANSFER_SLUG: &str = "virement-interne";

/// The member's categories in display order, archived ones included or not.
pub async fn categories_of_member<'e>(
    db: impl PgExecutor<'e>,
    member_id: i64,
    include_archived: bool,
) -> sqlx::Result<Vec<Category>> {
    sqlx::query_as(&format!(
        "SELECT {CATEGORY_COLUMNS} FROM category c WHERE c.member_id = $1 AND ($2 OR c.archived = false) \
         ORDER BY c.sort_order, c.id"
    ))
    .bind(member_id)
    .bind(include_archived)
    .fetch_all(db)
    .await
}

pub async fn category_of_member<'e>(
    db: impl PgExecutor<'e>,
    id: i64,
    member_id: i64,
) -> sqlx::Result<Option<Category>> {
    sqlx::query_as(&format!(
        "SELECT {CATEGORY_COLUMNS} FROM category c WHERE c.id = $1 AND c.member_id = $2"
    ))
    .bind(id)
    .bind(member_id)
    .fetch_optional(db)
    .await
}

async fn insert_default<'e>(
    db: impl PgExecutor<'e>,
    member_id: i64,
    d: &DefaultCategory,
    sort_order: i32,
    now: DateTime<Utc>,
) -> sqlx::Result<Category> {
    sqlx::query_as(&format!(
        "WITH c AS (INSERT INTO category (member_id, slug, name, kind, color, icon, is_default, archived, sort_order, \
         created_at, updated_at) VALUES ($1, $2, $3, $4::category_kind, $5, $6, true, false, $7, $8, $8) RETURNING *) \
         SELECT {CATEGORY_COLUMNS} FROM c"
    ))
    .bind(member_id)
    .bind(d.slug)
    .bind(d.name)
    .bind(d.kind)
    .bind(d.color)
    .bind(d.icon)
    .bind(sort_order)
    .bind(now)
    .fetch_one(db)
    .await
}

/// CategoryService.ensureSeeded: gives a member without any category the defaults, in a
/// transaction of its own (REQUIRES_NEW) so the seed survives the caller's rollback.
pub async fn ensure_seeded(db: &PgPool, member_id: i64, now: DateTime<Utc>) -> sqlx::Result<()> {
    let mut tx = db.begin().await?;
    seed_if_empty(&mut tx, member_id, now).await?;
    tx.commit().await
}

async fn seed_if_empty(
    conn: &mut PgConnection,
    member_id: i64,
    now: DateTime<Utc>,
) -> sqlx::Result<()> {
    let exists: bool =
        sqlx::query_scalar("SELECT EXISTS(SELECT 1 FROM category WHERE member_id = $1)")
            .bind(member_id)
            .fetch_one(&mut *conn)
            .await?;
    if !exists {
        for (order, d) in (0..).zip(DEFAULTS) {
            insert_default(&mut *conn, member_id, d, order, now).await?;
        }
    }
    Ok(())
}

/// CategoryService.findOrCreateDefault: the member's category of a default slug, recreated at
/// the end of the list if the member deleted it; None for an unknown slug or an archived one.
/// Everything happens in the caller's transaction: Java's inner ensureSeeded call is a
/// self-invocation, so its REQUIRES_NEW never applies there.
pub async fn find_or_create_default(
    conn: &mut PgConnection,
    member_id: i64,
    slug: &str,
    now: DateTime<Utc>,
) -> sqlx::Result<Option<Category>> {
    let Some(wanted) = DEFAULTS.iter().find(|d| d.slug == slug) else {
        return Ok(None);
    };
    seed_if_empty(&mut *conn, member_id, now).await?;
    let existing = categories_of_member(&mut *conn, member_id, true).await?;
    if let Some(found) = existing.iter().find(|c| c.slug.as_deref() == Some(slug)) {
        return Ok((!found.archived).then(|| found.clone()));
    }
    let sort_order = existing.iter().map(|c| c.sort_order).max().unwrap_or(-1) + 1;
    Ok(Some(
        insert_default(conn, member_id, wanted, sort_order, now).await?,
    ))
}

/// CategorizationService.categoriesBySlug: the member's active categories keyed by slug (the
/// first one wins on a duplicate slug), seeding the defaults first.
pub async fn categories_by_slug(
    db: &PgPool,
    member_id: i64,
    now: DateTime<Utc>,
) -> sqlx::Result<HashMap<String, Category>> {
    ensure_seeded(db, member_id, now).await?;
    let mut by_slug = HashMap::new();
    for category in categories_of_member(db, member_id, false).await? {
        if let Some(slug) = category.slug.clone().filter(|s| !java_is_blank(s)) {
            by_slug.entry(slug).or_insert(category);
        }
    }
    Ok(by_slug)
}

/// The member's rules, highest priority first.
pub async fn rules_of_member<'e>(
    db: impl PgExecutor<'e>,
    member_id: i64,
) -> sqlx::Result<Vec<CategorizationRule>> {
    sqlx::query_as(
        "SELECT id, member_id, match_type, pattern, category_id, priority, source, created_at \
         FROM categorization_rule WHERE member_id = $1 ORDER BY priority DESC, id",
    )
    .bind(member_id)
    .fetch_all(db)
    .await
}

/// BudgetSettingsRepository.findByMemberId.
pub async fn budget_settings_of_member<'e>(
    db: impl PgExecutor<'e>,
    member_id: i64,
) -> sqlx::Result<Option<BudgetSettings>> {
    sqlx::query_as(
        "SELECT id, member_id, cycle_start_day, logo_fetch_enabled, ai_categorization_enabled, ai_mode, \
         ai_confidence_threshold, updated_at FROM budget_settings WHERE member_id = $1",
    )
    .bind(member_id)
    .fetch_optional(db)
    .await
}

/// What the pipeline needs for one member, loaded once per import.
#[derive(Debug, Clone)]
pub struct CategorizationContext {
    pub rules: Vec<CategorizationRule>,
    pub categories_by_slug: HashMap<String, Category>,
    pub knowledge_base: Arc<KnowledgeBase>,
}

/// CategorizationService.loadContext.
pub async fn load_context(
    db: &PgPool,
    knowledge_base: Arc<KnowledgeBase>,
    member_id: i64,
    now: DateTime<Utc>,
) -> sqlx::Result<CategorizationContext> {
    Ok(CategorizationContext {
        rules: rules_of_member(db, member_id).await?,
        categories_by_slug: categories_by_slug(db, member_id, now).await?,
        knowledge_base,
    })
}

/// The fields the pipeline reads and writes, on a row to insert or a stored one.
pub trait Categorizable {
    fn counterparty(&self) -> Option<&str>;
    fn description(&self) -> &str;
    fn merchant_label(&self) -> Option<&str>;
    fn amount(&self) -> Decimal;
    fn category_id(&self) -> Option<i64>;
    fn merchant_brand_id(&self) -> Option<i64>;
    fn set_merchant(&mut self, label: String, brand_id: Option<i64>);
    fn set_category_id(&mut self, category_id: i64);
}

macro_rules! categorizable {
    ($t:ty) => {
        impl Categorizable for $t {
            fn counterparty(&self) -> Option<&str> {
                self.counterparty.as_deref()
            }
            fn description(&self) -> &str {
                &self.description
            }
            fn merchant_label(&self) -> Option<&str> {
                self.merchant_label.as_deref()
            }
            fn amount(&self) -> Decimal {
                self.amount
            }
            fn category_id(&self) -> Option<i64> {
                self.category_id
            }
            fn merchant_brand_id(&self) -> Option<i64> {
                self.merchant_brand_id
            }
            fn set_merchant(&mut self, label: String, brand_id: Option<i64>) {
                self.merchant_label = Some(label);
                if brand_id.is_some() {
                    self.merchant_brand_id = brand_id;
                }
            }
            fn set_category_id(&mut self, category_id: i64) {
                self.category_id = Some(category_id);
            }
        }
    };
}

categorizable!(NewTransaction);
categorizable!(Transaction);

/// CategorizationService.autoCategorize: enrich, then (unless already categorized) the first
/// matching rule, then the brand's default category, then built-in deterministic fallbacks
/// (card top-ups and common digital brands absent from the DB seed). True when a category was
/// assigned.
pub fn auto_categorize(tx: &mut impl Categorizable, ctx: &CategorizationContext) -> bool {
    enrich(tx, &ctx.knowledge_base);
    if tx.category_id().is_some() {
        return false;
    }
    if apply(tx, &ctx.rules) {
        return true;
    }
    if apply_brand_fallback(tx, ctx) {
        return true;
    }
    apply_builtin_fallback(tx, ctx)
}

/// Stamps the clean merchant label and the knowledge-base brand.
pub fn enrich(tx: &mut impl Categorizable, knowledge_base: &KnowledgeBase) {
    let label = merchants::normalize(tx.counterparty(), Some(tx.description()));
    if label.is_empty() {
        return;
    }
    let brand = knowledge_base
        .find_match(&merchants::match_key(&label))
        .map(|b| b.id);
    tx.set_merchant(label, brand);
}

/// The first rule (in priority order) that matches; never overrides an assigned category.
pub fn apply(tx: &mut impl Categorizable, rules: &[CategorizationRule]) -> bool {
    if tx.category_id().is_some() {
        return false;
    }
    let counterparty = lower(tx.counterparty());
    let description = lower(Some(tx.description()));
    let merchant_label = lower(tx.merchant_label());
    match rules
        .iter()
        .find(|r| matches(r, &counterparty, &description, &merchant_label))
    {
        Some(rule) => {
            tx.set_category_id(rule.category_id);
            true
        }
        None => false,
    }
}

fn apply_brand_fallback(tx: &mut impl Categorizable, ctx: &CategorizationContext) -> bool {
    let Some(brand_id) = tx.merchant_brand_id() else {
        return false;
    };
    let category = ctx
        .knowledge_base
        .find_by_id(brand_id)
        .and_then(|brand| ctx.categories_by_slug.get(&brand.default_category_slug));
    match category {
        Some(category) => {
            tx.set_category_id(category.id);
            true
        }
        None => false,
    }
}

/// Deterministic fallback for universal patterns and modern digital brands not seeded in V39.
/// Always runs after user rules and DB merchant brands so explicit user/DB choices win.
fn apply_builtin_fallback(tx: &mut impl Categorizable, ctx: &CategorizationContext) -> bool {
    let amount = tx.amount();
    let label_lower = lower(tx.merchant_label());
    let desc_lower = lower(Some(tx.description()));

    // Positive card/wallet top-ups (e.g. Revolut "Top-up by *6960", Apple/Google Pay top-up)
    // are always self-transfers into the wallet.
    if amount > Decimal::ZERO && is_card_topup(&label_lower, &desc_lower) {
        if let Some(transfer) = ctx
            .categories_by_slug
            .get(TRANSFER_SLUG)
            .filter(|c| c.kind == CategoryKind::Transfer)
        {
            tx.set_category_id(transfer.id);
            return true;
        }
        return false;
    }

    if amount < Decimal::ZERO {
        let label_key = tx
            .merchant_label()
            .map(merchants::match_key)
            .unwrap_or_default();
        let desc_key = merchants::match_key(tx.description());
        if let Some(slug) = builtin_expense_slug(&label_key, &desc_key)
            && let Some(category) = ctx.categories_by_slug.get(slug)
        {
            tx.set_category_id(category.id);
            return true;
        }
    }

    false
}

fn is_card_topup(label_lower: &str, desc_lower: &str) -> bool {
    const TOPUP_MARKERS: &[&str] = &[
        "top-up by ",
        "top up by ",
        "apple pay top-up",
        "apple pay top up",
        "google pay top-up",
        "google pay top up",
        "card top-up",
        "card top up",
        "rechargement par carte",
    ];
    [label_lower, desc_lower]
        .iter()
        .any(|s| TOPUP_MARKERS.iter().any(|m| s.contains(m)))
}

/// Exact single-token labels that are unambiguous only when they are the entire merchant label
/// (so e.g. "Virement Claude Dupont" or "Chez Claude" never falsely matches "Claude").
const EXACT_EXPENSE_BRANDS: &[(&str, &str)] = &[
    ("claude", "abonnements"),
    ("cursor", "abonnements"),
    ("proton", "abonnements"),
];

/// Whole-word/whole-phrase brands and French tax authorities absent from the V39 seed.
const PHRASE_EXPENSE_BRANDS: &[(&str, &str)] = &[
    ("anthropic", "abonnements"),
    ("claude ai", "abonnements"),
    ("chatgpt", "abonnements"),
    ("cursor ai", "abonnements"),
    ("cursor ide", "abonnements"),
    ("anysphere", "abonnements"),
    ("midjourney", "abonnements"),
    ("perplexity", "abonnements"),
    ("elevenlabs", "abonnements"),
    ("mistral ai", "abonnements"),
    ("github", "abonnements"),
    ("gitlab", "abonnements"),
    ("vercel", "abonnements"),
    ("netlify", "abonnements"),
    ("cloudflare", "abonnements"),
    ("hetzner", "abonnements"),
    ("ovh", "abonnements"),
    ("ovhcloud", "abonnements"),
    ("scaleway", "abonnements"),
    ("digitalocean", "abonnements"),
    ("railway", "abonnements"),
    ("supabase", "abonnements"),
    ("notion", "abonnements"),
    ("figma", "abonnements"),
    ("linear", "abonnements"),
    ("boosteroid", "abonnements"),
    ("geforce now", "abonnements"),
    ("protonmail", "abonnements"),
    ("protonvpn", "abonnements"),
    ("proton ag", "abonnements"),
    ("nordvpn", "abonnements"),
    ("expressvpn", "abonnements"),
    ("1password", "abonnements"),
    ("bitwarden", "abonnements"),
    ("icloud", "abonnements"),
    ("google one", "abonnements"),
    ("canva", "abonnements"),
    ("discord", "abonnements"),
    ("deepl", "abonnements"),
    ("molotov", "abonnements"),
    ("epic games", "loisirs"),
    ("riot games", "loisirs"),
    ("blizzard", "loisirs"),
    ("ubisoft", "loisirs"),
    ("dgfip", "impots"),
    ("impots gouv", "impots"),
    ("direction generale des finances publiques", "impots"),
    ("tresor public", "impots"),
];

fn builtin_expense_slug(label_key: &str, desc_key: &str) -> Option<&'static str> {
    let label_norm = normalize_tokens(label_key);
    for &(exact, slug) in EXACT_EXPENSE_BRANDS {
        if label_norm == exact {
            return Some(slug);
        }
    }
    let desc_norm = normalize_tokens(desc_key);
    for &(phrase, slug) in PHRASE_EXPENSE_BRANDS {
        if contains_token_phrase(&label_norm, phrase) || contains_token_phrase(&desc_norm, phrase) {
            return Some(slug);
        }
    }
    None
}

/// Replaces punctuation separators (`.`, `-`, `/`, `_`) with spaces and collapses whitespace
/// so e.g. `claude.ai` becomes `claude ai` and `impots.gouv.fr` becomes `impots gouv fr`.
fn normalize_tokens(key: &str) -> String {
    let mut out = String::with_capacity(key.len());
    let mut prev_space = true;
    for ch in key.chars() {
        if ch.is_alphanumeric() {
            out.push(ch);
            prev_space = false;
        } else if !prev_space {
            out.push(' ');
            prev_space = true;
        }
    }
    if out.ends_with(' ') {
        out.pop();
    }
    out
}

fn contains_token_phrase(haystack: &str, phrase: &str) -> bool {
    if haystack.is_empty() || phrase.is_empty() {
        return false;
    }
    let mut start = 0;
    while let Some(rel) = haystack[start..].find(phrase) {
        let idx = start + rel;
        let end = idx + phrase.len();
        let left_ok = idx == 0 || haystack.as_bytes()[idx - 1] == b' ';
        let right_ok = end == haystack.len() || haystack.as_bytes()[end] == b' ';
        if left_ok && right_ok {
            return true;
        }
        start = idx + 1;
    }
    false
}

fn lower(value: Option<&str>) -> String {
    value.map(str::to_lowercase).unwrap_or_default()
}

/// Whether the rule matches the lower-cased fields.
pub fn matches(
    rule: &CategorizationRule,
    counterparty: &str,
    description: &str,
    merchant_label: &str,
) -> bool {
    let pattern = rule.pattern.to_lowercase();
    let sources = [counterparty, description, merchant_label];
    match rule.match_type {
        RuleMatchType::Counterparty => !counterparty.is_empty() && counterparty == pattern,
        RuleMatchType::Keyword => any_contains(&pattern, &sources),
        RuleMatchType::KeywordsAll => java_split_whitespace(&pattern)
            .iter()
            .all(|t| any_contains(t, &sources)),
        RuleMatchType::KeywordsAny => java_split_whitespace(&pattern)
            .iter()
            .any(|t| any_contains(t, &sources)),
    }
}

fn any_contains(sub: &str, sources: &[&str]) -> bool {
    sources.iter().any(|s| !s.is_empty() && s.contains(sub))
}

/// String.split("\\s+"): a leading empty token is kept, trailing ones are dropped.
fn java_split_whitespace(value: &str) -> Vec<&str> {
    let mut tokens: Vec<&str> = value
        .split([' ', '\t', '\n', '\u{0B}', '\u{0C}', '\r'])
        .collect();
    let leading_empty = tokens.first().is_some_and(|t| t.is_empty());
    let mut kept: Vec<&str> = Vec::with_capacity(tokens.len());
    for (i, token) in tokens.drain(..).enumerate() {
        if !token.is_empty() || (i == 0 && leading_empty) {
            kept.push(token);
        }
    }
    if kept.is_empty() {
        kept.push("");
    }
    kept
}

#[cfg(test)]
mod tests {
    use chrono::NaiveDate;

    use super::*;
    use crate::ledger::model::RuleSource;

    fn category(id: i64, slug: &str, kind: CategoryKind) -> Category {
        Category {
            id,
            member_id: 1,
            slug: Some(slug.to_owned()),
            name: slug.to_owned(),
            kind,
            color: "#000000".to_owned(),
            icon: Some("tag".to_owned()),
            parent_id: None,
            is_default: true,
            archived: false,
            sort_order: 0,
        }
    }

    fn test_ctx(rules: Vec<CategorizationRule>) -> CategorizationContext {
        let mut by_slug = HashMap::new();
        by_slug.insert(
            TRANSFER_SLUG.to_owned(),
            category(10, TRANSFER_SLUG, CategoryKind::Transfer),
        );
        by_slug.insert(
            "abonnements".to_owned(),
            category(20, "abonnements", CategoryKind::Expense),
        );
        by_slug.insert(
            "loisirs".to_owned(),
            category(30, "loisirs", CategoryKind::Expense),
        );
        by_slug.insert(
            "impots".to_owned(),
            category(40, "impots", CategoryKind::Expense),
        );
        CategorizationContext {
            rules,
            categories_by_slug: by_slug,
            knowledge_base: Arc::new(KnowledgeBase::new(vec![], vec![])),
        }
    }

    fn draft(description: &str, amount: i64) -> NewTransaction {
        NewTransaction::new(
            1,
            NaiveDate::from_ymd_opt(2026, 11, 20).unwrap(),
            description.to_owned(),
            Decimal::from(amount),
        )
    }

    #[test]
    fn categorizes_positive_revolut_card_topups_as_internal_transfers() {
        let ctx = test_ctx(vec![]);

        let mut topup = draft("Top-up by *6960", 82);
        assert!(auto_categorize(&mut topup, &ctx));
        assert_eq!(topup.category_id, Some(10));

        let mut apple = draft("Apple Pay top-up", 50);
        assert!(auto_categorize(&mut apple, &ctx));
        assert_eq!(apple.category_id, Some(10));

        // Negative amount must not be auto-tagged as a top-up transfer
        let mut negative = draft("Top-up by *6960", -82);
        assert!(!auto_categorize(&mut negative, &ctx));
        assert_eq!(negative.category_id, None);
    }

    #[test]
    fn categorizes_modern_saas_and_tax_brands_without_false_positives() {
        let ctx = test_ctx(vec![]);

        let mut claude = draft("Claude", -90);
        assert!(auto_categorize(&mut claude, &ctx));
        assert_eq!(claude.category_id, Some(20));

        let mut claude_ai = draft("CB CLAUDE.AI SUBSCRIPTION", -20);
        assert!(auto_categorize(&mut claude_ai, &ctx));
        assert_eq!(claude_ai.category_id, Some(20));

        let mut boosteroid = draft("Boosteroid", -17);
        assert!(auto_categorize(&mut boosteroid, &ctx));
        assert_eq!(boosteroid.category_id, Some(20));

        let mut impots = draft("PRLV SEPA DGFIP IMPOTS.GOUV.FR", -450);
        assert!(auto_categorize(&mut impots, &ctx));
        assert_eq!(impots.category_id, Some(40));

        // Common French name "Claude" inside a longer label must NOT falsely match
        let mut person = draft("Virement Claude Dupont", -50);
        assert!(!auto_categorize(&mut person, &ctx));
        assert_eq!(person.category_id, None);

        let mut bistro = draft("Chez Claude", -35);
        assert!(!auto_categorize(&mut bistro, &ctx));
        assert_eq!(bistro.category_id, None);

        let mut bakery = draft("Bakery", -5);
        assert!(!auto_categorize(&mut bakery, &ctx));
        assert_eq!(bakery.category_id, None);
    }

    #[test]
    fn user_rule_takes_precedence_over_builtin_fallback() {
        let rule = CategorizationRule {
            id: 1,
            member_id: 1,
            match_type: RuleMatchType::Keyword,
            pattern: "boosteroid".to_owned(),
            category_id: 30,
            priority: 10,
            source: RuleSource::User,
            created_at: DateTime::UNIX_EPOCH,
        };
        let ctx = test_ctx(vec![rule]);

        let mut tx = draft("Boosteroid", -17);
        assert!(auto_categorize(&mut tx, &ctx));
        assert_eq!(tx.category_id, Some(30));
    }
}

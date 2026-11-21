//! The ingestion subset of CategorizationService and CategoryService: default categories, the
//! rule and brand pipeline every import runs (enrich, rules, brand fallback). Rule CRUD, AI
//! categorization and retro-application belong to track T3.

use std::collections::HashMap;
use std::sync::Arc;

use chrono::{DateTime, Utc};
use sqlx::{PgExecutor, PgPool};

use super::merchants::{self, KnowledgeBase};
use super::model::{
    CATEGORY_COLUMNS, CategorizationRule, Category, CategoryKind, NewTransaction, RuleMatchType,
    Transaction,
};

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
    let exists: bool =
        sqlx::query_scalar("SELECT EXISTS(SELECT 1 FROM category WHERE member_id = $1)")
            .bind(member_id)
            .fetch_one(&mut *tx)
            .await?;
    if exists {
        return Ok(());
    }
    for (order, d) in (0..).zip(DEFAULTS) {
        insert_default(&mut *tx, member_id, d, order, now).await?;
    }
    tx.commit().await
}

/// CategoryService.findOrCreateDefault: the member's category of a default slug, recreated at
/// the end of the list if the member deleted it; None for an unknown slug or an archived one.
pub async fn find_or_create_default(
    db: &PgPool,
    member_id: i64,
    slug: &str,
    now: DateTime<Utc>,
) -> sqlx::Result<Option<Category>> {
    let Some(wanted) = DEFAULTS.iter().find(|d| d.slug == slug) else {
        return Ok(None);
    };
    ensure_seeded(db, member_id, now).await?;
    let existing = categories_of_member(db, member_id, true).await?;
    if let Some(found) = existing.iter().find(|c| c.slug.as_deref() == Some(slug)) {
        return Ok((!found.archived).then(|| found.clone()));
    }
    let sort_order = existing.iter().map(|c| c.sort_order).max().unwrap_or(-1) + 1;
    Ok(Some(
        insert_default(db, member_id, wanted, sort_order, now).await?,
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
        if let Some(slug) = category.slug.clone().filter(|s| !s.trim().is_empty()) {
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
/// matching rule, then the brand's default category. True when a category was assigned.
pub fn auto_categorize(tx: &mut impl Categorizable, ctx: &CategorizationContext) -> bool {
    enrich(tx, &ctx.knowledge_base);
    if tx.category_id().is_some() {
        return false;
    }
    if apply(tx, &ctx.rules) {
        return true;
    }
    apply_brand_fallback(tx, ctx)
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

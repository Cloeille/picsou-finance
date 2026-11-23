-- Budget categorization foundation (1.1.0, phase C — "zero-config" redesign).
-- Shared, single-owner migration for the merchant-categorization / subcategory /
-- cashflow-flow work. Adds the columns every later phase (V39 merchant KB, V40
-- recurring v2) builds on, so those phases never re-touch these tables.

-- ── Categories: hierarchy + stable join key ────────────────────────────────
-- `parent_id` (self-FK) turns the flat list into a one-level tree: a category may
-- have a parent (its subcategories). `slug` is a stable, member-agnostic key that
-- the global merchant knowledge base targets ("courses", "transport", …) so one
-- seeded brand table can resolve to each member's own category rows without
-- knowing their ids. Slugs survive renames; only the default set carries one.
ALTER TABLE category
  ADD COLUMN parent_id BIGINT REFERENCES category(id) ON DELETE SET NULL,
  ADD COLUMN slug      VARCHAR(60);

-- Backfill slugs for the default categories seeded before this migration
-- (CategoryService.DEFAULTS). New members get their slugs at seed time.
UPDATE category SET slug = CASE name
    WHEN 'Courses'             THEN 'courses'
    WHEN 'Restaurants'         THEN 'restaurants'
    WHEN 'Transport'           THEN 'transport'
    WHEN 'Logement'            THEN 'logement'
    WHEN 'Factures & énergie'  THEN 'factures'
    WHEN 'Santé'               THEN 'sante'
    WHEN 'Loisirs'             THEN 'loisirs'
    WHEN 'Shopping'            THEN 'shopping'
    WHEN 'Abonnements'         THEN 'abonnements'
    WHEN 'Voyages'             THEN 'voyages'
    WHEN 'Divers'              THEN 'divers'
    WHEN 'Salaire'             THEN 'salaire'
    WHEN 'Autres revenus'      THEN 'autres-revenus'
    WHEN 'Remboursements'      THEN 'remboursements'
    WHEN 'Épargne'             THEN 'epargne'
    WHEN 'Investissement'      THEN 'investissement'
    WHEN 'Virement interne'    THEN 'virement-interne'
END
WHERE is_default = TRUE AND slug IS NULL;

-- One slug per member (only constrains rows that carry a slug; user-created
-- categories keep slug NULL and are unaffected).
CREATE UNIQUE INDEX idx_category_member_slug
    ON category(member_id, slug) WHERE slug IS NOT NULL;
CREATE INDEX idx_category_parent ON category(parent_id);

-- ── Transactions: canonical merchant label ─────────────────────────────────
-- `merchant_label` is the human-readable brand/merchant derived from the raw
-- bank `counterparty`+`description` by MerchantNormalizer (set in phase V39).
-- Always populated for synced transactions; drives clean names + brand matching.
ALTER TABLE transaction
  ADD COLUMN merchant_label VARCHAR(255);

-- ── Per-member budget settings: KB gate + cosmetic opt-in ──────────────────
-- `kb_version` records which merchant-KB revision last categorized this member,
-- so a KB bump can re-run categorization for uncategorized transactions only.
-- `logo_fetch_enabled` is an opt-in (OFF by default) for fetching brand logos
-- online — purely cosmetic, it never feeds categorization (offline ADR holds).
ALTER TABLE budget_settings
  ADD COLUMN kb_version         INTEGER,
  ADD COLUMN logo_fetch_enabled BOOLEAN NOT NULL DEFAULT FALSE;

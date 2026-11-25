-- Picsou SQLite consolidated baseline schema (parity with PostgreSQL Flyway V1..V155).
--
-- Invariants:
--   * Exact 67-table / 739-column parity with backend-rs/src/db/schema.tsv.
--   * ZERO SQLite REAL columns: every PostgreSQL NUMERIC is stored as canonical TEXT
--     preserving exact rust_decimal::Decimal precision and scale.
--   * Numeric CHECK constraints use deterministic/innocuous dec_cmp / dec_add UDFs
--     registered on every connection by db::sqlite::decimal::register_decimal_functions.
--   * Booleans are stored as INTEGER (0/1); dates as ISO-8601 YYYY-MM-DD TEXT;
--     timestamps as RFC 3339 UTC TEXT; UUIDs as canonical hyphenated TEXT; bytea as BLOB.
--   * All 80 foreign keys (with ON DELETE actions), 40 UNIQUE constraints, 79 CHECK
--     constraints, 8 enum CHECKs, and partial/expression indexes are preserved.

CREATE TABLE access_key (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    member_id INTEGER NOT NULL,
    created_by INTEGER NOT NULL,
    name TEXT NOT NULL,
    key_prefix TEXT NOT NULL,
    key_hash TEXT NOT NULL,
    scopes TEXT NOT NULL DEFAULT '',
    last_used_at TEXT,
    expires_at TEXT,
    revoked_at TEXT,
    created_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%f000Z', 'now')),
    updated_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%f000Z', 'now')),
    CONSTRAINT uk_access_key_prefix UNIQUE (key_prefix),
    CONSTRAINT access_key_created_by_fkey FOREIGN KEY (created_by) REFERENCES app_user(id) ON DELETE CASCADE,
    CONSTRAINT access_key_member_id_fkey FOREIGN KEY (member_id) REFERENCES family_member(id) ON DELETE CASCADE
);
CREATE INDEX idx_access_key_member ON access_key (member_id);

CREATE TABLE account (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    name TEXT NOT NULL,
    type TEXT NOT NULL DEFAULT 'OTHER' CHECK (type IN ('LEP', 'PEA', 'COMPTE_TITRES', 'CRYPTO', 'CHECKING', 'SAVINGS', 'REAL_ESTATE', 'LOAN', 'EMPLOYEE_SAVINGS', 'LIVRET_A', 'LDDS', 'LIVRET_JEUNE', 'PEL', 'CEL', 'ASSURANCE_VIE', 'SCPI', 'CREDIT_CARD', 'PER', 'PRECIOUS_METALS', 'BROKERAGE', 'PEER_LOAN', 'OTHER')),
    provider TEXT,
    currency TEXT NOT NULL DEFAULT 'EUR',
    current_balance TEXT NOT NULL DEFAULT '0',
    last_synced_at TEXT,
    external_account_id TEXT,
    is_manual INTEGER NOT NULL DEFAULT 1 CHECK (is_manual IN (0, 1)),
    color TEXT NOT NULL DEFAULT '#6366f1',
    ticker TEXT,
    created_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%f000Z', 'now')),
    updated_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%f000Z', 'now')),
    member_id INTEGER NOT NULL,
    deleted_at TEXT,
    parent_account_id INTEGER,
    iban TEXT,
    logo_url TEXT,
    hidden INTEGER NOT NULL DEFAULT 0 CHECK (hidden IN (0, 1)),
    cash_balance TEXT,
    logo_key TEXT,
    requisition_id INTEGER,
    opened_at TEXT,
    payment_due_amount TEXT,
    payment_due_date TEXT,
    reward_points INTEGER,
    card_nature TEXT,
    provider_invested_eur TEXT,
    provider_paid_in_eur TEXT,
    scheduled_payment_eur TEXT,
    scheduled_payment_cadence TEXT,
    institution_name TEXT,
    institution_domain TEXT,
    CONSTRAINT ck_account_card_nature CHECK ((card_nature IS NULL) OR (card_nature IN ('IMMEDIATE_DEBIT', 'DEFERRED_DEBIT', 'CREDIT'))),
    CONSTRAINT ck_account_opened_at CHECK ((opened_at IS NULL) OR (opened_at > '1900-01-01')),
    CONSTRAINT ck_account_scheduled_payment_cadence CHECK ((scheduled_payment_cadence IS NULL) OR (scheduled_payment_cadence IN ('WEEKLY', 'BIWEEKLY', 'MONTHLY', 'QUARTERLY', 'YEARLY'))),
    CONSTRAINT account_member_id_fkey FOREIGN KEY (member_id) REFERENCES family_member(id) ON DELETE CASCADE,
    CONSTRAINT account_parent_account_id_fkey FOREIGN KEY (parent_account_id) REFERENCES account(id) ON DELETE SET NULL,
    CONSTRAINT account_requisition_id_fkey FOREIGN KEY (requisition_id) REFERENCES requisition(id) ON DELETE SET NULL
);
CREATE UNIQUE INDEX account_external_unique_active ON account (external_account_id, member_id, provider) WHERE (deleted_at IS NULL) AND (external_account_id IS NOT NULL) AND (is_manual = 0);
CREATE INDEX idx_account_deleted_at ON account (deleted_at);
CREATE INDEX idx_account_external_id ON account (external_account_id) WHERE external_account_id IS NOT NULL;
CREATE INDEX idx_account_iban_member ON account (iban, member_id) WHERE iban IS NOT NULL;
CREATE INDEX idx_account_member ON account (member_id);
CREATE INDEX idx_account_parent_account_id ON account (parent_account_id) WHERE parent_account_id IS NOT NULL;
CREATE INDEX idx_account_requisition ON account (requisition_id) WHERE requisition_id IS NOT NULL;

CREATE TABLE account_display_pref (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    account_id INTEGER NOT NULL,
    member_id INTEGER NOT NULL,
    list_position INTEGER,
    list_collapsed INTEGER NOT NULL DEFAULT 0 CHECK (list_collapsed IN (0, 1)),
    created_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%f000Z', 'now')),
    updated_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%f000Z', 'now')),
    CONSTRAINT uk_account_display_pref_account_member UNIQUE (account_id, member_id),
    CONSTRAINT account_display_pref_account_id_fkey FOREIGN KEY (account_id) REFERENCES account(id) ON DELETE CASCADE,
    CONSTRAINT account_display_pref_member_id_fkey FOREIGN KEY (member_id) REFERENCES family_member(id) ON DELETE CASCADE
);
CREATE INDEX idx_account_display_pref_member ON account_display_pref (member_id);

CREATE TABLE account_holding (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    account_id INTEGER NOT NULL,
    ticker TEXT NOT NULL,
    name TEXT,
    quantity TEXT NOT NULL,
    average_buy_in TEXT,
    current_price TEXT,
    last_synced_at TEXT,
    created_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%f000Z', 'now')),
    updated_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%f000Z', 'now')),
    quote_currency TEXT,
    provider_value_eur TEXT,
    provider_pnl_eur TEXT,
    provider_price_date TEXT,
    provider_wealth_tier TEXT,
    CONSTRAINT account_holding_account_id_ticker_key UNIQUE (account_id, ticker),
    CONSTRAINT ck_account_holding_provider_wealth_tier CHECK ((provider_wealth_tier IS NULL) OR (provider_wealth_tier IN ('SAFETY_NET', 'REAL_ESTATE', 'EQUITY', 'CRYPTO', 'ALTERNATIVE'))),
    CONSTRAINT ck_account_holding_quote_currency CHECK ((quote_currency IS NULL) OR (length(quote_currency) = 3 AND quote_currency GLOB '[A-Z][A-Z][A-Z]')),
    CONSTRAINT account_holding_account_id_fkey FOREIGN KEY (account_id) REFERENCES account(id) ON DELETE CASCADE
);

CREATE TABLE account_ownership (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    account_id INTEGER NOT NULL,
    member_id INTEGER NOT NULL,
    share_percent TEXT NOT NULL,
    created_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%f000Z', 'now')),
    updated_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%f000Z', 'now')),
    CONSTRAINT uk_account_ownership_account_member UNIQUE (account_id, member_id),
    CONSTRAINT ck_account_ownership_share CHECK ((dec_cmp(share_percent, '0') > 0) AND (dec_cmp(share_percent, '100') <= 0)),
    CONSTRAINT account_ownership_account_id_fkey FOREIGN KEY (account_id) REFERENCES account(id) ON DELETE CASCADE,
    CONSTRAINT account_ownership_member_id_fkey FOREIGN KEY (member_id) REFERENCES family_member(id) ON DELETE CASCADE
);
CREATE INDEX idx_account_ownership_account ON account_ownership (account_id);
CREATE INDEX idx_account_ownership_member ON account_ownership (member_id);

CREATE TABLE ai_call_log (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    created_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%f000Z', 'now')),
    member_id INTEGER,
    transaction_id INTEGER,
    merchant_label TEXT,
    batch_id TEXT,
    provider TEXT NOT NULL,
    model TEXT,
    prompt TEXT,
    response TEXT,
    prompt_tokens INTEGER,
    completion_tokens INTEGER,
    total_tokens INTEGER,
    latency_ms INTEGER,
    status TEXT NOT NULL,
    error TEXT,
    chosen_slug TEXT,
    confidence INTEGER,
    applied INTEGER NOT NULL DEFAULT 0 CHECK (applied IN (0, 1)),
    CONSTRAINT ai_call_log_member_id_fkey FOREIGN KEY (member_id) REFERENCES family_member(id) ON DELETE CASCADE,
    CONSTRAINT ai_call_log_transaction_id_fkey FOREIGN KEY (transaction_id) REFERENCES "transaction"(id) ON DELETE SET NULL
);
CREATE INDEX idx_ai_call_log_created ON ai_call_log (created_at DESC);
CREATE INDEX idx_ai_call_log_member ON ai_call_log (member_id, created_at DESC);

CREATE TABLE app_setting (
    setting_key TEXT NOT NULL,
    value TEXT NOT NULL,
    created_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%f000Z', 'now')),
    updated_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%f000Z', 'now')),
    CONSTRAINT app_setting_pkey PRIMARY KEY (setting_key)
);

CREATE TABLE app_user (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    username TEXT NOT NULL,
    password_hash TEXT NOT NULL,
    created_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%f000Z', 'now')),
    updated_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%f000Z', 'now')),
    member_id INTEGER NOT NULL,
    role TEXT NOT NULL DEFAULT 'MEMBER',
    is_activated INTEGER NOT NULL DEFAULT 1 CHECK (is_activated IN (0, 1)),
    activation_token TEXT,
    activation_token_expires TEXT,
    acknowledged_warning INTEGER NOT NULL DEFAULT 0 CHECK (acknowledged_warning IN (0, 1)),
    token_version INTEGER NOT NULL DEFAULT 0,
    CONSTRAINT app_user_activation_token_key UNIQUE (activation_token),
    CONSTRAINT app_user_username_key UNIQUE (username),
    CONSTRAINT uq_app_user_member_id UNIQUE (member_id),
    CONSTRAINT app_user_member_id_fkey FOREIGN KEY (member_id) REFERENCES family_member(id) ON DELETE CASCADE
);

CREATE TABLE balance_snapshot (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    account_id INTEGER NOT NULL,
    date TEXT NOT NULL,
    balance TEXT NOT NULL,
    created_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%f000Z', 'now')),
    invested_amount TEXT NOT NULL,
    source TEXT NOT NULL DEFAULT 'DERIVED',
    native_balance TEXT,
    statement_entered_at TEXT,
    CONSTRAINT balance_snapshot_account_id_date_key UNIQUE (account_id, date),
    CONSTRAINT ck_balance_snapshot_source CHECK (source IN ('MANUAL', 'DERIVED')),
    CONSTRAINT balance_snapshot_account_id_fkey FOREIGN KEY (account_id) REFERENCES account(id) ON DELETE CASCADE
);
CREATE INDEX idx_balance_snapshot_account_date ON balance_snapshot (account_id, date DESC);

CREATE TABLE budget (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    member_id INTEGER NOT NULL,
    category_id INTEGER NOT NULL,
    monthly_limit TEXT NOT NULL,
    created_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%f000Z', 'now')),
    updated_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%f000Z', 'now')),
    CONSTRAINT budget_category_id_key UNIQUE (category_id),
    CONSTRAINT budget_monthly_limit_check CHECK (dec_cmp(monthly_limit, '0') >= 0),
    CONSTRAINT budget_category_id_fkey FOREIGN KEY (category_id) REFERENCES category(id) ON DELETE CASCADE,
    CONSTRAINT budget_member_id_fkey FOREIGN KEY (member_id) REFERENCES family_member(id) ON DELETE CASCADE
);
CREATE INDEX idx_budget_member ON budget (member_id);

CREATE TABLE budget_settings (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    member_id INTEGER NOT NULL,
    cycle_start_day INTEGER NOT NULL DEFAULT 1,
    updated_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%f000Z', 'now')),
    kb_version INTEGER,
    logo_fetch_enabled INTEGER NOT NULL DEFAULT 0 CHECK (logo_fetch_enabled IN (0, 1)),
    ai_categorization_enabled INTEGER NOT NULL DEFAULT 0 CHECK (ai_categorization_enabled IN (0, 1)),
    ai_mode TEXT NOT NULL DEFAULT 'AUTO_HIGH_CONFIDENCE',
    ai_confidence_threshold INTEGER NOT NULL DEFAULT 75,
    CONSTRAINT budget_settings_member_id_key UNIQUE (member_id),
    CONSTRAINT budget_settings_cycle_start_day_check CHECK ((cycle_start_day >= 1) AND (cycle_start_day <= 28)),
    CONSTRAINT budget_settings_member_id_fkey FOREIGN KEY (member_id) REFERENCES family_member(id) ON DELETE CASCADE
);

CREATE TABLE categorization_rule (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    member_id INTEGER NOT NULL,
    match_type TEXT NOT NULL,
    pattern TEXT NOT NULL,
    category_id INTEGER NOT NULL,
    priority INTEGER NOT NULL DEFAULT 0,
    source TEXT NOT NULL DEFAULT 'USER',
    created_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%f000Z', 'now')),
    CONSTRAINT categorization_rule_category_id_fkey FOREIGN KEY (category_id) REFERENCES category(id) ON DELETE CASCADE,
    CONSTRAINT categorization_rule_member_id_fkey FOREIGN KEY (member_id) REFERENCES family_member(id) ON DELETE CASCADE
);
CREATE INDEX idx_rule_member_priority ON categorization_rule (member_id, priority DESC);

CREATE TABLE category (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    member_id INTEGER NOT NULL,
    name TEXT NOT NULL,
    kind TEXT NOT NULL CHECK (kind IN ('INCOME', 'EXPENSE', 'TRANSFER')),
    color TEXT NOT NULL DEFAULT '#6366f1',
    icon TEXT,
    is_default INTEGER NOT NULL DEFAULT 0 CHECK (is_default IN (0, 1)),
    archived INTEGER NOT NULL DEFAULT 0 CHECK (archived IN (0, 1)),
    sort_order INTEGER NOT NULL DEFAULT 0,
    created_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%f000Z', 'now')),
    updated_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%f000Z', 'now')),
    parent_id INTEGER,
    slug TEXT,
    CONSTRAINT category_member_id_fkey FOREIGN KEY (member_id) REFERENCES family_member(id) ON DELETE CASCADE,
    CONSTRAINT category_parent_id_fkey FOREIGN KEY (parent_id) REFERENCES category(id) ON DELETE SET NULL
);
CREATE INDEX idx_category_member ON category (member_id);
CREATE UNIQUE INDEX idx_category_member_slug ON category (member_id, slug) WHERE slug IS NOT NULL;
CREATE INDEX idx_category_parent ON category (parent_id);

CREATE TABLE crypto_asset (
    ticker TEXT NOT NULL,
    coingecko_id TEXT NOT NULL,
    name TEXT NOT NULL,
    source TEXT NOT NULL,
    resolved_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%f000Z', 'now')),
    CONSTRAINT crypto_asset_pkey PRIMARY KEY (ticker),
    CONSTRAINT ck_crypto_asset_source CHECK (source IN ('CURATED', 'AUTO')),
    CONSTRAINT ck_crypto_asset_ticker_upper CHECK (ticker = upper(ticker))
);

CREATE TABLE crypto_exchange_position (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    account_id INTEGER NOT NULL,
    product TEXT NOT NULL,
    ticker TEXT NOT NULL,
    quantity TEXT NOT NULL,
    principal TEXT,
    interest TEXT,
    last_synced_at TEXT,
    created_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%f000Z', 'now')),
    updated_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%f000Z', 'now')),
    CONSTRAINT uk_crypto_exchange_position_account_product_ticker UNIQUE (account_id, product, ticker),
    CONSTRAINT crypto_exchange_position_account_id_fkey FOREIGN KEY (account_id) REFERENCES account(id) ON DELETE CASCADE
);
CREATE INDEX idx_crypto_exchange_position_account ON crypto_exchange_position (account_id);

CREATE TABLE crypto_exchange_session (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    exchange_type TEXT NOT NULL,
    api_key TEXT NOT NULL,
    api_secret TEXT,
    status TEXT NOT NULL DEFAULT 'CONNECTED',
    last_synced_at TEXT,
    created_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%f000Z', 'now')),
    updated_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%f000Z', 'now')),
    member_id INTEGER NOT NULL,
    api_passphrase TEXT,
    region TEXT,
    CONSTRAINT crypto_exchange_session_member_id_fkey FOREIGN KEY (member_id) REFERENCES family_member(id) ON DELETE CASCADE
);
CREATE INDEX idx_crypto_exchange_session_member ON crypto_exchange_session (member_id);

CREATE TABLE debt (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    account_id INTEGER NOT NULL,
    linked_account_id INTEGER,
    borrowed_amount TEXT NOT NULL,
    interest_rate TEXT,
    monthly_payment TEXT,
    lender_name TEXT,
    start_date TEXT,
    end_date TEXT,
    created_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%f000Z', 'now')),
    updated_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%f000Z', 'now')),
    member_id INTEGER NOT NULL,
    insurance_monthly TEXT,
    file_fees TEXT,
    CONSTRAINT uk_debt_account UNIQUE (account_id),
    CONSTRAINT debt_account_id_fkey FOREIGN KEY (account_id) REFERENCES account(id) ON DELETE CASCADE,
    CONSTRAINT debt_linked_account_id_fkey FOREIGN KEY (linked_account_id) REFERENCES account(id) ON DELETE SET NULL,
    CONSTRAINT debt_member_id_fkey FOREIGN KEY (member_id) REFERENCES family_member(id) ON DELETE CASCADE
);
CREATE INDEX idx_debt_linked_account ON debt (linked_account_id) WHERE linked_account_id IS NOT NULL;
CREATE INDEX idx_debt_member ON debt (member_id);

CREATE TABLE degiro_session (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    member_id INTEGER NOT NULL,
    session_blob TEXT NOT NULL,
    status TEXT NOT NULL DEFAULT 'ACTIVE',
    last_synced_at TEXT,
    last_error TEXT,
    created_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%f000Z', 'now')),
    updated_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%f000Z', 'now')),
    CONSTRAINT uq_degiro_session_member UNIQUE (member_id),
    CONSTRAINT ck_degiro_session_failed_error CHECK (((status = 'FAILED') AND (last_error IS NOT NULL)) OR (status <> 'FAILED')),
    CONSTRAINT ck_degiro_session_status CHECK (status IN ('ACTIVE', 'REAUTH_REQUIRED', 'FAILED')),
    CONSTRAINT fk_degiro_session_member FOREIGN KEY (member_id) REFERENCES family_member(id) ON DELETE CASCADE
);

CREATE TABLE dividend_event (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    source_id INTEGER NOT NULL,
    ex_date TEXT NOT NULL,
    amount TEXT NOT NULL,
    amount_eur TEXT NOT NULL,
    payment_date TEXT,
    declared INTEGER NOT NULL DEFAULT 0 CHECK (declared IN (0, 1)),
    created_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%f000Z', 'now')),
    updated_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%f000Z', 'now')),
    CONSTRAINT uk_dividend_event_source_ex_date UNIQUE (source_id, ex_date),
    CONSTRAINT dividend_event_source_id_fkey FOREIGN KEY (source_id) REFERENCES dividend_source(id) ON DELETE CASCADE
);

CREATE TABLE dividend_source (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    ticker TEXT NOT NULL,
    currency TEXT,
    status TEXT NOT NULL DEFAULT 'NEVER_FETCHED',
    refreshed_at TEXT,
    last_attempt_at TEXT,
    last_error TEXT,
    created_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%f000Z', 'now')),
    updated_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%f000Z', 'now')),
    CONSTRAINT uk_dividend_source_ticker UNIQUE (ticker),
    CONSTRAINT ck_dividend_source_status CHECK (status IN ('NEVER_FETCHED', 'OK', 'NO_DATA', 'FAILED'))
);

CREATE TABLE family_member (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    display_name TEXT NOT NULL,
    avatar_color TEXT NOT NULL DEFAULT '#6366f1',
    is_managed INTEGER NOT NULL DEFAULT 0 CHECK (is_managed IN (0, 1)),
    created_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%f000Z', 'now')),
    updated_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%f000Z', 'now'))
);

CREATE TABLE finary_session (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    email TEXT NOT NULL,
    password TEXT NOT NULL,
    status TEXT NOT NULL DEFAULT 'CONNECTED',
    last_synced_at TEXT,
    created_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%f000Z', 'now')),
    updated_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%f000Z', 'now')),
    member_id INTEGER NOT NULL,
    CONSTRAINT finary_session_member_id_fkey FOREIGN KEY (member_id) REFERENCES family_member(id) ON DELETE CASCADE
);
CREATE INDEX idx_finary_session_member ON finary_session (member_id);

CREATE TABLE goal (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    name TEXT NOT NULL,
    target_amount TEXT,
    deadline TEXT,
    created_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%f000Z', 'now')),
    updated_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%f000Z', 'now')),
    member_id INTEGER NOT NULL,
    history_start_month TEXT,
    type TEXT NOT NULL DEFAULT 'SAVINGS_TARGET',
    monthly_amount TEXT,
    expected_return TEXT,
    start_date TEXT,
    end_date TEXT,
    CONSTRAINT ck_goal_recurring_dates CHECK ((start_date IS NULL) OR (end_date IS NULL) OR (end_date > start_date)),
    CONSTRAINT ck_goal_type CHECK (type IN ('SAVINGS_TARGET', 'RECURRING_INVESTMENT')),
    CONSTRAINT ck_goal_type_fields CHECK (((type = 'SAVINGS_TARGET') AND (target_amount IS NOT NULL) AND (deadline IS NOT NULL)) OR ((type = 'RECURRING_INVESTMENT') AND (monthly_amount IS NOT NULL))),
    CONSTRAINT goal_member_id_fkey FOREIGN KEY (member_id) REFERENCES family_member(id) ON DELETE CASCADE
);
CREATE INDEX idx_goal_member ON goal (member_id);

CREATE TABLE goal_account (
    goal_id INTEGER NOT NULL,
    account_id INTEGER NOT NULL,
    CONSTRAINT goal_account_pkey PRIMARY KEY (goal_id, account_id),
    CONSTRAINT goal_account_account_id_fkey FOREIGN KEY (account_id) REFERENCES account(id) ON DELETE CASCADE,
    CONSTRAINT goal_account_goal_id_fkey FOREIGN KEY (goal_id) REFERENCES goal(id) ON DELETE CASCADE
);
CREATE INDEX idx_goal_account_account ON goal_account (account_id);
CREATE INDEX idx_goal_account_goal ON goal_account (goal_id);

CREATE TABLE goal_allocation (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    goal_id INTEGER NOT NULL,
    ticker TEXT NOT NULL,
    monthly_amount TEXT NOT NULL,
    created_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%f000Z', 'now')),
    updated_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%f000Z', 'now')),
    CONSTRAINT uk_goal_allocation_goal_ticker UNIQUE (goal_id, ticker),
    CONSTRAINT ck_goal_allocation_amount CHECK (dec_cmp(monthly_amount, '0') > 0),
    CONSTRAINT goal_allocation_goal_id_fkey FOREIGN KEY (goal_id) REFERENCES goal(id) ON DELETE CASCADE
);

CREATE TABLE goal_contributor (
    goal_id INTEGER NOT NULL,
    member_id INTEGER NOT NULL,
    CONSTRAINT goal_contributor_pkey PRIMARY KEY (goal_id, member_id),
    CONSTRAINT goal_contributor_goal_id_fkey FOREIGN KEY (goal_id) REFERENCES goal(id) ON DELETE CASCADE,
    CONSTRAINT goal_contributor_member_id_fkey FOREIGN KEY (member_id) REFERENCES family_member(id) ON DELETE CASCADE
);

CREATE TABLE goal_manual_contribution (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    goal_id INTEGER NOT NULL,
    year_month TEXT NOT NULL,
    amount TEXT NOT NULL,
    member_id INTEGER NOT NULL,
    CONSTRAINT uk_goal_manual_contribution_goal_year UNIQUE (goal_id, year_month),
    CONSTRAINT goal_manual_contribution_goal_id_fkey FOREIGN KEY (goal_id) REFERENCES goal(id) ON DELETE CASCADE,
    CONSTRAINT goal_manual_contribution_member_id_fkey FOREIGN KEY (member_id) REFERENCES family_member(id) ON DELETE CASCADE
);

CREATE TABLE goal_month_override (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    goal_id INTEGER NOT NULL,
    year_month TEXT NOT NULL,
    amount TEXT NOT NULL,
    CONSTRAINT goal_month_override_goal_id_year_month_key UNIQUE (goal_id, year_month),
    CONSTRAINT goal_month_override_goal_id_fkey FOREIGN KEY (goal_id) REFERENCES goal(id) ON DELETE CASCADE
);

CREATE TABLE holding_classification (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    member_id INTEGER NOT NULL,
    ticker TEXT NOT NULL,
    wealth_tier TEXT,
    created_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%f000Z', 'now')),
    updated_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%f000Z', 'now')),
    sector_key TEXT,
    country_key TEXT,
    CONSTRAINT uk_holding_classification_member_ticker UNIQUE (member_id, ticker),
    CONSTRAINT ck_holding_classification_country CHECK ((country_key IS NULL) OR (length(country_key) = 2 AND country_key GLOB '[A-Z][A-Z]')),
    CONSTRAINT ck_holding_classification_tier CHECK ((wealth_tier IS NULL) OR (wealth_tier IN ('SAFETY_NET', 'REAL_ESTATE', 'EQUITY', 'CRYPTO', 'ALTERNATIVE'))),
    CONSTRAINT holding_classification_member_id_fkey FOREIGN KEY (member_id) REFERENCES family_member(id) ON DELETE CASCADE
);

CREATE TABLE ibkr_connection (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    member_id INTEGER NOT NULL,
    token TEXT NOT NULL,
    query_id TEXT NOT NULL,
    status TEXT NOT NULL DEFAULT 'CONNECTED',
    last_synced_at TEXT,
    created_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%f000Z', 'now')),
    updated_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%f000Z', 'now')),
    CONSTRAINT ibkr_connection_member_id_fkey FOREIGN KEY (member_id) REFERENCES family_member(id) ON DELETE CASCADE
);
CREATE UNIQUE INDEX idx_ibkr_connection_member ON ibkr_connection (member_id);

CREATE TABLE instrument_logo (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    ticker TEXT NOT NULL,
    status TEXT NOT NULL,
    image BLOB,
    content_type TEXT,
    image_dark BLOB,
    content_type_dark TEXT,
    attempted_at TEXT NOT NULL,
    fetched_at TEXT,
    CONSTRAINT uk_instrument_logo_ticker UNIQUE (ticker),
    CONSTRAINT ck_instrument_logo_dark_pair CHECK ((image_dark IS NULL) = (content_type_dark IS NULL)),
    CONSTRAINT ck_instrument_logo_status CHECK (status IN ('STORED', 'ABSENT', 'FAILED')),
    CONSTRAINT ck_instrument_logo_stored_image CHECK ((status = 'STORED') = ((image IS NOT NULL) AND (content_type IS NOT NULL) AND (fetched_at IS NOT NULL)))
);

CREATE TABLE lbp_session (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    member_id INTEGER NOT NULL,
    session_state TEXT NOT NULL,
    last_validated_at TEXT,
    is_active INTEGER NOT NULL DEFAULT 1 CHECK (is_active IN (0, 1)),
    sync_status TEXT NOT NULL DEFAULT 'IDLE',
    last_sync_started_at TEXT,
    last_sync_completed_at TEXT,
    last_sync_error TEXT,
    unsupported_products TEXT,
    created_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%f000Z', 'now')),
    updated_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%f000Z', 'now')),
    encrypted_identifier TEXT,
    CONSTRAINT lbp_session_member_id_key UNIQUE (member_id),
    CONSTRAINT ck_lbp_session_failed_error CHECK (((sync_status = 'FAILED') AND (last_sync_error IS NOT NULL)) OR ((sync_status <> 'FAILED') AND (last_sync_error IS NULL))),
    CONSTRAINT ck_lbp_session_last_sync_error CHECK ((last_sync_error IS NULL) OR (last_sync_error IN ('SESSION_EXPIRED', 'UPSTREAM_UNAVAILABLE', 'UPSTREAM_FORMAT_CHANGED', 'INVALID_SESSION_STATE', 'INTERNAL_ERROR', 'INVALID_CREDENTIALS', 'KEYPAD_CHANGED', 'KEYPAD_EXPIRED', 'INVALID_POSITIONS', 'APP_VALIDATION_TIMEOUT', 'AUTH_ATTEMPT_EXPIRED'))),
    CONSTRAINT ck_lbp_session_sync_status CHECK (sync_status IN ('IDLE', 'QUEUED', 'RUNNING', 'SUCCESS', 'FAILED')),
    CONSTRAINT fk_lbp_session_member FOREIGN KEY (member_id) REFERENCES family_member(id) ON DELETE CASCADE
);

CREATE TABLE louve_connection (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    member_id INTEGER NOT NULL,
    token_state TEXT,
    status TEXT NOT NULL DEFAULT 'PENDING',
    grant_generation INTEGER NOT NULL DEFAULT 0,
    last_synced_at TEXT,
    oauth_state TEXT,
    oauth_verifier TEXT,
    oauth_redirect_uri TEXT,
    oauth_client_id TEXT,
    oauth_started_at TEXT,
    created_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%f000Z', 'now')),
    updated_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%f000Z', 'now')),
    CONSTRAINT ck_louve_connection_attempt CHECK ((oauth_state IS NULL) = (oauth_verifier IS NULL)),
    CONSTRAINT ck_louve_connection_status CHECK (status IN ('PENDING', 'CONNECTED', 'ERROR', 'REAUTH_REQUIRED')),
    CONSTRAINT louve_connection_member_id_fkey FOREIGN KEY (member_id) REFERENCES family_member(id) ON DELETE CASCADE
);
CREATE UNIQUE INDEX idx_louve_connection_member ON louve_connection (member_id);

CREATE TABLE member_allocation_profile (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    member_id INTEGER NOT NULL,
    monthly_essential_expenses TEXT,
    safety_net_months INTEGER NOT NULL DEFAULT 6,
    real_estate_pct TEXT NOT NULL DEFAULT '30.00',
    equity_pct TEXT NOT NULL DEFAULT '50.00',
    crypto_pct TEXT NOT NULL DEFAULT '10.00',
    alternative_pct TEXT NOT NULL DEFAULT '10.00',
    created_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%f000Z', 'now')),
    updated_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%f000Z', 'now')),
    CONSTRAINT uk_member_allocation_profile_member UNIQUE (member_id),
    CONSTRAINT ck_member_allocation_profile_expenses CHECK ((monthly_essential_expenses IS NULL) OR (dec_cmp(monthly_essential_expenses, '0') >= 0)),
    CONSTRAINT ck_member_allocation_profile_months CHECK ((safety_net_months >= 1) AND (safety_net_months <= 24)),
    CONSTRAINT ck_member_allocation_profile_pct CHECK ((dec_cmp(real_estate_pct, '0') >= 0) AND (dec_cmp(equity_pct, '0') >= 0) AND (dec_cmp(crypto_pct, '0') >= 0) AND (dec_cmp(alternative_pct, '0') >= 0)),
    CONSTRAINT ck_member_allocation_profile_sum CHECK (dec_cmp(dec_add(dec_add(dec_add(real_estate_pct, equity_pct), crypto_pct), alternative_pct), '100') = 0),
    CONSTRAINT member_allocation_profile_member_id_fkey FOREIGN KEY (member_id) REFERENCES family_member(id) ON DELETE CASCADE
);

CREATE TABLE member_profile (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    member_id INTEGER NOT NULL,
    birth_date TEXT,
    marginal_tax_rate TEXT,
    household_status TEXT,
    tax_household_parts TEXT,
    dependents INTEGER,
    annual_gross_income TEXT,
    monthly_net_before_tax TEXT,
    withholding_tax_rate TEXT,
    monthly_savings_capacity TEXT,
    target_retirement_age INTEGER,
    risk_profile TEXT,
    created_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%f000Z', 'now')),
    updated_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%f000Z', 'now')),
    CONSTRAINT uk_member_profile_member UNIQUE (member_id),
    CONSTRAINT ck_member_profile_annual_gross_income CHECK ((annual_gross_income IS NULL) OR (dec_cmp(annual_gross_income, '0') >= 0)),
    CONSTRAINT ck_member_profile_birth_date CHECK ((birth_date IS NULL) OR (birth_date > '1900-01-01')),
    CONSTRAINT ck_member_profile_dependents CHECK ((dependents IS NULL) OR ((dependents >= 0) AND (dependents <= 20))),
    CONSTRAINT ck_member_profile_household_status CHECK ((household_status IS NULL) OR (household_status IN ('SINGLE', 'COUPLE'))),
    CONSTRAINT ck_member_profile_marginal_tax_rate CHECK ((marginal_tax_rate IS NULL) OR ((dec_cmp(marginal_tax_rate, '0') >= 0) AND (dec_cmp(marginal_tax_rate, '100') <= 0))),
    CONSTRAINT ck_member_profile_monthly_net_before_tax CHECK ((monthly_net_before_tax IS NULL) OR (dec_cmp(monthly_net_before_tax, '0') >= 0)),
    CONSTRAINT ck_member_profile_monthly_savings_capacity CHECK ((monthly_savings_capacity IS NULL) OR (dec_cmp(monthly_savings_capacity, '0') >= 0)),
    CONSTRAINT ck_member_profile_parts CHECK ((tax_household_parts IS NULL) OR ((dec_cmp(tax_household_parts, '1') >= 0) AND (dec_cmp(tax_household_parts, '20') <= 0))),
    CONSTRAINT ck_member_profile_retirement_age CHECK ((target_retirement_age IS NULL) OR ((target_retirement_age >= 40) AND (target_retirement_age <= 90))),
    CONSTRAINT ck_member_profile_risk_profile CHECK ((risk_profile IS NULL) OR (risk_profile IN ('PRUDENT', 'BALANCED', 'DYNAMIC', 'AGGRESSIVE'))),
    CONSTRAINT ck_member_profile_withholding_tax_rate CHECK ((withholding_tax_rate IS NULL) OR ((dec_cmp(withholding_tax_rate, '0') >= 0) AND (dec_cmp(withholding_tax_rate, '100') <= 0))),
    CONSTRAINT member_profile_member_id_fkey FOREIGN KEY (member_id) REFERENCES family_member(id) ON DELETE CASCADE
);

CREATE TABLE merchant_alias (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    brand_id INTEGER NOT NULL,
    pattern TEXT NOT NULL,
    match_type TEXT NOT NULL,
    CONSTRAINT merchant_alias_brand_id_fkey FOREIGN KEY (brand_id) REFERENCES merchant_brand(id) ON DELETE CASCADE
);
CREATE INDEX idx_merchant_alias_brand ON merchant_alias (brand_id);

CREATE TABLE merchant_brand (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    slug TEXT NOT NULL,
    display_name TEXT NOT NULL,
    default_category_slug TEXT NOT NULL,
    color TEXT,
    monogram TEXT,
    logo_domain TEXT,
    CONSTRAINT merchant_brand_slug_key UNIQUE (slug)
);

CREATE TABLE oauth2_authorization (
    id TEXT NOT NULL,
    registered_client_id TEXT NOT NULL,
    principal_name TEXT NOT NULL,
    authorization_grant_type TEXT NOT NULL,
    authorized_scopes TEXT DEFAULT NULL,
    attributes TEXT,
    state TEXT DEFAULT NULL,
    authorization_code_value TEXT,
    authorization_code_issued_at TEXT,
    authorization_code_expires_at TEXT,
    authorization_code_metadata TEXT,
    access_token_value TEXT,
    access_token_issued_at TEXT,
    access_token_expires_at TEXT,
    access_token_metadata TEXT,
    access_token_type TEXT DEFAULT NULL,
    access_token_scopes TEXT DEFAULT NULL,
    oidc_id_token_value TEXT,
    oidc_id_token_issued_at TEXT,
    oidc_id_token_expires_at TEXT,
    oidc_id_token_metadata TEXT,
    refresh_token_value TEXT,
    refresh_token_issued_at TEXT,
    refresh_token_expires_at TEXT,
    refresh_token_metadata TEXT,
    user_code_value TEXT,
    user_code_issued_at TEXT,
    user_code_expires_at TEXT,
    user_code_metadata TEXT,
    device_code_value TEXT,
    device_code_issued_at TEXT,
    device_code_expires_at TEXT,
    device_code_metadata TEXT,
    CONSTRAINT oauth2_authorization_pkey PRIMARY KEY (id)
);

CREATE TABLE oauth2_authorization_consent (
    registered_client_id TEXT NOT NULL,
    principal_name TEXT NOT NULL,
    authorities TEXT NOT NULL,
    CONSTRAINT oauth2_authorization_consent_pkey PRIMARY KEY (registered_client_id, principal_name)
);

CREATE TABLE oauth2_registered_client (
    id TEXT NOT NULL,
    client_id TEXT NOT NULL,
    client_id_issued_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%f000Z', 'now')),
    client_secret TEXT DEFAULT NULL,
    client_secret_expires_at TEXT,
    client_name TEXT NOT NULL,
    client_authentication_methods TEXT NOT NULL,
    authorization_grant_types TEXT NOT NULL,
    redirect_uris TEXT DEFAULT NULL,
    post_logout_redirect_uris TEXT DEFAULT NULL,
    scopes TEXT NOT NULL,
    client_settings TEXT NOT NULL,
    token_settings TEXT NOT NULL,
    CONSTRAINT oauth2_registered_client_pkey PRIMARY KEY (id)
);

CREATE TABLE peer_counterparty (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    member_id INTEGER NOT NULL,
    name TEXT NOT NULL,
    note TEXT,
    created_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%f000Z', 'now')),
    updated_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%f000Z', 'now')),
    CONSTRAINT peer_counterparty_member_id_fkey FOREIGN KEY (member_id) REFERENCES family_member(id) ON DELETE CASCADE
);
CREATE UNIQUE INDEX uk_peer_counterparty_member_name ON peer_counterparty (member_id, lower(name));

CREATE TABLE peer_loan_repayment (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    account_id INTEGER NOT NULL,
    paid_on TEXT NOT NULL,
    amount TEXT NOT NULL,
    kind TEXT NOT NULL DEFAULT 'PRINCIPAL',
    note TEXT,
    created_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%f000Z', 'now')),
    updated_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%f000Z', 'now')),
    cash_transaction_id INTEGER,
    cash_leg_owned INTEGER NOT NULL DEFAULT 1 CHECK (cash_leg_owned IN (0, 1)),
    CONSTRAINT ck_peer_loan_repayment_amount CHECK (dec_cmp(amount, '0') > 0),
    CONSTRAINT ck_peer_loan_repayment_kind CHECK (kind IN ('PRINCIPAL', 'INTEREST', 'WRITE_OFF')),
    CONSTRAINT peer_loan_repayment_account_id_fkey FOREIGN KEY (account_id) REFERENCES account(id) ON DELETE CASCADE,
    CONSTRAINT peer_loan_repayment_cash_transaction_id_fkey FOREIGN KEY (cash_transaction_id) REFERENCES "transaction"(id) ON DELETE SET NULL
);
CREATE INDEX idx_peer_loan_repayment_account_date ON peer_loan_repayment (account_id, paid_on);

CREATE TABLE peer_loan_terms (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    account_id INTEGER NOT NULL,
    counterparty_id INTEGER NOT NULL,
    principal TEXT NOT NULL,
    lent_at TEXT NOT NULL,
    expected_back TEXT,
    annual_rate TEXT,
    planned_monthly_amount TEXT,
    planned_months INTEGER,
    linked_account_id INTEGER,
    note TEXT,
    closed_at TEXT,
    created_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%f000Z', 'now')),
    updated_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%f000Z', 'now')),
    lending_transaction_id INTEGER,
    rate_period TEXT NOT NULL DEFAULT 'ANNUAL',
    CONSTRAINT uk_peer_loan_terms_account UNIQUE (account_id),
    CONSTRAINT ck_peer_loan_terms_planned CHECK ((planned_monthly_amount IS NULL) = (planned_months IS NULL)),
    CONSTRAINT ck_peer_loan_terms_planned_months CHECK ((planned_months IS NULL) OR (planned_months > 0)),
    CONSTRAINT ck_peer_loan_terms_principal CHECK (dec_cmp(principal, '0') > 0),
    CONSTRAINT ck_peer_loan_terms_rate CHECK ((annual_rate IS NULL) OR (dec_cmp(annual_rate, '0') >= 0)),
    CONSTRAINT ck_peer_loan_terms_rate_period CHECK (rate_period IN ('ANNUAL', 'MONTHLY')),
    CONSTRAINT peer_loan_terms_account_id_fkey FOREIGN KEY (account_id) REFERENCES account(id) ON DELETE CASCADE,
    CONSTRAINT peer_loan_terms_counterparty_id_fkey FOREIGN KEY (counterparty_id) REFERENCES peer_counterparty(id),
    CONSTRAINT peer_loan_terms_lending_transaction_id_fkey FOREIGN KEY (lending_transaction_id) REFERENCES "transaction"(id) ON DELETE SET NULL,
    CONSTRAINT peer_loan_terms_linked_account_id_fkey FOREIGN KEY (linked_account_id) REFERENCES account(id) ON DELETE SET NULL
);

CREATE TABLE persistent_session (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    series_id TEXT NOT NULL,
    token_hash TEXT NOT NULL,
    user_id INTEGER NOT NULL,
    user_agent TEXT,
    ip_prefix TEXT,
    trusted_for_2fa INTEGER NOT NULL DEFAULT 0 CHECK (trusted_for_2fa IN (0, 1)),
    created_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%f000Z', 'now')),
    last_used_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%f000Z', 'now')),
    expires_at TEXT NOT NULL,
    revoked_at TEXT,
    previous_token_hash TEXT,
    previous_token_at TEXT,
    sso_authenticated_at TEXT,
    CONSTRAINT persistent_session_series_id_key UNIQUE (series_id),
    CONSTRAINT persistent_session_user_id_fkey FOREIGN KEY (user_id) REFERENCES app_user(id) ON DELETE CASCADE
);
CREATE INDEX idx_persistent_session_series ON persistent_session (series_id);
CREATE INDEX idx_persistent_session_user_active ON persistent_session (user_id) WHERE revoked_at IS NULL;

CREATE TABLE price_snapshot (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    ticker TEXT NOT NULL,
    date TEXT NOT NULL,
    price_eur TEXT NOT NULL,
    created_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%f000Z', 'now')),
    CONSTRAINT uk_price_snapshot_ticker_date UNIQUE (ticker, date)
);
CREATE INDEX idx_price_snapshot_ticker_date ON price_snapshot (ticker, date);

CREATE TABLE property_valuation (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    account_id INTEGER NOT NULL,
    member_id INTEGER NOT NULL,
    valued_at TEXT NOT NULL,
    estimated_value TEXT NOT NULL,
    low_value TEXT,
    high_value TEXT,
    price_per_sqm TEXT,
    provider TEXT NOT NULL,
    confidence TEXT,
    sample_size INTEGER,
    source_year INTEGER,
    method_detail TEXT,
    created_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%f000Z', 'now')),
    updated_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%f000Z', 'now')),
    CONSTRAINT uk_property_valuation_account_date UNIQUE (account_id, valued_at),
    CONSTRAINT ck_property_valuation_confidence CHECK ((confidence IS NULL) OR (confidence IN ('HIGH', 'MEDIUM', 'LOW'))),
    CONSTRAINT property_valuation_account_id_fkey FOREIGN KEY (account_id) REFERENCES account(id) ON DELETE CASCADE,
    CONSTRAINT property_valuation_member_id_fkey FOREIGN KEY (member_id) REFERENCES family_member(id) ON DELETE CASCADE
);
CREATE INDEX idx_property_valuation_account ON property_valuation (account_id);
CREATE INDEX idx_property_valuation_member ON property_valuation (member_id);

CREATE TABLE real_estate_metadata (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    account_id INTEGER NOT NULL,
    purchase_price TEXT NOT NULL,
    purchase_date TEXT,
    surface_area TEXT,
    address TEXT,
    property_type TEXT,
    rental_income TEXT DEFAULT '0',
    created_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%f000Z', 'now')),
    updated_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%f000Z', 'now')),
    member_id INTEGER NOT NULL,
    category TEXT,
    description TEXT,
    postal_code TEXT,
    city TEXT,
    country TEXT NOT NULL DEFAULT 'FR',
    insee_code TEXT,
    latitude TEXT,
    longitude TEXT,
    ban_id TEXT,
    parcel_id TEXT,
    geocode_score TEXT,
    geocoded_at TEXT,
    land_area TEXT,
    construction_year INTEGER,
    rooms INTEGER,
    bedrooms INTEGER,
    floor_number INTEGER,
    floors_total INTEGER,
    has_elevator INTEGER CHECK (has_elevator IS NULL OR has_elevator IN (0, 1)),
    garage_count INTEGER NOT NULL DEFAULT 0,
    parking_count INTEGER NOT NULL DEFAULT 0,
    has_garden INTEGER NOT NULL DEFAULT 0 CHECK (has_garden IN (0, 1)),
    has_terrace INTEGER NOT NULL DEFAULT 0 CHECK (has_terrace IN (0, 1)),
    has_balcony INTEGER NOT NULL DEFAULT 0 CHECK (has_balcony IN (0, 1)),
    energy_class TEXT,
    agency_fees TEXT,
    notary_fees TEXT,
    works_cost TEXT,
    valuation_mode TEXT NOT NULL DEFAULT 'ESTIMATED',
    bathrooms INTEGER,
    CONSTRAINT uk_real_estate_metadata_account UNIQUE (account_id),
    CONSTRAINT ck_real_estate_metadata_bathrooms CHECK ((bathrooms IS NULL) OR ((bathrooms >= 0) AND (bathrooms <= 100))),
    CONSTRAINT ck_real_estate_metadata_construction_year CHECK ((construction_year IS NULL) OR ((construction_year >= 1000) AND (construction_year <= 2200))),
    CONSTRAINT ck_real_estate_metadata_energy_class CHECK ((energy_class IS NULL) OR ((energy_class >= 'A') AND (energy_class <= 'G'))),
    CONSTRAINT ck_real_estate_metadata_valuation_mode CHECK (valuation_mode IN ('ESTIMATED', 'MANUAL')),
    CONSTRAINT real_estate_metadata_account_id_fkey FOREIGN KEY (account_id) REFERENCES account(id) ON DELETE CASCADE,
    CONSTRAINT real_estate_metadata_member_id_fkey FOREIGN KEY (member_id) REFERENCES family_member(id) ON DELETE CASCADE
);
CREATE INDEX idx_real_estate_metadata_insee ON real_estate_metadata (insee_code) WHERE insee_code IS NOT NULL;
CREATE INDEX idx_real_estate_metadata_member ON real_estate_metadata (member_id);

CREATE TABLE recurring_series (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    member_id INTEGER NOT NULL,
    label TEXT NOT NULL,
    counterparty TEXT,
    expected_amount TEXT NOT NULL,
    cadence TEXT NOT NULL CHECK (cadence IN ('WEEKLY', 'BIWEEKLY', 'MONTHLY', 'QUARTERLY', 'YEARLY')),
    next_due_date TEXT,
    last_seen_date TEXT,
    category_id INTEGER,
    status TEXT NOT NULL DEFAULT 'SUGGESTED' CHECK (status IN ('SUGGESTED', 'CONFIRMED', 'IGNORED')),
    created_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%f000Z', 'now')),
    updated_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%f000Z', 'now')),
    confidence TEXT,
    amount_min TEXT,
    amount_max TEXT,
    is_variable INTEGER NOT NULL DEFAULT 0 CHECK (is_variable IN (0, 1)),
    previous_amount TEXT,
    price_changed_at TEXT,
    auto_confirmed INTEGER NOT NULL DEFAULT 0 CHECK (auto_confirmed IN (0, 1)),
    CONSTRAINT recurring_series_category_id_fkey FOREIGN KEY (category_id) REFERENCES category(id) ON DELETE SET NULL,
    CONSTRAINT recurring_series_member_id_fkey FOREIGN KEY (member_id) REFERENCES family_member(id) ON DELETE CASCADE
);
CREATE INDEX idx_recurring_member ON recurring_series (member_id);
CREATE UNIQUE INDEX idx_recurring_member_label ON recurring_series (member_id, lower(label));

CREATE TABLE requisition (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    requisition_id TEXT NOT NULL,
    institution_id TEXT NOT NULL,
    institution_name TEXT,
    status TEXT NOT NULL DEFAULT 'CREATED' CHECK (status IN ('CREATED', 'LINKED', 'EXPIRED', 'FAILED')),
    auth_link TEXT,
    created_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%f000Z', 'now')),
    updated_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%f000Z', 'now')),
    last_synced_at TEXT,
    member_id INTEGER NOT NULL,
    logo_url TEXT,
    logo_backfill_attempted_at TEXT,
    oauth_state TEXT,
    CONSTRAINT requisition_requisition_id_key UNIQUE (requisition_id),
    CONSTRAINT requisition_member_id_fkey FOREIGN KEY (member_id) REFERENCES family_member(id) ON DELETE CASCADE
);
CREATE INDEX idx_requisition_member ON requisition (member_id);
CREATE UNIQUE INDEX idx_requisition_oauth_state ON requisition (oauth_state);
CREATE INDEX idx_requisition_status ON requisition (status);

CREATE TABLE revolut_session (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    member_id INTEGER NOT NULL,
    expires_at TEXT,
    created_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%f000Z', 'now')),
    updated_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%f000Z', 'now')),
    credentials_enc TEXT,
    remember_credentials INTEGER NOT NULL DEFAULT 0 CHECK (remember_credentials IN (0, 1)),
    last_synced_at TEXT,
    CONSTRAINT revolut_session_member_id_fkey FOREIGN KEY (member_id) REFERENCES family_member(id)
);

CREATE TABLE savings_interest_config (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    account_id INTEGER NOT NULL,
    product TEXT NOT NULL CHECK (product IN ('LIVRET_A', 'LDDS', 'LEP', 'COMMERCIAL')),
    annual_rate TEXT NOT NULL,
    rate_basis TEXT NOT NULL DEFAULT 'NET' CHECK (rate_basis IN ('GROSS', 'NET')),
    tax_rate_pct TEXT,
    ceiling TEXT,
    created_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%f000Z', 'now')),
    updated_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%f000Z', 'now')),
    CONSTRAINT savings_interest_config_account_id_key UNIQUE (account_id),
    CONSTRAINT savings_interest_config_account_id_fkey FOREIGN KEY (account_id) REFERENCES account(id) ON DELETE CASCADE
);
CREATE INDEX idx_savings_interest_config_account ON savings_interest_config (account_id);

CREATE TABLE scpi_position (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    account_id INTEGER NOT NULL,
    member_id INTEGER NOT NULL,
    isin TEXT,
    management_company TEXT,
    share_count TEXT NOT NULL,
    subscription_price_eur TEXT,
    withdrawal_price_eur TEXT,
    dividend_policy TEXT NOT NULL DEFAULT 'CASH',
    jouissance_date TEXT,
    valuation_status TEXT NOT NULL DEFAULT 'PRICE_INCOMPLETE',
    created_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%f000Z', 'now')),
    updated_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%f000Z', 'now')),
    corum_fund_code TEXT,
    sofidy_fund_code TEXT,
    annual_distribution_per_share_eur TEXT,
    distribution_frequency TEXT,
    louve_fund_code TEXT,
    CONSTRAINT scpi_position_account_id_key UNIQUE (account_id),
    CONSTRAINT ck_scpi_position_annual_distribution CHECK ((annual_distribution_per_share_eur IS NULL) OR (dec_cmp(annual_distribution_per_share_eur, '0') >= 0)),
    CONSTRAINT ck_scpi_position_distribution_frequency CHECK ((distribution_frequency IS NULL) OR (distribution_frequency IN ('MONTHLY', 'QUARTERLY'))),
    CONSTRAINT ck_scpi_position_dividend_policy CHECK (dividend_policy IN ('CASH', 'REINVEST')),
    CONSTRAINT ck_scpi_position_share_count CHECK (dec_cmp(share_count, '0') >= 0),
    CONSTRAINT ck_scpi_position_subscription_price CHECK ((subscription_price_eur IS NULL) OR (dec_cmp(subscription_price_eur, '0') >= 0)),
    CONSTRAINT ck_scpi_position_valuation_status CHECK (valuation_status IN ('OK', 'PRICE_INCOMPLETE')),
    CONSTRAINT ck_scpi_position_withdrawal_price CHECK ((withdrawal_price_eur IS NULL) OR (dec_cmp(withdrawal_price_eur, '0') >= 0)),
    CONSTRAINT scpi_position_account_id_fkey FOREIGN KEY (account_id) REFERENCES account(id) ON DELETE CASCADE,
    CONSTRAINT scpi_position_member_id_fkey FOREIGN KEY (member_id) REFERENCES family_member(id) ON DELETE CASCADE
);
CREATE UNIQUE INDEX uq_scpi_position_corum_fund ON scpi_position (member_id, corum_fund_code) WHERE corum_fund_code IS NOT NULL;
CREATE UNIQUE INDEX uq_scpi_position_louve_fund ON scpi_position (member_id, louve_fund_code) WHERE louve_fund_code IS NOT NULL;
CREATE UNIQUE INDEX uq_scpi_position_sofidy_fund ON scpi_position (member_id, sofidy_fund_code) WHERE sofidy_fund_code IS NOT NULL;

CREATE TABLE scpi_provider_fund (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    member_id INTEGER NOT NULL,
    provider TEXT NOT NULL,
    fund_code TEXT NOT NULL,
    label TEXT,
    ignored INTEGER NOT NULL DEFAULT 0 CHECK (ignored IN (0, 1)),
    last_outcome TEXT,
    last_seen_at TEXT,
    last_synced_at TEXT,
    created_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%f000Z', 'now')),
    updated_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%f000Z', 'now')),
    CONSTRAINT uq_scpi_provider_fund UNIQUE (member_id, provider, fund_code),
    CONSTRAINT ck_scpi_provider_fund_outcome CHECK ((last_outcome IS NULL) OR (last_outcome IN ('CREATED', 'LINKED', 'UPDATED', 'PRICE_INCOMPLETE', 'AMBIGUOUS', 'IGNORED', 'MISSING_AT_PROVIDER', 'UNSUPPORTED_OWNERSHIP'))),
    CONSTRAINT ck_scpi_provider_fund_provider CHECK (provider IN ('CORUM', 'SOFIDY', 'LOUVE')),
    CONSTRAINT scpi_provider_fund_member_id_fkey FOREIGN KEY (member_id) REFERENCES family_member(id) ON DELETE CASCADE
);

CREATE TABLE security_composition_slice (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    profile_id INTEGER NOT NULL,
    kind TEXT NOT NULL,
    label TEXT NOT NULL,
    percent TEXT NOT NULL,
    CONSTRAINT uk_security_composition_slice_profile_kind_label UNIQUE (profile_id, kind, label),
    CONSTRAINT ck_security_composition_slice_kind CHECK (kind IN ('COMPANY', 'COUNTRY', 'SECTOR')),
    CONSTRAINT security_composition_slice_profile_id_fkey FOREIGN KEY (profile_id) REFERENCES security_profile(id) ON DELETE CASCADE
);
CREATE INDEX idx_security_composition_slice_profile ON security_composition_slice (profile_id);

CREATE TABLE security_profile (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    ticker TEXT NOT NULL,
    asset_type TEXT NOT NULL,
    sector_key TEXT,
    country_key TEXT,
    source TEXT,
    as_of TEXT,
    refreshed_at TEXT,
    created_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%f000Z', 'now')),
    updated_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%f000Z', 'now')),
    isin TEXT,
    status TEXT NOT NULL DEFAULT 'OK',
    last_error TEXT,
    last_attempt_at TEXT,
    ter_percent TEXT,
    distribution_policy TEXT,
    replication TEXT,
    domicile_country TEXT,
    CONSTRAINT uk_security_profile_ticker UNIQUE (ticker),
    CONSTRAINT ck_security_profile_asset_type CHECK (asset_type IN ('ETF', 'STOCK', 'CRYPTO', 'UNKNOWN')),
    CONSTRAINT ck_security_profile_distribution_policy CHECK ((distribution_policy IS NULL) OR (distribution_policy IN ('ACCUMULATING', 'DISTRIBUTING'))),
    CONSTRAINT ck_security_profile_domicile CHECK ((domicile_country IS NULL) OR (length(domicile_country) = 2 AND domicile_country GLOB '[A-Z][A-Z]')),
    CONSTRAINT ck_security_profile_isin CHECK ((isin IS NULL) OR (length(isin) = 12 AND isin GLOB '[A-Z][A-Z][A-Z0-9][A-Z0-9][A-Z0-9][A-Z0-9][A-Z0-9][A-Z0-9][A-Z0-9][A-Z0-9][A-Z0-9][0-9]')),
    CONSTRAINT ck_security_profile_replication CHECK ((replication IS NULL) OR (replication IN ('PHYSICAL', 'SYNTHETIC'))),
    CONSTRAINT ck_security_profile_status CHECK (status IN ('NEVER_FETCHED', 'OK', 'NO_DATA', 'FAILED'))
);
CREATE INDEX idx_security_profile_isin ON security_profile (isin) WHERE isin IS NOT NULL;

CREATE TABLE setup_audit (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    event TEXT NOT NULL,
    actor_username TEXT,
    ip TEXT,
    user_agent TEXT,
    details TEXT,
    at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%f000Z', 'now'))
);
CREATE INDEX idx_setup_audit_event_at ON setup_audit (event, at);

CREATE TABLE shared_resource (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    owner_member_id INTEGER NOT NULL,
    resource_type TEXT NOT NULL,
    resource_id INTEGER NOT NULL,
    created_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%f000Z', 'now')),
    CONSTRAINT shared_resource_owner_member_id_resource_type_resource_id_key UNIQUE (owner_member_id, resource_type, resource_id),
    CONSTRAINT shared_resource_owner_member_id_fkey FOREIGN KEY (owner_member_id) REFERENCES family_member(id) ON DELETE CASCADE
);
CREATE INDEX idx_shared_resource_owner ON shared_resource (owner_member_id, resource_type);

CREATE TABLE sharing_settings (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    member_id INTEGER NOT NULL,
    resource_type TEXT NOT NULL,
    sharing_level TEXT NOT NULL DEFAULT 'NONE' CHECK (sharing_level IN ('ALL', 'NONE', 'MANUAL')),
    updated_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%f000Z', 'now')),
    CONSTRAINT sharing_settings_member_id_resource_type_key UNIQUE (member_id, resource_type),
    CONSTRAINT sharing_settings_member_id_fkey FOREIGN KEY (member_id) REFERENCES family_member(id) ON DELETE CASCADE
);

CREATE TABLE sidecar_session (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    bank TEXT NOT NULL,
    member_id INTEGER NOT NULL,
    session_state TEXT NOT NULL,
    encrypted_credentials TEXT,
    unsupported_contracts TEXT,
    last_validated_at TEXT,
    is_active INTEGER NOT NULL DEFAULT 1 CHECK (is_active IN (0, 1)),
    sync_status TEXT NOT NULL DEFAULT 'IDLE',
    last_sync_started_at TEXT,
    last_sync_completed_at TEXT,
    last_sync_error TEXT,
    last_sync_life_ambiguous INTEGER NOT NULL DEFAULT 0,
    created_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%f000Z', 'now')),
    updated_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%f000Z', 'now')),
    region TEXT,
    CONSTRAINT uq_sidecar_session_bank_member UNIQUE (bank, member_id),
    CONSTRAINT ck_sidecar_session_bank CHECK (bank IN ('AMEX', 'AMUNDI', 'BANQUE_POPULAIRE', 'BNP', 'BOURSE_DIRECT', 'BOURSO', 'CAISSE_EPARGNE', 'CIC', 'CMES', 'CORUM', 'FORTUNEO', 'NATIXIS', 'SOFIDY', 'TRADE_REPUBLIC')),
    CONSTRAINT ck_sidecar_session_credentials CHECK ((encrypted_credentials IS NULL) OR (bank IN ('BANQUE_POPULAIRE', 'BOURSO', 'CAISSE_EPARGNE', 'CIC', 'CMES', 'CORUM'))),
    CONSTRAINT ck_sidecar_session_failed_error CHECK (((sync_status = 'FAILED') AND (last_sync_error IS NOT NULL)) OR ((sync_status <> 'FAILED') AND (last_sync_error IS NULL))),
    CONSTRAINT ck_sidecar_session_last_sync_error CHECK ((last_sync_error IS NULL) OR ((bank = 'AMEX') AND (last_sync_error IN ('INVALID_CREDENTIALS', 'INVALID_OTP', 'AUTH_ATTEMPT_EXPIRED', 'SESSION_EXPIRED', 'UPSTREAM_FORMAT_CHANGED', 'INVALID_DATA', 'UPSTREAM_UNAVAILABLE', 'INTERNAL_ERROR'))) OR ((bank = 'AMUNDI') AND (last_sync_error IN ('INVALID_CREDENTIALS', 'CAPTCHA_BLOCKED', 'INVALID_OTP', 'APP_VALIDATION_TIMEOUT', 'AUTH_ATTEMPT_EXPIRED', 'SESSION_EXPIRED', 'PORTFOLIO_INCOMPLETE', 'UPSTREAM_FORMAT_CHANGED', 'INVALID_DATA', 'UPSTREAM_UNAVAILABLE', 'INTERNAL_ERROR'))) OR ((bank = 'BANQUE_POPULAIRE') AND (last_sync_error IN ('SESSION_EXPIRED', 'INVALID_SESSION_STATE', 'INVALID_CREDENTIALS', 'KEYPAD_EXPIRED', 'INVALID_POSITIONS', 'KEYPAD_CHANGED', 'APP_VALIDATION_TIMEOUT', 'AUTH_ATTEMPT_EXPIRED', 'UPSTREAM_FORMAT_CHANGED', 'UPSTREAM_UNAVAILABLE', 'INTERNAL_ERROR'))) OR ((bank = 'BNP') AND (last_sync_error IN ('SESSION_EXPIRED', 'INVALID_SESSION_STATE', 'INVALID_CREDENTIALS', 'KEYPAD_EXPIRED', 'INVALID_POSITIONS', 'KEYPAD_CHANGED', 'MFA_TYPE_UNSUPPORTED', 'INVALID_OTP', 'APP_VALIDATION_TIMEOUT', 'AUTH_ATTEMPT_EXPIRED', 'RATE_LIMITED', 'ACTION_REQUIRED', 'PORTFOLIO_INCOMPLETE', 'UPSTREAM_FORMAT_CHANGED', 'INVALID_DATA', 'UPSTREAM_UNAVAILABLE', 'INTERNAL_ERROR'))) OR ((bank = 'BOURSE_DIRECT') AND (last_sync_error IN ('INVALID_CREDENTIALS', 'INVALID_OTP', 'AUTH_ATTEMPT_EXPIRED', 'SESSION_EXPIRED', 'PORTFOLIO_INCOMPLETE', 'UPSTREAM_FORMAT_CHANGED', 'INVALID_DATA', 'UPSTREAM_UNAVAILABLE', 'INTERNAL_ERROR'))) OR ((bank = 'BOURSO') AND (last_sync_error IN ('INVALID_CREDENTIALS', 'FRAUD_ACK_REQUIRED', 'IDENTITY_SELECTION_UNSUPPORTED', 'MFA_TYPE_UNSUPPORTED', 'APP_VALIDATION_TIMEOUT', 'AUTH_ATTEMPT_EXPIRED', 'SESSION_EXPIRED', 'PORTFOLIO_INCOMPLETE', 'UPSTREAM_FORMAT_CHANGED', 'INVALID_DATA', 'UPSTREAM_UNAVAILABLE', 'INTERNAL_ERROR'))) OR ((bank = 'CAISSE_EPARGNE') AND (last_sync_error IN ('SESSION_EXPIRED', 'INVALID_SESSION_STATE', 'INVALID_CREDENTIALS', 'KEYPAD_EXPIRED', 'INVALID_POSITIONS', 'KEYPAD_CHANGED', 'APP_VALIDATION_TIMEOUT', 'AUTH_ATTEMPT_EXPIRED', 'UPSTREAM_FORMAT_CHANGED', 'UPSTREAM_UNAVAILABLE', 'INTERNAL_ERROR'))) OR ((bank = 'CIC') AND (last_sync_error IN ('INVALID_CREDENTIALS', 'ACTION_REQUIRED', 'MFA_TYPE_UNSUPPORTED', 'INVALID_OTP', 'APP_VALIDATION_TIMEOUT', 'APP_VALIDATION_CANCELLED', 'AUTH_ATTEMPT_EXPIRED', 'SESSION_EXPIRED', 'PORTFOLIO_INCOMPLETE', 'UPSTREAM_FORMAT_CHANGED', 'INVALID_DATA', 'UPSTREAM_UNAVAILABLE', 'INTERNAL_ERROR'))) OR ((bank = 'CMES') AND (last_sync_error IN ('INVALID_CREDENTIALS', 'ACTION_REQUIRED', 'MFA_TYPE_UNSUPPORTED', 'INVALID_OTP', 'APP_VALIDATION_TIMEOUT', 'APP_VALIDATION_CANCELLED', 'AUTH_ATTEMPT_EXPIRED', 'SESSION_EXPIRED', 'PORTFOLIO_INCOMPLETE', 'UPSTREAM_FORMAT_CHANGED', 'INVALID_DATA', 'UPSTREAM_UNAVAILABLE', 'INTERNAL_ERROR'))) OR ((bank = 'CORUM') AND (last_sync_error IN ('INVALID_CREDENTIALS', 'SESSION_EXPIRED', 'MULTIPLE_CONTRACTS', 'PORTFOLIO_INCOMPLETE', 'UPSTREAM_FORMAT_CHANGED', 'INVALID_DATA', 'UPSTREAM_UNAVAILABLE', 'INTERNAL_ERROR'))) OR ((bank = 'FORTUNEO') AND (last_sync_error IN ('INVALID_CREDENTIALS', 'INVALID_OTP', 'AUTH_ATTEMPT_EXPIRED', 'SESSION_EXPIRED', 'INVESTOR_PROFILE_REQUIRED', 'PORTFOLIO_INCOMPLETE', 'UPSTREAM_FORMAT_CHANGED', 'INVALID_DATA', 'UPSTREAM_UNAVAILABLE', 'INTERNAL_ERROR'))) OR ((bank = 'NATIXIS') AND (last_sync_error IN ('INVALID_CREDENTIALS', 'MFA_TYPE_UNSUPPORTED', 'INVALID_OTP', 'RATE_LIMITED', 'AUTH_ATTEMPT_EXPIRED', 'SESSION_EXPIRED', 'PORTFOLIO_INCOMPLETE', 'UPSTREAM_FORMAT_CHANGED', 'INVALID_DATA', 'UPSTREAM_UNAVAILABLE', 'INTERNAL_ERROR'))) OR ((bank = 'SOFIDY') AND (last_sync_error IN ('INVALID_CREDENTIALS', 'MFA_INVALID', 'FIRST_VISIT_PENDING', 'EMAIL_UNREACHABLE', 'ACCOUNT_INACTIVE', 'RATE_LIMITED', 'AUTH_ATTEMPT_EXPIRED', 'SESSION_EXPIRED', 'PORTFOLIO_INCOMPLETE', 'UPSTREAM_FORMAT_CHANGED', 'INVALID_DATA', 'UPSTREAM_UNAVAILABLE', 'INTERNAL_ERROR'))) OR ((bank = 'TRADE_REPUBLIC') AND (last_sync_error IN ('INVALID_CREDENTIALS', 'INVALID_OTP', 'APP_VALIDATION_TIMEOUT', 'APP_VALIDATION_CANCELLED', 'AUTH_ATTEMPT_EXPIRED', 'SESSION_EXPIRED', 'INVALID_SESSION_STATE', 'RATE_LIMITED', 'UPSTREAM_FORMAT_CHANGED', 'INVALID_DATA', 'UPSTREAM_UNAVAILABLE', 'INTERNAL_ERROR')))),
    CONSTRAINT ck_sidecar_session_life_ambiguous CHECK ((last_sync_life_ambiguous >= 0) AND ((bank = 'CORUM') OR (last_sync_life_ambiguous = 0))),
    CONSTRAINT ck_sidecar_session_region CHECK (((bank = 'BANQUE_POPULAIRE') AND (region IS NOT NULL) AND (region IN ('ALSACE_LORRAINE_CHAMPAGNE', 'AQUITAINE_CENTRE_ATLANTIQUE', 'AUVERGNE_RHONE_ALPES', 'BOURGOGNE_FRANCHE_COMTE', 'BRED', 'GRAND_OUEST', 'MEDITERRANEE', 'NORD', 'OCCITANE', 'RIVES_DE_PARIS', 'SUD', 'VAL_DE_FRANCE'))) OR ((bank <> 'BANQUE_POPULAIRE') AND (region IS NULL))),
    CONSTRAINT ck_sidecar_session_sync_status CHECK (sync_status IN ('IDLE', 'QUEUED', 'RUNNING', 'SUCCESS', 'FAILED')),
    CONSTRAINT ck_sidecar_session_unsupported_contracts CHECK ((unsupported_contracts IS NULL) OR (bank IN ('BANQUE_POPULAIRE', 'CAISSE_EPARGNE'))),
    CONSTRAINT fk_sidecar_session_member FOREIGN KEY (member_id) REFERENCES family_member(id) ON DELETE CASCADE
);
CREATE INDEX ix_sidecar_session_member ON sidecar_session (member_id);

CREATE TABLE simplefin_connection (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    member_id INTEGER NOT NULL,
    access_url TEXT NOT NULL,
    status TEXT NOT NULL DEFAULT 'CONNECTED',
    last_synced_at TEXT,
    created_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%f000Z', 'now')),
    updated_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%f000Z', 'now')),
    CONSTRAINT simplefin_connection_member_id_fkey FOREIGN KEY (member_id) REFERENCES family_member(id) ON DELETE CASCADE
);
CREATE UNIQUE INDEX idx_simplefin_connection_member ON simplefin_connection (member_id);

CREATE TABLE "transaction" (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    account_id INTEGER NOT NULL,
    date TEXT NOT NULL,
    description TEXT NOT NULL DEFAULT '',
    amount TEXT NOT NULL,
    type TEXT,
    category TEXT,
    native_currency TEXT NOT NULL DEFAULT 'EUR',
    created_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%f000Z', 'now')),
    is_manual INTEGER NOT NULL DEFAULT 0 CHECK (is_manual IN (0, 1)),
    tx_type TEXT,
    ticker TEXT,
    quantity TEXT,
    price_per_unit TEXT,
    category_id INTEGER,
    counterparty TEXT,
    external_id TEXT,
    recurring_series_id INTEGER,
    name TEXT,
    merchant_label TEXT,
    merchant_brand_id INTEGER,
    ai_suggested_category_id INTEGER,
    ai_confidence INTEGER,
    category_manual INTEGER NOT NULL DEFAULT 0 CHECK (category_manual IN (0, 1)),
    fees TEXT,
    external_transaction_id TEXT,
    budget_neutral INTEGER NOT NULL DEFAULT 0 CHECK (budget_neutral IN (0, 1)),
    CONSTRAINT fk_transaction_recurring_series FOREIGN KEY (recurring_series_id) REFERENCES recurring_series(id) ON DELETE SET NULL,
    CONSTRAINT transaction_account_id_fkey FOREIGN KEY (account_id) REFERENCES account(id) ON DELETE CASCADE,
    CONSTRAINT transaction_ai_suggested_category_id_fkey FOREIGN KEY (ai_suggested_category_id) REFERENCES category(id) ON DELETE SET NULL,
    CONSTRAINT transaction_category_id_fkey FOREIGN KEY (category_id) REFERENCES category(id) ON DELETE SET NULL,
    CONSTRAINT transaction_merchant_brand_id_fkey FOREIGN KEY (merchant_brand_id) REFERENCES merchant_brand(id) ON DELETE SET NULL
);
CREATE INDEX idx_transaction_account_date ON "transaction" (account_id, date DESC);
CREATE UNIQUE INDEX idx_transaction_account_external ON "transaction" (account_id, external_id) WHERE external_id IS NOT NULL;
CREATE INDEX idx_transaction_account_external_id ON "transaction" (account_id, external_transaction_id);
CREATE INDEX idx_transaction_account_manual ON "transaction" (account_id, is_manual);
CREATE INDEX idx_transaction_category ON "transaction" (category_id);
CREATE INDEX idx_transaction_merchant_brand ON "transaction" (merchant_brand_id);
CREATE INDEX idx_transaction_recurring_series ON "transaction" (recurring_series_id);
CREATE UNIQUE INDEX ux_transaction_account_external_id ON "transaction" (account_id, external_id) WHERE external_id IS NOT NULL;

CREATE TABLE transaction_v118_backup (
    id INTEGER NOT NULL,
    account_id INTEGER NOT NULL,
    date TEXT NOT NULL,
    description TEXT NOT NULL,
    amount TEXT NOT NULL,
    type TEXT,
    category TEXT,
    native_currency TEXT NOT NULL,
    created_at TEXT NOT NULL,
    is_manual INTEGER NOT NULL CHECK (is_manual IN (0, 1)),
    tx_type TEXT,
    ticker TEXT,
    quantity TEXT,
    price_per_unit TEXT,
    category_id INTEGER,
    counterparty TEXT,
    external_id TEXT,
    recurring_series_id INTEGER,
    name TEXT,
    merchant_label TEXT,
    merchant_brand_id INTEGER,
    ai_suggested_category_id INTEGER,
    ai_confidence INTEGER,
    category_manual INTEGER NOT NULL CHECK (category_manual IN (0, 1)),
    fees TEXT,
    external_transaction_id TEXT
);

CREATE TABLE user_identity (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    user_id INTEGER NOT NULL,
    issuer TEXT NOT NULL,
    subject TEXT NOT NULL,
    email TEXT,
    display_name TEXT,
    last_login_at TEXT,
    created_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%f000Z', 'now')),
    updated_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%f000Z', 'now')),
    CONSTRAINT uk_user_identity_issuer_subject UNIQUE (issuer, subject),
    CONSTRAINT fk_user_identity_user FOREIGN KEY (user_id) REFERENCES app_user(id) ON DELETE CASCADE
);
CREATE INDEX idx_user_identity_user_id ON user_identity (user_id);

CREATE TABLE user_mfa (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    user_id INTEGER NOT NULL,
    enabled INTEGER NOT NULL DEFAULT 0 CHECK (enabled IN (0, 1)),
    totp_secret_enc TEXT NOT NULL,
    last_used_step INTEGER,
    enrolled_at TEXT,
    created_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%f000Z', 'now')),
    updated_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%f000Z', 'now')),
    CONSTRAINT user_mfa_user_id_key UNIQUE (user_id),
    CONSTRAINT user_mfa_user_id_fkey FOREIGN KEY (user_id) REFERENCES app_user(id) ON DELETE CASCADE
);

CREATE TABLE user_mfa_recovery_code (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    user_mfa_id INTEGER NOT NULL,
    code_hash TEXT NOT NULL,
    used_at TEXT,
    created_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%f000Z', 'now')),
    CONSTRAINT user_mfa_recovery_code_user_mfa_id_fkey FOREIGN KEY (user_mfa_id) REFERENCES user_mfa(id) ON DELETE CASCADE
);
CREATE INDEX idx_recovery_code_active ON user_mfa_recovery_code (user_mfa_id) WHERE used_at IS NULL;

CREATE TABLE wallet_address (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    chain TEXT NOT NULL,
    address TEXT NOT NULL,
    label TEXT,
    last_synced_at TEXT,
    created_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%f000Z', 'now')),
    updated_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%f000Z', 'now')),
    member_id INTEGER NOT NULL,
    CONSTRAINT wallet_address_member_id_fkey FOREIGN KEY (member_id) REFERENCES family_member(id) ON DELETE CASCADE
);
CREATE INDEX idx_wallet_address_member ON wallet_address (member_id);

CREATE TABLE wise_connection (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    member_id INTEGER NOT NULL,
    api_token TEXT NOT NULL,
    status TEXT NOT NULL DEFAULT 'CONNECTED',
    last_synced_at TEXT,
    created_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%f000Z', 'now')),
    updated_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%f000Z', 'now')),
    CONSTRAINT wise_connection_member_id_fkey FOREIGN KEY (member_id) REFERENCES family_member(id) ON DELETE CASCADE
);
CREATE UNIQUE INDEX idx_wise_connection_member ON wise_connection (member_id);

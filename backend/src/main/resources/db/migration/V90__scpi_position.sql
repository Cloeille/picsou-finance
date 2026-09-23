-- One row per SCPI account. The share count is fractional because a scheduled
-- purchase or a reinvested dividend rarely lands on a whole share. The account
-- balance is written by the service from withdrawal_price_eur × share_count;
-- this table keeps both prices so the subscription price can be shown beside
-- the balance without ever becoming it.
CREATE TABLE scpi_position (
    id                       BIGSERIAL PRIMARY KEY,
    account_id               BIGINT NOT NULL UNIQUE REFERENCES account(id) ON DELETE CASCADE,
    member_id                BIGINT NOT NULL REFERENCES family_member(id) ON DELETE CASCADE,
    isin                     VARCHAR(12),
    management_company       VARCHAR(100),
    share_count              NUMERIC(20, 8) NOT NULL,
    subscription_price_eur   NUMERIC(20, 8),
    withdrawal_price_eur     NUMERIC(20, 8),
    dividend_policy          VARCHAR(20) NOT NULL DEFAULT 'CASH',
    jouissance_date          DATE,
    valuation_status         VARCHAR(30) NOT NULL DEFAULT 'PRICE_INCOMPLETE',
    created_at               TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at               TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT ck_scpi_position_share_count
        CHECK (share_count >= 0),
    CONSTRAINT ck_scpi_position_subscription_price
        CHECK (subscription_price_eur IS NULL OR subscription_price_eur >= 0),
    CONSTRAINT ck_scpi_position_withdrawal_price
        CHECK (withdrawal_price_eur IS NULL OR withdrawal_price_eur >= 0),
    CONSTRAINT ck_scpi_position_dividend_policy
        CHECK (dividend_policy IN ('CASH', 'REINVEST')),
    CONSTRAINT ck_scpi_position_valuation_status
        CHECK (valuation_status IN ('OK', 'PRICE_INCOMPLETE'))
);

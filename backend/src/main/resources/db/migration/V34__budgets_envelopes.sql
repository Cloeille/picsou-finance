-- Budget envelopes (1.1.0, phase A).
-- One spending cap per category, evaluated against the member's payday budget cycle.
-- No rollover: each cycle starts fresh (decision recorded in the budget ADR).

CREATE TABLE budget (
    id            BIGSERIAL PRIMARY KEY,
    member_id     BIGINT        NOT NULL REFERENCES family_member(id) ON DELETE CASCADE,
    category_id   BIGINT        NOT NULL UNIQUE REFERENCES category(id) ON DELETE CASCADE,
    monthly_limit NUMERIC(20,2) NOT NULL CHECK (monthly_limit >= 0),
    created_at    TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    updated_at    TIMESTAMPTZ   NOT NULL DEFAULT NOW()
);

-- category_id is globally unique (a category belongs to one member), so a single
-- envelope per category is guaranteed. member_id is indexed for the list query.
CREATE INDEX idx_budget_member ON budget(member_id);

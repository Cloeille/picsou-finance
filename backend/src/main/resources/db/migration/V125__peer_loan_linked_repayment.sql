-- A repayment can now be linked to an existing incoming transaction instead of
-- creating its own cash row. `cash_leg_owned` distinguishes the two: true when this
-- service wrote the row (and may update or delete it), false when it only linked a
-- member's or provider's row (never touched, or the bank would see a double).
ALTER TABLE peer_loan_repayment
    ADD COLUMN cash_leg_owned BOOLEAN NOT NULL DEFAULT TRUE;

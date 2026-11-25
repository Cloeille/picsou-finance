-- A peer loan needs a cash counterpart for the asset swap to hold: the money lent
-- left a cash account, and each repayment enters one. Two nullable links record the
-- rows we own. A row we created (manual account) is updated or deleted with the loan;
-- a provider row linked from an existing transaction is never touched.
ALTER TABLE peer_loan_terms
    ADD COLUMN lending_transaction_id BIGINT REFERENCES transaction(id) ON DELETE SET NULL;

ALTER TABLE peer_loan_repayment
    ADD COLUMN cash_transaction_id BIGINT REFERENCES transaction(id) ON DELETE SET NULL;

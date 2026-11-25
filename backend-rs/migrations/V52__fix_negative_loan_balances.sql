-- V52__fix_negative_loan_balances.sql
-- Finary API sync stored loan balances negative (fixed in code at the same
-- commit). Repo convention: LOAN balances are stored positive and negated at
-- aggregation. Flip corrupted rows and their snapshots.
UPDATE account
SET current_balance = -current_balance
WHERE type = 'LOAN' AND current_balance < 0;

UPDATE balance_snapshot bs
SET balance = -bs.balance
FROM account a
WHERE bs.account_id = a.id AND a.type = 'LOAN' AND bs.balance < 0;

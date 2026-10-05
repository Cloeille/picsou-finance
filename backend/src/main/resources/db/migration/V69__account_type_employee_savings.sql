-- French épargne salariale (PEE/PEG, PERCO, PER Collectif) is neither a
-- brokerage account nor a passbook: the money is locked, employer-funded and
-- held as FCPE units, so it gets its own type rather than being folded into
-- SAVINGS or COMPTE_TITRES.
--
-- Kept alone in its own migration on purpose: PostgreSQL refuses to use a new
-- enum value in the transaction that added it, so nothing here may reference
-- 'EMPLOYEE_SAVINGS'. V70 creates the Amundi table separately.
ALTER TYPE account_type ADD VALUE 'EMPLOYEE_SAVINGS' BEFORE 'OTHER';

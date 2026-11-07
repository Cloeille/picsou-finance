-- A peer loan is money lent to a person: a receivable, not a liability. It gets its
-- own account type because the existing LOAN type is the opposite direction (a debt
-- the member owes, negated in signedLiveBalanceEur) and its Debt row is read as a
-- bank contract by the dashboard, the GDPR exporter and the xlsx schedule.
--
-- Kept alone in its own migration, like V69, V79, V82, V100 and V112: PostgreSQL
-- refuses to use a new enum value in the transaction that added it.
ALTER TYPE account_type ADD VALUE 'PEER_LOAN' BEFORE 'OTHER';

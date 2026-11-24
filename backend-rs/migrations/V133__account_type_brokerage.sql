-- A broker account outside the French wrappers (US Roth IRA, HSA, 401(k), taxable brokerage...)
-- gets its own account type. It is not COMPTE_TITRES or PEA: those carry French rules, and the
-- aggregator-synced accounts that need this one have none of them.
--
-- BROKERAGE holds positions, so AccountType.isInvestment() accepts it.
--
-- Kept alone in its own migration on purpose, like V69, V82, V112 and V123: PostgreSQL refuses to
-- use a new enum value in the transaction that added it, so nothing here may reference the
-- label. Appended just before 'OTHER', which keeps that catch-all last.
ALTER TYPE account_type ADD VALUE 'BROKERAGE' BEFORE 'OTHER';

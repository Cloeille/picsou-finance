-- The French regulated passbooks each have their own ceiling, rate and tax regime,
-- and a household typically holds several at once. Folding them all into SAVINGS
-- ("Livret d'épargne") made them indistinguishable on the Accounts page and in the
-- savings filter, which is the one place the difference matters — LEP already had
-- its own value for exactly that reason.
--
-- Kept alone in its own migration on purpose, like V69: PostgreSQL refuses to use a
-- new enum value in the transaction that added it, so nothing here may reference the
-- labels below. Each is appended just before 'OTHER', which keeps that catch-all last.
ALTER TYPE account_type ADD VALUE 'LIVRET_A' BEFORE 'OTHER';
ALTER TYPE account_type ADD VALUE 'LDDS' BEFORE 'OTHER';
ALTER TYPE account_type ADD VALUE 'LIVRET_JEUNE' BEFORE 'OTHER';
ALTER TYPE account_type ADD VALUE 'PEL' BEFORE 'OTHER';
ALTER TYPE account_type ADD VALUE 'CEL' BEFORE 'OTHER';

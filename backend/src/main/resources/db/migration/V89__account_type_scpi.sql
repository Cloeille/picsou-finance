-- A SCPI share is not a house, a brokerage line, or a passbook. It gets its own
-- account type so the open-data property estimator never tries to price it.
--
-- Kept alone on purpose: PostgreSQL refuses to use a new enum value in the
-- transaction that added it. V90 creates scpi_position separately.
ALTER TYPE account_type ADD VALUE 'SCPI' BEFORE 'LOAN';

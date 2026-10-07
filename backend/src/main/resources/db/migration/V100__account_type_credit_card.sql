-- American Express France is a credit card, not a checking account or a
-- savings product. Adding its own type so the frontend can display credit
-- card-specific information (statement balance, payment due date, rewards).
--
-- Kept alone in its own migration on purpose, like V69 and V79: PostgreSQL
-- refuses to use a new enum value in the transaction that added it.
ALTER TYPE account_type ADD VALUE 'CREDIT_CARD' BEFORE 'OTHER';

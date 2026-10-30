ALTER TABLE account
    ADD COLUMN payment_due_amount NUMERIC(20, 8),
    ADD COLUMN payment_due_date DATE,
    ADD COLUMN reward_points BIGINT;

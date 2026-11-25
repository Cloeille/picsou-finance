-- Loan: monthly insurance and one-off file fees
ALTER TABLE debt
    ADD COLUMN insurance_monthly NUMERIC(20, 8),
    ADD COLUMN file_fees         NUMERIC(20, 8);

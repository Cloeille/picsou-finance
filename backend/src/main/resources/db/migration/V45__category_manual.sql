-- Add a manual-override flag to transactions.
-- When true, the automated categorization pipeline (rules, brand KB, AI) will not
-- overwrite the category chosen by the user.
ALTER TABLE transaction
    ADD COLUMN category_manual BOOLEAN NOT NULL DEFAULT false;

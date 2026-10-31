-- V58 and not V57: main gained V57__ibkr_connection while this branch was in review,
-- and that one already shipped in a published image -- renumbering it would break the
-- Flyway checksum of every install that pulled it. The whole Bourse Direct chain shifts
-- up one instead; none of it had ever been published.
ALTER TABLE account
    ADD COLUMN cash_balance NUMERIC(20, 8);

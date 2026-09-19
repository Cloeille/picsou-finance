-- What a fund costs and how it behaves, as distinct from what it holds.
--
-- justETF publishes these on the same page as the breakdown, so they arrive in the same request
-- the composition already makes. Stored now rather than when the fee scanner is built: the data
-- is free at fetch time and re-scraping every fund later to backfill would not be.
ALTER TABLE security_profile
    ADD COLUMN ter_percent         NUMERIC(6, 3),
    ADD COLUMN distribution_policy VARCHAR(16),
    ADD COLUMN replication         VARCHAR(16),
    ADD COLUMN domicile_country    VARCHAR(2);

ALTER TABLE security_profile
    ADD CONSTRAINT ck_security_profile_distribution_policy
        CHECK (distribution_policy IS NULL
               OR distribution_policy IN ('ACCUMULATING', 'DISTRIBUTING')),
    ADD CONSTRAINT ck_security_profile_replication
        CHECK (replication IS NULL OR replication IN ('PHYSICAL', 'SYNTHETIC')),
    -- The fund's own domicile, which is a legal and tax fact about the wrapper. Deliberately
    -- separate from the geographic breakdown, which is about the underlying holdings: an Irish
    -- MSCI World is not 100 % Ireland.
    ADD CONSTRAINT ck_security_profile_domicile
        CHECK (domicile_country IS NULL OR domicile_country ~ '^[A-Z]{2}$');

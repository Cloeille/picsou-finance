-- Number of bathrooms.
--
-- A separate migration rather than an edit to V66: V66 has already been applied to running
-- instances, and Flyway validates the checksum of every applied migration.
--
-- DVF records no bathroom count, so this cannot be calibrated against the open data -- it
-- feeds a declared heuristic in PropertyAdjustments, alongside floor, lift and outdoor space.

ALTER TABLE real_estate_metadata
    ADD COLUMN bathrooms SMALLINT;

ALTER TABLE real_estate_metadata
    ADD CONSTRAINT ck_real_estate_metadata_bathrooms
        CHECK (bathrooms IS NULL OR (bathrooms >= 0 AND bathrooms <= 100));

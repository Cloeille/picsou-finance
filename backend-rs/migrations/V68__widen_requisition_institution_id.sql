-- The institution id now carries a third segment (the PSU type), e.g.
-- "Swan::FR::business" instead of "Swan::FR". That leaves only 86 characters for
-- the bank name at VARCHAR(100), and an overflow would surface as a Postgres
-- 22001 *after* Enable Banking has already minted an authorization -- the
-- requisition is saved once the connector call has returned.
ALTER TABLE requisition ALTER COLUMN institution_id TYPE VARCHAR(255);

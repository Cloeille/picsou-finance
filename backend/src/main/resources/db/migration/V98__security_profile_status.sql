-- Tell a failed lookup apart from a security nothing knows about.
--
-- Until now refresh() stamped refreshed_at before it knew whether any provider had answered, and
-- wiped the slices on the way through. A single transient 502 therefore destroyed a good
-- look-through AND reset the 30-day clock, so the fund was neither classified nor retried for a
-- month -- while source/as_of kept advertising the last successful scrape. The breakdown then
-- told the user to classify it by hand, which is the one action that would permanently mask
-- whatever the provider later published.
ALTER TABLE security_profile
    ADD COLUMN status          VARCHAR(16) NOT NULL DEFAULT 'OK',
    ADD COLUMN last_error      VARCHAR(200),
    ADD COLUMN last_attempt_at TIMESTAMPTZ;

ALTER TABLE security_profile
    ADD CONSTRAINT ck_security_profile_status
        CHECK (status IN ('NEVER_FETCHED', 'OK', 'NO_DATA', 'FAILED'));

-- Existing rows: anything carrying data was a success; anything empty was either never resolved
-- or was emptied by the bug above -- indistinguishable now, and treated as the safer of the two.
UPDATE security_profile p SET status = CASE
    WHEN p.sector_key IS NOT NULL
      OR p.country_key IS NOT NULL
      OR EXISTS (SELECT 1 FROM security_composition_slice s WHERE s.profile_id = p.id)
    THEN 'OK'
    ELSE 'NO_DATA'
END;

UPDATE security_profile SET last_attempt_at = refreshed_at WHERE refreshed_at IS NOT NULL;

-- One-time repair of what the old refresh() destroyed. An emptied profile is locked out of retry
-- for 30 days by a refreshed_at it never earned; clearing it puts those rows back in the queue.
UPDATE security_profile
   SET refreshed_at = NULL, last_attempt_at = NULL, status = 'NEVER_FETCHED'
 WHERE status = 'NO_DATA';

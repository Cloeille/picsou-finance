-- Goals gain a type. Until now one shape existed — a target amount by a deadline — which cannot
-- express "300 EUR into the PEA every month", a recurrence with no end and no target. The
-- projection curve is built from those, so they had to become first-class rather than a savings
-- goal with the fields left meaningless.
--
-- VARCHAR + a named CHECK rather than CREATE TYPE, like V83 and V84: only the three enums
-- predating V21 are native.

ALTER TABLE goal
    ADD COLUMN type            VARCHAR(24)   NOT NULL DEFAULT 'SAVINGS_TARGET',
    ADD COLUMN monthly_amount  NUMERIC(20, 2),
    ADD COLUMN expected_return NUMERIC(6, 3),
    ADD COLUMN start_date      DATE,
    ADD COLUMN end_date        DATE;

-- Both were NOT NULL because every goal was a savings target. A recurring investment has
-- neither, so the columns become nullable and per-type integrity moves into a CHECK — dropping
-- the NOT NULLs alone would let a savings target be created with no target at all.
ALTER TABLE goal ALTER COLUMN target_amount DROP NOT NULL;
ALTER TABLE goal ALTER COLUMN deadline      DROP NOT NULL;

ALTER TABLE goal ADD CONSTRAINT ck_goal_type
    CHECK (type IN ('SAVINGS_TARGET', 'RECURRING_INVESTMENT'));

ALTER TABLE goal ADD CONSTRAINT ck_goal_type_fields CHECK (
       (type = 'SAVINGS_TARGET'       AND target_amount IS NOT NULL AND deadline IS NOT NULL)
    OR (type = 'RECURRING_INVESTMENT' AND monthly_amount IS NOT NULL)
);

ALTER TABLE goal ADD CONSTRAINT ck_goal_recurring_dates
    CHECK (start_date IS NULL OR end_date IS NULL OR end_date > start_date);

-- ---------------------------------------------------------------------------
-- Dropping chk_goal_deadline fixes a bug that predates this feature entirely.
--
-- V2 shipped CHECK (deadline > CURRENT_DATE). CURRENT_DATE is not immutable, so PostgreSQL
-- re-evaluates that constraint on every UPDATE of the row — meaning any save() on a goal whose
-- deadline has passed fails at the database. That silently broke GoalService.update,
-- extendHistory, extendHistoryByMonth, setMonthOverride and setManualContribution for every
-- expired goal: the one moment a user is most likely to revisit a goal is when it has come due.
--
-- The rule itself is not lost. @Future on GoalRequest.deadline enforces it where it means what
-- the user meant — at creation — instead of forbidding all later edits.
-- ---------------------------------------------------------------------------
ALTER TABLE goal DROP CONSTRAINT chk_goal_deadline;

-- Allows backfilling goal history earlier than the goal's creation date.
-- When set (format "YYYY-MM"), the monthly calendar starts from this month
-- instead of the goal's createdAt. NULL keeps the default (createdAt-derived) start.
ALTER TABLE goal ADD COLUMN history_start_month VARCHAR(7);

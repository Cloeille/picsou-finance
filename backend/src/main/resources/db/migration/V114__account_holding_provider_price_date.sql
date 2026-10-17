-- Preserve the bank's fund NAV date separately from the sync timestamp.
ALTER TABLE account_holding
    ADD COLUMN provider_price_date DATE;
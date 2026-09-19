-- Trade Republic holdings synced before provider_value_eur was written carry NULL,
-- so AccountService.liveBalanceEur has nothing to fall back on and keeps dropping
-- the positions Yahoo cannot price (GH issue #76) until the next WebSocket sync.
--
-- Recover an EUR position snapshot only when the account proves current_price is
-- both EUR-denominated and complete: the aggregate must reconcile with the broker
-- total TradeRepublicAdapter stored in current_balance. Reproduce the adapter's
-- per-position cent rounding exactly; a broad account-level tolerance could otherwise
-- mistake real cash or an incomplete portfolio for rounding noise.
--
-- Accounts that do not reconcile are deliberately left untouched -- notably PEA
-- accounts, whose current_balance also includes a scoped cash amount Picsou never
-- persists. Those self-heal on the next sync.
WITH reconcilable_legacy_accounts AS (
    SELECT a.id
    FROM account a
    JOIN account_holding h ON h.account_id = a.id
    WHERE a.provider = 'Trade Republic'
      AND a.current_balance IS NOT NULL
    GROUP BY a.id, a.current_balance
    HAVING COUNT(*) > 0
       AND BOOL_AND(h.provider_value_eur IS NULL)
       AND BOOL_AND(h.quote_currency IS NULL)
       AND BOOL_AND(h.current_price IS NOT NULL)
       AND BOOL_AND(h.current_price > 0)
       AND SUM(ROUND(h.quantity * h.current_price, 2)) = a.current_balance
)
UPDATE account_holding h
SET provider_value_eur = ROUND(h.quantity * h.current_price, 2),
    quote_currency = 'EUR'
FROM reconcilable_legacy_accounts account
WHERE h.account_id = account.id;

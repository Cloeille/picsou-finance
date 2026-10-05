-- Early Bourse Direct builds stored only quantity and current_price. Recover an
-- EUR position snapshot only when the complete account proves those prices are
-- EUR-denominated: their aggregate must reconcile with broker total minus cash.
-- Accounts that do not reconcile are deliberately left untouched.
WITH reconcilable_legacy_accounts AS (
    SELECT a.id
    FROM account a
    JOIN account_holding h ON h.account_id = a.id
    WHERE a.provider = 'Bourse Direct'
      AND a.current_balance IS NOT NULL
      AND a.cash_balance IS NOT NULL
    GROUP BY a.id, a.current_balance, a.cash_balance
    HAVING COUNT(*) > 0
       AND BOOL_AND(h.provider_value_eur IS NULL)
       AND BOOL_AND(h.quote_currency IS NULL)
       AND BOOL_AND(h.current_price IS NOT NULL)
       AND ABS(
           SUM(h.quantity * h.current_price)
           - (a.current_balance - a.cash_balance)
       ) <= 0.05
)
UPDATE account_holding h
SET provider_value_eur = h.quantity * h.current_price,
    provider_pnl_eur = CASE
        WHEN h.average_buy_in IS NOT NULL
            THEN h.quantity * (h.current_price - h.average_buy_in)
        ELSE NULL
    END,
    quote_currency = 'EUR'
FROM reconcilable_legacy_accounts account
WHERE h.account_id = account.id;

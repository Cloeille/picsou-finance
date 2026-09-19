-- Backfill manually-entered Trade Republic on-platform crypto transactions.
--
-- Before PR #25, entering a TR-internal crypto ISIN (e.g. XF000BTC0017) as a manual
-- transaction stored that fake ISIN verbatim as the ticker, because OpenFIGI cannot
-- resolve it. Since PR #25, OpenFigiIsinConverter.resolve() maps it to the real symbol
-- (XF000BTC0017 -> BTC). HoldingComputeService groups holdings by exact ticker, so a
-- pre-PR row (ticker "XF000BTC0017") would no longer aggregate with a new one
-- (ticker "BTC"): the position splits into a stale, unpriceable "XF000..." holding and
-- a separate "BTC" one. This one-time backfill rewrites the historical rows so they
-- merge again. The description gets the same substitution so it stops showing the fake
-- ISIN; the derived account_holding rows self-heal on the next recompute (which runs on
-- the next transaction change or sync for the account).
--
-- Restricted to the crypto symbols the app knew at this release (mirrors
-- CoinGeckoPriceProvider.TICKER_TO_ID): resolve() only short-circuits those, so rewriting
-- an unrecognized symbol would instead CREATE a split (old row -> symbol, new row still
-- the fake ISIN). This is a point-in-time backfill and never re-runs, so the frozen list
-- is correct — later-added coins had no pre-PR resolution path to reconcile.

-- upper(ticker) throughout: pre-PR resolve() did not normalize case, so a lowercase
-- manual entry ("xf000btc0017") was stored lowercase. New rows are uppercase ("BTC"),
-- so the backfill must uppercase to match both the known-symbol list and the new rows.
UPDATE transaction
SET
    description = replace(description, ticker, substring(upper(ticker) from '^XF000([A-Z]+)[0-9]+$')),
    ticker      = substring(upper(ticker) from '^XF000([A-Z]+)[0-9]+$')
WHERE upper(ticker) ~ '^XF000[A-Z]+[0-9]+$'
  AND substring(upper(ticker) from '^XF000([A-Z]+)[0-9]+$') IN (
      'BTC', 'ETH', 'SOL', 'BNB', 'ADA', 'XRP', 'DOGE', 'DOT', 'MATIC', 'AVAX',
      'LINK', 'UNI', 'ATOM', 'LTC', 'NEAR', 'ARB', 'OP', 'SHIB', 'PEPE', 'SUI'
  );

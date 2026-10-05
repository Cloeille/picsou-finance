-- V75: Let an account pick which bundled logo it shows.
--
-- Until now a bundled logo was derived from account.provider alone (frontend
-- PROVIDER_LOGOS map). On-chain wallets can't work that way: WalletSyncService writes the
-- native symbol into provider ("BTC", "SOL", "ETH"...), so there is no stable string to key
-- on, and the same wallet may be a plain address or a Ledger device -- a distinction only
-- the user knows. logo_key stores that choice; the frontend owns the key -> asset mapping
-- and falls back to the provider map, then the account color, for an unknown or NULL key.
ALTER TABLE account ADD COLUMN logo_key VARCHAR(32);

-- Wallets that already exist get the default WalletSyncService now writes at creation.
-- Joined through wallet_address rather than matched on 'wallet\_%': external_account_id is
-- free text on manual accounts, and one that merely starts with "wallet_" is not a wallet.
-- Guarded on IS NULL so a replay (a restored dump, a repaired history) cannot reset a user
-- who has since picked the Ledger mark back to the generic one.
UPDATE account a
   SET logo_key = 'blockchain'
  FROM wallet_address w
 WHERE a.external_account_id = 'wallet_' || lower(w.chain) || '_' || w.id
   AND a.logo_key IS NULL;

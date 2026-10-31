-- V54 converted ETHEREUM wallets to EVM and re-pointed their accounts, but left
-- account.name alone. An unlabelled wallet's account was named from the chain
-- ("ETHEREUM Wallet", see WalletSyncService.resolveAccount), and resolveAccount only
-- refreshes balance/lastSyncedAt/ticker for an account it finds -- never the name. So a
-- migrated wallet would display the retired chain name forever, even though it now also
-- tracks BNB, POL and AVAX.
--
-- Key on wallet_address.label IS NULL, NOT on the display name alone: resolveAccount uses
-- a user's label verbatim, so someone who typed "ETHEREUM Wallet" as their own label owns
-- a row indistinguishable by name from an auto-named one. Renaming that would silently
-- destroy a user's chosen label, and resolveAccount never rewrites an existing account's
-- name, so it could never be restored except by hand. Joining through the wallet lets us
-- rename only the genuinely auto-named rows.
UPDATE account a
   SET name = 'EVM Wallet'
  FROM wallet_address w
 WHERE a.external_account_id = 'wallet_evm_' || w.id
   AND w.chain = 'EVM'
   AND w.label IS NULL
   AND a.name = 'ETHEREUM Wallet';

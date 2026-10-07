-- The former single-chain ETHEREUM wallet is replaced by the EVM fan-out
-- (one 0x address tracked across every enabled EVM network). Convert existing
-- rows in place so tracked addresses keep working with no user action.
UPDATE wallet_address SET chain = 'EVM' WHERE chain = 'ETHEREUM';

-- The synced crypto account is keyed by external_account_id = 'wallet_<chain>_<id>'
-- (see WalletSyncService). Rewrite the chain segment so the account -- and its
-- balance snapshots and holdings -- stay attached to the migrated wallet instead
-- of being orphaned and re-created on the next sync.
UPDATE account
   SET external_account_id = 'wallet_evm_' || split_part(external_account_id, '_', 3)
 WHERE external_account_id LIKE 'wallet_ethereum_%';

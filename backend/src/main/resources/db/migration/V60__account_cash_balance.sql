-- V60 and not V58: merging main into 1.1.0 collided with V57__wallet_evm_account_name
-- and V58__persistent_session_previous_token, already published under the `1.1.0` image
-- tag -- renumbering those would break the Flyway checksum of every install tracking it.
-- The whole IBKR + Bourse Direct chain (previously V57-V63 on main) shifts up two instead.
ALTER TABLE account
    ADD COLUMN cash_balance NUMERIC(20, 8);

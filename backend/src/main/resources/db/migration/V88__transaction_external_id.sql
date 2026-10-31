-- V88: Dedup key for transactions imported from a bank connector (GH issue #71).
--
-- Enable Banking sync stored accounts and balances but never transactions. Importing
-- them needs a way to recognise an entry already stored, because every sync re-reads
-- an overlapping window: the bank's own `entry_reference` when it sends one, otherwise
-- a fingerprint of (date, amount, description) computed by the importer.
--
-- Deliberately NOT unique. A few ASPSPs reuse an entry reference across entries, and a
-- genuine same-day duplicate (two identical card payments at the same merchant) shares
-- its fingerprint with the first one. A unique index would abort the whole sync
-- transaction on such a row and flip the requisition to FAILED; BankTransactionImportService
-- dedups by reading the existing keys of the window first, so the index only has to make
-- that read fast.
--
-- Null for manual rows and for Finary, which replaces its non-manual rows wholesale.

ALTER TABLE transaction ADD COLUMN external_transaction_id VARCHAR(128);

CREATE INDEX idx_transaction_account_external_id
    ON transaction(account_id, external_transaction_id);

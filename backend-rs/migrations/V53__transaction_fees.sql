-- Adds a per-transaction fees column for BUY/SELL rows (PEA/CTO/CRYPTO).
-- Nullable: existing rows have no recorded fee and are treated as zero downstream
-- (HoldingComputeService, TransactionAmountCalculator). Fees fold into the PMP
-- (average buy-in) cost basis on BUY transactions, matching the French PEA tax
-- convention of including acquisition costs in the cost basis.
ALTER TABLE transaction ADD COLUMN fees NUMERIC(20, 8) NULL;

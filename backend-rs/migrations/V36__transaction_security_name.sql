-- Human-readable security name for a BUY/SELL transaction, distinct from the
-- row description. Auto-filled from OpenFIGI when an ISIN is entered, or from the
-- user-supplied "Nom". HoldingComputeService labels each position with the most
-- recent transaction that carries a name (see V24 for the other instrument columns).
ALTER TABLE transaction
  ADD COLUMN name VARCHAR(100) NULL;

-- V132: the institution an aggregator account really belongs to.
--
-- SimpleFIN is a bridge, not a bank: its accounts all carry provider = 'SimpleFIN', so Chase and
-- Fidelity looked identical. provider must stay 'SimpleFIN' (Sync All and deletion find the
-- connection through it), so the real institution gets its own columns.
-- institution_domain is the bank's website host, used only to fetch an opt-in logo.

ALTER TABLE account ADD COLUMN institution_name   VARCHAR(100);
ALTER TABLE account ADD COLUMN institution_domain VARCHAR(255);

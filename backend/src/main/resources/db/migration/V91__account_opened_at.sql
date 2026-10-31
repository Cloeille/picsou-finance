-- When the wrapper was opened, as opposed to when Picsou first heard about it.
--
-- A PEA's tax treatment is a function of its age and nothing else: a withdrawal before five
-- years closes the plan and forfeits the exemption, after five the gains escape income tax. The
-- account's `created_at` answers a different question -- when the row was written -- and for a
-- plan opened in 2014 and typed in last month the two are a decade apart.
--
-- On `account` rather than a PEA-specific table, and deliberately not named `pea_opened_at`: an
-- assurance-vie has the same shape of rule at eight years, a PER at retirement, a PEA-PME the
-- same five. Nothing here is PEA-specific but the form that currently offers it.
--
-- Nullable and unbackfilled: no existing row can know its own opening date, and inventing one
-- from `created_at` would put a fabricated fiscal anniversary in an export.

ALTER TABLE account
    ADD COLUMN opened_at DATE;

ALTER TABLE account
    ADD CONSTRAINT ck_account_opened_at CHECK (opened_at IS NULL OR opened_at > DATE '1900-01-01');

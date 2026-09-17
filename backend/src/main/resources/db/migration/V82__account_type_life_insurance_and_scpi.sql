-- Assurance-vie and SCPI each get their own account type because the investment
-- pyramid names them and no existing type carries them: an AV entered as OTHER or
-- SAVINGS would be scored as an alternative asset or as emergency cash, and SCPI
-- shares are real estate held through a fund, not a brokerage line.
--
-- ASSURANCE_VIE holds positions (unités de compte), so AccountType.isInvestment()
-- accepts it and the euro fund lives in account.cash_balance, exactly like the cash
-- sitting inside a PEA/CTO envelope. SCPI does not: its shares have no Yahoo ticker,
-- so valuing it per line would drop it into the "no price" branch and report zero.
-- It stays a typed balance, like REAL_ESTATE.
--
-- Kept alone in its own migration on purpose, like V69 and V79: PostgreSQL refuses to
-- use a new enum value in the transaction that added it, so nothing here may reference
-- the labels below. Each is appended just before 'OTHER', which keeps that catch-all last.
ALTER TYPE account_type ADD VALUE 'ASSURANCE_VIE' BEFORE 'OTHER';
ALTER TYPE account_type ADD VALUE 'SCPI' BEFORE 'OTHER';

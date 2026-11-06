-- A transaction that moves money inside an investment wrapper rather than in or out
-- of the member's budget: a coupon reinvested in a life-insurance contract, its
-- management fees, a switch between funds, a profit participation.
--
-- Such a row belongs on its account's ledger only. Every budget, cashflow,
-- categorisation and global-transactions query filters it out
-- (TransactionRepository); the account page and the account export keep it.
-- FALSE for every existing row, so nothing already counted changes.
ALTER TABLE transaction
    ADD COLUMN budget_neutral BOOLEAN NOT NULL DEFAULT FALSE;

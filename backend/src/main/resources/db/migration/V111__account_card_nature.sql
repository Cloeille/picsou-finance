-- How a bank card is debited (set by the Caisse d'Epargne sync; NULL for every other account).
ALTER TABLE account ADD COLUMN card_nature VARCHAR(20);

ALTER TABLE account
    ADD CONSTRAINT ck_account_card_nature
        CHECK (card_nature IS NULL OR card_nature IN ('IMMEDIATE_DEBIT', 'DEFERRED_DEBIT', 'CREDIT'));

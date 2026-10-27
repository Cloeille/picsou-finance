-- Extra structure for TransactionRepositoryAccountFetchTest, run after
-- transaction-repository-test-schema.sql.
--
-- The queries under test JOIN FETCH t.account, so Hibernate selects every column the Account
-- entity maps: the seed `account` table gets the remaining ones here. Categories are needed for
-- the kind and category filters, with every mapped column since searchByMember fetches them too.

ALTER TABLE account ADD cash_balance DECIMAL(20, 8);
ALTER TABLE account ADD color VARCHAR(7);
ALTER TABLE account ADD created_at TIMESTAMP;
ALTER TABLE account ADD currency VARCHAR(10);
ALTER TABLE account ADD current_balance DECIMAL(20, 8);
ALTER TABLE account ADD external_account_id VARCHAR(100);
ALTER TABLE account ADD iban VARCHAR(34);
ALTER TABLE account ADD last_synced_at TIMESTAMP;
ALTER TABLE account ADD logo_key VARCHAR(32);
ALTER TABLE account ADD logo_url VARCHAR(255);
ALTER TABLE account ADD name VARCHAR(100);
ALTER TABLE account ADD opened_at DATE;
ALTER TABLE account ADD parent_account_id BIGINT;
ALTER TABLE account ADD payment_due_amount DECIMAL(20, 8);
ALTER TABLE account ADD payment_due_date DATE;
ALTER TABLE account ADD provider VARCHAR(100);
ALTER TABLE account ADD requisition_id BIGINT;
ALTER TABLE account ADD reward_points BIGINT;
ALTER TABLE account ADD ticker VARCHAR(20);
ALTER TABLE account ADD type VARCHAR(100);
ALTER TABLE account ADD updated_at TIMESTAMP;

DROP TABLE IF EXISTS category;
CREATE TABLE category (
    id         BIGINT PRIMARY KEY,
    member_id  BIGINT       NOT NULL,
    parent_id  BIGINT,
    name       VARCHAR(100) NOT NULL DEFAULT 'category',
    slug       VARCHAR(60),
    kind       VARCHAR(20)  NOT NULL,
    color      VARCHAR(7)   NOT NULL DEFAULT '#6366f1',
    icon       VARCHAR(50),
    is_default BOOLEAN      NOT NULL DEFAULT FALSE,
    archived   BOOLEAN      NOT NULL DEFAULT FALSE,
    sort_order INTEGER      NOT NULL DEFAULT 0,
    created_at TIMESTAMP,
    updated_at TIMESTAMP
);
INSERT INTO category (id, member_id, kind) VALUES (10, 1, 'TRANSFER');
INSERT INTO category (id, member_id, kind) VALUES (11, 1, 'EXPENSE');

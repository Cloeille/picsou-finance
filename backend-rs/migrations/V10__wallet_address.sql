-- V8: On-chain wallet addresses (Solana, Ethereum, Bitcoin)

CREATE TABLE wallet_address (
    id              BIGSERIAL PRIMARY KEY,
    chain           VARCHAR(20)  NOT NULL,
    address         VARCHAR(200) NOT NULL,
    label           VARCHAR(100),
    last_synced_at  TIMESTAMPTZ,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

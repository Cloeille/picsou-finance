-- Crypto registry: which tickers are crypto, and which CoinGecko coin prices each of them.
--
-- Global reference data, not member-scoped (same as price_snapshot): a ticker maps to one coin
-- for everybody. It is also the router -- PriceService sends a ticker the registry knows to
-- CoinGecko and everything else to Yahoo Finance, with no notion of which account it came from.
--
-- CURATED rows are the seed below. AUTO rows are learned at runtime for a ticker held in a
-- crypto account, only when Yahoo does not quote the symbol and CoinGecko has one clearly
-- dominant ranked coin under it (see CompositePriceProvider.learnCryptoTickers).
--
-- Seed rules: resolve each id against /api/v3/coins/list and pick the canonical entry, never a
-- binance-peg-*, *-wormhole or bridged-* homonym -- a wrong id is a wrong valuation, and
-- nothing surfaces it.
--
-- Deliberately NOT seeded, despite Meria offering them: STX (Nasdaq: Seagate), SNX (NYSE: TD
-- SYNNEX), SEI (NYSE: Solaris Energy Infrastructure) and APT (NYSE American: Alpha Pro Tech)
-- are live equity symbols, so a seeded row would reroute those stock holdings to CoinGecko.
-- ONE is left out because six CoinGecko coins share that symbol and none dominates.
CREATE TABLE crypto_asset (
    ticker        VARCHAR(30)  PRIMARY KEY,
    coingecko_id  VARCHAR(100) NOT NULL,
    name          VARCHAR(255) NOT NULL,
    source        VARCHAR(16)  NOT NULL,
    resolved_at   TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    CONSTRAINT ck_crypto_asset_source CHECK (source IN ('CURATED', 'AUTO')),
    CONSTRAINT ck_crypto_asset_ticker_upper CHECK (ticker = UPPER(ticker))
);

INSERT INTO crypto_asset (ticker, coingecko_id, name, source) VALUES
    ('BTC', 'bitcoin', 'Bitcoin', 'CURATED'),
    ('ETH', 'ethereum', 'Ethereum', 'CURATED'),
    ('SOL', 'solana', 'Solana', 'CURATED'),
    ('BNB', 'binancecoin', 'BNB', 'CURATED'),
    ('ADA', 'cardano', 'Cardano', 'CURATED'),
    ('XRP', 'ripple', 'XRP', 'CURATED'),
    ('DOGE', 'dogecoin', 'Dogecoin', 'CURATED'),
    ('DOT', 'polkadot', 'Polkadot', 'CURATED'),
    ('MATIC', 'matic-network', 'MATIC (migrated to POL)', 'CURATED'),
    ('POL', 'polygon-ecosystem-token', 'POL (ex-MATIC)', 'CURATED'),
    ('AVAX', 'avalanche-2', 'Avalanche', 'CURATED'),
    ('USDT', 'tether', 'Tether', 'CURATED'),
    ('USDC', 'usd-coin', 'USDC', 'CURATED'),
    ('DAI', 'dai', 'Dai', 'CURATED'),
    ('EURC', 'euro-coin', 'EURC', 'CURATED'),
    ('LINK', 'chainlink', 'Chainlink', 'CURATED'),
    ('UNI', 'uniswap', 'Uniswap', 'CURATED'),
    ('ATOM', 'cosmos', 'Cosmos Hub', 'CURATED'),
    ('LTC', 'litecoin', 'Litecoin', 'CURATED'),
    ('NEAR', 'near', 'NEAR Protocol', 'CURATED'),
    ('ARB', 'arbitrum', 'Arbitrum', 'CURATED'),
    ('OP', 'optimism', 'Optimism', 'CURATED'),
    ('SHIB', 'shiba-inu', 'Shiba Inu', 'CURATED'),
    ('PEPE', 'pepe', 'Pepe', 'CURATED'),
    ('SUI', 'sui', 'Sui', 'CURATED'),
    ('EGLD', 'elrond-erd-2', 'MultiversX', 'CURATED'),
    ('XTZ', 'tezos', 'Tezos', 'CURATED'),
    ('ALGO', 'algorand', 'Algorand', 'CURATED'),
    ('KSM', 'kusama', 'Kusama', 'CURATED'),
    ('TIA', 'celestia', 'Celestia', 'CURATED'),
    ('INJ', 'injective-protocol', 'Injective', 'CURATED'),
    ('ROSE', 'oasis-network', 'Oasis', 'CURATED'),
    ('KAVA', 'kava', 'Kava', 'CURATED'),
    ('ZEC', 'zcash', 'Zcash', 'CURATED'),
    ('ETC', 'ethereum-classic', 'Ethereum Classic', 'CURATED'),
    ('XLM', 'stellar', 'Stellar', 'CURATED'),
    ('TRX', 'tron', 'TRON', 'CURATED'),
    ('BCH', 'bitcoin-cash', 'Bitcoin Cash', 'CURATED'),
    ('MINA', 'mina-protocol', 'Mina Protocol', 'CURATED'),
    ('OSMO', 'osmosis', 'Osmosis', 'CURATED'),
    ('AKT', 'akash-network', 'Akash Network', 'CURATED'),
    ('DYDX', 'dydx-chain', 'dYdX', 'CURATED'),
    ('CELO', 'celo', 'Celo', 'CURATED'),
    ('BAND', 'band-protocol', 'Band', 'CURATED'),
    ('XMR', 'monero', 'Monero', 'CURATED'),
    ('VET', 'vechain', 'VeChain', 'CURATED'),
    ('HBAR', 'hedera-hashgraph', 'Hedera', 'CURATED'),
    ('GRT', 'the-graph', 'The Graph', 'CURATED'),
    ('NEXO', 'nexo', 'NEXO', 'CURATED'),
    ('CRO', 'crypto-com-chain', 'Cronos', 'CURATED');

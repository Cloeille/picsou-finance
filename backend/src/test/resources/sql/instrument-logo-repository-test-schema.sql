-- Minimal H2 schema for InstrumentLogoRepositoryTest (@DataJpaTest).
--
-- Same reasoning as sql/transaction-repository-test-schema.sql: the real migrations cannot run
-- on H2 (docs/conventions/testing.md), so this stands up the two tables the logo queries read,
-- with the same columns and CHECK constraints as V107 and V14.

DROP TABLE IF EXISTS instrument_logo;
DROP TABLE IF EXISTS price_snapshot;

CREATE TABLE instrument_logo (
    id                BIGINT AUTO_INCREMENT PRIMARY KEY,
    ticker            VARCHAR(30)  NOT NULL,
    status            VARCHAR(16)  NOT NULL,
    image             VARBINARY(262144),
    content_type      VARCHAR(32),
    image_dark        VARBINARY(262144),
    content_type_dark VARCHAR(32),
    attempted_at      TIMESTAMP WITH TIME ZONE NOT NULL,
    fetched_at        TIMESTAMP WITH TIME ZONE,
    CONSTRAINT uk_instrument_logo_ticker UNIQUE (ticker),
    CONSTRAINT ck_instrument_logo_status CHECK (status IN ('STORED', 'ABSENT', 'FAILED')),
    CONSTRAINT ck_instrument_logo_stored_image CHECK (
        (status = 'STORED') = (image IS NOT NULL AND content_type IS NOT NULL AND fetched_at IS NOT NULL)
    ),
    CONSTRAINT ck_instrument_logo_dark_pair CHECK ((image_dark IS NULL) = (content_type_dark IS NULL))
);

CREATE TABLE price_snapshot (
    id         BIGINT AUTO_INCREMENT PRIMARY KEY,
    ticker     VARCHAR(30)    NOT NULL,
    date       DATE           NOT NULL,
    price_eur  DECIMAL(20, 8) NOT NULL,
    created_at TIMESTAMP      NOT NULL
);

-- Stored with a dark variant, stored without one, a permanent miss, a recent failure and an old
-- failure. 2026-10-01 is "now" in the test; the retry cut-off sits seven days before it.
INSERT INTO instrument_logo (ticker, status, image, content_type, image_dark, content_type_dark, attempted_at, fetched_at)
VALUES ('AAPL', 'STORED', X'89504E47', 'image/png', X'89504E47', 'image/png',
        TIMESTAMP WITH TIME ZONE '2026-09-01 10:00:00+00', TIMESTAMP WITH TIME ZONE '2026-09-01 10:00:00+00');
INSERT INTO instrument_logo (ticker, status, image, content_type, attempted_at, fetched_at)
VALUES ('MC.PA', 'STORED', X'89504E47', 'image/png',
        TIMESTAMP WITH TIME ZONE '2026-09-02 10:00:00+00', TIMESTAMP WITH TIME ZONE '2026-09-02 10:00:00+00');
INSERT INTO instrument_logo (ticker, status, attempted_at)
VALUES ('NOLOGO', 'ABSENT', TIMESTAMP WITH TIME ZONE '2025-01-01 10:00:00+00');
INSERT INTO instrument_logo (ticker, status, attempted_at)
VALUES ('RECENT', 'FAILED', TIMESTAMP WITH TIME ZONE '2026-09-30 10:00:00+00');
INSERT INTO instrument_logo (ticker, status, attempted_at)
VALUES ('STALE', 'FAILED', TIMESTAMP WITH TIME ZONE '2026-09-01 10:00:00+00');

INSERT INTO price_snapshot (ticker, date, price_eur, created_at) VALUES ('AAPL', DATE '2026-09-30', 200, TIMESTAMP '2026-09-30 10:00:00');
INSERT INTO price_snapshot (ticker, date, price_eur, created_at) VALUES ('AAPL', DATE '2026-10-01', 201, TIMESTAMP '2026-10-01 10:00:00');
INSERT INTO price_snapshot (ticker, date, price_eur, created_at) VALUES ('IWDA.AS', DATE '2026-10-01', 100, TIMESTAMP '2026-10-01 10:00:00');

-- Tighten real_estate_metadata.energy_class CHECK to enforce the 1-character limit that
-- PostgreSQL enforces via VARCHAR(1) alongside BETWEEN 'A' AND 'G'.
CREATE TABLE real_estate_metadata_new (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    account_id INTEGER NOT NULL,
    purchase_price TEXT NOT NULL,
    purchase_date TEXT,
    surface_area TEXT,
    address TEXT,
    property_type TEXT,
    rental_income TEXT DEFAULT '0',
    created_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%f000Z', 'now')),
    updated_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%f000Z', 'now')),
    member_id INTEGER NOT NULL,
    category TEXT,
    description TEXT,
    postal_code TEXT,
    city TEXT,
    country TEXT NOT NULL DEFAULT 'FR',
    insee_code TEXT,
    latitude TEXT,
    longitude TEXT,
    ban_id TEXT,
    parcel_id TEXT,
    geocode_score TEXT,
    geocoded_at TEXT,
    land_area TEXT,
    construction_year INTEGER,
    rooms INTEGER,
    bedrooms INTEGER,
    floor_number INTEGER,
    floors_total INTEGER,
    has_elevator INTEGER CHECK (has_elevator IS NULL OR has_elevator IN (0, 1)),
    garage_count INTEGER NOT NULL DEFAULT 0,
    parking_count INTEGER NOT NULL DEFAULT 0,
    has_garden INTEGER NOT NULL DEFAULT 0 CHECK (has_garden IN (0, 1)),
    has_terrace INTEGER NOT NULL DEFAULT 0 CHECK (has_terrace IN (0, 1)),
    has_balcony INTEGER NOT NULL DEFAULT 0 CHECK (has_balcony IN (0, 1)),
    energy_class TEXT,
    agency_fees TEXT,
    notary_fees TEXT,
    works_cost TEXT,
    valuation_mode TEXT NOT NULL DEFAULT 'ESTIMATED',
    bathrooms INTEGER,
    CONSTRAINT uk_real_estate_metadata_account UNIQUE (account_id),
    CONSTRAINT ck_real_estate_metadata_bathrooms CHECK ((bathrooms IS NULL) OR ((bathrooms >= 0) AND (bathrooms <= 100))),
    CONSTRAINT ck_real_estate_metadata_construction_year CHECK ((construction_year IS NULL) OR ((construction_year >= 1000) AND (construction_year <= 2200))),
    CONSTRAINT ck_real_estate_metadata_energy_class CHECK ((energy_class IS NULL) OR ((length(energy_class) = 1) AND (energy_class >= 'A') AND (energy_class <= 'G'))),
    CONSTRAINT ck_real_estate_metadata_valuation_mode CHECK (valuation_mode IN ('ESTIMATED', 'MANUAL')),
    CONSTRAINT real_estate_metadata_account_id_fkey FOREIGN KEY (account_id) REFERENCES account(id) ON DELETE CASCADE,
    CONSTRAINT real_estate_metadata_member_id_fkey FOREIGN KEY (member_id) REFERENCES family_member(id) ON DELETE CASCADE
);

INSERT INTO real_estate_metadata_new (
    id, account_id, purchase_price, purchase_date, surface_area, address, property_type,
    rental_income, created_at, updated_at, member_id, category, description, postal_code, city,
    country, insee_code, latitude, longitude, ban_id, parcel_id, geocode_score, geocoded_at,
    land_area, construction_year, rooms, bedrooms, floor_number, floors_total, has_elevator,
    garage_count, parking_count, has_garden, has_terrace, has_balcony, energy_class, agency_fees,
    notary_fees, works_cost, valuation_mode, bathrooms
)
SELECT
    id, account_id, purchase_price, purchase_date, surface_area, address, property_type,
    rental_income, created_at, updated_at, member_id, category, description, postal_code, city,
    country, insee_code, latitude, longitude, ban_id, parcel_id, geocode_score, geocoded_at,
    land_area, construction_year, rooms, bedrooms, floor_number, floors_total, has_elevator,
    garage_count, parking_count, has_garden, has_terrace, has_balcony, energy_class, agency_fees,
    notary_fees, works_cost, valuation_mode, bathrooms
FROM real_estate_metadata;

DROP TABLE real_estate_metadata;
ALTER TABLE real_estate_metadata_new RENAME TO real_estate_metadata;

CREATE INDEX idx_real_estate_metadata_insee ON real_estate_metadata (insee_code) WHERE insee_code IS NOT NULL;
CREATE INDEX idx_real_estate_metadata_member ON real_estate_metadata (member_id);

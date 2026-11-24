-- Key-value settings store used by the first-launch setup wizard and for
-- runtime-tunable configuration (CORS origins, cookie security flag, per-integration toggles).
--
-- Existing installs (where at least one AppUser already exists) are seeded
-- with 'COMPLETE' so their users go straight to /login. Fresh installs are
-- seeded 'PENDING_ADMIN' so they land on the wizard.

CREATE TABLE app_setting (
    setting_key VARCHAR(100)  PRIMARY KEY,
    value       TEXT          NOT NULL,
    created_at  TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    updated_at  TIMESTAMPTZ   NOT NULL DEFAULT NOW()
);

INSERT INTO app_setting (setting_key, value)
SELECT 'setup.state',
       CASE WHEN EXISTS (SELECT 1 FROM app_user) THEN 'COMPLETE' ELSE 'PENDING_ADMIN' END;

INSERT INTO app_setting (setting_key, value) VALUES
    ('integration.enablebanking.enabled', 'false'),
    ('integration.boursobank.enabled',    'false'),
    ('integration.traderepublic.enabled', 'false'),
    ('integration.finary.enabled',        'false'),
    ('integration.crypto.enabled',        'false');

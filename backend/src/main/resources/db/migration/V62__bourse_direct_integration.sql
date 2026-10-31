INSERT INTO app_setting (setting_key, value)
VALUES ('integration.boursedirect.enabled', 'false')
ON CONFLICT (setting_key) DO NOTHING;

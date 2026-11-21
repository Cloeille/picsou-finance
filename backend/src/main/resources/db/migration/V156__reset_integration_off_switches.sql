-- A stored false now stops syncing (#666). V25, V62 and V95 seeded false on every install and it
-- never had an effect, so clear it rather than stop those syncs on upgrade.
DELETE FROM app_setting WHERE setting_key LIKE 'integration.%.enabled' AND LOWER(value) <> 'true';

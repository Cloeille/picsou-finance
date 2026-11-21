-- The Admin integration switches start to mean something (#666): a stored false now
-- stops Picsou's own syncs for that integration. Until now no stored false had any
-- effect, and V25, V62 and V95 seeded false for seven integrations on every install,
-- so keeping those rows would silently stop syncing on upgrade. Remove every off
-- switch: a missing row reads as Enabled, and the admin turns off what they want off.
DELETE FROM app_setting
 WHERE setting_key LIKE 'integration.%.enabled'
   AND LOWER(value) <> 'true';

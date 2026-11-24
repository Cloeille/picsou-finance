-- 1. Create admin family_member from existing user
INSERT INTO family_member (display_name, avatar_color, is_managed)
SELECT COALESCE(username, 'Admin'), '#6366f1', FALSE
FROM app_user LIMIT 1;

-- 2. Link existing app_user to admin member with ADMIN role
UPDATE app_user
SET member_id = (SELECT id FROM family_member LIMIT 1),
    role = 'ADMIN',
    acknowledged_warning = TRUE;

-- 3. Make member_id UNIQUE on app_user
ALTER TABLE app_user ADD CONSTRAINT uq_app_user_member_id UNIQUE (member_id);

-- 4. Assign all existing data to admin member
UPDATE account                  SET member_id = (SELECT id FROM family_member LIMIT 1);
UPDATE goal                     SET member_id = (SELECT id FROM family_member LIMIT 1);
UPDATE requisition              SET member_id = (SELECT id FROM family_member LIMIT 1);
UPDATE trade_republic_session   SET member_id = (SELECT id FROM family_member LIMIT 1);
UPDATE crypto_exchange_session  SET member_id = (SELECT id FROM family_member LIMIT 1);
UPDATE finary_session           SET member_id = (SELECT id FROM family_member LIMIT 1);
UPDATE wallet_address           SET member_id = (SELECT id FROM family_member LIMIT 1);
UPDATE real_estate_metadata     SET member_id = (SELECT id FROM family_member LIMIT 1);
UPDATE debt                     SET member_id = (SELECT id FROM family_member LIMIT 1);

-- 5. Backfill goal_manual_contribution.member_id
UPDATE goal_manual_contribution
SET member_id = (SELECT id FROM family_member LIMIT 1)
WHERE member_id IS NULL;

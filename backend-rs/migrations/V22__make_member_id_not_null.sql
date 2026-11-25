-- Safe: V21 populated all rows with admin member_id

ALTER TABLE app_user ALTER COLUMN member_id SET NOT NULL;

ALTER TABLE account                  ALTER COLUMN member_id SET NOT NULL;
ALTER TABLE goal                     ALTER COLUMN member_id SET NOT NULL;
ALTER TABLE requisition              ALTER COLUMN member_id SET NOT NULL;
ALTER TABLE trade_republic_session   ALTER COLUMN member_id SET NOT NULL;
ALTER TABLE crypto_exchange_session  ALTER COLUMN member_id SET NOT NULL;
ALTER TABLE finary_session           ALTER COLUMN member_id SET NOT NULL;
ALTER TABLE wallet_address           ALTER COLUMN member_id SET NOT NULL;
ALTER TABLE real_estate_metadata     ALTER COLUMN member_id SET NOT NULL;
ALTER TABLE debt                     ALTER COLUMN member_id SET NOT NULL;

ALTER TABLE goal_manual_contribution ALTER COLUMN member_id SET NOT NULL;

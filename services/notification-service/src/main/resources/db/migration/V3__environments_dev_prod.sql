-- Environments are renamed: TEST -> DEV, LIVE -> PROD (a deployment is exactly one of them, ADERA_ENV).
-- Existing rows are kept, renamed in place. The unique and primary keys include the column; the values do not collide
-- because no row can hold both TEST and DEV yet.
UPDATE notification SET environment = 'DEV'  WHERE environment = 'TEST';
UPDATE notification SET environment = 'PROD' WHERE environment = 'LIVE';
UPDATE usage_daily  SET environment = 'DEV'  WHERE environment = 'TEST';
UPDATE usage_daily  SET environment = 'PROD' WHERE environment = 'LIVE';
UPDATE usage_total  SET environment = 'DEV'  WHERE environment = 'TEST';
UPDATE usage_total  SET environment = 'PROD' WHERE environment = 'LIVE';
-- callbacks that are still queued carry the environment in their payload
UPDATE callback SET payload = jsonb_set(payload, '{environment}', '"DEV"')  WHERE payload->>'environment' = 'TEST';
UPDATE callback SET payload = jsonb_set(payload, '{environment}', '"PROD"') WHERE payload->>'environment' = 'LIVE';

ALTER TABLE notification ADD CONSTRAINT notification_environment_check CHECK (environment IN ('DEV', 'PROD'));
ALTER TABLE usage_daily  ADD CONSTRAINT usage_daily_environment_check  CHECK (environment IN ('DEV', 'PROD'));
ALTER TABLE usage_total  ADD CONSTRAINT usage_total_environment_check  CHECK (environment IN ('DEV', 'PROD'));

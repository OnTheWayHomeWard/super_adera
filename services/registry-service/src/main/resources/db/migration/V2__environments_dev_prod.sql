-- Environments are renamed: TEST -> DEV, LIVE -> PROD. A deployment is exactly one of them (ADERA_ENV);
-- allowed addresses and keys are stored for both, only the matching one is active.
-- Existing rows are kept (renamed in place). Key ids such as ak_test_xxx are identifiers partners already use: they stay as they are.
UPDATE app_environment SET environment = 'DEV'  WHERE environment = 'TEST';
UPDATE app_environment SET environment = 'PROD' WHERE environment = 'LIVE';
UPDATE partner_key     SET environment = 'DEV'  WHERE environment = 'TEST';
UPDATE partner_key     SET environment = 'PROD' WHERE environment = 'LIVE';

ALTER TABLE app_environment ADD CONSTRAINT app_environment_environment_check CHECK (environment IN ('DEV', 'PROD'));
ALTER TABLE partner_key     ADD CONSTRAINT partner_key_environment_check     CHECK (environment IN ('DEV', 'PROD'));

COMMENT ON COLUMN app_environment.environment IS 'DEV | PROD';
COMMENT ON COLUMN partner_key.environment IS 'DEV | PROD';

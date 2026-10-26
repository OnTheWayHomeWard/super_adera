-- Audit entries about an app's keys, reviews, reports and templates used the key/review/report/template id as their
-- target, so an app's own audit view missed them. Every entry now also records the app it concerns (NULL for entries
-- that are not about one app, such as staff or user changes).
ALTER TABLE audit_event ADD COLUMN app_id UUID;

-- Entries whose target IS the app.
UPDATE audit_event SET app_id = CAST(target_id AS uuid)
WHERE target_type = 'mini_app'
  AND target_id ~* '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$';

-- Older entries about something that belongs to an app carry the app id in their meta (key.*, review.moderate,
-- report.handle, template.*).
UPDATE audit_event SET app_id = CAST(meta ->> 'appId' AS uuid)
WHERE app_id IS NULL
  AND meta ->> 'appId' ~* '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$';

CREATE INDEX idx_audit_app ON audit_event (app_id, at DESC) WHERE app_id IS NOT NULL;

-- Why staff hid/restored a review or handled/reopened a report (optional, staff-only, never shown to users).
ALTER TABLE app_review ADD COLUMN moderation_reason VARCHAR(500);
ALTER TABLE app_report ADD COLUMN handling_reason   VARCHAR(500);

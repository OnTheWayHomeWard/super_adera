-- Where a partner wants consent callbacks (user.consent_changed, user.disconnected) for this environment. Optional: no URL, no callback.
ALTER TABLE app_environment ADD COLUMN callback_url VARCHAR(500);

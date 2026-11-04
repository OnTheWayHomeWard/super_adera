-- Callbacks are not only status reports of a notification any more: the same queue (signed, retried for 24 hours) also carries
-- user.consent_changed and user.disconnected to the app's callback URL. Those have no notification behind them.
ALTER TABLE callback ALTER COLUMN notification_id DROP NOT NULL;
ALTER TABLE callback ADD COLUMN event  VARCHAR(40) NOT NULL DEFAULT 'notification.status';
ALTER TABLE callback ADD COLUMN app_id UUID;

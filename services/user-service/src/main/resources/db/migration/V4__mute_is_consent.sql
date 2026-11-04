-- Notification consent replaces the mute switches: "muted" means the user answered DENIED for notify.inapp / notify.sms / notify.email.
-- Every existing mute becomes a DENIED consent (count 0, source MUTE, decided at the time of the mute) with its proof row; a consent the
-- user already gave for the same capability is left alone. Then the old table goes.
INSERT INTO consent_event (user_id, app_id, capability, from_state, to_state, at, source)
SELECT p.user_id, p.app_id, 'notify.inapp', 'NOT_ASKED', 'DENIED', p.updated_at, 'MUTE' FROM app_notification_preference p
WHERE p.mute_in_app AND NOT EXISTS (SELECT 1 FROM app_consent c WHERE c.user_id = p.user_id AND c.app_id = p.app_id AND c.capability = 'notify.inapp');
INSERT INTO consent_event (user_id, app_id, capability, from_state, to_state, at, source)
SELECT p.user_id, p.app_id, 'notify.sms', 'NOT_ASKED', 'DENIED', p.updated_at, 'MUTE' FROM app_notification_preference p
WHERE p.mute_sms AND NOT EXISTS (SELECT 1 FROM app_consent c WHERE c.user_id = p.user_id AND c.app_id = p.app_id AND c.capability = 'notify.sms');
INSERT INTO consent_event (user_id, app_id, capability, from_state, to_state, at, source)
SELECT p.user_id, p.app_id, 'notify.email', 'NOT_ASKED', 'DENIED', p.updated_at, 'MUTE' FROM app_notification_preference p
WHERE p.mute_email AND NOT EXISTS (SELECT 1 FROM app_consent c WHERE c.user_id = p.user_id AND c.app_id = p.app_id AND c.capability = 'notify.email');

INSERT INTO app_consent (user_id, app_id, capability, state, denial_count, decided_at, source)
SELECT p.user_id, p.app_id, 'notify.inapp', 'DENIED', 0, p.updated_at, 'MUTE' FROM app_notification_preference p WHERE p.mute_in_app
ON CONFLICT (user_id, app_id, capability) DO NOTHING;
INSERT INTO app_consent (user_id, app_id, capability, state, denial_count, decided_at, source)
SELECT p.user_id, p.app_id, 'notify.sms', 'DENIED', 0, p.updated_at, 'MUTE' FROM app_notification_preference p WHERE p.mute_sms
ON CONFLICT (user_id, app_id, capability) DO NOTHING;
INSERT INTO app_consent (user_id, app_id, capability, state, denial_count, decided_at, source)
SELECT p.user_id, p.app_id, 'notify.email', 'DENIED', 0, p.updated_at, 'MUTE' FROM app_notification_preference p WHERE p.mute_email
ON CONFLICT (user_id, app_id, capability) DO NOTHING;

DROP TABLE app_notification_preference;

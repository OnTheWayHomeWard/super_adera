-- Tells the partner when a user changes a permission (user.consent_changed) or disconnects (user.disconnected). Written in the SAME
-- transaction as the change, delivered by a background worker to notification-service (which signs it and keeps retrying for 24 hours),
-- so a crash between the change and the notification loses nothing. The payload holds the pseudonymous id only.
CREATE TABLE partner_event_outbox (
    id               BIGSERIAL    PRIMARY KEY,
    event            VARCHAR(30)  NOT NULL,                  -- user.consent_changed | user.disconnected
    app_id           UUID         NOT NULL,
    alias            VARCHAR(40)  NOT NULL,
    payload          JSONB        NOT NULL,                  -- capability + state, or the list of capabilities taken back
    created_at       TIMESTAMPTZ  NOT NULL,
    sent_at          TIMESTAMPTZ,
    attempts         INT          NOT NULL DEFAULT 0,
    next_attempt_at  TIMESTAMPTZ  NOT NULL,
    last_error       VARCHAR(60)
);
CREATE INDEX idx_partner_event_due ON partner_event_outbox (next_attempt_at) WHERE sent_at IS NULL;

-- Per-capability consent: what each user allowed each mini app to do. Staff decide what an app MAY ask for (registry-service);
-- this table records what the user answered. One current row per (user, app, capability); no row means NOT_ASKED.
CREATE TABLE app_consent (
    user_id            UUID         NOT NULL REFERENCES app_user (id) ON DELETE CASCADE,
    app_id             UUID         NOT NULL,
    capability         VARCHAR(40)  NOT NULL,                  -- user.full_name, location.fine, notify.sms ...
    state              VARCHAR(10)  NOT NULL,                  -- NOT_ASKED | GRANTED | DENIED | BLOCKED
    denial_count       INT          NOT NULL DEFAULT 0,        -- consecutive refusals; the second one blocks
    decided_at         TIMESTAMPTZ,                            -- null until the user (or the menu) decided
    source             VARCHAR(16),                            -- DIALOG | MENU | DISCONNECT | MUTE
    prompt_id          VARCHAR(40),                            -- the one-time dialog ticket, while a dialog is open
    prompt_expires_at  TIMESTAMPTZ,
    PRIMARY KEY (user_id, app_id, capability)
);
CREATE INDEX idx_app_consent_app ON app_consent (app_id, capability, state);

-- Append only: proof of what the user agreed to and when.
CREATE TABLE consent_event (
    id          BIGSERIAL    PRIMARY KEY,
    user_id     UUID         NOT NULL REFERENCES app_user (id) ON DELETE CASCADE,
    app_id      UUID         NOT NULL,
    capability  VARCHAR(40)  NOT NULL,
    from_state  VARCHAR(10)  NOT NULL,
    to_state    VARCHAR(10)  NOT NULL,
    at          TIMESTAMPTZ  NOT NULL,
    source      VARCHAR(16)  NOT NULL
);
CREATE INDEX idx_consent_event_user ON consent_event (user_id, app_id, at);

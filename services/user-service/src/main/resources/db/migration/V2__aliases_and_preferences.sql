-- Pseudonymous per-app user ids: a partner only ever sees alias, never our user id, and the same person has a
-- different alias in every mini app, so two partners cannot join their data. alias = HMAC(secret, user:app).
CREATE TABLE app_user_alias (
    app_id      UUID         NOT NULL,
    alias       VARCHAR(40)  NOT NULL,
    user_id     UUID         NOT NULL REFERENCES app_user (id) ON DELETE CASCADE,
    created_at  TIMESTAMPTZ  NOT NULL,
    PRIMARY KEY (app_id, alias),
    UNIQUE (app_id, user_id)
);

-- What a user chose about one mini app: mute a channel, or everything.
CREATE TABLE app_notification_preference (
    user_id     UUID         NOT NULL REFERENCES app_user (id) ON DELETE CASCADE,
    app_id      UUID         NOT NULL,
    mute_in_app BOOLEAN      NOT NULL DEFAULT FALSE,
    mute_sms    BOOLEAN      NOT NULL DEFAULT FALSE,
    mute_email  BOOLEAN      NOT NULL DEFAULT FALSE,
    updated_at  TIMESTAMPTZ  NOT NULL,
    PRIMARY KEY (user_id, app_id)
);

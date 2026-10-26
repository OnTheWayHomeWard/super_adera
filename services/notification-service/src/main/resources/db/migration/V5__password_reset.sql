-- Password reset by phone (POST /public/v1/password-reset/start and /finish). Kratos' own recovery flow only accepts an email
-- address, so for phones we keep the one-time code ourselves. Only a salted hash of the code and a hash of the phone are stored.
-- A row is created only for a phone that belongs to an account; the answers never reveal whether one was created.
CREATE TABLE password_reset (
    id              UUID        PRIMARY KEY,
    identity_id     UUID        NOT NULL,           -- the Kratos identity (= user id)
    recipient_hash  VARCHAR(64) NOT NULL,           -- sha-256 (hex) of the normalised phone
    code_salt       VARCHAR(32) NOT NULL,
    code_hash       VARCHAR(64) NOT NULL,           -- sha-256 (hex) of salt + ':' + code
    attempts        INT         NOT NULL DEFAULT 0, -- wrong codes tried so far
    expires_at      TIMESTAMPTZ NOT NULL,
    used_at         TIMESTAMPTZ,
    created_at      TIMESTAMPTZ NOT NULL
);
CREATE INDEX idx_password_reset_recipient ON password_reset (recipient_hash, created_at);

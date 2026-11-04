-- One row per partner read of GET /v1/users/{alias}/profile: who asked, for which pseudonymous user, which permissions were
-- released, and the outcome. It never holds the values themselves (name, phone, ...), so the log is not itself personal data.
CREATE TABLE profile_access (
    id           BIGSERIAL    PRIMARY KEY,
    app_id       UUID         NOT NULL,
    environment  VARCHAR(4)   NOT NULL CHECK (environment IN ('DEV', 'PROD')),
    alias        VARCHAR(80)  NOT NULL,
    outcome      VARCHAR(20)  NOT NULL,                  -- OK | CONSENT_REQUIRED
    granted      TEXT[]       NOT NULL DEFAULT '{}',     -- the permissions the user allowed at that moment (and the app holds)
    at           TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX idx_profile_access_app ON profile_access (app_id, at DESC);

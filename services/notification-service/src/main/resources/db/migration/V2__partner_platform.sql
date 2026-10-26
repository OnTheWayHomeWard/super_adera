-- ---------------------------------------------------------------------------------------------------------------
-- Partner notification platform. Mini-app backends ask us to reach a user; we render an admin-approved template,
-- pick the channel from what we know about the user, deliver in the background, and report back by signed callback.
-- ---------------------------------------------------------------------------------------------------------------

-- Admin-approved message templates, per mini app. Partners only choose a template and fill in the declared values.
CREATE TABLE template (
    id          UUID         PRIMARY KEY,
    app_id      UUID         NOT NULL,
    code        VARCHAR(60)  NOT NULL,                       -- human label, unique per app
    kind        VARCHAR(10)  NOT NULL,                       -- DIRECT (sms/email) | IN_APP (inbox + push)
    category    VARCHAR(16)  NOT NULL,                       -- TRANSACTIONAL | REMINDER | PROMOTIONAL
    params      JSONB        NOT NULL DEFAULT '[]'::jsonb,   -- [{"name":"name","maxLength":50,"required":true,"allowLinks":false}]
    content     JSONB        NOT NULL,                       -- {"en":{"title","body","sms","emailSubject","emailHtml"},"am":{...},"om":{...}}
    active      BOOLEAN      NOT NULL DEFAULT TRUE,
    valid_from  TIMESTAMPTZ,
    expires_at  TIMESTAMPTZ,                                 -- so a forgotten template switches itself off
    version     INT          NOT NULL DEFAULT 1,
    created_by  UUID,
    updated_by  UUID,
    created_at  TIMESTAMPTZ  NOT NULL,
    updated_at  TIMESTAMPTZ  NOT NULL,
    UNIQUE (app_id, code)
);
CREATE INDEX idx_template_app ON template (app_id);

-- One row per request from a partner.
CREATE TABLE notification (
    id             UUID         PRIMARY KEY,
    app_id         UUID         NOT NULL,
    environment    VARCHAR(5)   NOT NULL,                    -- TEST | LIVE
    external_id    VARCHAR(100) NOT NULL,                    -- the partner's own unique id: idempotency + their reference
    request_hash   VARCHAR(64)  NOT NULL,                    -- same externalId + same request = replay; different = conflict
    user_id        UUID         NOT NULL,
    user_alias     VARCHAR(40)  NOT NULL,
    template_id    UUID         NOT NULL,
    template_version INT        NOT NULL,
    params         JSONB        NOT NULL DEFAULT '{}'::jsonb,
    data           JSONB        NOT NULL DEFAULT '{}'::jsonb,
    kind           VARCHAR(10)  NOT NULL,
    channel        VARCHAR(10)  NOT NULL,                    -- SMS | EMAIL | IN_APP (chosen by us, not the partner)
    status         VARCHAR(12)  NOT NULL,                    -- QUEUED PROCESSING RETRYING SENT FAILED UNREACHABLE SUPPRESSED EXPIRED
    reason         VARCHAR(64),                              -- why it ended the way it did (error code)
    attempts       INT          NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMPTZ NOT NULL,
    locked_until   TIMESTAMPTZ,
    callback_url   VARCHAR(500),
    expires_at     TIMESTAMPTZ  NOT NULL,
    created_at     TIMESTAMPTZ  NOT NULL,
    updated_at     TIMESTAMPTZ  NOT NULL,
    completed_at   TIMESTAMPTZ,
    UNIQUE (app_id, environment, external_id)
);
CREATE INDEX idx_notification_due ON notification (next_attempt_at) WHERE status IN ('QUEUED', 'RETRYING', 'PROCESSING');
CREATE INDEX idx_notification_app ON notification (app_id, created_at DESC);

-- Every delivery attempt (so "why did this not arrive" can be answered).
CREATE TABLE notification_attempt (
    id              UUID         PRIMARY KEY,
    notification_id UUID         NOT NULL REFERENCES notification (id) ON DELETE CASCADE,
    channel         VARCHAR(10)  NOT NULL,
    provider        VARCHAR(32)  NOT NULL,
    outcome         VARCHAR(12)  NOT NULL,                   -- OK | TRANSIENT | PERMANENT | SKIPPED
    error_code      VARCHAR(64),
    at              TIMESTAMPTZ  NOT NULL
);
CREATE INDEX idx_attempt_notification ON notification_attempt (notification_id);

-- The in-app inbox (what the user sees in the bell menu).
CREATE TABLE inbox_item (
    id              UUID         PRIMARY KEY,
    user_id         UUID         NOT NULL,
    app_id          UUID         NOT NULL,
    notification_id UUID         NOT NULL UNIQUE,
    title           VARCHAR(200) NOT NULL,
    body            VARCHAR(2000) NOT NULL,
    data            JSONB        NOT NULL DEFAULT '{}'::jsonb,
    created_at      TIMESTAMPTZ  NOT NULL,
    read_at         TIMESTAMPTZ
);
CREATE INDEX idx_inbox_user ON inbox_item (user_id, created_at DESC);

-- Signed status reports back to the partner, delivered in the background with retries.
CREATE TABLE callback (
    id              UUID         PRIMARY KEY,
    notification_id UUID         NOT NULL REFERENCES notification (id) ON DELETE CASCADE,
    url             VARCHAR(500) NOT NULL,
    payload         JSONB        NOT NULL,
    status          VARCHAR(10)  NOT NULL DEFAULT 'PENDING', -- PENDING | PROCESSING | DELIVERED | FAILED
    attempts        INT          NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMPTZ  NOT NULL,
    locked_until    TIMESTAMPTZ,
    last_http_status INT,
    last_error      VARCHAR(100),
    created_at      TIMESTAMPTZ  NOT NULL,
    updated_at      TIMESTAMPTZ  NOT NULL
);
CREATE INDEX idx_callback_due ON callback (next_attempt_at) WHERE status IN ('PENDING', 'PROCESSING');

-- Usage counters for the per-app limits (per user per day, per day, distinct users per day, lifetime).
CREATE TABLE usage_daily (
    app_id      UUID         NOT NULL,
    environment VARCHAR(5)   NOT NULL,
    channel     VARCHAR(10)  NOT NULL,
    day         DATE         NOT NULL,
    user_id     UUID         NOT NULL,
    sent        INT          NOT NULL DEFAULT 0,
    PRIMARY KEY (app_id, environment, channel, day, user_id)
);
CREATE TABLE usage_total (
    app_id      UUID         NOT NULL,
    environment VARCHAR(5)   NOT NULL,
    channel     VARCHAR(10)  NOT NULL,
    total       BIGINT       NOT NULL DEFAULT 0,
    PRIMARY KEY (app_id, environment, channel)
);

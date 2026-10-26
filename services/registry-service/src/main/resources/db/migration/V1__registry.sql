-- Dashboard staff. The Kratos identity id is the primary key; the ROLE itself lives in Kratos
-- (metadata_public.role, writable only through the admin API), this table is the listing + status + audit trail.
CREATE TABLE staff (
    identity_id  UUID         PRIMARY KEY,
    email        VARCHAR(320) NOT NULL,
    role         VARCHAR(16)  NOT NULL,                 -- SUPER_ADMIN | ADMIN | VIEWER
    status       VARCHAR(10)  NOT NULL DEFAULT 'ACTIVE', -- ACTIVE | DISABLED
    created_by   UUID,
    created_at   TIMESTAMPTZ  NOT NULL,
    updated_at   TIMESTAMPTZ  NOT NULL
);
CREATE UNIQUE INDEX uq_staff_email ON staff (lower(email));

-- A mini app: who it is, how it looks, whether it is allowed to run.
CREATE TABLE mini_app (
    id                  UUID         PRIMARY KEY,
    slug                VARCHAR(40)  NOT NULL UNIQUE,        -- permanent, used in links
    name                JSONB        NOT NULL,               -- {"en": "...", "am": "...", "om": "..."}
    description         JSONB        NOT NULL DEFAULT '{}'::jsonb,
    logo_image_id       UUID,                                 -- file in RustFS: apps/{id}/logo/{logo_image_id}.png
    logo_content_type   VARCHAR(64),
    brand_color         VARCHAR(7),
    categories          JSONB        NOT NULL DEFAULT '[]'::jsonb,
    markets             JSONB        NOT NULL DEFAULT '["ET"]'::jsonb,
    publisher_name      VARCHAR(150),
    publisher_legal_name VARCHAR(200),
    support_email       VARCHAR(320),
    support_phone       VARCHAR(30),
    website             VARCHAR(300),
    entry_url           VARCHAR(500),                         -- where the app is served (used by the frontend bridge later)
    allowed_origins     JSONB        NOT NULL DEFAULT '[]'::jsonb,
    status              VARCHAR(10)  NOT NULL DEFAULT 'DRAFT', -- DRAFT | LIVE | PAUSED | KILLED
    priority            INT          NOT NULL DEFAULT 100,    -- lower = shown first
    rating_avg          NUMERIC(3,2) NOT NULL DEFAULT 0,
    rating_count        INT          NOT NULL DEFAULT 0,
    extra               JSONB        NOT NULL DEFAULT '{}'::jsonb,
    created_by          UUID,
    created_at          TIMESTAMPTZ  NOT NULL,
    updated_at          TIMESTAMPTZ  NOT NULL
);
CREATE INDEX idx_mini_app_listing ON mini_app (status, priority);

-- What data / services we allow this app to request. The "admin grants" gate; the per-user consent gate comes
-- with the frontend bridge. The reason is shown to users in the consent sheet.
CREATE TABLE app_capability (
    app_id       UUID         NOT NULL REFERENCES mini_app (id) ON DELETE CASCADE,
    capability   VARCHAR(40)  NOT NULL,                       -- user.full_name, notify.sms, pay.request ...
    state        VARCHAR(10)  NOT NULL,                       -- GRANTED | DENIED | SUSPENDED
    reason       JSONB        NOT NULL DEFAULT '{}'::jsonb,
    updated_by   UUID,
    updated_at   TIMESTAMPTZ  NOT NULL,
    PRIMARY KEY (app_id, capability)
);

CREATE TABLE app_payment_settings (
    app_id             UUID        PRIMARY KEY REFERENCES mini_app (id) ON DELETE CASCADE,
    allowed_currencies JSONB       NOT NULL DEFAULT '[]'::jsonb,   -- ["ETB","USD"]
    allow_hold         BOOLEAN     NOT NULL DEFAULT FALSE,         -- authorise now, capture later
    min_amount_minor   BIGINT,
    max_amount_minor   BIGINT,
    updated_at         TIMESTAMPTZ NOT NULL
);

-- How much an app may send, per channel. NULL = unlimited, 0 = none.
CREATE TABLE app_notification_limit (
    app_id                      UUID        NOT NULL REFERENCES mini_app (id) ON DELETE CASCADE,
    channel                     VARCHAR(10) NOT NULL,           -- SMS | EMAIL | IN_APP
    max_per_user_per_day        INT,
    max_per_day                 INT,
    max_distinct_users_per_day  INT,
    max_total                   BIGINT,
    updated_at                  TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (app_id, channel)
);

-- Per environment (TEST = sandbox, LIVE): which partner server addresses may call our API.
CREATE TABLE app_environment (
    app_id         UUID        NOT NULL REFERENCES mini_app (id) ON DELETE CASCADE,
    environment    VARCHAR(5)  NOT NULL,                       -- TEST | LIVE
    allowed_cidrs  JSONB       NOT NULL DEFAULT '[]'::jsonb,   -- ["203.0.113.10/32", "198.51.100.0/24"]
    updated_at     TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (app_id, environment)
);

-- The partner's PUBLIC key (they keep the private key). key_id is an identifier, not a secret.
CREATE TABLE partner_key (
    key_id          VARCHAR(64)  PRIMARY KEY,                 -- ak_test_xxx / ak_live_xxx
    app_id          UUID         NOT NULL REFERENCES mini_app (id) ON DELETE CASCADE,
    environment     VARCHAR(5)   NOT NULL,
    algorithm       VARCHAR(10)  NOT NULL DEFAULT 'RS256',
    public_key_pem  TEXT         NOT NULL,
    fingerprint     VARCHAR(64)  NOT NULL,                    -- sha-256 of the DER public key
    label           VARCHAR(100),
    status          VARCHAR(10)  NOT NULL DEFAULT 'ACTIVE',   -- ACTIVE | REVOKED
    expires_at      TIMESTAMPTZ,
    created_by      UUID,
    created_at      TIMESTAMPTZ  NOT NULL,
    revoked_at      TIMESTAMPTZ
);
CREATE INDEX idx_partner_key_app ON partner_key (app_id);

CREATE TABLE app_review (
    id          UUID         PRIMARY KEY,
    app_id      UUID         NOT NULL REFERENCES mini_app (id) ON DELETE CASCADE,
    user_id     UUID         NOT NULL,
    rating      SMALLINT     NOT NULL CHECK (rating BETWEEN 1 AND 5),
    comment     VARCHAR(2000),
    status      VARCHAR(10)  NOT NULL DEFAULT 'VISIBLE',      -- VISIBLE | HIDDEN (moderation)
    created_at  TIMESTAMPTZ  NOT NULL,
    updated_at  TIMESTAMPTZ  NOT NULL,
    UNIQUE (app_id, user_id)
);

CREATE TABLE app_report (
    id          UUID         PRIMARY KEY,
    app_id      UUID         NOT NULL REFERENCES mini_app (id) ON DELETE CASCADE,
    user_id     UUID         NOT NULL,
    reason      VARCHAR(30)  NOT NULL,                        -- SPAM | SCAM | INAPPROPRIATE | BROKEN | OTHER
    details     VARCHAR(2000),
    status      VARCHAR(10)  NOT NULL DEFAULT 'OPEN',         -- OPEN | REVIEWED | DISMISSED
    handled_by  UUID,
    handled_at  TIMESTAMPTZ,
    created_at  TIMESTAMPTZ  NOT NULL
);
CREATE INDEX idx_app_report_open ON app_report (status, created_at DESC);

-- Append-only record of every change staff make.
CREATE TABLE audit_event (
    id          UUID         PRIMARY KEY,
    actor_id    UUID,
    actor_role  VARCHAR(16),
    action      VARCHAR(60)  NOT NULL,
    target_type VARCHAR(30),
    target_id   VARCHAR(64),
    meta        JSONB        NOT NULL DEFAULT '{}'::jsonb,
    ip          VARCHAR(64),
    at          TIMESTAMPTZ  NOT NULL
);
CREATE INDEX idx_audit_at ON audit_event (at DESC);

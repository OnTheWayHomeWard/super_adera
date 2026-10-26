-- The full user record. The id IS the Ory Kratos identity id: Kratos owns identity + login + sessions,
-- this table owns everything else about the person. email/phone are copies of the Kratos traits for convenience.
CREATE TABLE app_user (
    id                      UUID         PRIMARY KEY,
    email                   VARCHAR(320),
    phone_e164              VARCHAR(20),

    first_name              VARCHAR(100),
    last_name               VARCHAR(100),
    gender                  VARCHAR(20),                 -- MALE | FEMALE | OTHER | PREFER_NOT_TO_SAY
    date_of_birth           DATE,
    language                VARCHAR(5)   NOT NULL DEFAULT 'en',          -- am | en | om
    market                  VARCHAR(2)   NOT NULL DEFAULT 'ET',          -- ET | US
    timezone                VARCHAR(64)  NOT NULL DEFAULT 'Africa/Addis_Ababa',

    -- avatar: the image lives in RustFS under avatars/{user id}/{avatar_image_id}.jpg
    avatar_image_id         UUID,
    avatar_content_type     VARCHAR(64),
    avatar_updated_at       TIMESTAMPTZ,

    -- optional, user-provided, coarse location (for "near me"); null until the user shares it
    country_code            VARCHAR(2),
    region                  VARCHAR(100),
    city                    VARCHAR(100),
    latitude                DOUBLE PRECISION,
    longitude               DOUBLE PRECISION,
    location_updated_at     TIMESTAMPTZ,

    verification_tier       VARCHAR(16)  NOT NULL DEFAULT 'CONTACT',     -- CONTACT | ID | PROVIDER
    status                  VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE',      -- ACTIVE | SUSPENDED | DELETED
    terms_accepted_version  VARCHAR(32),
    terms_accepted_at       TIMESTAMPTZ,

    -- free-form data we did not plan for; merged with JSON-merge-patch semantics through the API
    extra                   JSONB        NOT NULL DEFAULT '{}'::jsonb,

    last_seen_at            TIMESTAMPTZ,
    created_at              TIMESTAMPTZ  NOT NULL,
    updated_at              TIMESTAMPTZ  NOT NULL,
    deleted_at              TIMESTAMPTZ
);

CREATE UNIQUE INDEX uq_app_user_email ON app_user (lower(email)) WHERE email IS NOT NULL AND deleted_at IS NULL;
CREATE UNIQUE INDEX uq_app_user_phone ON app_user (phone_e164) WHERE phone_e164 IS NOT NULL AND deleted_at IS NULL;
CREATE INDEX idx_app_user_city ON app_user (country_code, city) WHERE city IS NOT NULL;

-- One row per app installation that can receive push notifications.
CREATE TABLE device (
    id               UUID         PRIMARY KEY,
    user_id          UUID         NOT NULL REFERENCES app_user (id) ON DELETE CASCADE,
    installation_id  VARCHAR(64),                     -- stable id the app generates; lets a rotated FCM token replace the old one
    platform         VARCHAR(10)  NOT NULL,           -- ANDROID | IOS | WEB
    fcm_token        TEXT         NOT NULL,
    app_version      VARCHAR(32),
    os_version       VARCHAR(32),
    device_name      VARCHAR(100),
    locale           VARCHAR(16),
    is_active        BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at       TIMESTAMPTZ  NOT NULL,
    last_seen_at     TIMESTAMPTZ  NOT NULL,
    token_updated_at TIMESTAMPTZ  NOT NULL
);

CREATE UNIQUE INDEX uq_device_fcm_token ON device (fcm_token);
CREATE UNIQUE INDEX uq_device_installation ON device (user_id, installation_id) WHERE installation_id IS NOT NULL;
CREATE INDEX idx_device_user ON device (user_id) WHERE is_active;

-- Promotional banners shown in the super app. Staff set only `status` (DRAFT | PUBLISHED | PAUSED); whether a banner is
-- showing is computed when reading: PUBLISHED, starts_at <= now < ends_at (or no end), and the linked app (if any) LIVE.
-- Nothing is ever written when a banner starts or ends.
CREATE TABLE banner (
    id              UUID         PRIMARY KEY,
    title           JSONB        NOT NULL,                     -- {"en": ..., "am": ..., "om": ...}, en required
    subtitle        JSONB        NOT NULL DEFAULT '{}'::jsonb,
    placement       VARCHAR(20)  NOT NULL CHECK (placement IN ('HOME_HERO', 'FEATURED', 'PROMOTIONS', 'CATALOG_TOP')),
    category        VARCHAR(20)  NOT NULL CHECK (category IN ('PROMOTION', 'FEATURED_APP', 'ANNOUNCEMENT', 'EVENT')),
    -- a banner about an app goes with the app
    app_id          UUID         REFERENCES mini_app (id) ON DELETE CASCADE,
    action_type     VARCHAR(10)  NOT NULL CHECK (action_type IN ('MINI_APP', 'INTERNAL', 'EXTERNAL', 'NONE')),
    target          VARCHAR(500),
    -- the uploaded image: banners/{id}/{image_id}/{small|medium|large}.jpg, with each size's width and height
    image_id        UUID,
    image_variants  JSONB,
    starts_at       TIMESTAMPTZ  NOT NULL,
    ends_at         TIMESTAMPTZ,
    priority        INTEGER      NOT NULL DEFAULT 0 CHECK (priority BETWEEN 0 AND 100000),
    status          VARCHAR(10)  NOT NULL DEFAULT 'DRAFT' CHECK (status IN ('DRAFT', 'PUBLISHED', 'PAUSED')),
    created_by      UUID,
    updated_by      UUID,
    created_at      TIMESTAMPTZ  NOT NULL,
    updated_at      TIMESTAMPTZ  NOT NULL,
    CONSTRAINT banner_window CHECK (ends_at IS NULL OR ends_at > starts_at),
    CONSTRAINT banner_app_needed CHECK (app_id IS NOT NULL OR (category <> 'FEATURED_APP' AND action_type <> 'MINI_APP')),
    CONSTRAINT banner_target_none CHECK (action_type <> 'NONE' OR target IS NULL)
);

-- The public list: published banners of one placement, highest priority first, newest first.
CREATE INDEX idx_banner_public ON banner (placement, priority DESC, created_at DESC) WHERE status = 'PUBLISHED';
CREATE INDEX idx_banner_app ON banner (app_id) WHERE app_id IS NOT NULL;

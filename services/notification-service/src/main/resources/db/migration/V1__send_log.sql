-- One row per delivery attempt. Never stores the message body (it contains one-time codes)
-- and never stores the full recipient: only a masked form for humans and a hash for rate limiting.
CREATE TABLE send_log (
    id              UUID         PRIMARY KEY,
    channel         VARCHAR(10)  NOT NULL,           -- SMS | EMAIL
    recipient_hash  VARCHAR(64)  NOT NULL,           -- sha-256 (hex) of the normalised recipient
    recipient_masked VARCHAR(64) NOT NULL,
    template_type   VARCHAR(64),
    provider        VARCHAR(32)  NOT NULL,           -- console | twilio | mailpit | resend
    status          VARCHAR(16)  NOT NULL,           -- SENT | FAILED | SUPPRESSED | SKIPPED
    error_code      VARCHAR(64),
    created_at      TIMESTAMPTZ  NOT NULL
);

-- rate-limit lookups: "how many attempts to this recipient on this channel recently?"
CREATE INDEX idx_send_log_recipient ON send_log (channel, recipient_hash, created_at DESC);
CREATE INDEX idx_send_log_created ON send_log (created_at);

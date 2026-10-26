-- One row per "may I ask for a sign-in code for this address?" check (POST /public/v1/login-codes/check), whether or not an
-- account exists for it, so the answer cannot be used to find out which addresses are registered. Only a hash of the
-- address is kept. Old rows are pruned by the check itself.
CREATE TABLE code_request (
    id              UUID        PRIMARY KEY,
    channel         VARCHAR(10) NOT NULL,           -- SMS | EMAIL
    recipient_hash  VARCHAR(64) NOT NULL,           -- sha-256 (hex) of the normalised address
    created_at      TIMESTAMPTZ NOT NULL
);
CREATE INDEX idx_code_request_recipient ON code_request (channel, recipient_hash, created_at);
CREATE INDEX idx_code_request_created ON code_request (created_at);

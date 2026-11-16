CREATE TABLE password_reset (
    user_id UUID PRIMARY KEY REFERENCES app_user(id) ON DELETE CASCADE,
    token_hash CHAR(64) NOT NULL UNIQUE,
    expires_at TIMESTAMPTZ NOT NULL,
    consumed_at TIMESTAMPTZ,
    security_version BIGINT NOT NULL
);

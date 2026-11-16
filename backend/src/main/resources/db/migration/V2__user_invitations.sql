ALTER TABLE app_user ALTER COLUMN password_hash DROP NOT NULL;

CREATE TABLE user_invitation (
    user_id UUID PRIMARY KEY REFERENCES app_user(id) ON DELETE CASCADE,
    token_hash CHAR(64) NOT NULL UNIQUE,
    expires_at TIMESTAMPTZ NOT NULL,
    consumed_at TIMESTAMPTZ
);

CREATE TABLE account_audit (
    id UUID PRIMARY KEY,
    actor_id UUID NOT NULL,
    subject_id UUID NOT NULL,
    action TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

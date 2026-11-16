CREATE TABLE app_user (
    id UUID PRIMARY KEY,
    email TEXT NOT NULL,
    password_hash TEXT NOT NULL,
    first_name TEXT NOT NULL CHECK (length(trim(first_name)) > 0),
    last_name TEXT NOT NULL CHECK (length(trim(last_name)) > 0),
    nickname TEXT,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    membership_current BOOLEAN NOT NULL DEFAULT FALSE,
    administrator BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CHECK (email = lower(trim(email)))
);
CREATE UNIQUE INDEX app_user_email_unique ON app_user (lower(email));

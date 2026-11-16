ALTER TABLE app_user ADD COLUMN board_member BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE app_user ADD CONSTRAINT account_exclusive_role CHECK (NOT (administrator AND board_member));

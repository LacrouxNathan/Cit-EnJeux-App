ALTER TABLE app_user ADD COLUMN version BIGINT NOT NULL DEFAULT 0;
ALTER TABLE app_user ADD COLUMN security_version BIGINT NOT NULL DEFAULT 0;
ALTER TABLE account_audit ADD COLUMN changes JSONB NOT NULL DEFAULT '{}'::jsonb;
CREATE INDEX account_audit_subject_index ON account_audit(subject_id, created_at);

-- Main admin of the whole installation (sees the System page: backups, Google Drive).
ALTER TABLE app_user ADD COLUMN platform_admin BOOLEAN NOT NULL DEFAULT FALSE;

-- Existing installs: the first owner who signed up becomes the main admin.
UPDATE app_user SET platform_admin = TRUE
WHERE id = (SELECT id FROM app_user WHERE role = 'OWNER' ORDER BY created_at LIMIT 1);

-- Installation-wide settings (backup schedule, Google Drive connection).
CREATE TABLE system_setting (
    setting_key  VARCHAR(80) PRIMARY KEY,
    setting_value TEXT NOT NULL
);

-- One row per backup attempt.
CREATE TABLE backup_run (
    id             UUID PRIMARY KEY,
    started_at     TIMESTAMP WITH TIME ZONE NOT NULL,
    finished_at    TIMESTAMP WITH TIME ZONE,
    trigger_type   VARCHAR(16)  NOT NULL,
    status         VARCHAR(16)  NOT NULL,
    file_name      VARCHAR(200),
    size_bytes     BIGINT,
    drive_file_id  VARCHAR(120),
    message        VARCHAR(1000)
);
CREATE INDEX idx_backup_run_started ON backup_run(started_at);

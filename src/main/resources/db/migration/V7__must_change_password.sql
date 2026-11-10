-- The ready-made "admin" login of a fresh install must pick its own password at first sign-in.
ALTER TABLE app_user ADD COLUMN must_change_password BOOLEAN NOT NULL DEFAULT FALSE;

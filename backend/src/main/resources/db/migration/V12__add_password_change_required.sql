ALTER TABLE app_user
    ADD COLUMN password_change_required BOOLEAN NOT NULL DEFAULT FALSE;

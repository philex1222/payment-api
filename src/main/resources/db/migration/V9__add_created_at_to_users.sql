-- Add created_at timestamp to users table.
-- Existing rows are back-filled with the epoch so the NOT NULL constraint is satisfied.
ALTER TABLE users
    ADD COLUMN created_at DATETIME(6) NOT NULL DEFAULT '1970-01-01 00:00:00.000000';

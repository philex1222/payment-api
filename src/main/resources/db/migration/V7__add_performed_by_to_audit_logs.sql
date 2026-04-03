-- =============================================================================
-- V7 — Add performed_by to audit_logs
-- Tracks the actor (username) who triggered each audit event.
-- Nullable for backwards compatibility with existing rows.
-- =============================================================================
ALTER TABLE audit_logs
    ADD COLUMN performed_by VARCHAR(50) NULL
        COMMENT 'Username of the principal who triggered the event, or "system" for scheduled jobs';

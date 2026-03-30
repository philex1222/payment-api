-- =============================================================================
-- V5 — Security: add created_by column to payments for ownership tracking
--
-- This column records the username of the authenticated user who initiated
-- each payment, enabling BOLA (Broken Object Level Authorization) checks so
-- that a user can only access/modify their own payments unless they are an
-- administrator.
--
-- Nullable to preserve backwards compatibility with existing rows.
-- =============================================================================

ALTER TABLE payments
    ADD COLUMN created_by VARCHAR(50) NULL AFTER retry_count;

CREATE INDEX idx_payments_created_by ON payments (created_by);

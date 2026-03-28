-- =============================================================================
-- V3 — Add retry_count to payments
--
-- Tracks how many times a FAILED payment has been retried by the scheduler.
-- The retry job skips payments where retry_count >= max configured attempts (3).
-- =============================================================================

ALTER TABLE payments
    ADD COLUMN retry_count INT NOT NULL DEFAULT 0;

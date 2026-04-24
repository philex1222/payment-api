-- =============================================================================
-- V14 - Add Temporal workflow id to payments
--
-- Temporal activities are at-least-once. Storing the workflow id on the payment
-- row gives the persistPending activity a durable idempotency key so retries
-- after worker crashes or lost activity results cannot create duplicate rows.
--
-- Nullable for existing rows and non-Temporal legacy paths.
-- =============================================================================

ALTER TABLE payments
    ADD COLUMN temporal_workflow_id VARCHAR(255) NULL AFTER created_by;

CREATE UNIQUE INDEX uk_payments_temporal_workflow_id
    ON payments (temporal_workflow_id);

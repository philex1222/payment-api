-- =============================================================================
-- V2 — Indexes and column width corrections
--
-- 1. Fix payment_id columns: VARCHAR(255) → VARCHAR(36) to match payments.id
-- 2. Add FK constraints linking transactions and audit_logs to payments
-- 3. Add indexes on payment_id (most common join/filter column) and payments.status
-- =============================================================================

-- Fix column widths
ALTER TABLE transactions
    MODIFY COLUMN payment_id VARCHAR(36) NOT NULL;

ALTER TABLE audit_logs
    MODIFY COLUMN payment_id VARCHAR(36) NOT NULL;

-- Indexes on payment_id (one-to-many joins from payments)
CREATE INDEX idx_transactions_payment_id ON transactions (payment_id);
CREATE INDEX idx_audit_logs_payment_id   ON audit_logs   (payment_id);

-- Index on payments.status for filtered queries (e.g. WHERE status = 'PENDING')
CREATE INDEX idx_payments_status ON payments (status);

-- FK constraints (applied after column width fix)
ALTER TABLE transactions
    ADD CONSTRAINT fk_transactions_payment
        FOREIGN KEY (payment_id) REFERENCES payments (id);

ALTER TABLE audit_logs
    ADD CONSTRAINT fk_audit_logs_payment
        FOREIGN KEY (payment_id) REFERENCES payments (id);

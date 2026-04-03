-- V8: Add JPA-auditing timestamp columns to the transactions table.
-- created_at: set once on insert; updated_at: updated on every write.
-- Both default to the current timestamp so existing rows get a sensible value.
ALTER TABLE transactions
    ADD COLUMN created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
        COMMENT 'Timestamp when the transaction record was created',
    ADD COLUMN updated_at DATETIME(6)          DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6)
        COMMENT 'Timestamp when the transaction record was last modified';

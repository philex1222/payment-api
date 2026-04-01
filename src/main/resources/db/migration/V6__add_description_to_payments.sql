-- =============================================================================
-- V6 — Add optional description column to payments
-- Stores a client-supplied payment reference or memo (e.g. invoice number).
-- NULL for pre-existing rows; clients may supply it on new payment creation.
-- =============================================================================

ALTER TABLE payments
    ADD COLUMN description VARCHAR(255) NULL AFTER currency;

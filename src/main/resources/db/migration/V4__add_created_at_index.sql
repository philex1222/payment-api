-- =============================================================================
-- V4 — Performance: index payments.created_at + composite status+created_at
--
-- The /api/v1/payments endpoint supports date-range filtering
-- (createdAfter / createdBefore via PaymentSpecification). Without an index
-- on created_at, every filtered query performs a full table scan.
--
-- A composite index on (status, created_at) additionally accelerates queries
-- that filter by both status and date range — the most common production pattern.
-- =============================================================================

-- Index for date-range-only queries (e.g. dateFrom / dateTo without status)
CREATE INDEX idx_payments_created_at ON payments (created_at);

-- Composite index: covers the common case of filtering by status AND date range.
-- MySQL can use the leftmost prefix (status) for status-only queries too.
CREATE INDEX idx_payments_status_created_at ON payments (status, created_at);

-- Index payments.source_account and destination_account — used in findBy* queries
-- with no pagination. Without indexes these are full scans for large tables.
CREATE INDEX idx_payments_source_account      ON payments (source_account);
CREATE INDEX idx_payments_destination_account ON payments (destination_account);

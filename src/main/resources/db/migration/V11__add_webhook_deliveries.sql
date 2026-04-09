-- V11: Create webhook_deliveries table.
-- Composite index on (status, next_retry_at) supports the dispatcher's polling query.
CREATE TABLE webhook_deliveries (
    id              VARCHAR(36) NOT NULL,
    subscription_id VARCHAR(36) NOT NULL,
    payment_id      VARCHAR(36) NOT NULL,
    event_type      VARCHAR(50) NOT NULL,
    payload         TEXT        NOT NULL,
    status          VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    attempt_count   INT         NOT NULL DEFAULT 0,
    last_attempt_at DATETIME(6)          NULL,
    next_retry_at   DATETIME(6) NOT NULL,
    response_status INT                  NULL,
    created_at      DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    CONSTRAINT fk_wd_sub FOREIGN KEY (subscription_id) REFERENCES webhook_subscriptions (id) ON DELETE CASCADE,
    INDEX idx_wd_status_retry (status, next_retry_at),
    INDEX idx_wd_payment_id   (payment_id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci;

-- V10: Create webhook_subscriptions table.
-- event_types stores comma-separated WebhookEventType names.
CREATE TABLE webhook_subscriptions (
    id           VARCHAR(36)  NOT NULL,
    user_id      BIGINT       NOT NULL,
    target_url   VARCHAR(512) NOT NULL,
    bearer_token VARCHAR(512) NOT NULL,
    event_types  VARCHAR(512) NOT NULL COMMENT 'Comma-separated WebhookEventType names',
    admin_scope  BOOLEAN      NOT NULL DEFAULT FALSE,
    active       BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at   DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    CONSTRAINT fk_ws_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE,
    INDEX idx_ws_user_id (user_id),
    INDEX idx_ws_active  (active)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci;

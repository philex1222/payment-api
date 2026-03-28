-- =============================================================================
-- V1 — Initial schema
-- Matches the JPA entity definitions exactly so that Hibernate validation passes.
-- =============================================================================

CREATE TABLE IF NOT EXISTS users (
    id       BIGINT       NOT NULL AUTO_INCREMENT,
    username VARCHAR(255) NOT NULL,
    password VARCHAR(255) NOT NULL,
    role     VARCHAR(255) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_users_username (username)
);

CREATE TABLE IF NOT EXISTS payments (
    id                  VARCHAR(36)    NOT NULL,
    source_account      VARCHAR(255)   NOT NULL,
    destination_account VARCHAR(255)   NOT NULL,
    amount              DECIMAL(19, 4) NOT NULL,
    currency            VARCHAR(3)     NOT NULL,
    status              VARCHAR(20)    NOT NULL,
    created_at          DATETIME(6)    NOT NULL,
    updated_at          DATETIME(6),
    PRIMARY KEY (id)
);

CREATE TABLE IF NOT EXISTS transactions (
    id             VARCHAR(36)  NOT NULL,
    payment_id     VARCHAR(255) NOT NULL,
    status         VARCHAR(20)  NOT NULL,
    failure_reason VARCHAR(255),
    PRIMARY KEY (id)
);

CREATE TABLE IF NOT EXISTS audit_logs (
    id         BIGINT       NOT NULL AUTO_INCREMENT,
    payment_id VARCHAR(255) NOT NULL,
    event      VARCHAR(500) NOT NULL,
    timestamp  DATETIME(6)  NOT NULL,
    PRIMARY KEY (id)
);

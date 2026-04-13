-- V12: Expand bearer_token column to accommodate AES-256-GCM encrypted values.
-- A 512-char plaintext token produces approximately 720 chars after encryption.
-- V13 (Java migration) encrypts any existing plaintext values in-place.
ALTER TABLE webhook_subscriptions
    MODIFY COLUMN bearer_token VARCHAR(1024) NOT NULL;

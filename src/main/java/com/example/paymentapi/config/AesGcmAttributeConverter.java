package com.example.paymentapi.config;

import jakarta.annotation.PostConstruct;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;

/**
 * JPA AttributeConverter that transparently encrypts/decrypts webhook bearer tokens
 * using AES-256-GCM. Each encryption generates a fresh 12-byte IV stored as the
 * first 12 bytes of the base64-encoded ciphertext.
 *
 * <p>Configure via {@code webhook.encryption.secret-key} (base64-encoded 32-byte key).
 * Local/test profiles may opt into a fixed development key with
 * {@code webhook.encryption.allow-dev-key=true}; production must provide a key.</p>
 */
@Converter
@Component
public class AesGcmAttributeConverter implements AttributeConverter<String, String> {

    private static final int GCM_IV_LENGTH = 12;
    private static final int GCM_TAG_LENGTH_BITS = 128;
    private static final int AES_256_KEY_LENGTH_BYTES = 32;
    private static final String ALGORITHM = "AES/GCM/NoPadding";

    // Fixed 32-byte dev key (all zeros encoded as base64). NEVER use in production.
    private static final String DEV_KEY_BASE64 = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=";

    @Value("${webhook.encryption.secret-key:}")
    private String secretKeyBase64;

    @Value("${webhook.encryption.allow-dev-key:false}")
    private boolean allowDevKey;

    private volatile SecretKey resolvedKey;

    @PostConstruct
    void init() {
        this.resolvedKey = buildKey();
    }

    @Override
    public String convertToDatabaseColumn(String plaintext) {
        if (plaintext == null) return null;
        try {
            byte[] iv = new byte[GCM_IV_LENGTH];
            new SecureRandom().nextBytes(iv);
            Cipher cipher = Cipher.getInstance(ALGORITHM);
            cipher.init(Cipher.ENCRYPT_MODE, resolveKey(),
                    new GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv));
            byte[] ciphertext = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            byte[] result = new byte[iv.length + ciphertext.length];
            System.arraycopy(iv, 0, result, 0, iv.length);
            System.arraycopy(ciphertext, 0, result, iv.length, ciphertext.length);
            return Base64.getEncoder().encodeToString(result);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to encrypt bearer token", e);
        }
    }

    @Override
    public String convertToEntityAttribute(String encrypted) {
        if (encrypted == null) return null;
        try {
            byte[] decoded = Base64.getDecoder().decode(encrypted);
            byte[] iv = Arrays.copyOfRange(decoded, 0, GCM_IV_LENGTH);
            byte[] ciphertext = Arrays.copyOfRange(decoded, GCM_IV_LENGTH, decoded.length);
            Cipher cipher = Cipher.getInstance(ALGORITHM);
            cipher.init(Cipher.DECRYPT_MODE, resolveKey(),
                    new GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv));
            return new String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to decrypt bearer token", e);
        }
    }

    private SecretKey resolveKey() {
        SecretKey key = resolvedKey;
        if (key == null) {
            key = buildKey();
            resolvedKey = key;
        }
        return key;
    }

    private SecretKey buildKey() {
        String keyStr;
        if (secretKeyBase64 != null && !secretKeyBase64.isBlank()) {
            keyStr = secretKeyBase64;
        } else if (allowDevKey) {
            keyStr = DEV_KEY_BASE64;
        } else {
            throw new IllegalStateException(
                    "webhook.encryption.secret-key must be set to a base64-encoded 32-byte key");
        }

        byte[] keyBytes;
        try {
            keyBytes = Base64.getDecoder().decode(keyStr);
        } catch (IllegalArgumentException ex) {
            throw new IllegalStateException(
                    "webhook.encryption.secret-key must be valid base64", ex);
        }
        if (keyBytes.length != AES_256_KEY_LENGTH_BYTES) {
            throw new IllegalStateException(
                    "webhook.encryption.secret-key must decode to exactly 32 bytes");
        }
        return new SecretKeySpec(keyBytes, "AES");
    }
}

package com.example.paymentapi.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AesGcmAttributeConverterTest {

    private AesGcmAttributeConverter converter;

    @BeforeEach
    void setUp() {
        converter = new AesGcmAttributeConverter();
        ReflectionTestUtils.setField(converter, "allowDevKey", true);
        ReflectionTestUtils.invokeMethod(converter, "init");
    }

    @Test
    void roundTrip_encryptThenDecrypt() {
        String plaintext = "my-webhook-bearer-token-secret";
        String encrypted = converter.convertToDatabaseColumn(plaintext);

        assertThat(encrypted).isNotEqualTo(plaintext);
        assertThat(encrypted).isNotBlank();

        String decrypted = converter.convertToEntityAttribute(encrypted);
        assertThat(decrypted).isEqualTo(plaintext);
    }

    @Test
    void eachEncryptionProducesUniqueOutput_dueToRandomIv() {
        String plaintext = "same-token";
        String enc1 = converter.convertToDatabaseColumn(plaintext);
        String enc2 = converter.convertToDatabaseColumn(plaintext);

        assertThat(enc1).isNotEqualTo(enc2);
        assertThat(converter.convertToEntityAttribute(enc1)).isEqualTo(plaintext);
        assertThat(converter.convertToEntityAttribute(enc2)).isEqualTo(plaintext);
    }

    @Test
    void nullInput_returnsNull() {
        assertThat(converter.convertToDatabaseColumn(null)).isNull();
        assertThat(converter.convertToEntityAttribute(null)).isNull();
    }

    @Test
    void encryptedValueIsBase64Encoded() {
        String encrypted = converter.convertToDatabaseColumn("token");
        // Base64 characters only: A-Z, a-z, 0-9, +, /, =
        assertThat(encrypted).matches("[A-Za-z0-9+/=]+");
    }

    @Test
    void customKey_roundTripsSuccessfully() {
        AesGcmAttributeConverter custom = new AesGcmAttributeConverter();
        byte[] key = new byte[32];
        for (int i = 0; i < 32; i++) key[i] = (byte) i;
        ReflectionTestUtils.setField(custom, "secretKeyBase64", Base64.getEncoder().encodeToString(key));
        ReflectionTestUtils.invokeMethod(custom, "init");
        String plaintext = "custom-key-token";
        String encrypted = custom.convertToDatabaseColumn(plaintext);
        assertThat(custom.convertToEntityAttribute(encrypted)).isEqualTo(plaintext);
    }

    @Test
    void decrypt_withMalformedBase64_throwsIllegalState() {
        assertThatThrownBy(() -> converter.convertToEntityAttribute("!!!not-base64!!!"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("decrypt");
    }

    @Test
    void decrypt_withTamperedCiphertext_throwsIllegalState() {
        String encrypted = converter.convertToDatabaseColumn("some-token");
        // Replace last char to corrupt the GCM tag
        String tampered = encrypted.substring(0, encrypted.length() - 2) + "AA";
        assertThatThrownBy(() -> converter.convertToEntityAttribute(tampered))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void encrypt_withInvalidKey_throwsIllegalState() {
        AesGcmAttributeConverter badKey = new AesGcmAttributeConverter();
        ReflectionTestUtils.setField(badKey, "secretKeyBase64", "@@@not-valid-base64@@@");
        assertThatThrownBy(() -> badKey.convertToDatabaseColumn("x"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("encrypt");
    }

    @Test
    void init_blankKeyWithoutDevFallback_throwsIllegalState() {
        AesGcmAttributeConverter strict = new AesGcmAttributeConverter();
        ReflectionTestUtils.setField(strict, "allowDevKey", false);
        assertThatThrownBy(() -> ReflectionTestUtils.invokeMethod(strict, "init"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("webhook.encryption.secret-key");
    }

    @Test
    void init_keyWithWrongLength_throwsIllegalState() {
        AesGcmAttributeConverter strict = new AesGcmAttributeConverter();
        ReflectionTestUtils.setField(strict, "secretKeyBase64",
                Base64.getEncoder().encodeToString(new byte[16]));
        assertThatThrownBy(() -> ReflectionTestUtils.invokeMethod(strict, "init"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("exactly 32 bytes");
    }
}

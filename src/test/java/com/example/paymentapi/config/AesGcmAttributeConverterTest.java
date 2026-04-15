package com.example.paymentapi.config;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AesGcmAttributeConverterTest {

    // Uses the default dev key (secretKeyBase64 is null → DEV_KEY used internally)
    private final AesGcmAttributeConverter converter = new AesGcmAttributeConverter();

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
}

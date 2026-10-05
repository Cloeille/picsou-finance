package com.picsou.config;

import org.junit.jupiter.api.Test;

import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CryptoEncryptionTest {

    private static final String KEY_256 = Base64.getEncoder().encodeToString(new byte[32]);

    private CryptoEncryption crypto() {
        return new CryptoEncryption(KEY_256);
    }

    @Test
    void roundTrips_asciiValue() {
        CryptoEncryption crypto = crypto();
        String secret = "sk_live_0123456789";

        assertThat(crypto.decrypt(crypto.encrypt(secret))).isEqualTo(secret);
    }

    @Test
    void roundTripsNonAsciiValue() {
        // Same-JVM round-trip smoke test for non-ASCII secrets. Note: this can't by
        // itself distinguish the fix from the bug — with a *single* consistent
        // charset both encrypt and decrypt agree, so it passes either way. The real
        // value of pinning UTF-8 is deterministic behaviour across JVMs / interop;
        // this just guards that non-ASCII input survives the round-trip at all.
        CryptoEncryption crypto = crypto();
        String secret = "clé-très-secrète_日本語_🔐";

        assertThat(crypto.decrypt(crypto.encrypt(secret))).isEqualTo(secret);
    }

    @Test
    void producesDifferentCiphertextEachTime_randomNonce() {
        CryptoEncryption crypto = crypto();

        assertThat(crypto.encrypt("same-input")).isNotEqualTo(crypto.encrypt("same-input"));
    }

    @Test
    void nullValuesPassThrough() {
        CryptoEncryption crypto = crypto();

        assertThat(crypto.encrypt(null)).isNull();
        assertThat(crypto.decrypt(null)).isNull();
    }

    @Test
    void rejectsBlankKey() {
        assertThatThrownBy(() -> new CryptoEncryption("  "))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("CRYPTO_ENCRYPTION_KEY is required");
    }

    @Test
    void rejectsInvalidBase64Key() {
        assertThatThrownBy(() -> new CryptoEncryption("not base64!!!"))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("not valid base64");
    }

    @Test
    void rejectsWrongKeyLength() {
        String twentyBytes = Base64.getEncoder().encodeToString(new byte[20]);

        assertThatThrownBy(() -> new CryptoEncryption(twentyBytes))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("16, 24 or 32 bytes")
            .hasMessageContaining("got 20 bytes");
    }

    @Test
    void acceptsAes128And192KeyLengths() {
        // 16 and 24-byte keys are valid AES sizes; only lengths outside {16,24,32} fail.
        assertThat(new CryptoEncryption(Base64.getEncoder().encodeToString(new byte[16]))).isNotNull();
        assertThat(new CryptoEncryption(Base64.getEncoder().encodeToString(new byte[24]))).isNotNull();
    }
}

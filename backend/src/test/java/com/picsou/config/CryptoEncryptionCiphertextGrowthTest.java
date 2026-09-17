package com.picsou.config;

import org.junit.jupiter.api.Test;

import java.security.SecureRandom;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins how much {@link CryptoEncryption} inflates a value, because that number is what sizes
 * (or, since V86, stops sizing) the columns encrypted values land in.
 *
 * <p>The ciphertext is {@code IV (12 bytes) + AES-GCM output (plaintext length) + tag (16
 * bytes)}, Base64-encoded: {@code 4 * ceil((n + 28) / 3)} characters for {@code n} plaintext
 * bytes. So a {@code VARCHAR(2000)} column silently capped the plaintext at 1472 bytes, and the
 * Trade Republic session token crossing that line is what #115 looked like. Runs without
 * Docker, unlike the migration test that checks the columns themselves.
 */
class CryptoEncryptionCiphertextGrowthTest {

    private final CryptoEncryption encryption = new CryptoEncryption(randomKey());

    @Test
    void ciphertextLengthIsFourThirdsOfPlaintextPlusTwentyEightBytes() {
        for (int n : new int[] {0, 1, 2, 3, 100, 347, 348, 1472, 1473, 2972, 2973, 4000}) {
            String plain = "t".repeat(n); // ASCII: one byte per char
            int expected = 4 * ((n + 28 + 2) / 3); // 4 * ceil((n + 28) / 3)
            assertThat(encryption.encrypt(plain))
                .as("ciphertext length for %d plaintext bytes", n)
                .hasSize(expected);
        }
    }

    @Test
    void aTokenOf1473BytesIsTheSmallestThatOverflowedVarchar2000() {
        assertThat(encryption.encrypt("t".repeat(1472))).hasSize(2000);
        assertThat(encryption.encrypt("t".repeat(1473))).hasSize(2004);
    }

    @Test
    void theOtherFormerBoundsCappedPlaintextAt347And2972Bytes() {
        // VARCHAR(500): crypto_exchange_session.api_key / api_secret, still bounded on purpose.
        assertThat(encryption.encrypt("t".repeat(347))).hasSize(500);
        assertThat(encryption.encrypt("t".repeat(348))).hasSize(504);
        // VARCHAR(4000): trade_republic_session.refresh_token and degiro_session.session_blob before V86.
        assertThat(encryption.encrypt("t".repeat(2972))).hasSize(4000);
        assertThat(encryption.encrypt("t".repeat(2973))).hasSize(4004);
    }

    private static String randomKey() {
        byte[] key = new byte[32];
        new SecureRandom().nextBytes(key);
        return Base64.getEncoder().encodeToString(key);
    }
}

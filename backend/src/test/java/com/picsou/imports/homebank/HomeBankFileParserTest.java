package com.picsou.imports.homebank;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.zip.Deflater;
import java.util.zip.DeflaterOutputStream;
import javax.crypto.Cipher;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class HomeBankFileParserTest {

    private final HomeBankFileParser parser = new HomeBankFileParser(new ObjectMapper());

    @Test
    void parsesRawDeflateAndPreservesExactAmountsAndLeapDate() throws Exception {
        byte[] json = fixture();
        byte[] compressed = rawDeflate(json);

        ParsedHomeBankData parsed = parser.parse(compressed, "test.HBK", null);

        assertThat(parsed.accounts()).hasSize(1);
        assertThat(parsed.accounts().get(0).initialBalance()).isEqualByComparingTo(new BigDecimal("1234.56789012"));
        assertThat(parsed.transactions()).hasSize(1);
        assertThat(parsed.transactions().get(0).amount()).isEqualByComparingTo(new BigDecimal("-12.34000001"));
        assertThat(parsed.transactions().get(0).date()).hasToString("2024-02-29");
        assertThat(parsed.transactions().get(0).payee()).isEqualTo("Synthetic Shop");
        assertThat(parsed.transactions().get(0).notes()).isNull();
        assertThatThrownBy(() -> parsed.accounts().clear()).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void decryptsVersionOneAesGcmExportWithUnicodePassword() throws Exception {
        byte[] encrypted = encryptedExport(rawDeflate(fixture()), "sécurité🔐");

        ParsedHomeBankData parsed = parser.parse(encrypted, "sample.hbexport", "sécurité🔐");

        assertThat(parsed.transactions()).hasSize(1);
    }

    @Test
    void rejectsMissingOrIncorrectPasswordWithoutLeakingIt() throws Exception {
        byte[] encrypted = encryptedExport(rawDeflate(fixture()), "very-secret");

        assertThatThrownBy(() -> parser.parse(encrypted, "sample.hbexport", null))
                .hasMessage("Password required for HomeBank export");
        assertThatThrownBy(() -> parser.parse(encrypted, "sample.hbexport", "wrong-password"))
                .hasMessage("Incorrect password or corrupted HomeBank export")
                .hasMessageNotContaining("wrong-password");
        assertThatThrownBy(() -> parser.parse(encrypted, "sample.hbexport", "x".repeat(1025)))
                .hasMessage("HomeBank password is too long");
    }

    @Test
    void rejectsTamperedEncryptedPayloadAndUnknownVersion() throws Exception {
        byte[] encrypted = encryptedExport(rawDeflate(fixture()), "secret");
        encrypted[encrypted.length - 1] ^= 1;
        assertThatThrownBy(() -> parser.parse(encrypted, "sample.hbexport", "secret"))
                .hasMessage("Incorrect password or corrupted HomeBank export");
        byte[] unknownVersion = encryptedExport(rawDeflate(fixture()), "secret");
        unknownVersion[3] = 2;
        assertThatThrownBy(() -> parser.parse(unknownVersion, "sample.hbexport", "secret"))
                .hasMessage("Unsupported HomeBank export encryption version");
    }

    @Test
    void rejectsDuplicateKeysInvalidReferencesDatesAndPrecision() throws Exception {
        String valid = new String(fixture(), StandardCharsets.UTF_8);
        assertInvalid(valid.replace("\"name\":\"Synthetic Main\"", "\"name\":\"Synthetic Main\",\"name\":\"Other\""));
        assertInvalid(valid.replace("a12b3c4d-1111-4111-8111-111111111111\",\"amount\"", "e12b3c4d-1111-4111-8111-111111111111\",\"amount\""));
        assertInvalid(valid.replace("\"day\":29", "\"day\":30"));
        assertInvalid(valid.replace("-12.34000001", "-12.340000001"));
        assertInvalid(valid.replace("\"schemaVersion\":3", "\"schemaVersion\":4"));
        assertInvalid(valid.replace("\"isForecast\":false", "\"isForecast\":false,\"splits\":[{}]"));
        assertInvalid(valid.replace("\"currencyCode\":\"EUR\"},\"date\"", "\"currencyCode\":\"USD\"},\"date\""));
        assertInvalid(valid.replace("\"name\":\"Synthetic Food\",\"isIncomeType\"",
                "\"name\":\"Synthetic Food\",\"parentID\":\"b12b3c4d-1111-4111-8111-111111111111\",\"isIncomeType\""));
        assertInvalid(valid + "{}");
    }

    @Test
    void rejectsSourceArraysAboveTheirSupportedLimits() throws Exception {
        String valid = new String(fixture(), StandardCharsets.UTF_8);
        assertLimitExceeded(withRows(valid, "accounts", 101, "a12b3c4d-1111-4111-8111-111111111111"));
        assertLimitExceeded(withRows(valid, "categories", 201, "b12b3c4d-1111-4111-8111-111111111111"));
        assertLimitExceeded(withRows(valid, "payees", 10_001, "c12b3c4d-1111-4111-8111-111111111111"));
        assertLimitExceeded(withRows(valid, "transactions", 25_001, "d12b3c4d-1111-4111-8111-111111111111"));
    }

    @Test
    void rejectsOversizedSelectedStringsAndDeepUnknownMetadata() throws Exception {
        String valid = new String(fixture(), StandardCharsets.UTF_8);
        assertInvalid(valid.replace("Synthetic Main", "x".repeat(101)));
        assertInvalid(valid.replace("Synthetic Bank", "x".repeat(101)));
        assertInvalid(valid.replace("Synthetic Food", "x".repeat(101)));
        assertInvalid(valid.replace("Synthetic Shop", "x".repeat(256)));
        assertInvalid(valid.replace("\"isForecast\":false", "\"memo\":\"" + "x".repeat(256)
                + "\",\"isForecast\":false"));
        assertInvalid(valid.replace("\"manifest\":", "\"metadata\":{" + "\"nested\":{".repeat(64)
                + "\"x\":true" + "}".repeat(64) + "},\"manifest\":"));
    }

    @Test
    void rejectsDuplicateKeysInsideIgnoredMetadataButAcceptsBoundedUnusedMetadata() throws Exception {
        String valid = new String(fixture(), StandardCharsets.UTF_8);
        assertInvalid(valid.replace("\"manifest\":", "\"metadata\":{\"x\":1,\"x\":2},\"manifest\":"));
        String withUnused = valid.replace("\"manifest\":", "\"unused\":[{\"text\":\"" + "x".repeat(4096)
                + "\"}],\"manifest\":");
        ParsedHomeBankData parsed = parser.parse(rawDeflate(withUnused.getBytes(StandardCharsets.UTF_8)), "sample.hbk", null);
        assertThat(parsed.transactions()).hasSize(1);
    }

    @Test
    void rejectsInvalidExtensionTruncatedAndTrailingCompressedData() throws Exception {
        byte[] compressed = rawDeflate(fixture());
        assertThatThrownBy(() -> parser.parse(compressed, "sample.zip", null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> parser.parse(java.util.Arrays.copyOf(compressed, compressed.length - 1), "sample.hbk", null))
                .isInstanceOf(IllegalArgumentException.class);
        byte[] trailing = java.util.Arrays.copyOf(compressed, compressed.length + 1);
        assertThatThrownBy(() -> parser.parse(trailing, "sample.hbk", null)).isInstanceOf(IllegalArgumentException.class);
    }

    private void assertInvalid(String json) throws Exception {
        assertThatThrownBy(() -> parser.parse(rawDeflate(json.getBytes(StandardCharsets.UTF_8)), "sample.hbk", null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private void assertLimitExceeded(String json) throws Exception {
        assertThatThrownBy(() -> parser.parse(rawDeflate(json.getBytes(StandardCharsets.UTF_8)), "sample.hbk", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("HomeBank list exceeds the supported limit");
    }

    private static String withRows(String json, String field, int count, String firstId) {
        String array = "\"" + field + "\":[";
        int start = json.indexOf(array) + array.length();
        int end = field.equals("transactions") ? json.indexOf("]}", start) : json.indexOf("],\"", start);
        String template = json.substring(start, end);
        StringBuilder rows = new StringBuilder(template.length() * count);
        for (int i = 0; i < count; i++) {
            if (i > 0) rows.append(',');
            String id = String.format(java.util.Locale.ROOT, "%08x-1111-4111-8111-%012x", i + 1, i + 1);
            rows.append(template.replace("\"id\":\"" + firstId + "\"", "\"id\":\"" + id + "\""));
        }
        return json.substring(0, start) + rows + json.substring(end);
    }

    private static byte[] fixture() throws Exception {
        try (InputStream in = HomeBankFileParserTest.class.getResourceAsStream("/imports/homebank/synthetic-v3.json")) {
            if (in == null) throw new IllegalStateException("Synthetic HomeBank fixture missing");
            return in.readAllBytes();
        }
    }

    private static byte[] encryptedExport(byte[] compressed, String password) throws Exception {
        byte[] salt = new byte[32];
        byte[] nonce = new byte[12];
        SecureRandom random = new SecureRandom();
        random.nextBytes(salt);
        random.nextBytes(nonce);
        PBEKeySpec spec = new PBEKeySpec(password.toCharArray(), salt, 600_000, 256);
        byte[] key;
        try { key = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded(); }
        finally { spec.clearPassword(); }
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, nonce));
        byte[] ciphertext = cipher.doFinal(compressed);
        ByteBuffer output = ByteBuffer.allocate(4 + salt.length + nonce.length + ciphertext.length).order(ByteOrder.BIG_ENDIAN);
        output.putInt(1).put(salt).put(nonce).put(ciphertext);
        java.util.Arrays.fill(key, (byte) 0);
        return output.array();
    }

    private static byte[] rawDeflate(byte[] input) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        Deflater deflater = new Deflater(Deflater.DEFAULT_COMPRESSION, true);
        try (DeflaterOutputStream stream = new DeflaterOutputStream(bytes, deflater)) {
            stream.write(input);
        }
        return bytes.toByteArray();
    }
}

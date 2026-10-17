package com.picsou.imports.homebank;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class HomeBankRealFileSmokeTest {

    @Test
    void privateRawAndEncryptedExportsNormalizeIdenticallyWhenOptedIn() throws Exception {
        String fixtureDir = System.getProperty("picsou.homebank.fixtureDir");
        String password = System.getenv("PICSOU_HOMEBANK_PASSWORD");
        assumeTrue(fixtureDir != null && !fixtureDir.isBlank(), "Set picsou.homebank.fixtureDir to opt in");
        assumeTrue(password != null && !password.isEmpty(), "Set PICSOU_HOMEBANK_PASSWORD to opt in");
        Path directory = Path.of(fixtureDir);
        Path rawPath = directory.resolve("homebank-clear.hbk");
        Path encryptedPath = directory.resolve("homebank-encrypted.hbexport");
        assumeTrue(Files.isRegularFile(rawPath) && Files.isRegularFile(encryptedPath), "Private HomeBank fixtures are absent");

        HomeBankFileParser parser = new HomeBankFileParser(new ObjectMapper());
        ParsedHomeBankData raw = parser.parse(Files.readAllBytes(rawPath), rawPath.getFileName().toString(), null);
        ParsedHomeBankData encrypted = parser.parse(Files.readAllBytes(encryptedPath), encryptedPath.getFileName().toString(), password);

        // Never render private account/transaction objects in assertion diagnostics.
        assertThat(raw.equals(encrypted)).as("clear/encrypted normalized data equality").isTrue();
        System.out.printf("HomeBank smoke equality verified: accounts=%d categories=%d transactions=%d%n",
                raw.accounts().size(), raw.categories().size(), raw.transactions().size());
    }
}

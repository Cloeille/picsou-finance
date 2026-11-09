package com.picsou.model;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class WiseConnectionTest {

    private static final String CIPHERTEXT = "v1:Zm9vYmFyLWNpcGhlcnRleHQ=";

    @Test
    void serialization_neverIncludesTheStoredToken() throws Exception {
        WiseConnection connection = WiseConnection.builder()
            .id(5L).apiToken(CIPHERTEXT).status("CONNECTED").lastSyncedAt(Instant.parse("2026-11-03T06:00:00Z"))
            .build();

        String json = new ObjectMapper().registerModule(new JavaTimeModule()).writeValueAsString(connection);

        JsonNode tree = new ObjectMapper().readTree(json);
        assertThat(tree.has("apiToken")).isFalse();
        assertThat(json).doesNotContain(CIPHERTEXT);
        assertThat(tree.get("status").asText()).isEqualTo("CONNECTED");
    }
}

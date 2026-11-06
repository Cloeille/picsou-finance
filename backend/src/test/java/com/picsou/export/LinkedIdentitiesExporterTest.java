package com.picsou.export;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.picsou.model.AppUser;
import com.picsou.model.UserIdentity;
import com.picsou.repository.UserIdentityRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class LinkedIdentitiesExporterTest {

    private static final String HEADER = "id,issuer,subject,email,display_name,created_at,last_login_at";

    @Mock private UserIdentityRepository repository;

    private LinkedIdentitiesExporter exporter;
    private AppUser user;
    private final ExportContext ctx = new ExportContext(false);

    @BeforeEach
    void setUp() {
        exporter = new LinkedIdentitiesExporter(repository);
        user = AppUser.builder().id(7L).username("chloe").build();
    }

    private String csv() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        CsvWriter writer = new CsvWriter(out);
        writer.writeRow(exporter.csvHeader());
        exporter.writeCsv(user, ctx, writer);
        writer.flush();
        return out.toString(StandardCharsets.UTF_8).replace("\uFEFF", "");
    }

    private JsonNode json() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (JsonGenerator gen = new JsonFactory().createGenerator(out)) {
            exporter.writeJson(user, ctx, gen);
        }
        return new ObjectMapper().readTree(out.toByteArray());
    }

    private static UserIdentity identity() {
        UserIdentity identity = UserIdentity.builder()
            .id(3L).issuer("https://idp.example.com").subject("abc-123").email("c@example.com")
            .displayName("Chloé, Test").lastLoginAt(Instant.parse("2026-02-01T00:00:00Z")).build();
        ReflectionTestUtils.setField(identity, "createdAt", Instant.parse("2026-01-01T00:00:00Z"));
        return identity;
    }

    @Test
    void nameAndHeader() {
        assertThat(exporter.name()).isEqualTo("linked_identities");
        assertThat(String.join(",", exporter.csvHeader())).isEqualTo(HEADER);
    }

    @Test
    void emptyCase_isHeaderOnlyAndEmptyArray() throws Exception {
        when(repository.findByUserIdOrderByCreatedAtAsc(7L)).thenReturn(List.of());

        assertThat(csv()).isEqualTo(HEADER + "\r\n");
        JsonNode json = json();
        assertThat(json.isArray()).isTrue();
        assertThat(json).isEmpty();
    }

    @Test
    void csvAndJson_carryEveryColumn_forTheExportingUserOnly() throws Exception {
        when(repository.findByUserIdOrderByCreatedAtAsc(7L)).thenReturn(List.of(identity()));

        assertThat(csv()).isEqualTo(HEADER + "\r\n"
            + "3,https://idp.example.com,abc-123,c@example.com,\"Chloé, Test\","
            + "2026-01-01T00:00:00Z,2026-02-01T00:00:00Z\r\n");
        JsonNode row = json().get(0);
        assertThat(row.get("id").asLong()).isEqualTo(3L);
        assertThat(row.get("issuer").asText()).isEqualTo("https://idp.example.com");
        assertThat(row.get("subject").asText()).isEqualTo("abc-123");
        assertThat(row.get("email").asText()).isEqualTo("c@example.com");
        assertThat(row.get("display_name").asText()).isEqualTo("Chloé, Test");
        assertThat(row.get("created_at").asText()).isEqualTo("2026-01-01T00:00:00Z");
        assertThat(row.get("last_login_at").asText()).isEqualTo("2026-02-01T00:00:00Z");
        verify(repository, org.mockito.Mockito.times(2)).findByUserIdOrderByCreatedAtAsc(7L);
        verifyNoMoreInteractions(repository);
    }
}

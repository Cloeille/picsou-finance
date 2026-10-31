package com.picsou.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class InstanceStatusResponseTest {

    @Test
    void serialisesExplicitNullsAndEnumNames() throws Exception {
        ObjectMapper mapper = new ObjectMapper().setDefaultPropertyInclusion(JsonInclude.Include.NON_NULL);
        JsonNode emptyUpdate = mapper.readTree(mapper.writeValueAsString(new InstanceStatusResponse(
            "1.1.0",
            null,
            List.of(new SidecarIssue(SidecarIssueCode.SIDECAR_UNREACHABLE, "amex-auth", null)))));
        assertThat(emptyUpdate.get("currentVersion").asText()).isEqualTo("1.1.0");
        assertThat(emptyUpdate.has("update")).isTrue();
        assertThat(emptyUpdate.get("update").isNull()).isTrue();
        assertThat(emptyUpdate.get("issues").get(0).get("code").asText()).isEqualTo("SIDECAR_UNREACHABLE");
        assertThat(emptyUpdate.get("issues").get(0).get("sidecar").asText()).isEqualTo("amex-auth");
        assertThat(emptyUpdate.get("issues").get(0).has("expectedBase")).isTrue();
        assertThat(emptyUpdate.get("issues").get(0).get("expectedBase").isNull()).isTrue();

        JsonNode withUpdate = mapper.readTree(mapper.writeValueAsString(new InstanceStatusResponse(
            "1.1.0",
            new ReleaseUpdate("1.2.0", "https://github.com/Cloeille/picsou-finance/releases/tag/1.2.0"),
            List.of(new SidecarIssue(SidecarIssueCode.SIDECAR_OUTDATED, "bourso-auth", "sidecar-python")))));
        assertThat(withUpdate.get("update").get("latestVersion").asText()).isEqualTo("1.2.0");
        assertThat(withUpdate.get("update").get("releaseUrl").asText())
            .isEqualTo("https://github.com/Cloeille/picsou-finance/releases/tag/1.2.0");
        assertThat(withUpdate.get("issues").get(0).get("code").asText()).isEqualTo("SIDECAR_OUTDATED");
        assertThat(withUpdate.get("issues").get(0).get("expectedBase").asText()).isEqualTo("sidecar-python");
    }
}

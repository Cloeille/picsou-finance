package com.picsou.dto;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.picsou.model.ScpiPosition;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ScpiPositionResponseTest {
    @Test
    void from_exposesBothStoredProviderLinksToTheEditForm() {
        ScpiPosition position = ScpiPosition.builder()
            .corumFundCode("FUND-42").sofidyFundCode("DY").build();

        JsonNode json = new ObjectMapper().valueToTree(ScpiPositionResponse.from(position, null));

        assertThat(json.path("corumFundCode").asText()).isEqualTo("FUND-42");
        assertThat(json.path("sofidyFundCode").asText()).isEqualTo("DY");
    }

    @Test
    void from_exposesUnlinkedFundsAsExplicitNulls() {
        JsonNode json = new ObjectMapper().valueToTree(
            ScpiPositionResponse.from(ScpiPosition.builder().build(), null));

        assertThat(json.has("corumFundCode")).isTrue();
        assertThat(json.path("corumFundCode").isNull()).isTrue();
        assertThat(json.has("sofidyFundCode")).isTrue();
        assertThat(json.path("sofidyFundCode").isNull()).isTrue();
    }
}

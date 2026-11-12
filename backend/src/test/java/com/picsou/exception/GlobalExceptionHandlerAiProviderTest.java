package com.picsou.exception;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class GlobalExceptionHandlerAiProviderTest {

    @Test
    void missingAiProviderIsAConflictWithAStableCode() {
        var detail = new GlobalExceptionHandler().handleAiProviderNotConfigured(new AiProviderNotConfiguredException());

        assertThat(detail.getStatus()).isEqualTo(409);
        assertThat(detail.getProperties()).containsEntry("code", "AI_PROVIDER_NOT_CONFIGURED");
    }
}

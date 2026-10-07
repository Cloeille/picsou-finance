package com.picsou.exception;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class GlobalExceptionHandlerImportPreviewTest {

    @Test
    void importPreviewCapacityIsATooManyRequestsRefusalWithAFixedMessage() {
        GlobalExceptionHandler handler = new GlobalExceptionHandler();

        var detail = handler.handleImportPreviewCapacity(new ImportPreviewCapacityException());

        assertThat(detail.getStatus()).isEqualTo(429);
        assertThat(detail.getDetail()).isEqualTo(ImportPreviewCapacityException.MESSAGE);
        assertThat(detail.getDetail()).contains("try again");
    }
}

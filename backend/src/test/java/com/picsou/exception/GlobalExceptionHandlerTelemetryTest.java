package com.picsou.exception;

import com.picsou.telemetry.TelemetryService;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class GlobalExceptionHandlerTelemetryTest {

    @Test
    void unhandledException_isHandedToTelemetry_andStill500() {
        GlobalExceptionHandler handler = new GlobalExceptionHandler();
        TelemetryService telemetry = mock(TelemetryService.class);
        ReflectionTestUtils.setField(handler, "telemetry", telemetry);
        RuntimeException boom = new RuntimeException("boom");

        var detail = handler.handleGeneric(boom);

        assertThat(detail.getStatus()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR.value());
        assertThat(detail.getDetail()).isEqualTo("An unexpected error occurred");
        verify(telemetry).captureServerError(boom);
    }

    @Test
    void unhandledException_withoutTelemetryBean_stillAnswers500() {
        var detail = new GlobalExceptionHandler().handleGeneric(new RuntimeException("boom"));

        assertThat(detail.getStatus()).isEqualTo(500);
    }
}

package com.picsou.exception;

import com.picsou.port.BourseDirectErrorCode;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class GlobalExceptionHandlerSyncTest {

    @Test
    void codedSyncErrorExposesItsMachineCodeInProblemDetail() {
        GlobalExceptionHandler handler = new GlobalExceptionHandler();
        SyncException exception = new SyncException(
            "Bourse Direct returned an incomplete portfolio",
            null,
            BourseDirectErrorCode.PORTFOLIO_INCOMPLETE.name()
        );

        var detail = handler.handleSync(exception);

        assertThat(detail.getStatus()).isEqualTo(422);
        assertThat(detail.getDetail()).isEqualTo("Bourse Direct returned an incomplete portfolio");
        assertThat(detail.getProperties())
            .containsEntry("code", BourseDirectErrorCode.PORTFOLIO_INCOMPLETE.name());
    }
}

package com.picsou.controller;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.picsou.config.RateLimitConfig;
import com.picsou.dto.ExportRequest;
import com.picsou.export.DataExportService;
import com.picsou.model.AppUser;
import com.picsou.service.ReAuthService;
import io.github.bucket4j.Bucket;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockHttpServletRequest;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class MeExportControllerTest {

    private ListAppender<ILoggingEvent> logs;
    private ch.qos.logback.classic.Logger logger;

    @BeforeEach
    void captureLogs() {
        logger = (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(MeExportController.class);
        logs = new ListAppender<>();
        logs.start();
        logger.addAppender(logs);
    }

    @AfterEach
    void releaseLogs() {
        logger.detachAppender(logs);
    }

    @Test
    void export_aUsernameWithALineBreak_staysOnOneLogLine_andOutOfTheHeader() {
        Map<String, Bucket> buckets = new HashMap<>();
        var controller = new MeExportController(mock(DataExportService.class), mock(ReAuthService.class), buckets);
        AppUser user = AppUser.builder().id(7L).username("alice\r\nexport.requested userId=1").build();

        var response = controller.export(user, new ExportRequest(null, false), new MockHttpServletRequest());

        assertThat(logs.list).filteredOn(e -> e.getLevel() == Level.WARN).singleElement()
            .satisfies(e -> assertThat(e.getFormattedMessage())
                .contains("username=alice?export.requested userId=1")
                .doesNotContain("\r", "\n"));
        assertThat(response.getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION))
            .startsWith("attachment; filename=\"picsou-export-alice__export.requested_userId_1-")
            .endsWith(".zip\"");
    }

    @Test
    void export_rateLimited_logsTheRemoteAddressOnOneLine() {
        Map<String, Bucket> buckets = new HashMap<>();
        Bucket drained = RateLimitConfig.createExportBucket();
        while (drained.tryConsume(1)) { }
        buckets.put("7", drained);
        var controller = new MeExportController(mock(DataExportService.class), mock(ReAuthService.class), buckets);
        var request = new MockHttpServletRequest();
        request.setRemoteAddr("10.0.0.1\nforged");

        controller.export(AppUser.builder().id(7L).username("alice").build(), new ExportRequest(null, false), request);

        assertThat(logs.list).singleElement()
            .satisfies(e -> assertThat(e.getFormattedMessage()).endsWith("ip=10.0.0.1?forged"));
    }
}

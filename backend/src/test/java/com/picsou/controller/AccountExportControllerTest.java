package com.picsou.controller;

import com.picsou.config.RateLimitConfig;
import com.picsou.dto.AccountsExportRequest;
import com.picsou.export.xlsx.AccountsWorkbookService;
import com.picsou.export.xlsx.SheetLabels;
import com.picsou.model.AppUser;
import com.picsou.service.UserContext;
import io.github.bucket4j.Bucket;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AccountExportControllerTest {

    private static final String XLSX_MIME =
        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";

    @Mock AccountsWorkbookService workbookService;
    @Mock UserContext userContext;

    private Map<String, Bucket> buckets;
    private AccountExportController controller;
    private final HttpServletRequest httpRequest = new MockHttpServletRequest();

    @BeforeEach
    void setUp() {
        buckets = new HashMap<>();
        controller = new AccountExportController(workbookService, userContext, buckets);
        when(userContext.currentMemberId()).thenReturn(7L);
    }

    @Test
    void export_returnsAnXlsxAttachment() {
        ResponseEntity<StreamingResponseBody> response =
            controller.export(user(1L), request(List.of(1L, 2L)), httpRequest);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getHeaders().getContentType()).hasToString(XLSX_MIME);
        assertThat(response.getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION))
            .startsWith("attachment; filename=\"picsou-comptes-")
            .endsWith(".xlsx\"");
    }

    @Test
    void export_streamsTheRequestedAccountsForTheCallersMember() throws IOException {
        ResponseEntity<StreamingResponseBody> response =
            controller.export(user(1L), request(List.of(4L, 9L)), httpRequest);

        response.getBody().writeTo(new ByteArrayOutputStream());

        verify(workbookService).export(eq(List.of(4L, 9L)), eq(7L), any(SheetLabels.class), any());
    }

    @Test
    void export_passesTheClientLabelsThrough() throws IOException {
        AccountsExportRequest req =
            new AccountsExportRequest(List.of(1L), Map.of("quantity", "Quantité"));

        controller.export(user(1L), req, httpRequest).getBody().writeTo(new ByteArrayOutputStream());

        ArgumentCaptor<SheetLabels> labels = ArgumentCaptor.forClass(SheetLabels.class);
        verify(workbookService).export(any(), any(), labels.capture(), any());
        assertThat(labels.getValue().get(com.picsou.export.xlsx.LabelKey.QUANTITY))
            .isEqualTo("Quantité");
    }

    @Test
    void export_withoutLabels_stillBuildsAWorkbook() throws IOException {
        AccountsExportRequest req = new AccountsExportRequest(List.of(1L), null);

        controller.export(user(1L), req, httpRequest).getBody().writeTo(new ByteArrayOutputStream());

        ArgumentCaptor<SheetLabels> labels = ArgumentCaptor.forClass(SheetLabels.class);
        verify(workbookService).export(any(), any(), labels.capture(), any());
        assertThat(labels.getValue().get(com.picsou.export.xlsx.LabelKey.QUANTITY))
            .isEqualTo("Quantity");
    }

    @Test
    void export_returns429OnceTheHourlyQuotaIsSpent() {
        Bucket spent = RateLimitConfig.createAccountExportBucket();
        spent.tryConsumeAsMuchAsPossible();
        buckets.put("1", spent);

        ResponseEntity<StreamingResponseBody> response =
            controller.export(user(1L), request(List.of(1L)), httpRequest);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(response.getBody()).isNull();
    }

    @Test
    void export_quotaIsPerUser_notShared() {
        Bucket spent = RateLimitConfig.createAccountExportBucket();
        spent.tryConsumeAsMuchAsPossible();
        buckets.put("1", spent);

        ResponseEntity<StreamingResponseBody> other =
            controller.export(user(2L), request(List.of(1L)), httpRequest);

        assertThat(other.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void export_aMidStreamFailureIsSwallowed_becauseTheHeadersAreAlreadySent() throws IOException {
        doThrow(new IOException("disk full"))
            .when(workbookService).export(any(), any(), any(), any());

        StreamingResponseBody body =
            controller.export(user(1L), request(List.of(1L)), httpRequest).getBody();

        // Must not propagate: Spring has flushed the 200 and the content headers already, so
        // there is no way back to a JSON error response.
        OutputStream out = new ByteArrayOutputStream();
        body.writeTo(out);
    }

    @Test
    void export_doesNotTouchTheWorkbookServiceWhenRateLimited() throws IOException {
        Bucket spent = RateLimitConfig.createAccountExportBucket();
        spent.tryConsumeAsMuchAsPossible();
        buckets.put("1", spent);

        controller.export(user(1L), request(List.of(1L)), httpRequest);

        verify(workbookService, never()).export(any(), any(), any(), any());
    }

    private AccountsExportRequest request(List<Long> ids) {
        return new AccountsExportRequest(ids, Map.of());
    }

    private AppUser user(Long id) {
        AppUser user = new AppUser();
        user.setId(id);
        user.setUsername("lucas");
        return user;
    }
}

package com.picsou.controller;

import com.picsou.dto.AccountResponse;
import com.picsou.dto.IbkrConnectRequest;
import com.picsou.dto.IbkrConnectionStatusResponse;
import com.picsou.service.IbkrSyncService;
import com.picsou.service.UserContext;
import io.github.bucket4j.Bucket;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Controller-level coverage for {@code /api/ibkr/*}: delegation to the service and,
 * most importantly, per-IP rate-limit enforcement on {@code /sync} — the bucket
 * store is real (a plain map + the production bucket factory), only the service
 * and user context are mocked, mirroring {@link AuthControllerTest}'s style.
 */
@ExtendWith(MockitoExtension.class)
class IbkrControllerTest {

    private static final int SYNC_BUDGET_PER_MINUTE = 6;

    @Mock IbkrSyncService ibkrService;
    @Mock UserContext userContext;

    Map<String, Bucket> ibkrSyncBuckets;
    IbkrController controller;
    MockHttpServletRequest httpReq;

    @BeforeEach
    void setUp() {
        ibkrSyncBuckets = new HashMap<>();
        controller = new IbkrController(ibkrService, userContext, ibkrSyncBuckets);
        httpReq = new MockHttpServletRequest();
        httpReq.setRemoteAddr("10.0.0.5");
    }

    @Test
    void connect_delegatesToService_andReturns204() {
        when(userContext.currentMemberId()).thenReturn(42L);

        ResponseEntity<Void> res = controller.connect(new IbkrConnectRequest("flex-token", "1234567"));

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        verify(ibkrService).connect("flex-token", "1234567", 42L);
    }

    @Test
    void getStatus_delegatesToService() {
        when(userContext.currentMemberId()).thenReturn(42L);
        IbkrConnectionStatusResponse status =
            new IbkrConnectionStatusResponse(false, null, null, null, null);
        when(ibkrService.getConnectionStatus(42L)).thenReturn(status);

        assertThat(controller.getStatus()).isSameAs(status);
    }

    @Test
    void clearConnection_delegatesToService_andReturns204() {
        when(userContext.currentMemberId()).thenReturn(42L);

        ResponseEntity<Void> res = controller.clearConnection();

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        verify(ibkrService).deleteConnection(42L);
    }

    @Test
    void sync_returnsAccounts_whileWithinTheRateLimit() {
        when(userContext.currentMemberId()).thenReturn(42L);
        List<AccountResponse> accounts = List.of();
        when(ibkrService.sync(42L)).thenReturn(accounts);

        ResponseEntity<?> res = controller.sync(httpReq);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(res.getBody()).isSameAs(accounts);
    }

    /**
     * The per-IP budget is 6 sync calls per minute ({@code createIbkrSyncBucket}):
     * calls 1-6 must reach the service, call 7 must be rejected with 429 BEFORE
     * touching the service — the limiter protects the shared Flex token's own
     * server-side budget at IBKR.
     */
    @Test
    void sync_returns429_andSkipsTheService_onceThePerIpBudgetIsExhausted() {
        when(userContext.currentMemberId()).thenReturn(42L);
        when(ibkrService.sync(42L)).thenReturn(List.of());

        for (int i = 0; i < SYNC_BUDGET_PER_MINUTE; i++) {
            assertThat(controller.sync(httpReq).getStatusCode()).isEqualTo(HttpStatus.OK);
        }

        ResponseEntity<?> rejected = controller.sync(httpReq);

        assertThat(rejected.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(((ProblemDetail) rejected.getBody()).getDetail()).contains("Too many sync requests");
        verify(ibkrService, times(SYNC_BUDGET_PER_MINUTE)).sync(42L);
    }

    /**
     * Service failures must propagate untouched: the controller does no catching, so
     * a SyncException reaches GlobalExceptionHandler (mapped there, not here) instead
     * of being swallowed or rewrapped. Pinned by identity, not just by type.
     */
    @Test
    void sync_propagatesServiceExceptions_untouched() {
        when(userContext.currentMemberId()).thenReturn(42L);
        com.picsou.exception.SyncException boom =
            new com.picsou.exception.SyncException("Token expired. Please reconnect.");
        when(ibkrService.sync(42L)).thenThrow(boom);

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> controller.sync(httpReq))
            .isSameAs(boom);
    }

    /** The budget is per client IP: exhausting one IP must not throttle another. */
    @Test
    void sync_rateLimitIsKeyedPerClientIp() {
        when(userContext.currentMemberId()).thenReturn(42L);
        when(ibkrService.sync(42L)).thenReturn(List.of());

        for (int i = 0; i < SYNC_BUDGET_PER_MINUTE; i++) {
            controller.sync(httpReq);
        }
        assertThat(controller.sync(httpReq).getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);

        MockHttpServletRequest otherIp = new MockHttpServletRequest();
        otherIp.setRemoteAddr("10.0.0.6");
        assertThat(controller.sync(otherIp).getStatusCode()).isEqualTo(HttpStatus.OK);
    }
}

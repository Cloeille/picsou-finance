package com.picsou.controller;

import com.picsou.dto.AccountResponse;
import com.picsou.dto.SimplefinConnectRequest;
import com.picsou.dto.SimplefinConnectionStatusResponse;
import com.picsou.exception.GlobalExceptionHandler;
import com.picsou.exception.SyncException;
import com.picsou.service.SimplefinSyncService;
import com.picsou.service.UserContext;
import io.github.bucket4j.Bucket;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Controller-level coverage for {@code /api/simplefin/*}: the member id always comes from
 * {@link UserContext} (the scoping contract), the per-IP limiter shared by connect and sync,
 * and the HTTP error mapping (via {@link GlobalExceptionHandler}) never echoing a secret.
 *
 * <p>Pure Mockito for delegation and rate limiting, standalone MockMvc where the HTTP status
 * and body are the behavior under test (validation, SyncException mapping).
 */
@ExtendWith(MockitoExtension.class)
class SimplefinControllerTest {

    private static final long MEMBER_ID = 42L;
    private static final long OTHER_MEMBER_ID = 99L;
    /** Mirrors RateLimitConfig.createSimplefinRequestBucket(): 6 requests per minute per IP. */
    private static final int BUDGET_PER_MINUTE = 6;

    private static final String SETUP_TOKEN = "SECRET-SETUP-TOKEN-aGVsbG8";
    private static final String ACCESS_URL = "https://user1234:hunter2@beta-bridge.simplefin.org/simplefin";

    @Mock SimplefinSyncService simplefinService;
    @Mock UserContext userContext;

    SimplefinController controller;
    MockHttpServletRequest httpReq;
    MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        Map<String, Bucket> simplefinRequestBuckets = new HashMap<>();
        controller = new SimplefinController(simplefinService, userContext, simplefinRequestBuckets);
        httpReq = new MockHttpServletRequest();
        httpReq.setRemoteAddr("10.0.0.5");
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();
        lenient().when(userContext.currentMemberId()).thenReturn(MEMBER_ID);
    }

    // ── member scoping: each call carries the id of the principal making it ─────

    @Test
    void connect_followsThePrincipalAndReturns204() {
        when(userContext.currentMemberId()).thenReturn(1L, 2L);

        ResponseEntity<?> res = controller.connect(new SimplefinConnectRequest("t1"), httpReq);
        controller.connect(new SimplefinConnectRequest("t2"), httpReq);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        verify(simplefinService).connect("t1", 1L);
        verify(simplefinService).connect("t2", 2L);
        verifyNoMoreInteractions(simplefinService);
    }

    @Test
    void getStatus_followsThePrincipal() {
        SimplefinConnectionStatusResponse first =
            new SimplefinConnectionStatusResponse(true, 5L, "CONNECTED", Instant.EPOCH, "••••1234");
        SimplefinConnectionStatusResponse second =
            new SimplefinConnectionStatusResponse(true, 6L, "ERROR", Instant.EPOCH, "••••5678");
        when(userContext.currentMemberId()).thenReturn(1L, 2L);
        when(simplefinService.getConnectionStatus(1L)).thenReturn(first);
        when(simplefinService.getConnectionStatus(2L)).thenReturn(second);

        assertThat(controller.getStatus()).isSameAs(first);
        assertThat(controller.getStatus()).isSameAs(second);
        verifyNoMoreInteractions(simplefinService);
    }

    @Test
    void sync_followsThePrincipalAndReturnsTheAccounts() {
        List<AccountResponse> accounts = List.of();
        when(userContext.currentMemberId()).thenReturn(1L, 2L);
        when(simplefinService.sync(1L)).thenReturn(accounts);

        ResponseEntity<?> res = controller.sync(httpReq);
        controller.sync(httpReq);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(res.getBody()).isSameAs(accounts);
        verify(simplefinService).sync(2L);
        verifyNoMoreInteractions(simplefinService);
    }

    @Test
    void clearConnection_followsThePrincipalAndReturns204() {
        when(userContext.currentMemberId()).thenReturn(1L, 2L);

        ResponseEntity<Void> res = controller.clearConnection();
        controller.clearConnection();

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        verify(simplefinService).deleteConnection(1L);
        verify(simplefinService).deleteConnection(2L);
        verifyNoMoreInteractions(simplefinService);
    }

    /**
     * A member id smuggled in the query string or JSON body is ignored: the only source is
     * UserContext. (The admin-on-managed-profile override is UserContext's job.)
     */
    @Test
    void everyEndpoint_ignoresAMemberIdInTheQueryOrBody() throws Exception {
        String otherId = String.valueOf(OTHER_MEMBER_ID);

        mockMvc.perform(post("/api/simplefin/connect").param("memberId", otherId)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"token\":\"" + SETUP_TOKEN + "\",\"memberId\":" + OTHER_MEMBER_ID + "}"))
            .andExpect(status().isNoContent());
        mockMvc.perform(get("/api/simplefin/status").param("memberId", otherId)).andExpect(status().isOk());
        mockMvc.perform(post("/api/simplefin/sync").param("memberId", otherId)).andExpect(status().isOk());
        mockMvc.perform(delete("/api/simplefin/connection").param("memberId", otherId))
            .andExpect(status().isNoContent());

        verify(simplefinService).connect(SETUP_TOKEN, MEMBER_ID);
        verify(simplefinService).getConnectionStatus(MEMBER_ID);
        verify(simplefinService).sync(MEMBER_ID);
        verify(simplefinService).deleteConnection(MEMBER_ID);
        verifyNoMoreInteractions(simplefinService);
    }

    @Test
    void getStatus_withoutAConnection_omitsTheConnectionFields() throws Exception {
        when(simplefinService.getConnectionStatus(MEMBER_ID))
            .thenReturn(new SimplefinConnectionStatusResponse(false, null, null, null, null));

        mockMvc.perform(get("/api/simplefin/status"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.connected").value(false))
            .andExpect(jsonPath("$.connectionId").doesNotExist())
            .andExpect(jsonPath("$.maskedToken").doesNotExist());
    }

    // ── validation ──────────────────────────────────────────────────────────────

    @Test
    void connect_blankToken_returns422BeforeReachingTheService() throws Exception {
        mockMvc.perform(post("/api/simplefin/connect")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"token\":\"   \"}"))
            .andExpect(status().isUnprocessableEntity())
            .andExpect(jsonPath("$.errors.token").exists());

        verify(simplefinService, never()).connect(anyString(), anyLong());
    }

    @Test
    void connect_tokenOver4096Chars_returns422WithoutEchoingIt() throws Exception {
        String huge = "A".repeat(4097);

        mockMvc.perform(post("/api/simplefin/connect")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"token\":\"" + huge + "\"}"))
            .andExpect(status().isUnprocessableEntity())
            .andExpect(jsonPath("$.errors.token").exists())
            .andExpect(result -> assertThat(result.getResponse().getContentAsString()).doesNotContain(huge));

        verify(simplefinService, never()).connect(anyString(), anyLong());
    }

    @Test
    void connect_withoutABody_returns400BeforeReachingTheService() throws Exception {
        mockMvc.perform(post("/api/simplefin/connect").contentType(MediaType.APPLICATION_JSON))
            .andExpect(status().isBadRequest());

        verify(simplefinService, never()).connect(anyString(), anyLong());
    }

    // ── error mapping ───────────────────────────────────────────────────────────

    @Test
    void connect_claimFails_returns422WithoutEchoingTheSetupToken() throws Exception {
        doThrow(new SyncException("That does not look like a SimpleFIN setup token."))
            .when(simplefinService).connect(SETUP_TOKEN, MEMBER_ID);

        mockMvc.perform(post("/api/simplefin/connect")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"token\":\"" + SETUP_TOKEN + "\"}"))
            .andExpect(status().isUnprocessableEntity())
            .andExpect(jsonPath("$.detail").value("That does not look like a SimpleFIN setup token."))
            .andExpect(result -> assertThat(result.getResponse().getContentAsString()).doesNotContain(SETUP_TOKEN));
    }

    @Test
    void sync_serviceFailure_returns422WithoutLeakingTheCauseOrTheAccessUrl() throws Exception {
        // The wrapped cause carries the access URL (as an HTTP client error message might).
        when(simplefinService.sync(MEMBER_ID)).thenThrow(new SyncException(
            "SimpleFIN sync failed. Try again in a moment.",
            new IllegalStateException("GET " + ACCESS_URL + "/accounts failed")));

        mockMvc.perform(post("/api/simplefin/sync"))
            .andExpect(status().isUnprocessableEntity())
            .andExpect(jsonPath("$.detail").value("SimpleFIN sync failed. Try again in a moment."))
            .andExpect(jsonPath("$.code").doesNotExist())
            .andExpect(result -> assertThat(result.getResponse().getContentAsString())
                .doesNotContain("hunter2").doesNotContain("user1234")
                .doesNotContain("simplefin.org").doesNotContain("IllegalStateException"));
    }

    @Test
    void sync_unexpectedException_returnsAGeneric500WithoutItsMessage() throws Exception {
        when(simplefinService.sync(MEMBER_ID))
            .thenThrow(new IllegalStateException("decrypt failed for " + ACCESS_URL));

        mockMvc.perform(post("/api/simplefin/sync"))
            .andExpect(status().isInternalServerError())
            .andExpect(jsonPath("$.detail").value("An unexpected error occurred"))
            .andExpect(result -> assertThat(result.getResponse().getContentAsString())
                .doesNotContain("hunter2").doesNotContain(ACCESS_URL));
    }

    // ── rate limiting ───────────────────────────────────────────────────────────

    /** Connect and sync share one 6/min per-IP bucket; call 7 is rejected before the service. */
    @Test
    void connectAndSync_shareOnePerIpBudget_andTheSeventhCallIs429() {
        for (int i = 0; i < BUDGET_PER_MINUTE / 2; i++) {
            controller.connect(new SimplefinConnectRequest("t"), httpReq);
            controller.sync(httpReq);
        }

        ResponseEntity<?> rejectedSync = controller.sync(httpReq);
        ResponseEntity<?> rejectedConnect = controller.connect(new SimplefinConnectRequest("t"), httpReq);

        assertThat(rejectedSync.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(rejectedConnect.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(((ProblemDetail) rejectedConnect.getBody()).getDetail()).contains("Too many SimpleFIN requests");
        verify(simplefinService, times(BUDGET_PER_MINUTE / 2)).connect("t", MEMBER_ID);
        verify(simplefinService, times(BUDGET_PER_MINUTE / 2)).sync(MEMBER_ID);
    }

    /** Status and disconnect never touch the bridge, so they stay available when throttled. */
    @Test
    void statusAndDisconnect_whenThrottled_stayAvailable() {
        for (int i = 0; i <= BUDGET_PER_MINUTE; i++) controller.sync(httpReq);

        controller.getStatus();
        ResponseEntity<Void> cleared = controller.clearConnection();

        assertThat(cleared.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        verify(simplefinService).getConnectionStatus(MEMBER_ID);
        verify(simplefinService).deleteConnection(MEMBER_ID);
    }

    @Test
    void rateLimit_isKeyedPerClientIp() {
        for (int i = 0; i <= BUDGET_PER_MINUTE; i++) controller.sync(httpReq);
        MockHttpServletRequest otherIp = new MockHttpServletRequest();
        otherIp.setRemoteAddr("10.0.0.6");

        assertThat(controller.sync(otherIp).getStatusCode()).isEqualTo(HttpStatus.OK);
    }
}

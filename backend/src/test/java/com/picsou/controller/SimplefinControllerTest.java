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

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.time.Instant;
import java.util.Arrays;
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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
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
    /** Mirrors RateLimitConfig.createSimplefinBucket(): 6 requests per minute per IP. */
    private static final int BUDGET_PER_MINUTE = 6;

    private static final String SETUP_TOKEN = "SECRET-SETUP-TOKEN-aGVsbG8";
    private static final String ACCESS_URL = "https://user1234:hunter2@beta-bridge.simplefin.org/simplefin";

    @Mock SimplefinSyncService simplefinService;
    @Mock UserContext userContext;

    Map<String, Bucket> simplefinBuckets;
    SimplefinController controller;
    MockHttpServletRequest httpReq;
    MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        simplefinBuckets = new HashMap<>();
        controller = new SimplefinController(simplefinService, userContext, simplefinBuckets);
        httpReq = new MockHttpServletRequest();
        httpReq.setRemoteAddr("10.0.0.5");
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();
        lenient().when(userContext.currentMemberId()).thenReturn(MEMBER_ID);
    }

    // ── member scoping ──────────────────────────────────────────────────────────

    @Test
    void connect_claimsTheTokenForTheCurrentMember_andReturns204() {
        ResponseEntity<?> res = controller.connect(new SimplefinConnectRequest(SETUP_TOKEN), httpReq);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(res.getBody()).isNull();
        verify(simplefinService).connect(SETUP_TOKEN, MEMBER_ID);
        verifyNoMoreInteractions(simplefinService);
    }

    @Test
    void getStatus_readsTheCurrentMembersConnection() {
        SimplefinConnectionStatusResponse status =
            new SimplefinConnectionStatusResponse(true, 5L, "CONNECTED", Instant.EPOCH, "••••1234");
        when(simplefinService.getConnectionStatus(MEMBER_ID)).thenReturn(status);

        assertThat(controller.getStatus()).isSameAs(status);
        verifyNoMoreInteractions(simplefinService);
    }

    @Test
    void sync_syncsTheCurrentMembersConnection() {
        List<AccountResponse> accounts = List.of();
        when(simplefinService.sync(MEMBER_ID)).thenReturn(accounts);

        ResponseEntity<?> res = controller.sync(httpReq);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(res.getBody()).isSameAs(accounts);
        verifyNoMoreInteractions(simplefinService);
    }

    @Test
    void clearConnection_deletesTheCurrentMembersConnection_andReturns204() {
        ResponseEntity<Void> res = controller.clearConnection();

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        verify(simplefinService).deleteConnection(MEMBER_ID);
        verifyNoMoreInteractions(simplefinService);
    }

    /**
     * Two principals hit the same controller instance: every call must carry the id of the
     * principal making it, never the other's.
     */
    @Test
    void everyEndpoint_followsThePrincipal_notAPreviousCaller() {
        when(userContext.currentMemberId()).thenReturn(1L, 2L, 1L, 2L, 1L, 2L, 1L, 2L);
        when(simplefinService.sync(anyLong())).thenReturn(List.of());

        controller.connect(new SimplefinConnectRequest("t1"), httpReq);   // 1
        controller.connect(new SimplefinConnectRequest("t2"), httpReq);   // 2
        controller.getStatus();                                           // 1
        controller.getStatus();                                           // 2
        controller.sync(httpReq);                                         // 1
        controller.sync(httpReq);                                         // 2
        controller.clearConnection();                                     // 1
        controller.clearConnection();                                     // 2

        verify(simplefinService).connect("t1", 1L);
        verify(simplefinService).connect("t2", 2L);
        verify(simplefinService).getConnectionStatus(1L);
        verify(simplefinService).getConnectionStatus(2L);
        verify(simplefinService).sync(1L);
        verify(simplefinService).sync(2L);
        verify(simplefinService).deleteConnection(1L);
        verify(simplefinService).deleteConnection(2L);
        verifyNoMoreInteractions(simplefinService);
    }

    /**
     * A member id smuggled in the query string or JSON body is ignored by the controller: the
     * only source is UserContext. (The admin-on-managed-profile {@code ?memberId=} override is
     * UserContext's job and is covered by UserContextTest, identical to IBKR.)
     */
    @Test
    void aMemberIdInTheQueryOrBody_isNeverUsed() throws Exception {
        mockMvc.perform(post("/api/simplefin/connect")
                .param("memberId", String.valueOf(OTHER_MEMBER_ID))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"token\":\"" + SETUP_TOKEN + "\",\"memberId\":" + OTHER_MEMBER_ID + "}"))
            .andExpect(status().isNoContent());
        mockMvc.perform(get("/api/simplefin/status").param("memberId", String.valueOf(OTHER_MEMBER_ID)))
            .andExpect(status().isOk());
        mockMvc.perform(post("/api/simplefin/sync").param("memberId", String.valueOf(OTHER_MEMBER_ID)))
            .andExpect(status().isOk());
        mockMvc.perform(delete("/api/simplefin/connection").param("memberId", String.valueOf(OTHER_MEMBER_ID)))
            .andExpect(status().isNoContent());

        verify(simplefinService).connect(SETUP_TOKEN, MEMBER_ID);
        verify(simplefinService).getConnectionStatus(MEMBER_ID);
        verify(simplefinService).sync(MEMBER_ID);
        verify(simplefinService).deleteConnection(MEMBER_ID);
        verify(simplefinService, never()).connect(anyString(), org.mockito.ArgumentMatchers.eq(OTHER_MEMBER_ID));
        verify(simplefinService, never()).getConnectionStatus(OTHER_MEMBER_ID);
        verify(simplefinService, never()).sync(OTHER_MEMBER_ID);
        verify(simplefinService, never()).deleteConnection(OTHER_MEMBER_ID);
    }

    /** Structural guard: no handler method can bind a member id from the request. */
    @Test
    void noHandlerAcceptsAMemberIdFromTheRequest() {
        List<Method> handlers = Arrays.stream(SimplefinController.class.getDeclaredMethods())
            .filter(m -> java.lang.reflect.Modifier.isPublic(m.getModifiers()))
            .toList();
        assertThat(handlers).hasSize(4);
        for (Method m : handlers) {
            for (Parameter p : m.getParameters()) {
                assertThat(p.getType())
                    .as("%s parameter %s", m.getName(), p.getName())
                    .isIn(SimplefinConnectRequest.class, jakarta.servlet.http.HttpServletRequest.class);
            }
        }
        assertThat(Arrays.stream(SimplefinConnectRequest.class.getRecordComponents())
            .map(c -> c.getName()).toList()).containsExactly("token");
    }

    // ── no connection ───────────────────────────────────────────────────────────

    @Test
    void getStatus_forAMemberWithoutAConnection_reportsNotConnected() throws Exception {
        when(simplefinService.getConnectionStatus(MEMBER_ID))
            .thenReturn(new SimplefinConnectionStatusResponse(false, null, null, null, null));

        mockMvc.perform(get("/api/simplefin/status"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.connected").value(false))
            .andExpect(jsonPath("$.connectionId").doesNotExist())
            .andExpect(jsonPath("$.maskedToken").doesNotExist());
    }

    @Test
    void sync_forAMemberWithoutAConnection_returns422WithAFriendlyMessage() throws Exception {
        when(simplefinService.sync(MEMBER_ID)).thenThrow(
            new SyncException("No SimpleFIN connection. Connect with a setup token first."));

        mockMvc.perform(post("/api/simplefin/sync"))
            .andExpect(status().isUnprocessableEntity())
            .andExpect(jsonPath("$.detail").value("No SimpleFIN connection. Connect with a setup token first."));
    }

    /** Delete is idempotent: nothing to delete is still a 204, never a 404/500. */
    @Test
    void clearConnection_forAMemberWithoutAConnection_stillReturns204() throws Exception {
        when(simplefinService.deleteConnection(MEMBER_ID)).thenReturn(false);

        mockMvc.perform(delete("/api/simplefin/connection"))
            .andExpect(status().isNoContent())
            .andExpect(content().string(""));
    }

    // ── validation ──────────────────────────────────────────────────────────────

    @Test
    void connect_rejectsABlankToken_beforeReachingTheService() throws Exception {
        mockMvc.perform(post("/api/simplefin/connect")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"token\":\"   \"}"))
            .andExpect(status().isUnprocessableEntity())
            .andExpect(jsonPath("$.errors.token").exists());

        verify(simplefinService, never()).connect(anyString(), anyLong());
    }

    @Test
    void connect_rejectsATokenOver4096Chars_withoutEchoingIt() throws Exception {
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
    void connect_withoutABody_isRejected_andNeverReachesTheService() throws Exception {
        mockMvc.perform(post("/api/simplefin/connect").contentType(MediaType.APPLICATION_JSON))
            .andExpect(status().is4xxClientError());

        verify(simplefinService, never()).connect(anyString(), anyLong());
    }

    // ── error mapping ───────────────────────────────────────────────────────────

    /** A failed claim is a 422 carrying only the service's own message — not the token. */
    @Test
    void connect_whenTheClaimFails_returns422_withoutEchoingTheSetupToken() throws Exception {
        doThrow(new SyncException("That does not look like a SimpleFIN setup token."))
            .when(simplefinService).connect(SETUP_TOKEN, MEMBER_ID);

        mockMvc.perform(post("/api/simplefin/connect")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"token\":\"" + SETUP_TOKEN + "\"}"))
            .andExpect(status().isUnprocessableEntity())
            .andExpect(jsonPath("$.detail").value("That does not look like a SimpleFIN setup token."))
            .andExpect(result -> assertThat(result.getResponse().getContentAsString())
                .doesNotContain(SETUP_TOKEN));
    }

    @Test
    void sync_serviceFailure_returns422_withoutLeakingTheCauseOrTheAccessUrl() throws Exception {
        // The wrapped cause carries the access URL (as an HTTP client error message might).
        SyncException failure = new SyncException(
            "SimpleFIN sync failed. Try again in a moment.",
            new IllegalStateException("GET " + ACCESS_URL + "/accounts failed"));
        when(simplefinService.sync(MEMBER_ID)).thenThrow(failure);

        mockMvc.perform(post("/api/simplefin/sync"))
            .andExpect(status().isUnprocessableEntity())
            .andExpect(jsonPath("$.detail").value("SimpleFIN sync failed. Try again in a moment."))
            .andExpect(jsonPath("$.code").doesNotExist())
            .andExpect(result -> {
                String body = result.getResponse().getContentAsString();
                assertThat(body).doesNotContain("hunter2").doesNotContain("user1234")
                    .doesNotContain("simplefin.org").doesNotContain("IllegalStateException");
            });
    }

    @Test
    void sync_anUnexpectedException_returnsAGeneric500_withoutItsMessage() throws Exception {
        when(simplefinService.sync(MEMBER_ID))
            .thenThrow(new IllegalStateException("decrypt failed for " + ACCESS_URL));

        mockMvc.perform(post("/api/simplefin/sync"))
            .andExpect(status().isInternalServerError())
            .andExpect(jsonPath("$.detail").value("An unexpected error occurred"))
            .andExpect(result -> assertThat(result.getResponse().getContentAsString())
                .doesNotContain("hunter2").doesNotContain(ACCESS_URL));
    }

    /** The controller does no catching: the SyncException reaches the advice by identity. */
    @Test
    void sync_propagatesServiceExceptions_untouched() {
        SyncException boom = new SyncException("SimpleFIN refused the stored access.");
        when(simplefinService.sync(MEMBER_ID)).thenThrow(boom);

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> controller.sync(httpReq)).isSameAs(boom);
    }

    // ── rate limiting ───────────────────────────────────────────────────────────

    /** Connect and sync share one 6/min per-IP bucket; call 7 is rejected before the service. */
    @Test
    void connectAndSync_shareOnePerIpBudget_andTheSeventhCallIs429() {
        when(simplefinService.sync(MEMBER_ID)).thenReturn(List.of());

        for (int i = 0; i < BUDGET_PER_MINUTE / 2; i++) {
            assertThat(controller.connect(new SimplefinConnectRequest("t"), httpReq).getStatusCode())
                .isEqualTo(HttpStatus.NO_CONTENT);
            assertThat(controller.sync(httpReq).getStatusCode()).isEqualTo(HttpStatus.OK);
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
    void statusAndDisconnect_areNotRateLimited() {
        when(simplefinService.sync(MEMBER_ID)).thenReturn(List.of());
        for (int i = 0; i < BUDGET_PER_MINUTE; i++) controller.sync(httpReq);
        assertThat(controller.sync(httpReq).getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);

        controller.getStatus();
        assertThat(controller.clearConnection().getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

        verify(simplefinService).getConnectionStatus(MEMBER_ID);
        verify(simplefinService).deleteConnection(MEMBER_ID);
    }

    @Test
    void rateLimit_isKeyedPerClientIp() {
        when(simplefinService.sync(MEMBER_ID)).thenReturn(List.of());
        for (int i = 0; i < BUDGET_PER_MINUTE; i++) controller.sync(httpReq);
        assertThat(controller.sync(httpReq).getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);

        MockHttpServletRequest otherIp = new MockHttpServletRequest();
        otherIp.setRemoteAddr("10.0.0.6");

        assertThat(controller.sync(otherIp).getStatusCode()).isEqualTo(HttpStatus.OK);
    }
}

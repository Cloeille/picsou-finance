package com.picsou.controller;

import com.picsou.dto.AccountResponse;
import com.picsou.dto.WiseConnectRequest;
import com.picsou.dto.WiseConnectionStatusResponse;
import com.picsou.exception.GlobalExceptionHandler;
import com.picsou.exception.SyncException;
import com.picsou.service.UserContext;
import com.picsou.service.WiseSyncService;
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

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
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
 * {@code /api/wise/*}: the member always comes from {@link UserContext}, connect and sync share
 * one per-IP limiter, and errors never echo the token.
 */
@ExtendWith(MockitoExtension.class)
class WiseControllerTest {

    private static final long MEMBER_ID = 42L;
    private static final long OTHER_MEMBER_ID = 99L;
    /** Mirrors RateLimitConfig.createWiseRequestBucket(): 6 requests per minute per IP. */
    private static final int BUDGET_PER_MINUTE = 6;
    private static final String TOKEN = "0f1e2d3c-4b5a-6978-8796-a5b4c3d2e1f0";

    @Mock WiseSyncService wiseService;
    @Mock UserContext userContext;

    WiseController controller;
    MockHttpServletRequest httpReq;
    MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        Map<String, Bucket> buckets = new HashMap<>();
        controller = new WiseController(wiseService, userContext, buckets);
        httpReq = new MockHttpServletRequest();
        httpReq.setRemoteAddr("10.0.0.5");
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();
        lenient().when(userContext.currentMemberId()).thenReturn(MEMBER_ID);
    }

    @Test
    void connect_returnsTheImportedJarsOfThePrincipal() {
        List<AccountResponse> jars = List.of();
        when(wiseService.connect(TOKEN, MEMBER_ID)).thenReturn(jars);

        ResponseEntity<?> res = controller.connect(new WiseConnectRequest(TOKEN), httpReq);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(res.getBody()).isSameAs(jars);
    }

    @Test
    void everyEndpoint_ignoresAMemberIdInTheQueryOrBody() throws Exception {
        String otherId = String.valueOf(OTHER_MEMBER_ID);
        when(wiseService.getConnectionStatus(MEMBER_ID))
            .thenReturn(new WiseConnectionStatusResponse(false, null, null, null, null));

        mockMvc.perform(post("/api/wise/connect").param("memberId", otherId)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"token\":\"" + TOKEN + "\",\"memberId\":" + OTHER_MEMBER_ID + "}"))
            .andExpect(status().isOk());
        mockMvc.perform(get("/api/wise/status").param("memberId", otherId)).andExpect(status().isOk());
        mockMvc.perform(post("/api/wise/sync").param("memberId", otherId)).andExpect(status().isOk());
        mockMvc.perform(delete("/api/wise/connection").param("memberId", otherId))
            .andExpect(status().isNoContent());

        verify(wiseService).connect(TOKEN, MEMBER_ID);
        verify(wiseService).getConnectionStatus(MEMBER_ID);
        verify(wiseService).sync(MEMBER_ID);
        verify(wiseService).deleteConnection(MEMBER_ID);
        verifyNoMoreInteractions(wiseService);
    }

    @Test
    void connect_blankToken_returns422BeforeReachingTheService() throws Exception {
        mockMvc.perform(post("/api/wise/connect")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"token\":\"   \"}"))
            .andExpect(status().isUnprocessableEntity())
            .andExpect(jsonPath("$.errors.token").exists());

        verify(wiseService, never()).connect(anyString(), anyLong());
    }

    @Test
    void connect_tokenOverTheLimit_returns422WithoutEchoingIt() throws Exception {
        String huge = "a".repeat(513);

        mockMvc.perform(post("/api/wise/connect")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"token\":\"" + huge + "\"}"))
            .andExpect(status().isUnprocessableEntity())
            .andExpect(result -> assertThat(result.getResponse().getContentAsString()).doesNotContain(huge));

        verify(wiseService, never()).connect(anyString(), anyLong());
    }

    @Test
    void connect_refusedToken_returns422WithTheReauthCodeAndWithoutTheToken() throws Exception {
        when(wiseService.connect(TOKEN, MEMBER_ID)).thenThrow(new SyncException(
            "Wise refused the API token. It may have been revoked. Create a new token in Wise and connect again.",
            null, "SESSION_EXPIRED"));

        mockMvc.perform(post("/api/wise/connect")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"token\":\"" + TOKEN + "\"}"))
            .andExpect(status().isUnprocessableEntity())
            .andExpect(jsonPath("$.code").value("SESSION_EXPIRED"))
            .andExpect(result -> assertThat(result.getResponse().getContentAsString()).doesNotContain(TOKEN));
    }

    @Test
    void connectAndSync_shareOnePerIpBudget_andTheSeventhCallIs429() {
        for (int i = 0; i < BUDGET_PER_MINUTE / 2; i++) {
            controller.connect(new WiseConnectRequest(TOKEN), httpReq);
            controller.sync(httpReq);
        }

        ResponseEntity<?> rejected = controller.connect(new WiseConnectRequest(TOKEN), httpReq);

        assertThat(rejected.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(((ProblemDetail) rejected.getBody()).getDetail()).contains("Too many Wise requests");
        verify(wiseService, times(BUDGET_PER_MINUTE / 2)).connect(TOKEN, MEMBER_ID);
        verify(wiseService, times(BUDGET_PER_MINUTE / 2)).sync(MEMBER_ID);
    }

    @Test
    void statusAndDisconnect_whenThrottled_stayAvailable() {
        for (int i = 0; i <= BUDGET_PER_MINUTE; i++) controller.sync(httpReq);

        controller.getStatus();
        ResponseEntity<Void> cleared = controller.clearConnection();

        assertThat(cleared.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        verify(wiseService).getConnectionStatus(MEMBER_ID);
        verify(wiseService).deleteConnection(MEMBER_ID);
    }
}

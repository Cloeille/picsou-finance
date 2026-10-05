package com.picsou.controller;

import com.picsou.exception.GlobalExceptionHandler;
import com.picsou.exception.SyncException;
import com.picsou.model.BoursoSyncStatus;
import com.picsou.port.BoursoErrorCode;
import com.picsou.service.BoursoSyncService;
import com.picsou.service.UserContext;
import io.github.bucket4j.Bucket;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class BoursoControllerTest {
    private static final Long MEMBER_ID = 7L;

    @Mock BoursoSyncService service;
    @Mock UserContext userContext;
    @Mock HttpServletRequest request;

    private BoursoController controller;
    private ConcurrentHashMap<String, Bucket> authBuckets;
    private ConcurrentHashMap<String, Bucket> syncBuckets;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        authBuckets = new ConcurrentHashMap<>();
        syncBuckets = new ConcurrentHashMap<>();
        controller = new BoursoController(service, userContext, authBuckets, syncBuckets);
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();
        lenient().when(userContext.currentMemberId()).thenReturn(MEMBER_ID);
    }

    @Test
    void initiateScopesAuthenticationToTheCurrentMember() {
        when(request.getRemoteAddr()).thenReturn("127.0.0.1");
        var expected = new BoursoSyncService.AuthInitResponse("process", true, "APP_PUSH");
        when(service.initiateAuth("12345678", "123456", MEMBER_ID)).thenReturn(expected);

        var response = controller.initiate(
            new BoursoController.InitiateRequest("12345678", "123456"),
            request
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isSameAs(expected);
        verify(service).initiateAuth("12345678", "123456", MEMBER_ID);
    }

    @Test
    void authenticationRateLimitUsesTheTrustedProxyClientIp() {
        var proxiedRequest = new MockHttpServletRequest();
        proxiedRequest.setRemoteAddr("172.18.0.2");
        proxiedRequest.addHeader("X-Forwarded-For", "1.2.3.4");
        proxiedRequest.addHeader("X-Real-IP", "203.0.113.9");
        when(service.initiateAuth("12345678", "123456", MEMBER_ID))
            .thenReturn(new BoursoSyncService.AuthInitResponse("process", true, "APP_PUSH"));

        controller.initiate(new BoursoController.InitiateRequest("12345678", "123456"), proxiedRequest);

        assertThat(authBuckets).containsOnlyKeys("203.0.113.9");
    }

    /** The app push carries nothing to type, so completion takes the id alone. */
    @Test
    void completeAuthEndpointTakesOnlyTheProcessId() throws Exception {
        when(service.completeAuth("process-123", MEMBER_ID))
            .thenReturn(sessionStatus(BoursoSyncStatus.QUEUED));

        mockMvc.perform(post("/api/bourso/auth/complete")
                .with(request -> {
                    request.setRemoteAddr("127.0.0.1");
                    return request;
                })
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"processId\":\"process-123\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.syncStatus").value("QUEUED"));

        verify(service).completeAuth("process-123", MEMBER_ID);
    }

    @Test
    void anUnconfirmedAppPushReachesTheClientAsItsOwnTranslatableCode() throws Exception {
        when(service.completeAuth("process-123", MEMBER_ID)).thenThrow(
            new SyncException(
                "The BoursoBank app validation was not confirmed in time",
                null,
                BoursoErrorCode.APP_VALIDATION_TIMEOUT.name()
            )
        );

        mockMvc.perform(post("/api/bourso/auth/complete")
                .with(request -> {
                    request.setRemoteAddr("127.0.0.1");
                    return request;
                })
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"processId\":\"process-123\"}"))
            .andExpect(status().isUnprocessableEntity())
            .andExpect(jsonPath("$.code").value("APP_VALIDATION_TIMEOUT"));
    }

    /**
     * An SMS prompt is a BoursoBank security setting the user can change, so it
     * must reach them as its own code rather than as a wrong-password error.
     */
    @Test
    void anUnsupportedSecondFactorReachesTheClientAsItsOwnCode() throws Exception {
        when(service.initiateAuth("12345678", "123456", MEMBER_ID)).thenThrow(
            new SyncException("sms", null, BoursoErrorCode.MFA_TYPE_UNSUPPORTED.name())
        );

        mockMvc.perform(post("/api/bourso/auth/initiate")
                .with(request -> {
                    request.setRemoteAddr("127.0.0.1");
                    return request;
                })
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"customerId\":\"12345678\",\"password\":\"123456\"}"))
            .andExpect(status().isUnprocessableEntity())
            .andExpect(jsonPath("$.code").value("MFA_TYPE_UNSUPPORTED"));
    }

    @Test
    void authenticationPayloadValidationRejectsOversizedAndMissingFields() throws Exception {
        mockMvc.perform(post("/api/bourso/auth/initiate")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"customerId\":\"" + "1".repeat(101) + "\",\"password\":\"123456\"}"))
            .andExpect(status().isUnprocessableEntity())
            .andExpect(jsonPath("$.errors.customerId").exists());

        mockMvc.perform(post("/api/bourso/auth/complete")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"processId\":\"\"}"))
            .andExpect(status().isUnprocessableEntity())
            .andExpect(jsonPath("$.errors.processId").exists());

        verify(service, times(0)).initiateAuth(anyString(), anyString(), anyLong());
        verify(service, times(0)).completeAuth(anyString(), anyLong());
    }

    @Test
    void sixthAuthenticationAttemptFromTheSameIpIsRateLimited() {
        when(request.getRemoteAddr()).thenReturn("127.0.0.1");
        when(service.initiateAuth("12345678", "123456", MEMBER_ID)).thenReturn(
            new BoursoSyncService.AuthInitResponse("process", true, "APP_PUSH")
        );

        for (int attempt = 0; attempt < 5; attempt++) {
            assertThat(controller.initiate(
                new BoursoController.InitiateRequest("12345678", "123456"),
                request
            ).getStatusCode()).isEqualTo(HttpStatus.OK);
        }

        assertThat(controller.initiate(
            new BoursoController.InitiateRequest("12345678", "123456"),
            request
        ).getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        verify(service, times(5)).initiateAuth("12345678", "123456", MEMBER_ID);
    }

    @Test
    void syncReturnsAcceptedAndTheObservableQueueStatus() {
        when(request.getRemoteAddr()).thenReturn("127.0.0.1");
        var queued = sessionStatus(BoursoSyncStatus.QUEUED);
        when(service.queueSync(MEMBER_ID)).thenReturn(queued);

        var response = controller.sync(request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(response.getBody()).isSameAs(queued);
        verify(service).queueSync(MEMBER_ID);
    }

    @Test
    void eleventhSyncFromTheSameIpIsRateLimited() {
        when(request.getRemoteAddr()).thenReturn("127.0.0.1");
        when(service.queueSync(MEMBER_ID)).thenReturn(sessionStatus(BoursoSyncStatus.QUEUED));

        for (int attempt = 0; attempt < 10; attempt++) {
            assertThat(controller.sync(request).getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        }

        assertThat(controller.sync(request).getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        verify(service, times(10)).queueSync(MEMBER_ID);
    }

    @Test
    void syncAndAuthenticationDrawOnSeparateBudgets() {
        when(request.getRemoteAddr()).thenReturn("127.0.0.1");
        when(service.queueSync(MEMBER_ID)).thenReturn(sessionStatus(BoursoSyncStatus.QUEUED));
        when(service.initiateAuth("12345678", "123456", MEMBER_ID))
            .thenReturn(new BoursoSyncService.AuthInitResponse("process", true, "APP_PUSH"));

        for (int attempt = 0; attempt < 5; attempt++) {
            controller.initiate(new BoursoController.InitiateRequest("12345678", "123456"), request);
        }

        assertThat(controller.sync(request).getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
    }

    @Test
    void statusAndClearUseOnlyTheCurrentMember() {
        var success = sessionStatus(BoursoSyncStatus.SUCCESS);
        when(service.getStatus(MEMBER_ID)).thenReturn(success);

        assertThat(controller.status()).isSameAs(success);
        assertThat(controller.clear().getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        verify(service).getStatus(MEMBER_ID);
        verify(service).clearSession(MEMBER_ID);
    }

    private BoursoSyncService.SessionStatusResponse sessionStatus(BoursoSyncStatus syncStatus) {
        return new BoursoSyncService.SessionStatusResponse(true, syncStatus, null, null, null);
    }
}

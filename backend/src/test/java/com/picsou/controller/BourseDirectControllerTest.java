package com.picsou.controller;

import com.picsou.exception.GlobalExceptionHandler;
import com.picsou.exception.SyncException;
import com.picsou.model.BourseDirectSyncStatus;
import com.picsou.port.BourseDirectErrorCode;
import com.picsou.service.BourseDirectSyncService;
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
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class BourseDirectControllerTest {
    private static final Long MEMBER_ID = 7L;

    @Mock BourseDirectSyncService service;
    @Mock UserContext userContext;
    @Mock HttpServletRequest request;

    private BourseDirectController controller;
    private ConcurrentHashMap<String, Bucket> authBuckets;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        authBuckets = new ConcurrentHashMap<>();
        controller = new BourseDirectController(service, userContext, authBuckets);
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();
        lenient().when(userContext.currentMemberId()).thenReturn(MEMBER_ID);
    }

    @Test
    void initiateScopesAuthenticationToTheCurrentMember() {
        when(request.getRemoteAddr()).thenReturn("127.0.0.1");
        var expected = new BourseDirectSyncService.AuthInitResponse("process", true, "OTP");
        when(service.initiateAuth("login", "password", MEMBER_ID)).thenReturn(expected);

        var response = controller.initiate(
            new BourseDirectController.InitiateRequest("login", "password"),
            request
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isSameAs(expected);
        verify(service).initiateAuth("login", "password", MEMBER_ID);
    }

    @Test
    void authenticationRateLimitUsesTheTrustedProxyClientIp() {
        var proxiedRequest = new MockHttpServletRequest();
        proxiedRequest.setRemoteAddr("172.18.0.2");
        proxiedRequest.addHeader("X-Forwarded-For", "1.2.3.4");
        proxiedRequest.addHeader("X-Real-IP", "203.0.113.9");
        when(service.initiateAuth("login", "password", MEMBER_ID))
            .thenReturn(new BourseDirectSyncService.AuthInitResponse("process", true, "OTP"));

        controller.initiate(new BourseDirectController.InitiateRequest("login", "password"), proxiedRequest);

        assertThat(authBuckets).containsOnlyKeys("203.0.113.9");
    }

    @Test
    void completeAuthEndpointScopesOtpToTheCurrentMember() throws Exception {
        var queued = sessionStatus(BourseDirectSyncStatus.QUEUED);
        when(service.completeAuth("process-123", "123456", MEMBER_ID)).thenReturn(queued);

        mockMvc.perform(post("/api/bourse-direct/auth/complete")
                .with(request -> {
                    request.setRemoteAddr("127.0.0.1");
                    return request;
                })
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"processId\":\"process-123\",\"code\":\"123456\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.syncStatus").value("QUEUED"));

        verify(service).completeAuth("process-123", "123456", MEMBER_ID);
    }

    @Test
    void completeAuthEndpointMapsInvalidOtpCode() throws Exception {
        when(service.completeAuth("process-123", "000000", MEMBER_ID)).thenThrow(
            new SyncException(
                "Bourse Direct rejected the verification code",
                null,
                BourseDirectErrorCode.INVALID_OTP.name()
            )
        );

        mockMvc.perform(post("/api/bourse-direct/auth/complete")
                .with(request -> {
                    request.setRemoteAddr("127.0.0.1");
                    return request;
                })
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"processId\":\"process-123\",\"code\":\"000000\"}"))
            .andExpect(status().isUnprocessableEntity())
            .andExpect(jsonPath("$.code").value("INVALID_OTP"));
    }

    @Test
    void authenticationPayloadValidationRejectsOversizedLoginAndMalformedOtp() throws Exception {
        mockMvc.perform(post("/api/bourse-direct/auth/initiate")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"login\":\"" + "x".repeat(101) + "\",\"password\":\"secret\"}"))
            .andExpect(status().isUnprocessableEntity())
            .andExpect(jsonPath("$.errors.login").exists());

        mockMvc.perform(post("/api/bourse-direct/auth/complete")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"processId\":\"process\",\"code\":\"12ab\"}"))
            .andExpect(status().isUnprocessableEntity())
            .andExpect(jsonPath("$.errors.code").exists());

        verify(service, times(0)).initiateAuth(anyString(), anyString(), org.mockito.ArgumentMatchers.anyLong());
        verify(service, times(0)).completeAuth(anyString(), anyString(), org.mockito.ArgumentMatchers.anyLong());
    }

    @Test
    void sixthAuthenticationAttemptFromTheSameIpIsRateLimited() {
        when(request.getRemoteAddr()).thenReturn("127.0.0.1");
        when(service.initiateAuth("login", "password", MEMBER_ID)).thenReturn(
            new BourseDirectSyncService.AuthInitResponse("process", true, "OTP")
        );

        for (int attempt = 0; attempt < 5; attempt++) {
            assertThat(controller.initiate(
                new BourseDirectController.InitiateRequest("login", "password"),
                request
            ).getStatusCode()).isEqualTo(HttpStatus.OK);
        }

        assertThat(controller.initiate(
            new BourseDirectController.InitiateRequest("login", "password"),
            request
        ).getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        verify(service, times(5)).initiateAuth("login", "password", MEMBER_ID);
    }

    @Test
    void syncReturnsAcceptedAndTheObservableQueueStatus() {
        var queued = sessionStatus(BourseDirectSyncStatus.QUEUED);
        when(service.queueSync(MEMBER_ID)).thenReturn(queued);

        var response = controller.sync();

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(response.getBody()).isSameAs(queued);
        verify(service).queueSync(MEMBER_ID);
    }

    @Test
    void statusAndClearUseOnlyTheCurrentMember() {
        var success = sessionStatus(BourseDirectSyncStatus.SUCCESS);
        when(service.getStatus(MEMBER_ID)).thenReturn(success);

        assertThat(controller.status()).isSameAs(success);
        assertThat(controller.clear().getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        verify(service).getStatus(MEMBER_ID);
        verify(service).clearSession(MEMBER_ID);
    }

    private BourseDirectSyncService.SessionStatusResponse sessionStatus(BourseDirectSyncStatus syncStatus) {
        return new BourseDirectSyncService.SessionStatusResponse(
            true, null, syncStatus, null, null, null
        );
    }
}

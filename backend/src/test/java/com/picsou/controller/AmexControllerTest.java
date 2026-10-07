package com.picsou.controller;

import com.picsou.exception.GlobalExceptionHandler;
import com.picsou.exception.SyncException;
import com.picsou.model.AmexSyncStatus;
import com.picsou.port.AmexErrorCode;
import com.picsou.service.AmexSyncService;
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
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class AmexControllerTest {
    private static final Long MEMBER_ID = 7L;

    @Mock AmexSyncService service;
    @Mock UserContext userContext;
    @Mock HttpServletRequest request;

    private AmexController controller;
    private ConcurrentHashMap<String, Bucket> authBuckets;
    private ConcurrentHashMap<String, Bucket> syncBuckets;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        authBuckets = new ConcurrentHashMap<>();
        syncBuckets = new ConcurrentHashMap<>();
        controller = new AmexController(service, userContext, authBuckets, syncBuckets);
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();
        lenient().when(userContext.currentMemberId()).thenReturn(MEMBER_ID);
    }

    @Test
    void initiatePassesTheChosenOtpMethodToTheService() {
        when(request.getRemoteAddr()).thenReturn("127.0.0.1");
        var expected = new AmexSyncService.AuthInitResponse("process", true, "OTP");
        when(service.initiateAuth("login", "password", "sms", MEMBER_ID)).thenReturn(expected);

        var response = controller.initiate(
            new AmexController.InitiateRequest("login", "password", "sms"),
            request
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isSameAs(expected);
        verify(service).initiateAuth("login", "password", "sms", MEMBER_ID);
    }

    @Test
    void initiateRejectsAnUnsupportedMethod() throws Exception {
        mockMvc.perform(post("/api/amex/auth/initiate")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"login\":\"login\",\"password\":\"secret\",\"method\":\"push\"}"))
            .andExpect(status().isUnprocessableEntity())
            .andExpect(jsonPath("$.errors.method").exists());

        verify(service, times(0)).initiateAuth(org.mockito.ArgumentMatchers.anyString(),
            org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString(),
            org.mockito.ArgumentMatchers.anyLong());
    }

    @Test
    void completeAuthEndpointScopesTheOtpToTheCurrentMember() throws Exception {
        when(service.completeAuth("process-123", "123456", MEMBER_ID))
            .thenReturn(sessionStatus(AmexSyncStatus.QUEUED));

        mockMvc.perform(post("/api/amex/auth/complete")
                .with(req -> {
                    req.setRemoteAddr("127.0.0.1");
                    return req;
                })
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"processId\":\"process-123\",\"otp\":\"123456\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.syncStatus").value("QUEUED"));

        verify(service).completeAuth("process-123", "123456", MEMBER_ID);
    }

    @Test
    void completeAuthRejectsAMalformedOtp() throws Exception {
        mockMvc.perform(post("/api/amex/auth/complete")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"processId\":\"process\",\"otp\":\"12ab\"}"))
            .andExpect(status().isUnprocessableEntity())
            .andExpect(jsonPath("$.errors.otp").exists());
    }

    @Test
    void completeAuthEndpointMapsInvalidOtpCode() throws Exception {
        when(service.completeAuth("process-123", "000000", MEMBER_ID)).thenThrow(
            new SyncException(
                "Amex rejected the verification code",
                null,
                AmexErrorCode.INVALID_OTP.name()
            )
        );

        mockMvc.perform(post("/api/amex/auth/complete")
                .with(req -> {
                    req.setRemoteAddr("127.0.0.1");
                    return req;
                })
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"processId\":\"process-123\",\"otp\":\"000000\"}"))
            .andExpect(status().isUnprocessableEntity())
            .andExpect(jsonPath("$.code").value("INVALID_OTP"));
    }

    @Test
    void sixthAuthenticationAttemptFromTheSameIpIsRateLimited() {
        when(request.getRemoteAddr()).thenReturn("127.0.0.1");
        when(service.initiateAuth("login", "password", "sms", MEMBER_ID)).thenReturn(
            new AmexSyncService.AuthInitResponse("process", true, "OTP")
        );

        for (int attempt = 0; attempt < 5; attempt++) {
            assertThat(controller.initiate(
                new AmexController.InitiateRequest("login", "password", "sms"),
                request
            ).getStatusCode()).isEqualTo(HttpStatus.OK);
        }

        assertThat(controller.initiate(
            new AmexController.InitiateRequest("login", "password", "sms"),
            request
        ).getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        verify(service, times(5)).initiateAuth("login", "password", "sms", MEMBER_ID);
    }

    @Test
    void statusAndClearUseOnlyTheCurrentMember() {
        var success = sessionStatus(AmexSyncStatus.SUCCESS);
        when(service.getStatus(MEMBER_ID)).thenReturn(success);

        assertThat(controller.status()).isSameAs(success);
        assertThat(controller.clear().getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        verify(service).getStatus(MEMBER_ID);
        verify(service).clearSession(MEMBER_ID);
    }

    private AmexSyncService.SessionStatusResponse sessionStatus(AmexSyncStatus syncStatus) {
        return new AmexSyncService.SessionStatusResponse(true, syncStatus, null, null, null);
    }
}

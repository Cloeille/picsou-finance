package com.picsou.controller;

import com.picsou.exception.GlobalExceptionHandler;
import com.picsou.exception.SyncException;
import com.picsou.model.CmesSyncStatus;
import com.picsou.port.CmesErrorCode;
import com.picsou.service.CmesSyncService;
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
import org.springframework.http.ResponseEntity;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
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

@ExtendWith(MockitoExtension.class)
class CmesControllerTest {
    private static final Long MEMBER_ID = 7L;

    @Mock CmesSyncService service;
    @Mock UserContext userContext;
    @Mock HttpServletRequest request;

    private CmesController controller;
    private ConcurrentHashMap<String, Bucket> authBuckets;
    private ConcurrentHashMap<String, Bucket> completeBuckets;
    private ConcurrentHashMap<String, Bucket> syncBuckets;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        authBuckets = new ConcurrentHashMap<>();
        completeBuckets = new ConcurrentHashMap<>();
        syncBuckets = new ConcurrentHashMap<>();
        controller = new CmesController(service, userContext, authBuckets, completeBuckets, syncBuckets);
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();
        lenient().when(userContext.currentMemberId()).thenReturn(MEMBER_ID);
        lenient().when(request.getRemoteAddr()).thenReturn("127.0.0.1");
    }

    @Test
    void initiateScopesTheLoginToTheCurrentMember() throws Exception {
        when(service.initiateAuth("12345678", "secret", MEMBER_ID))
            .thenReturn(new CmesSyncService.AuthInitResponse("process", true, "SMS"));

        mockMvc.perform(post("/api/cmes/auth/initiate")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"login\":\"12345678\",\"password\":\"secret\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.processId").value("process"))
            .andExpect(jsonPath("$.mfaType").value("SMS"));
    }

    @Test
    void completeScopesTheSmsCodeToTheCurrentMember() throws Exception {
        when(service.completeAuth("process", "123456", MEMBER_ID)).thenReturn(sessionStatus(CmesSyncStatus.QUEUED));

        mockMvc.perform(post("/api/cmes/auth/complete")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"processId\":\"process\",\"code\":\"123456\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.syncStatus").value("QUEUED"));
    }

    /** A mobile confirmation is approved in the bank's app: there is no code to send. */
    @Test
    void completeAcceptsAMobileConfirmationWithoutACode() throws Exception {
        when(service.completeAuth("process", null, MEMBER_ID)).thenReturn(sessionStatus(CmesSyncStatus.QUEUED));

        mockMvc.perform(post("/api/cmes/auth/complete")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"processId\":\"process\"}"))
            .andExpect(status().isOk());
    }

    @Test
    void aWrongSmsCodeReachesTheClientAsItsOwnCode() throws Exception {
        when(service.completeAuth("process", "000000", MEMBER_ID))
            .thenThrow(new SyncException("wrong code", null, CmesErrorCode.INVALID_OTP.name()));

        mockMvc.perform(post("/api/cmes/auth/complete")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"processId\":\"process\",\"code\":\"000000\"}"))
            .andExpect(status().isUnprocessableEntity())
            .andExpect(jsonPath("$.code").value("INVALID_OTP"));
    }

    @Test
    void malformedPayloadsAreRefusedBeforeReachingTheService() throws Exception {
        mockMvc.perform(post("/api/cmes/auth/initiate")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"login\":\"" + "x".repeat(101) + "\",\"password\":\"secret\"}"))
            .andExpect(status().isUnprocessableEntity())
            .andExpect(jsonPath("$.errors.login").exists());
        mockMvc.perform(post("/api/cmes/auth/initiate")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"login\":\"12345678\",\"password\":\" \"}"))
            .andExpect(status().isUnprocessableEntity())
            .andExpect(jsonPath("$.errors.password").exists());

        for (String code : new String[] {"12ab", "123", "123456789"}) {
            mockMvc.perform(post("/api/cmes/auth/complete")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"processId\":\"process\",\"code\":\"" + code + "\"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.errors.code").exists());
        }
        mockMvc.perform(post("/api/cmes/auth/complete")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"processId\":\"\",\"code\":\"123456\"}"))
            .andExpect(status().isUnprocessableEntity())
            .andExpect(jsonPath("$.errors.processId").exists());

        verify(service, never()).initiateAuth(anyString(), anyString(), anyLong());
        verify(service, never()).completeAuth(anyString(), any(), anyLong());
    }

    @Test
    void theSixthLoginFromTheSameIpIsRateLimited() {
        when(service.initiateAuth("12345678", "secret", MEMBER_ID))
            .thenReturn(new CmesSyncService.AuthInitResponse("process", true, "APP_PUSH"));

        for (int attempt = 0; attempt < 5; attempt++) {
            assertThat(initiate().getStatusCode()).isEqualTo(HttpStatus.OK);
        }

        assertThat(initiate().getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        verify(service, times(5)).initiateAuth("12345678", "secret", MEMBER_ID);
    }

    @Test
    void theEleventhCompletionFromTheSameIpIsRateLimited() {
        when(service.completeAuth("process", "000000", MEMBER_ID)).thenReturn(sessionStatus(CmesSyncStatus.QUEUED));

        for (int attempt = 0; attempt < 10; attempt++) {
            assertThat(complete().getStatusCode()).isEqualTo(HttpStatus.OK);
        }

        assertThat(complete().getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        verify(service, times(10)).completeAuth("process", "000000", MEMBER_ID);
    }

    /**
     * Once the phone has been notified, the confirmation must not be refused
     * because the password budget ran out, nor wrong codes lock out the login.
     */
    @Test
    void loginsAndCompletionsDrawOnIndependentBudgets() {
        when(service.initiateAuth("12345678", "secret", MEMBER_ID))
            .thenReturn(new CmesSyncService.AuthInitResponse("process", true, "SMS"));
        when(service.completeAuth("process", "000000", MEMBER_ID)).thenReturn(sessionStatus(CmesSyncStatus.QUEUED));

        for (int attempt = 0; attempt < 5; attempt++) {
            initiate();
        }
        assertThat(initiate().getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(complete().getStatusCode()).isEqualTo(HttpStatus.OK);

        authBuckets.clear();
        for (int attempt = 0; attempt < 9; attempt++) {
            complete();
        }
        assertThat(complete().getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(initiate().getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void syncIsQueuedForTheCurrentMemberAndThrottled() {
        when(service.queueSync(MEMBER_ID)).thenReturn(sessionStatus(CmesSyncStatus.QUEUED));

        for (int attempt = 0; attempt < 10; attempt++) {
            assertThat(controller.sync(request).getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        }

        assertThat(controller.sync(request).getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        verify(service, times(10)).queueSync(MEMBER_ID);
    }

    @Test
    void statusAndDisconnectTouchOnlyTheCurrentMember() throws Exception {
        when(service.getStatus(MEMBER_ID)).thenReturn(sessionStatus(CmesSyncStatus.SUCCESS));

        mockMvc.perform(get("/api/cmes/status"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.syncStatus").value("SUCCESS"));
        mockMvc.perform(delete("/api/cmes/session"))
            .andExpect(status().isNoContent());

        verify(service).getStatus(MEMBER_ID);
        verify(service).clearSession(MEMBER_ID);
        verifyNoMoreInteractions(service);
    }

    private ResponseEntity<?> initiate() {
        return controller.initiate(new CmesController.InitiateRequest("12345678", "secret"), request);
    }

    private ResponseEntity<?> complete() {
        return controller.complete(new CmesController.CompleteRequest("process", "000000"), request);
    }

    private CmesSyncService.SessionStatusResponse sessionStatus(CmesSyncStatus syncStatus) {
        return new CmesSyncService.SessionStatusResponse(true, syncStatus, null, null, null);
    }
}

package com.picsou.controller;

import com.picsou.exception.GlobalExceptionHandler;
import com.picsou.exception.SyncException;
import com.picsou.model.SidecarSyncStatus;
import com.picsou.port.SidecarErrorCode;
import com.picsou.service.NatixisSyncService;
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
class NatixisControllerTest {
    private static final Long MEMBER_ID = 7L;

    @Mock NatixisSyncService service;
    @Mock UserContext userContext;
    @Mock HttpServletRequest request;

    private NatixisController controller;
    private ConcurrentHashMap<String, Bucket> authBuckets;
    private ConcurrentHashMap<String, Bucket> completeBuckets;
    private ConcurrentHashMap<String, Bucket> syncBuckets;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        authBuckets = new ConcurrentHashMap<>();
        completeBuckets = new ConcurrentHashMap<>();
        syncBuckets = new ConcurrentHashMap<>();
        controller = new NatixisController(service, userContext, authBuckets, completeBuckets, syncBuckets);
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();
        lenient().when(userContext.currentMemberId()).thenReturn(MEMBER_ID);
        lenient().when(request.getRemoteAddr()).thenReturn("127.0.0.1");
    }

    @Test
    void initiateTakesAnEmailAndScopesTheLoginToTheCurrentMember() throws Exception {
        when(service.initiateAuth("saver@example.com", "secret", MEMBER_ID))
            .thenReturn(new NatixisSyncService.AuthInitResponse("process", true, "VOICE"));

        mockMvc.perform(post("/api/natixis/auth/initiate")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"saver@example.com\",\"password\":\"secret\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.processId").value("process"))
            .andExpect(jsonPath("$.mfaRequired").value(true))
            .andExpect(jsonPath("$.mfaType").value("VOICE"));
    }

    @Test
    void completeScopesTheCodeToTheCurrentMember() throws Exception {
        when(service.completeAuth("process", "123456", MEMBER_ID)).thenReturn(sessionStatus(SidecarSyncStatus.QUEUED));

        mockMvc.perform(post("/api/natixis/auth/complete")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"processId\":\"process\",\"code\":\"123456\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.syncStatus").value("QUEUED"));
    }

    @Test
    void aWrongCodeReachesTheClientAsItsOwnCode() throws Exception {
        when(service.completeAuth("process", "000000", MEMBER_ID))
            .thenThrow(new SyncException("wrong code", null, SidecarErrorCode.INVALID_OTP.name()));

        mockMvc.perform(post("/api/natixis/auth/complete")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"processId\":\"process\",\"code\":\"000000\"}"))
            .andExpect(status().isUnprocessableEntity())
            .andExpect(jsonPath("$.code").value("INVALID_OTP"));
    }

    @Test
    void malformedPayloadsAreRefusedBeforeReachingTheService() throws Exception {
        mockMvc.perform(post("/api/natixis/auth/initiate")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"" + "x".repeat(255) + "\",\"password\":\"secret\"}"))
            .andExpect(status().isUnprocessableEntity())
            .andExpect(jsonPath("$.errors.email").exists());
        mockMvc.perform(post("/api/natixis/auth/initiate")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"saver@example.com\",\"password\":\" \"}"))
            .andExpect(status().isUnprocessableEntity())
            .andExpect(jsonPath("$.errors.password").exists());

        // Natixis has no mobile confirmation: a code is always required.
        for (String code : new String[] {"12ab", "123", "1234567890123"}) {
            mockMvc.perform(post("/api/natixis/auth/complete")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"processId\":\"process\",\"code\":\"" + code + "\"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.errors.code").exists());
        }
        mockMvc.perform(post("/api/natixis/auth/complete")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"processId\":\"process\"}"))
            .andExpect(status().isUnprocessableEntity())
            .andExpect(jsonPath("$.errors.code").exists());

        verify(service, never()).initiateAuth(anyString(), anyString(), anyLong());
        verify(service, never()).completeAuth(anyString(), any(), anyLong());
    }

    @Test
    void loginAndCodeHaveSeparateBudgets() {
        when(service.initiateAuth("saver@example.com", "secret", MEMBER_ID))
            .thenReturn(new NatixisSyncService.AuthInitResponse("process", true, "SMS"));
        when(service.completeAuth("process", "000000", MEMBER_ID)).thenReturn(sessionStatus(SidecarSyncStatus.QUEUED));

        for (int attempt = 0; attempt < 5; attempt++) {
            assertThat(initiate().getStatusCode()).isEqualTo(HttpStatus.OK);
        }
        assertThat(initiate().getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);

        // An exhausted login budget never blocks typing the code that was sent.
        for (int attempt = 0; attempt < 10; attempt++) {
            assertThat(complete().getStatusCode()).isEqualTo(HttpStatus.OK);
        }
        assertThat(complete().getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        verify(service, times(5)).initiateAuth("saver@example.com", "secret", MEMBER_ID);
        verify(service, times(10)).completeAuth("process", "000000", MEMBER_ID);
    }

    @Test
    void syncIsQueuedForTheCurrentMemberAndThrottled() {
        when(service.queueSync(MEMBER_ID)).thenReturn(sessionStatus(SidecarSyncStatus.QUEUED));

        for (int attempt = 0; attempt < 10; attempt++) {
            assertThat(controller.sync(request).getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        }

        assertThat(controller.sync(request).getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        verify(service, times(10)).queueSync(MEMBER_ID);
    }

    @Test
    void statusAndDisconnectTouchOnlyTheCurrentMember() throws Exception {
        when(service.getStatus(MEMBER_ID)).thenReturn(sessionStatus(SidecarSyncStatus.SUCCESS));

        mockMvc.perform(get("/api/natixis/status"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.syncStatus").value("SUCCESS"));
        mockMvc.perform(delete("/api/natixis/session"))
            .andExpect(status().isNoContent());

        verify(service).getStatus(MEMBER_ID);
        verify(service).clearSession(MEMBER_ID);
        verifyNoMoreInteractions(service);
    }

    private org.springframework.http.ResponseEntity<?> initiate() {
        return controller.initiate(new NatixisController.InitiateRequest("saver@example.com", "secret"), request);
    }

    private org.springframework.http.ResponseEntity<?> complete() {
        return controller.complete(new NatixisController.CompleteRequest("process", "000000"), request);
    }

    private NatixisSyncService.SessionStatusResponse sessionStatus(SidecarSyncStatus syncStatus) {
        return new NatixisSyncService.SessionStatusResponse(true, syncStatus, null, null, null);
    }
}

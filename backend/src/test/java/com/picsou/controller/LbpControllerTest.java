package com.picsou.controller;

import com.picsou.exception.GlobalExceptionHandler;
import com.picsou.adapter.LbpAdapter;
import com.picsou.model.LbpSyncStatus;
import com.picsou.service.LbpSyncService;
import com.picsou.service.UserContext;
import io.github.bucket4j.Bucket;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class LbpControllerTest {
    private static final Long MEMBER_ID = 7L;
    private static final List<String> KEYS = List.of("5", "3", "9", "8", "6", "1", "0", "7", "2", "4");

    @Mock LbpSyncService service;
    @Mock UserContext userContext;

    private ConcurrentHashMap<String, Bucket> authBuckets;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        authBuckets = new ConcurrentHashMap<>();
        LbpController controller = new LbpController(
            service, userContext, authBuckets, new ConcurrentHashMap<>(), new ConcurrentHashMap<>());
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();
        lenient().when(userContext.currentMemberId()).thenReturn(MEMBER_ID);
    }

    private static LbpSyncService.SessionStatusResponse statusOf(LbpSyncStatus syncStatus) {
        return new LbpSyncService.SessionStatusResponse(
            true, syncStatus, null, null, null,
            List.of(new LbpSyncService.UnsupportedProduct("9001", "ASSURANCE_VIE")), null);
    }

    private static LbpSyncService.InitiateResponse initiate() {
        return new LbpSyncService.InitiateResponse("proc-1",
            new LbpAdapter.Keypad(KEYS, 5), 90);
    }

    @Test
    void initiateStartsALoginAndAnswersTheKeypad() throws Exception {
        when(service.initiateAuth("1234567890", false, MEMBER_ID)).thenReturn(initiate());

        mockMvc.perform(post("/api/lbp/auth/initiate")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"customerId\":\"1234567890\"}"))
            .andExpect(status().isOk())
            .andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(jsonPath("$.processId").value("proc-1"))
            .andExpect(jsonPath("$.expiresInSeconds").value(90))
            .andExpect(jsonPath("$.keypad.columns").value(5))
            .andExpect(jsonPath("$.keypad.keys.length()").value(10))
            .andExpect(jsonPath("$.keypad.keys[0]").value("5"));
    }

    @Test
    void initiateForwardsTheRememberIdentifierFlag() throws Exception {
        when(service.initiateAuth("1234567890", true, MEMBER_ID)).thenReturn(initiate());

        mockMvc.perform(post("/api/lbp/auth/initiate")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"customerId\":\"1234567890\",\"rememberIdentifier\":true}"))
            .andExpect(status().isOk());

        verify(service).initiateAuth("1234567890", true, MEMBER_ID);
    }

    @Test
    void initiateRefusesANonBooleanRememberIdentifier() throws Exception {
        mockMvc.perform(post("/api/lbp/auth/initiate")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"customerId\":\"1234567890\",\"rememberIdentifier\":\"yes\"}"))
            .andExpect(status().isBadRequest());

        verify(service, never()).initiateAuth(org.mockito.ArgumentMatchers.any(), anyBoolean(), anyLong());
    }

    @Test
    void initiateRefusesAnUnexpectedFieldSuchAsAPassword() throws Exception {
        mockMvc.perform(post("/api/lbp/auth/initiate")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"customerId\":\"1234567890\",\"password\":\"hunter2\"}"))
            .andExpect(status().isBadRequest());

        verify(service, never()).initiateAuth(org.mockito.ArgumentMatchers.any(), anyBoolean(), anyLong());
    }

    @Test
    void initiateRefusesAnInvalidCustomerId() throws Exception {
        for (String id : List.of("abc", "12345", "1234567890123", "")) {
            mockMvc.perform(post("/api/lbp/auth/initiate")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"customerId\":\"" + id + "\"}"))
                .andExpect(status().isBadRequest());
        }
        verify(service, never()).initiateAuth(org.mockito.ArgumentMatchers.any(), anyBoolean(), anyLong());
    }

    @Test
    void theLoginBucketStopsAFourthInitiatePerIp() throws Exception {
        when(service.initiateAuth(org.mockito.ArgumentMatchers.any(), anyBoolean(), anyLong())).thenReturn(initiate());
        var request = post("/api/lbp/auth/initiate")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"customerId\":\"1234567890\"}")
            .with(r -> { r.setRemoteAddr("127.0.0.1"); return r; });

        for (int i = 0; i < 3; i++) {
            mockMvc.perform(request).andExpect(status().isOk());
        }
        mockMvc.perform(request)
            .andExpect(status().isTooManyRequests())
            .andExpect(header().exists("Retry-After"));

        verify(service, times(3)).initiateAuth("1234567890", false, MEMBER_ID);
        assertThat(authBuckets).containsOnlyKeys("127.0.0.1");
    }

    @Test
    void keypadForwardsOnlyTheClickedPositions() throws Exception {
        mockMvc.perform(post("/api/lbp/auth/keypad")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"processId\":\"proc-1\",\"positions\":[7,3,0,9,4,1]}"))
            .andExpect(status().isOk())
            .andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(jsonPath("$.processId").value("proc-1"))
            .andExpect(jsonPath("$.status").value("APPROVAL_PENDING"));

        verify(service).submitKeypad("proc-1", List.of(7, 3, 0, 9, 4, 1), MEMBER_ID);
    }

    @Test
    void keypadRefusesAForgedOrMalformedChoice() throws Exception {
        for (String body : List.of(
            "{\"processId\":\"proc-1\",\"positions\":[7,3,0]}",
            "{\"processId\":\"proc-1\",\"positions\":[7,3,0,9,4,1,2]}",
            "{\"processId\":\"proc-1\",\"positions\":[7,3,0,9,4,1,2,5,6,8,0,1]}",
            "{\"processId\":\"proc-1\",\"positions\":[7,3,0,9,4,10]}",
            "{\"processId\":\"proc-1\",\"positions\":[\"7\",3,0,9,4,1]}",
            "{\"processId\":\"proc-1\",\"positions\":[7,3,0,9,4,1],\"password\":\"x\"}")) {
            mockMvc.perform(post("/api/lbp/auth/keypad")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(body))
                .andExpect(status().isBadRequest());
        }
        verify(service, never()).submitKeypad(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(), anyLong());
    }

    @Test
    void completeStoresTheSessionAndAnswersTheStatus() throws Exception {
        when(service.completeAuth("proc-1", MEMBER_ID)).thenReturn(statusOf(LbpSyncStatus.IDLE));

        mockMvc.perform(post("/api/lbp/auth/complete")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"processId\":\"proc-1\"}"))
            .andExpect(status().isOk())
            .andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(jsonPath("$.isActive").value(true))
            .andExpect(jsonPath("$.unsupportedCount").value(1));
    }

    @Test
    void completeRefusesAnUnexpectedFieldAndAMissingProcessId() throws Exception {
        for (String body : List.of(
            "{\"processId\":\"proc-1\",\"code\":\"123456\"}",
            "{\"processId\":\"proc-1\",\"password\":\"x\"}",
            "{}")) {
            mockMvc.perform(post("/api/lbp/auth/complete")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(body))
                .andExpect(status().isBadRequest());
        }
        verify(service, never()).completeAuth(org.mockito.ArgumentMatchers.any(), anyLong());
    }

    @Test
    void syncQueuesTheSyncForTheCurrentMemberAndAnswers202() throws Exception {
        when(service.queueSync(MEMBER_ID)).thenReturn(statusOf(LbpSyncStatus.QUEUED));

        mockMvc.perform(post("/api/lbp/sync"))
            .andExpect(status().isAccepted())
            .andExpect(jsonPath("$.syncStatus").value("QUEUED"))
            .andExpect(jsonPath("$.unsupported[0].familyCode").value("ASSURANCE_VIE"));

        verify(service).queueSync(MEMBER_ID);
    }

    @Test
    void statusAndClearDelegate() throws Exception {
        when(service.getStatus(MEMBER_ID)).thenReturn(statusOf(LbpSyncStatus.IDLE));
        when(service.clearSession(MEMBER_ID)).thenReturn(true);

        mockMvc.perform(get("/api/lbp/status"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.syncStatus").value("IDLE"));

        mockMvc.perform(delete("/api/lbp/session"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.removed").value(true))
            .andExpect(jsonPath("$.bankSessionRevoked").value(false))
            .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("not revoked")));
    }
}

package com.picsou.controller;

import com.picsou.adapter.BnpAdapter;
import com.picsou.exception.GlobalExceptionHandler;
import com.picsou.exception.SyncException;
import com.picsou.model.SidecarSyncStatus;
import com.picsou.port.SidecarErrorCode;
import com.picsou.service.BnpSyncService;
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

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class BnpControllerTest {
    private static final Long MEMBER_ID = 7L;
    private static final String KEYPAD = "{\"processId\":\"process\",\"positions\":[0,1,2,3,4,5]}";
    private static final String CODE = "{\"processId\":\"process\",\"code\":\"000000\"}";

    @Mock BnpSyncService service;
    @Mock UserContext userContext;
    @Mock HttpServletRequest request;

    private BnpController controller;
    private ConcurrentHashMap<String, Bucket> authBuckets;
    private ConcurrentHashMap<String, Bucket> stepBuckets;
    private ConcurrentHashMap<String, Bucket> completeBuckets;
    private ConcurrentHashMap<String, Bucket> syncBuckets;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        authBuckets = new ConcurrentHashMap<>();
        stepBuckets = new ConcurrentHashMap<>();
        completeBuckets = new ConcurrentHashMap<>();
        syncBuckets = new ConcurrentHashMap<>();
        controller = new BnpController(service, userContext, authBuckets, stepBuckets, completeBuckets, syncBuckets);
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();
        lenient().when(userContext.currentMemberId()).thenReturn(MEMBER_ID);
        lenient().when(request.getRemoteAddr()).thenReturn("127.0.0.1");
    }

    @Test
    void initiateTakesACustomerNumberAndScopesTheLoginToTheCurrentMember() throws Exception {
        when(service.initiateAuth("1234567890", "bnp", MEMBER_ID))
            .thenReturn(new BnpSyncService.InitiateResponse("process", new BnpAdapter.Keypad(List.of("4", "7"), 5), 300));

        mockMvc.perform(post("/api/bnp/auth/initiate")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"customerId\":\"1234567890\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.processId").value("process"))
            .andExpect(jsonPath("$.keypad.columns").value(5));
    }

    @Test
    void helloBankIsAcceptedAsABrand() throws Exception {
        when(service.initiateAuth("1234567890", "hellobank", MEMBER_ID))
            .thenReturn(new BnpSyncService.InitiateResponse("process", new BnpAdapter.Keypad(List.of("4", "7"), 5), 300));

        mockMvc.perform(post("/api/bnp/auth/initiate")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"customerId\":\"1234567890\",\"brand\":\"hellobank\"}"))
            .andExpect(status().isOk());
    }

    @Test
    void malformedBodiesAreRefusedBeforeReachingTheService() throws Exception {
        String[] initiates = {
            "{\"customerId\":\"12ab\"}",
            "{\"customerId\":\"123456\"}",
            "{\"customerId\":\"12345678901\"}",
            "{\"customerId\":\"1234567890\",\"brand\":\"other\"}",
            "{\"customerId\":\"1234567890\",\"password\":\"123456\"}",
            "{\"customerId\":1234567890}",
            "[]"
        };
        for (String body : initiates) {
            mockMvc.perform(post("/api/bnp/auth/initiate").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest());
        }
        String[] keypads = {
            "{\"processId\":\"process\",\"positions\":[0,1,2,3,4]}",
            "{\"processId\":\"process\",\"positions\":[0,1,2,3,4,10]}",
            "{\"processId\":\"process\",\"positions\":[0,1,2,3,4,-1]}",
            "{\"processId\":\"process\",\"positions\":[0,1,2,3,4,\"5\"]}",
            "{\"processId\":\"process\",\"positions\":[0,1,2,3,4,5],\"code\":\"123456\"}",
            "{\"positions\":[0,1,2,3,4,5]}"
        };
        for (String body : keypads) {
            mockMvc.perform(post("/api/bnp/auth/keypad").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest());
        }
        mockMvc.perform(post("/api/bnp/auth/complete").contentType(MediaType.APPLICATION_JSON)
                .content("{\"processId\":\"process\",\"code\":\"12ab\"}"))
            .andExpect(status().isBadRequest());

        verify(service, never()).initiateAuth(anyString(), anyString(), anyLong());
        verify(service, never()).submitKeypad(anyString(), anyList(), anyLong());
        verify(service, never()).completeAuth(anyString(), any(), anyLong());
    }

    @Test
    void theKeypadAndTheCodeAreScopedToTheCurrentMemberAndNeverEchoed() throws Exception {
        when(service.submitKeypad("process", List.of(0, 1, 2, 3, 4, 5), MEMBER_ID))
            .thenReturn(new BnpSyncService.KeypadResponse("process", "CODE_REQUIRED"));
        when(service.completeAuth("process", "123456", MEMBER_ID)).thenReturn(sessionStatus(SidecarSyncStatus.QUEUED));

        mockMvc.perform(post("/api/bnp/auth/keypad").contentType(MediaType.APPLICATION_JSON).content(KEYPAD))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("CODE_REQUIRED"))
            .andExpect(jsonPath("$.positions").doesNotExist());
        mockMvc.perform(post("/api/bnp/auth/complete").contentType(MediaType.APPLICATION_JSON)
                .content("{\"processId\":\"process\",\"code\":\"123456\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.syncStatus").value("QUEUED"));
    }

    @Test
    void completeWithoutACodeWaitsForTheAppApproval() throws Exception {
        when(service.completeAuth("process", null, MEMBER_ID)).thenReturn(sessionStatus(SidecarSyncStatus.QUEUED));

        mockMvc.perform(post("/api/bnp/auth/complete").contentType(MediaType.APPLICATION_JSON)
                .content("{\"processId\":\"process\"}"))
            .andExpect(status().isOk());
    }

    @Test
    void aWrongCodeReachesTheClientAsItsOwnCode() throws Exception {
        when(service.completeAuth("process", "000000", MEMBER_ID))
            .thenThrow(new SyncException("wrong code", null, SidecarErrorCode.INVALID_OTP.name()));

        mockMvc.perform(post("/api/bnp/auth/complete").contentType(MediaType.APPLICATION_JSON)
                .content("{\"processId\":\"process\",\"code\":\"000000\"}"))
            .andExpect(status().isUnprocessableEntity())
            .andExpect(jsonPath("$.code").value("INVALID_OTP"));
    }

    @Test
    void loginAllowsThreeAttemptsPerWindowAndNeverBlocksTheKeypad() {
        when(service.initiateAuth("1234567890", "bnp", MEMBER_ID))
            .thenReturn(new BnpSyncService.InitiateResponse("process", new BnpAdapter.Keypad(List.of("4", "7"), 5), 300));
        when(service.submitKeypad("process", List.of(0, 1, 2, 3, 4, 5), MEMBER_ID))
            .thenReturn(new BnpSyncService.KeypadResponse("process", "APPROVAL_PENDING"));
        com.fasterxml.jackson.databind.ObjectMapper json = new com.fasterxml.jackson.databind.ObjectMapper();

        for (int attempt = 0; attempt < 3; attempt++) {
            assertThat(controller.initiate(node(json, "{\"customerId\":\"1234567890\"}"), request).getStatusCode())
                .isEqualTo(HttpStatus.OK);
        }
        assertThat(controller.initiate(node(json, "{\"customerId\":\"1234567890\"}"), request).getStatusCode())
            .isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        // The exhausted login budget never blocks the keypad of an attempt already started.
        assertThat(controller.keypad(node(json, KEYPAD), request).getStatusCode()).isEqualTo(HttpStatus.OK);
        verify(service, times(3)).initiateAuth("1234567890", "bnp", MEMBER_ID);
    }

    @Test
    void aSpentKeypadBudgetNeverRefusesTheSmsCodeTheBankAlreadySent() {
        when(service.submitKeypad("process", List.of(0, 1, 2, 3, 4, 5), MEMBER_ID))
            .thenReturn(new BnpSyncService.KeypadResponse("process", "CODE_REQUIRED"));
        when(service.completeAuth("process", "000000", MEMBER_ID)).thenReturn(sessionStatus(SidecarSyncStatus.QUEUED));
        com.fasterxml.jackson.databind.ObjectMapper json = new com.fasterxml.jackson.databind.ObjectMapper();

        for (int step = 0; step < 5; step++) {
            assertThat(controller.keypad(node(json, KEYPAD), request).getStatusCode()).isEqualTo(HttpStatus.OK);
        }
        assertThat(controller.keypad(node(json, KEYPAD), request).getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);

        // The code is throttled by its own bucket: ten tries, then 429, and the service never sees the eleventh.
        for (int attempt = 0; attempt < 10; attempt++) {
            assertThat(controller.complete(node(json, CODE), request).getStatusCode()).isEqualTo(HttpStatus.OK);
        }
        assertThat(controller.complete(node(json, CODE), request).getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        verify(service, times(10)).completeAuth("process", "000000", MEMBER_ID);
    }

    @Test
    void waitingForTheAppApprovalDoesNotSpendTheCodeBudget() {
        when(service.completeAuth("process", null, MEMBER_ID)).thenReturn(sessionStatus(SidecarSyncStatus.QUEUED));
        com.fasterxml.jackson.databind.ObjectMapper json = new com.fasterxml.jackson.databind.ObjectMapper();

        for (int poll = 0; poll < 12; poll++) {
            assertThat(controller.complete(node(json, "{\"processId\":\"process\"}"), request).getStatusCode())
                .isEqualTo(HttpStatus.OK);
        }
        assertThat(completeBuckets).isEmpty();
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
        when(service.clearSession(MEMBER_ID)).thenReturn(true);

        mockMvc.perform(get("/api/bnp/status"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.syncStatus").value("SUCCESS"));
        mockMvc.perform(delete("/api/bnp/session"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.removed").value(true))
            .andExpect(jsonPath("$.bankSessionRevoked").value(false));

        verify(service).getStatus(MEMBER_ID);
        verify(service).clearSession(MEMBER_ID);
    }

    private static com.fasterxml.jackson.databind.JsonNode node(com.fasterxml.jackson.databind.ObjectMapper json, String body) {
        try {
            return json.readTree(body);
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }

    private BnpSyncService.SessionStatusResponse sessionStatus(SidecarSyncStatus syncStatus) {
        return new BnpSyncService.SessionStatusResponse(true, syncStatus, null, null, null, List.of());
    }
}

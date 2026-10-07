package com.picsou.controller;

import com.picsou.exception.GlobalExceptionHandler;
import com.picsou.exception.SyncException;
import com.picsou.model.CaisseEpargneSyncStatus;
import com.picsou.port.CaisseEpargneErrorCode;
import com.picsou.service.CaisseEpargneSyncService;
import com.picsou.service.UserContext;
import io.github.bucket4j.Bucket;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class CaisseEpargneControllerAuthTest {
    private static final Long MEMBER_ID = 7L;
    private static final String PASSWORD = "9482716350";
    private static final String INITIATE_BODY = "{\"customerId\":\"12345678\",\"password\":\"" + PASSWORD + "\"}";

    @Mock CaisseEpargneSyncService service;
    @Mock UserContext userContext;

    private ConcurrentHashMap<String, Bucket> authBuckets;
    private ConcurrentHashMap<String, Bucket> syncBuckets;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        authBuckets = new ConcurrentHashMap<>();
        syncBuckets = new ConcurrentHashMap<>();
        CaisseEpargneController controller =
            new CaisseEpargneController(service, userContext, authBuckets, syncBuckets);
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();
        lenient().when(userContext.currentMemberId()).thenReturn(MEMBER_ID);
    }

    private static RequestPostProcessor from(String ip) {
        return request -> {
            request.setRemoteAddr(ip);
            return request;
        };
    }

    private org.springframework.test.web.servlet.ResultActions initiate(String body, String ip) throws Exception {
        return mockMvc.perform(post("/api/caisse-epargne/auth/initiate")
            .contentType(MediaType.APPLICATION_JSON).content(body).with(from(ip)));
    }

    // -- initiate -----------------------------------------------------------

    @Test
    void initiateScopesTheAttemptToTheCurrentMemberAndAnswersTheChallenge() throws Exception {
        when(service.initiateAuth("12345678", PASSWORD, MEMBER_ID))
            .thenReturn(new CaisseEpargneSyncService.InitiateResponse("proc-1", true, "SECURPASS", 300));

        initiate(INITIATE_BODY, "127.0.0.1")
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.processId").value("proc-1"))
            .andExpect(jsonPath("$.mfaRequired").value(true))
            .andExpect(jsonPath("$.mfaType").value("SECURPASS"))
            .andExpect(jsonPath("$.expiresInSeconds").value(300))
            .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString(PASSWORD))));

        verify(service).initiateAuth("12345678", PASSWORD, MEMBER_ID);
    }

    @Test
    void initiateRefusesAnInvalidBodyBeforeAnyBankCallAndWithoutConsumingAnAttempt() throws Exception {
        for (String body : List.of(
            "{\"customerId\":\"\",\"password\":\"123456\"}",
            "{\"customerId\":\"12345678\",\"password\":\"\"}",
            "{\"customerId\":\"12345678\"}",
            "{\"customerId\":\"abc\",\"password\":\"123456\"}",
            "{\"customerId\":\"12345678\",\"password\":\"12ab56\"}",
            "{\"customerId\":\"12345678\",\"password\":\"123\"}",
            "{\"customerId\":\"12345678\",\"password\":\"123456789012345678901\"}",
            "{\"customerId\":\"123456789012345678901\",\"password\":\"123456\"}")) {
            initiate(body, "127.0.0.1").andExpect(status().is4xxClientError())
                .andExpect(content().string(org.hamcrest.Matchers.not(
                    org.hamcrest.Matchers.containsString("123456"))));
        }

        verify(service, never()).initiateAuth(anyString(), anyString(), anyLong());
        assertThat(authBuckets).isEmpty();
    }

    @Test
    void aMalformedBodyNeverEchoesThePassword() throws Exception {
        String broken = "{\"customerId\":\"12345678\",\"password\":\"" + PASSWORD + "\"";

        initiate(broken, "127.0.0.1")
            .andExpect(status().is4xxClientError())
            .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString(PASSWORD))));
    }

    @Test
    void initiateAllowsThreeAttemptsPer15MinutesPerIpThenAnswers429WithRetryAfter() throws Exception {
        when(service.initiateAuth(anyString(), anyString(), anyLong()))
            .thenReturn(new CaisseEpargneSyncService.InitiateResponse("proc", true, "SECURPASS", 300));

        for (int i = 0; i < 3; i++) {
            initiate(INITIATE_BODY, "127.0.0.1").andExpect(status().isOk());
        }
        initiate(INITIATE_BODY, "127.0.0.1")
            .andExpect(status().isTooManyRequests())
            .andExpect(header().exists("Retry-After"))
            .andExpect(result -> {
                long seconds = Long.parseLong(result.getResponse().getHeader("Retry-After"));
                assertThat(seconds).isBetween(1L, 15L * 60L);
            })
            .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString(PASSWORD))));

        verify(service, times(3)).initiateAuth(anyString(), anyString(), anyLong());
        assertThat(authBuckets).containsOnlyKeys("127.0.0.1");
        assertThat(syncBuckets).isEmpty();
    }

    @Test
    void theInitiateRateLimitIsPerClientIp() throws Exception {
        when(service.initiateAuth(anyString(), anyString(), anyLong()))
            .thenReturn(new CaisseEpargneSyncService.InitiateResponse("proc", true, "SECURPASS", 300));
        for (int i = 0; i < 3; i++) {
            initiate(INITIATE_BODY, "127.0.0.1").andExpect(status().isOk());
        }

        initiate(INITIATE_BODY, "127.0.0.2").andExpect(status().isOk());
    }

    @Test
    void aFailedLoginIsNotRetriedByTheControllerAndKeepsItsCode() throws Exception {
        when(service.initiateAuth("12345678", PASSWORD, MEMBER_ID)).thenThrow(new SyncException(
            "Caisse d'Epargne refused the identifier or password", null,
            CaisseEpargneErrorCode.INVALID_CREDENTIALS.name()));

        initiate(INITIATE_BODY, "127.0.0.1")
            .andExpect(status().isUnprocessableEntity())
            .andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"))
            .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString(PASSWORD))));

        verify(service, times(1)).initiateAuth(anyString(), anyString(), anyLong());
    }

    @Test
    void theRequestDtoNeverExposesThePasswordThroughToString() {
        var dto = new CaisseEpargneController.InitiateRequest("12345678", PASSWORD);

        assertThat(dto.toString()).doesNotContain(PASSWORD).contains("password=***");
        assertThat(dto.customerId()).isEqualTo("12345678");
        assertThat(dto.password()).isEqualTo(PASSWORD);
    }

    // -- complete -----------------------------------------------------------

    @Test
    void completeStoresTheSessionForTheCurrentMemberAndAnswersConnected() throws Exception {
        when(service.completeAuth("proc-1", MEMBER_ID)).thenReturn(
            new CaisseEpargneSyncService.SessionStatusResponse(
                true, CaisseEpargneSyncStatus.IDLE, null, null, null, List.of()));

        mockMvc.perform(post("/api/caisse-epargne/auth/complete")
                .contentType(MediaType.APPLICATION_JSON).content("{\"processId\":\"proc-1\"}").with(from("127.0.0.1")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.connected").value(true))
            .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("sessionState"))));

        verify(service).completeAuth("proc-1", MEMBER_ID);
    }

    @Test
    void completeSurfacesEverySidecarOutcomeAsATranslatableCode() throws Exception {
        for (CaisseEpargneErrorCode code : List.of(
            CaisseEpargneErrorCode.APP_VALIDATION_TIMEOUT, CaisseEpargneErrorCode.AUTH_ATTEMPT_EXPIRED,
            CaisseEpargneErrorCode.INVALID_CREDENTIALS, CaisseEpargneErrorCode.UPSTREAM_UNAVAILABLE)) {
            org.mockito.Mockito.doThrow(new SyncException("failed", null, code.name()))
                .when(service).completeAuth("proc-1", MEMBER_ID);

            mockMvc.perform(post("/api/caisse-epargne/auth/complete")
                    .contentType(MediaType.APPLICATION_JSON).content("{\"processId\":\"proc-1\"}").with(from("127.0.0.1")))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value(code.name()));
        }
    }

    @Test
    void completeRefusesABlankOrMissingProcessId() throws Exception {
        for (String body : List.of("{}", "{\"processId\":\"\"}", "{\"processId\":\"" + "x".repeat(101) + "\"}")) {
            mockMvc.perform(post("/api/caisse-epargne/auth/complete")
                    .contentType(MediaType.APPLICATION_JSON).content(body).with(from("127.0.0.1")))
                .andExpect(status().is4xxClientError());
        }
        verify(service, never()).completeAuth(any(), anyLong());
    }

    @Test
    void completeDoesNotSpendAnInitiateAttempt() throws Exception {
        when(service.completeAuth(anyString(), anyLong())).thenReturn(
            new CaisseEpargneSyncService.SessionStatusResponse(
                true, CaisseEpargneSyncStatus.IDLE, null, null, null, List.of()));

        for (int i = 0; i < 5; i++) {
            mockMvc.perform(post("/api/caisse-epargne/auth/complete")
                    .contentType(MediaType.APPLICATION_JSON).content("{\"processId\":\"p\"}").with(from("127.0.0.1")))
                .andExpect(status().isOk());
        }
        assertThat(authBuckets).isEmpty();
    }
}

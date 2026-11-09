package com.picsou.controller;

import com.picsou.config.RateLimitConfig;
import com.picsou.exception.GlobalExceptionHandler;
import com.picsou.exception.SyncException;
import com.picsou.model.SidecarSyncStatus;
import com.picsou.port.SidecarErrorCode;
import com.picsou.port.BanquePopulairePort;
import com.picsou.port.BanquePopulaireRegion;
import com.picsou.service.BpceSyncEngine;
import com.picsou.service.BanquePopulaireSyncService;
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
class BanquePopulaireControllerAuthTest {
    private static final Long MEMBER_ID = 7L;
    private static final String IMAGE = "data:image/png;base64,iVBORw0KGgo=";
    private static final String INITIATE_BODY = "{\"customerId\":\"12345678\",\"region\":\"BRED\"}";
    private static final String KEYPAD_BODY = "{\"processId\":\"proc-1\",\"positions\":[7,3,0,9,4,1]}";

    @Mock BanquePopulaireSyncService service;
    @Mock UserContext userContext;

    private ConcurrentHashMap<String, Bucket> authBuckets;
    private ConcurrentHashMap<String, Bucket> keypadBuckets;
    private ConcurrentHashMap<String, Bucket> syncBuckets;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        authBuckets = new ConcurrentHashMap<>();
        keypadBuckets = new ConcurrentHashMap<>();
        syncBuckets = new ConcurrentHashMap<>();
        BanquePopulaireController controller =
            new BanquePopulaireController(service, userContext, authBuckets, keypadBuckets, syncBuckets);
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
        return mockMvc.perform(post("/api/banque-populaire/auth/initiate")
            .contentType(MediaType.APPLICATION_JSON).content(body).with(from(ip)));
    }

    private org.springframework.test.web.servlet.ResultActions keypad(String body, String ip) throws Exception {
        return mockMvc.perform(post("/api/banque-populaire/auth/keypad")
            .contentType(MediaType.APPLICATION_JSON).content(body).with(from(ip)));
    }

    private static BpceSyncEngine.InitiateResponse challenge(String processId) {
        return new BpceSyncEngine.InitiateResponse(processId,
            new BanquePopulairePort.Keypad(java.util.Collections.nCopies(10, IMAGE), 5), 90);
    }

    // -- initiate -----------------------------------------------------------

    @Test
    void initiateScopesTheAttemptToTheCurrentMemberAndAnswersTheKeypad() throws Exception {
        when(service.initiateAuth("12345678", BanquePopulaireRegion.BRED, MEMBER_ID)).thenReturn(challenge("proc-1"));

        initiate(INITIATE_BODY, "127.0.0.1")
            .andExpect(status().isOk())
            .andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(jsonPath("$.processId").value("proc-1"))
            .andExpect(jsonPath("$.keypad.columns").value(5))
            .andExpect(jsonPath("$.keypad.images.length()").value(10))
            .andExpect(jsonPath("$.keypad.images[0]").value(IMAGE))
            .andExpect(jsonPath("$.expiresInSeconds").value(90))
            .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("12345678"))));

        verify(service).initiateAuth("12345678", BanquePopulaireRegion.BRED, MEMBER_ID);
    }

    @Test
    void initiateKeepsTheLetterOfABanquePopulaireIdentifier() throws Exception {
        // A letter then digits (observed live): it must reach the bank unchanged.
        when(service.initiateAuth("A1234567", BanquePopulaireRegion.RIVES_DE_PARIS, MEMBER_ID))
            .thenReturn(null);

        initiate("{\"customerId\":\"A1234567\",\"region\":\"RIVES_DE_PARIS\"}", "127.0.0.1");

        verify(service).initiateAuth("A1234567", BanquePopulaireRegion.RIVES_DE_PARIS, MEMBER_ID);
    }

    @Test
    void initiateRefusesAnInvalidBodyBeforeAnyBankCallAndWithoutConsumingAnAttempt() throws Exception {
        for (String body : List.of(
            "{}",
            "{\"customerId\":\"12345678\"}",
            "{\"region\":\"BRED\"}",
            "{\"customerId\":null,\"region\":\"BRED\"}",
            "{\"customerId\":12345678,\"region\":\"BRED\"}",
            "{\"customerId\":\"A123-4567\",\"region\":\"BRED\"}",
            "{\"customerId\":\"A123 4567\",\"region\":\"BRED\"}",
            "{\"customerId\":\"\u00c91234567\",\"region\":\"BRED\"}",
            "{\"customerId\":\"123456789012345678901\",\"region\":\"BRED\"}",
            "{\"customerId\":\"\"}",
            "{\"customerId\":\"\",\"region\":7}")) {
            initiate(body, "127.0.0.1").andExpect(status().is4xxClientError());
        }

        verify(service, never()).initiateAuth(any(), any(), anyLong());
        assertThat(authBuckets).isEmpty();
    }

    @Test
    void initiateWithAnEmptyIdentifierAsksTheServiceForTheRememberedOne() throws Exception {
        when(service.initiateAuth(null, null, MEMBER_ID)).thenReturn(challenge("proc-1"));

        initiate("{\"customerId\":\"\",\"region\":\"\"}", "127.0.0.1").andExpect(status().isOk());
        // A region sent along is ignored: the remembered identifier only goes back to its own bank.
        initiate("{\"customerId\":\"\",\"region\":\"BRED\"}", "127.0.0.1").andExpect(status().isOk());
        initiate("{\"customerId\":\"\",\"region\":\"NOT_A_REGION\"}", "127.0.0.1").andExpect(status().isOk());

        verify(service, times(3)).initiateAuth(null, null, MEMBER_ID);
    }

    @Test
    void initiatePassesEveryRegionToTheServiceByItsExactName() throws Exception {
        when(service.initiateAuth(anyString(), any(), anyLong())).thenReturn(challenge("proc"));
        for (BanquePopulaireRegion region : BanquePopulaireRegion.values()) {
            authBuckets.clear();
            initiate("{\"customerId\":\"12345678\",\"region\":\"" + region.name() + "\"}", "127.0.0.1")
                .andExpect(status().isOk());
            verify(service).initiateAuth("12345678", region, MEMBER_ID);
        }
        assertThat(BanquePopulaireRegion.values()).extracting(Enum::name).containsExactly(
            "ALSACE_LORRAINE_CHAMPAGNE", "AQUITAINE_CENTRE_ATLANTIQUE", "AUVERGNE_RHONE_ALPES",
            "BOURGOGNE_FRANCHE_COMTE", "BRED", "GRAND_OUEST", "MEDITERRANEE", "NORD", "OCCITANE",
            "RIVES_DE_PARIS", "SUD", "VAL_DE_FRANCE");
    }

    @Test
    void initiateRefusesAnUnknownOrMistypedRegionWithoutConsumingAnAttempt() throws Exception {
        for (String region : List.of("\"\"", "\"bred\"", "\" BRED\"", "\"PARIS\"", "\"RIVES DE PARIS\"",
            "null", "4", "[\"BRED\"]")) {
            initiate("{\"customerId\":\"12345678\",\"region\":" + region + "}", "127.0.0.1")
                .andExpect(status().isBadRequest())
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("12345678"))));
        }

        verify(service, never()).initiateAuth(anyString(), any(), anyLong());
        assertThat(authBuckets).isEmpty();
    }

    @Test
    void initiateNeverAcceptsAPasswordNorEchoesIt() throws Exception {
        // Lenient: this stub must stay unused, the request is refused before the service.
        lenient().when(service.initiateAuth("12345678", BanquePopulaireRegion.BRED, MEMBER_ID)).thenReturn(challenge("proc-1"));

        // an unexpected password field is not part of the contract: refused, never forwarded
        initiate("{\"customerId\":\"12345678\",\"region\":\"BRED\",\"password\":\"9482716350\"}", "127.0.0.1")
            .andExpect(status().is4xxClientError())
            .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("9482716350"))));
        verify(service, never()).initiateAuth(anyString(), any(), anyLong());
        assertThat(java.util.Arrays.stream(BanquePopulaireController.InitiateRequest.class.getRecordComponents())
            .map(c -> c.getName())).containsExactly("customerId", "region");
    }

    @Test
    void aMalformedBodyNeverEchoesTheIdentifier() throws Exception {
        initiate("{\"customerId\":\"12345678\"", "127.0.0.1")
            .andExpect(status().is4xxClientError())
            .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("12345678"))));
    }

    @Test
    void initiateAllowsThreeAttemptsPer15MinutesPerIpThenAnswers429WithRetryAfter() throws Exception {
        when(service.initiateAuth(anyString(), any(), anyLong())).thenReturn(challenge("proc"));

        for (int i = 0; i < 3; i++) {
            initiate(INITIATE_BODY, "127.0.0.1").andExpect(status().isOk());
        }
        initiate(INITIATE_BODY, "127.0.0.1")
            .andExpect(status().isTooManyRequests())
            .andExpect(header().exists("Retry-After"))
            .andExpect(result -> {
                long seconds = Long.parseLong(result.getResponse().getHeader("Retry-After"));
                assertThat(seconds).isBetween(1L, 15L * 60L);
            });

        verify(service, times(3)).initiateAuth(anyString(), any(), anyLong());
        assertThat(authBuckets).containsOnlyKeys("127.0.0.1");
        assertThat(keypadBuckets).isEmpty();
        assertThat(syncBuckets).isEmpty();
    }

    @Test
    void theInitiateRateLimitIsPerClientIp() throws Exception {
        when(service.initiateAuth(anyString(), any(), anyLong())).thenReturn(challenge("proc"));
        for (int i = 0; i < 3; i++) {
            initiate(INITIATE_BODY, "127.0.0.1").andExpect(status().isOk());
        }

        initiate(INITIATE_BODY, "127.0.0.2").andExpect(status().isOk());
    }

    @Test
    void aFailedLoginIsNotRetriedByTheControllerAndKeepsItsCode() throws Exception {
        when(service.initiateAuth("12345678", BanquePopulaireRegion.BRED, MEMBER_ID)).thenThrow(new SyncException(
            "Banque Populaire changed its login page", null,
            SidecarErrorCode.KEYPAD_CHANGED.name()));

        initiate(INITIATE_BODY, "127.0.0.1")
            .andExpect(status().isUnprocessableEntity())
            .andExpect(jsonPath("$.code").value("KEYPAD_CHANGED"))
            .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("12345678"))));

        verify(service, times(1)).initiateAuth(anyString(), any(), anyLong());
    }

    @Test
    void theRequestDtoNeverExposesTheIdentifierThroughToString() {
        var dto = new BanquePopulaireController.InitiateRequest("12345678", BanquePopulaireRegion.BRED);

        assertThat(dto.toString()).doesNotContain("12345678");
        assertThat(dto.customerId()).isEqualTo("12345678");
    }

    // -- keypad -------------------------------------------------------------

    @Test
    void keypadForwardsThePositionsForTheCurrentMemberAndAnswersSecurPassPending() throws Exception {
        keypad(KEYPAD_BODY, "127.0.0.1")
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.processId").value("proc-1"))
            .andExpect(jsonPath("$.status").value("SECURPASS_PENDING"))
            .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("positions"))));

        verify(service).submitKeypad("proc-1", List.of(7, 3, 0, 9, 4, 1), MEMBER_ID);
    }

    @Test
    void keypadRefusesAnInvalidBodyWithoutCallingAnythingOrSpendingTheBucket() throws Exception {
        for (String body : List.of(
            "{}",
            "{\"processId\":\"proc-1\"}",
            "{\"positions\":[1,2,3,4,5,6]}",
            "{\"processId\":\"\",\"positions\":[1,2,3,4,5,6]}",
            "{\"processId\":\"" + "x".repeat(101) + "\",\"positions\":[1,2,3,4,5,6]}",
            "{\"processId\":\"proc-1\",\"positions\":[1,2,3,4,5]}",
            "{\"processId\":\"proc-1\",\"positions\":[1,2,3,4,5,6,7,8,9,0,1,2,3]}",
            "{\"processId\":\"proc-1\",\"positions\":[1,2,3,4,5,10]}",
            "{\"processId\":\"proc-1\",\"positions\":[1,2,3,4,5,-1]}",
            "{\"processId\":\"proc-1\",\"positions\":[1,2,3,4,5,null]}",
            "{\"processId\":\"proc-1\",\"positions\":[1,2,3,4,5,\"a\"]}",
            "{\"processId\":\"proc-1\",\"positions\":\"123456\"}")) {
            keypad(body, "127.0.0.1").andExpect(status().is4xxClientError());
        }

        verify(service, never()).submitKeypad(any(), any(), anyLong());
        assertThat(keypadBuckets).isEmpty();
    }

    @Test
    void keypadAcceptsTheLengthBoundsSixAndTwelve() throws Exception {
        keypad("{\"processId\":\"a\",\"positions\":[0,1,2,3,4,5]}", "127.0.0.1").andExpect(status().isOk());
        keypad("{\"processId\":\"b\",\"positions\":[0,1,2,3,4,5,6,7,8,9,0,1]}", "127.0.0.1")
            .andExpect(status().isOk());
    }

    @Test
    void keypadErrorsNeverEchoThePositions() throws Exception {
        keypad("{\"processId\":\"proc-1\",\"positions\":[7,3,0,9,4,10]}", "127.0.0.1")
            .andExpect(status().is4xxClientError())
            .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("7,3,0"))));
        keypad("{\"processId\":\"proc-1\",\"positions\":[7,3,0,9,4,1]", "127.0.0.1")
            .andExpect(status().is4xxClientError())
            .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("7,3,0"))));
    }

    @Test
    void keypadSurfacesEverySidecarOutcomeAsATranslatableCode() throws Exception {
        for (SidecarErrorCode code : List.of(
            SidecarErrorCode.KEYPAD_CHANGED, SidecarErrorCode.KEYPAD_EXPIRED,
            SidecarErrorCode.INVALID_POSITIONS, SidecarErrorCode.AUTH_ATTEMPT_EXPIRED,
            SidecarErrorCode.INVALID_CREDENTIALS, SidecarErrorCode.UPSTREAM_UNAVAILABLE)) {
            org.mockito.Mockito.doThrow(new SyncException("failed", null, code.name()))
                .when(service).submitKeypad("proc-1", List.of(7, 3, 0, 9, 4, 1), MEMBER_ID);

            keypad(KEYPAD_BODY, "127.0.0." + code.ordinal())
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value(code.name()));
        }
    }

    @Test
    void keypadHasItsOwnBucketAndNeverDrawsFromTheInitiateOne() throws Exception {
        for (int i = 0; i < 3; i++) {
            initiate(INITIATE_BODY, "127.0.0.1");
        }
        keypad(KEYPAD_BODY, "127.0.0.1").andExpect(status().isOk());

        assertThat(keypadBuckets).containsOnlyKeys("127.0.0.1");
        assertThat(authBuckets).containsOnlyKeys("127.0.0.1");
        assertThat(syncBuckets).isEmpty();
    }

    @Test
    void keypadIsRateLimitedPerIpWithRetryAfter() throws Exception {
        io.github.bucket4j.Bucket bucket = RateLimitConfig.createBanquePopulaireKeypadBucket();
        int allowed = 0;
        while (bucket.tryConsume(1)) {
            allowed++;
        }
        assertThat(allowed).isBetween(3, 10);

        for (int i = 0; i < allowed; i++) {
            keypad(KEYPAD_BODY, "127.0.0.1").andExpect(status().isOk());
        }
        keypad(KEYPAD_BODY, "127.0.0.1")
            .andExpect(status().isTooManyRequests())
            .andExpect(header().exists("Retry-After"));
        keypad(KEYPAD_BODY, "127.0.0.2").andExpect(status().isOk());

        verify(service, times(allowed + 1)).submitKeypad(anyString(), any(), anyLong());
    }

    // -- complete -----------------------------------------------------------

    @Test
    void completeStoresTheSessionForTheCurrentMemberAndAnswersConnected() throws Exception {
        when(service.completeAuth("proc-1", MEMBER_ID)).thenReturn(
            new BanquePopulaireSyncService.SessionStatusResponse(
                true, SidecarSyncStatus.IDLE, null, null, null, List.of(), false));

        mockMvc.perform(post("/api/banque-populaire/auth/complete")
                .contentType(MediaType.APPLICATION_JSON).content("{\"processId\":\"proc-1\"}").with(from("127.0.0.1")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.connected").value(true))
            .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("sessionState"))));

        verify(service).completeAuth("proc-1", MEMBER_ID);
    }

    @Test
    void completeSurfacesEverySidecarOutcomeAsATranslatableCode() throws Exception {
        for (SidecarErrorCode code : List.of(
            SidecarErrorCode.APP_VALIDATION_TIMEOUT, SidecarErrorCode.AUTH_ATTEMPT_EXPIRED,
            SidecarErrorCode.INVALID_CREDENTIALS, SidecarErrorCode.UPSTREAM_UNAVAILABLE)) {
            org.mockito.Mockito.doThrow(new SyncException("failed", null, code.name()))
                .when(service).completeAuth("proc-1", MEMBER_ID);

            mockMvc.perform(post("/api/banque-populaire/auth/complete")
                    .contentType(MediaType.APPLICATION_JSON).content("{\"processId\":\"proc-1\"}").with(from("127.0.0.1")))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value(code.name()));
        }
    }

    @Test
    void completeRefusesABlankOrMissingProcessId() throws Exception {
        for (String body : List.of("{}", "{\"processId\":\"\"}", "{\"processId\":\"" + "x".repeat(101) + "\"}")) {
            mockMvc.perform(post("/api/banque-populaire/auth/complete")
                    .contentType(MediaType.APPLICATION_JSON).content(body).with(from("127.0.0.1")))
                .andExpect(status().is4xxClientError());
        }
        verify(service, never()).completeAuth(any(), anyLong());
    }

    @Test
    void completeDoesNotSpendAnInitiateAttempt() throws Exception {
        when(service.completeAuth(anyString(), anyLong())).thenReturn(
            new BanquePopulaireSyncService.SessionStatusResponse(
                true, SidecarSyncStatus.IDLE, null, null, null, List.of(), false));

        for (int i = 0; i < 5; i++) {
            mockMvc.perform(post("/api/banque-populaire/auth/complete")
                    .contentType(MediaType.APPLICATION_JSON).content("{\"processId\":\"p\"}").with(from("127.0.0.1")))
                .andExpect(status().isOk());
        }
        assertThat(authBuckets).isEmpty();
    }
}

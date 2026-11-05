package com.picsou.controller;

import com.picsou.adapter.TradeRepublicAdapter.TrInitiate;
import com.picsou.config.RateLimitConfig;
import com.picsou.exception.GlobalExceptionHandler;
import com.picsou.service.TradeRepublicSyncService;
import com.picsou.service.UserContext;
import io.github.bucket4j.Bucket;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** {@code /api/tr/auth/*}: stored-phone reconnect, password bounds, per-IP rate limit, no secret in logs. */
@ExtendWith({MockitoExtension.class, OutputCaptureExtension.class})
class TradeRepublicControllerTest {

    private static final String PASSWORD = "correct-horse-battery-9!";
    private static final String PHONE = "+33612345699";

    @Mock TradeRepublicSyncService trService;
    @Mock UserContext userContext;

    Map<String, Bucket> buckets;
    MockMvc mvc;

    @BeforeEach
    void setUp() {
        buckets = new HashMap<>();
        var validator = new LocalValidatorFactoryBean();
        validator.afterPropertiesSet();
        mvc = MockMvcBuilders
            .standaloneSetup(new TradeRepublicController(trService, userContext, buckets,
                new RateLimitConfig().syncBuckets()))
            .setControllerAdvice(new GlobalExceptionHandler())
            .setValidator(validator)
            .build();
    }

    private org.springframework.test.web.servlet.ResultActions postJson(String path, String body) throws Exception {
        return mvc.perform(post(path).contentType(MediaType.APPLICATION_JSON).content(body)
            .with(request -> {
                request.setRemoteAddr("10.0.0.5");
                return request;
            }));
    }

    private static TradeRepublicSyncService.AuthInitResponse init(String flow) {
        return new TradeRepublicSyncService.AuthInitResponse("p1", flow, 120);
    }

    @Test
    void tenSyncsArePermittedThenTheEleventhIsRateLimited() throws Exception {
        when(userContext.currentMemberId()).thenReturn(7L);
        for (int attempt = 0; attempt < 10; attempt++) {
            postJson("/api/tr/sync", "{}").andExpect(status().isOk());
        }

        postJson("/api/tr/sync", "{}").andExpect(status().isTooManyRequests());

        verify(trService, times(10)).queueSync(7L);
    }

    @Test
    void reconnect_usesTheStoredPhoneAndTheGivenPin() throws Exception {
        when(userContext.currentMemberId()).thenReturn(7L);
        when(trService.storedPhone(7L)).thenReturn(Optional.of(PHONE));
        when(trService.initiateAuth(PHONE, "1234")).thenReturn(init("confirmInApp"));

        postJson("/api/tr/auth/reconnect", "{\"pin\":\"1234\"}")
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.processId").value("p1"))
            .andExpect(jsonPath("$.flow").value("confirmInApp"))
            .andExpect(jsonPath("$.countdownInSeconds").value(120));
        verify(trService).initiateAuth(PHONE, "1234");
    }

    @Test
    void reconnect_worksOnADeactivatedSessionAndRelaysTheAuthenticatorFlow() throws Exception {
        // storedPhone reads the row whether it is active or deactivated (SESSION_EXPIRED): see the service test.
        when(userContext.currentMemberId()).thenReturn(7L);
        when(trService.storedPhone(7L)).thenReturn(Optional.of(PHONE));
        when(trService.initiateAuth(PHONE, PASSWORD)).thenReturn(init("authenticator"));

        postJson("/api/tr/auth/reconnect", "{\"pin\":\"" + PASSWORD + "\"}")
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.flow").value("authenticator"));
    }

    @Test
    void reconnect_withoutAStoredPhoneIs409InvalidSessionState() throws Exception {
        when(userContext.currentMemberId()).thenReturn(7L);
        when(trService.storedPhone(7L)).thenReturn(Optional.empty());

        postJson("/api/tr/auth/reconnect", "{\"pin\":\"1234\"}")
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("INVALID_SESSION_STATE"));
        verify(trService, never()).initiateAuth(anyString(), anyString());
    }

    @Test
    void reconnect_rejectsAnEmptyOrTooLongPin() throws Exception {
        postJson("/api/tr/auth/reconnect", "{\"pin\":\"\"}").andExpect(status().isUnprocessableEntity());
        postJson("/api/tr/auth/reconnect", "{}").andExpect(status().isUnprocessableEntity());
        postJson("/api/tr/auth/reconnect", "{\"pin\":\"" + "a".repeat(129) + "\"}")
            .andExpect(status().isUnprocessableEntity());
        verify(trService, never()).initiateAuth(anyString(), anyString());
    }

    @Test
    void reconnect_acceptsTheBoundaryLengths() throws Exception {
        when(userContext.currentMemberId()).thenReturn(7L);
        when(trService.storedPhone(7L)).thenReturn(Optional.of(PHONE));
        when(trService.initiateAuth(anyString(), anyString())).thenReturn(init("otp"));

        postJson("/api/tr/auth/reconnect", "{\"pin\":\"x\"}").andExpect(status().isOk());
        postJson("/api/tr/auth/reconnect", "{\"pin\":\"" + "a".repeat(128) + "\"}").andExpect(status().isOk());
    }

    @Test
    void initiate_acceptsA20CharacterPassword() throws Exception {
        String password = "a1B2c3D4e5F6g7H8i9J0";
        when(trService.initiateAuth(PHONE, password)).thenReturn(init("otp"));

        postJson("/api/tr/auth/initiate", "{\"phoneNumber\":\"" + PHONE + "\",\"pin\":\"" + password + "\"}")
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.flow").value("otp"));
    }

    @Test
    void initiate_stillRequiresAPhoneAndAPin() throws Exception {
        postJson("/api/tr/auth/initiate", "{\"phoneNumber\":\"\",\"pin\":\"1234\"}")
            .andExpect(status().isUnprocessableEntity());
        postJson("/api/tr/auth/initiate", "{\"phoneNumber\":\"" + PHONE + "\"}")
            .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void complete_acceptsFourOrSixDigitsOrNoCode() throws Exception {
        when(userContext.currentMemberId()).thenReturn(7L);
        when(trService.completeAuth(anyString(), any(), any())).thenReturn(null);

        postJson("/api/tr/auth/complete", "{\"processId\":\"p1\",\"tan\":\"1234\"}").andExpect(status().isOk());
        postJson("/api/tr/auth/complete", "{\"processId\":\"p1\",\"tan\":\"123456\"}").andExpect(status().isOk());
        postJson("/api/tr/auth/complete", "{\"processId\":\"p1\"}").andExpect(status().isOk());
        postJson("/api/tr/auth/complete", "{\"processId\":\"p1\",\"tan\":null}").andExpect(status().isOk());
    }

    @Test
    void complete_rejectsACodeThatIsNeitherFourNorSixDigits() throws Exception {
        postJson("/api/tr/auth/complete", "{\"processId\":\"p1\",\"tan\":\"12345\"}")
            .andExpect(status().isUnprocessableEntity());
        postJson("/api/tr/auth/complete", "{\"processId\":\"p1\",\"tan\":\"12ab\"}")
            .andExpect(status().isUnprocessableEntity());
        verify(trService, never()).completeAuth(anyString(), any(), any());
    }

    @Test
    void reconnect_sharesTheAuthRateLimitWithInitiate() throws Exception {
        when(userContext.currentMemberId()).thenReturn(7L);
        when(trService.storedPhone(7L)).thenReturn(Optional.of(PHONE));
        when(trService.initiateAuth(anyString(), anyString())).thenReturn(init("otp"));
        int ok = 0;
        int limited = 0;
        for (int i = 0; i < 20; i++) {
            int code = postJson("/api/tr/auth/reconnect", "{\"pin\":\"1234\"}").andReturn().getResponse().getStatus();
            if (code == 200) {
                ok++;
            } else if (code == 429) {
                limited++;
            }
        }
        assertThat(ok).isPositive();
        assertThat(limited).isPositive();
        assertThat(postJson("/api/tr/auth/initiate", "{\"phoneNumber\":\"" + PHONE + "\",\"pin\":\"1234\"}")
            .andReturn().getResponse().getStatus()).isEqualTo(429);
    }

    @Test
    void thePinAndThePhoneNeverReachTheLogs(CapturedOutput output) throws Exception {
        when(userContext.currentMemberId()).thenReturn(7L);
        when(trService.storedPhone(7L)).thenReturn(Optional.of(PHONE));
        when(trService.initiateAuth(PHONE, PASSWORD)).thenReturn(init("confirmInApp"));

        postJson("/api/tr/auth/reconnect", "{\"pin\":\"" + PASSWORD + "\"}").andExpect(status().isOk());
        postJson("/api/tr/auth/reconnect", "{\"pin\":\"" + "a".repeat(129) + PASSWORD + "\"}")
            .andExpect(status().isUnprocessableEntity());

        assertThat(output.getAll()).doesNotContain(PASSWORD).doesNotContain(PHONE).doesNotContain("612345699");
    }
}

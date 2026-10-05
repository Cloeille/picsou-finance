package com.picsou.controller;

import com.picsou.exception.GlobalExceptionHandler;
import com.picsou.model.DegiroSessionStatus;
import com.picsou.service.DegiroSyncService;
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
class DegiroControllerTest {
    private static final Long MEMBER_ID = 7L;

    @Mock DegiroSyncService service;
    @Mock UserContext userContext;
    @Mock HttpServletRequest request;

    private DegiroController controller;
    private ConcurrentHashMap<String, Bucket> authBuckets;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        authBuckets = new ConcurrentHashMap<>();
        controller = new DegiroController(service, userContext, authBuckets);
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();
        lenient().when(userContext.currentMemberId()).thenReturn(MEMBER_ID);
    }

    @Test
    void initiateScopesAuthenticationToTheCurrentMember() {
        when(request.getRemoteAddr()).thenReturn("127.0.0.1");
        var expected = new DegiroSyncService.AuthInitResponse("process", true);
        when(service.initiateAuth("user", "password", MEMBER_ID)).thenReturn(expected);

        var response = controller.initiateAuth(
            new DegiroController.InitiateAuthRequest("user", "password"),
            request
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isSameAs(expected);
        verify(service).initiateAuth("user", "password", MEMBER_ID);
    }

    @Test
    void authenticationRateLimitUsesTheTrustedProxyClientIp() {
        var proxiedRequest = new MockHttpServletRequest();
        proxiedRequest.setRemoteAddr("172.18.0.2");
        proxiedRequest.addHeader("X-Forwarded-For", "1.2.3.4");
        proxiedRequest.addHeader("X-Real-IP", "203.0.113.9");
        when(service.initiateAuth("user", "password", MEMBER_ID))
            .thenReturn(new DegiroSyncService.AuthInitResponse("process", true));

        controller.initiateAuth(new DegiroController.InitiateAuthRequest("user", "password"), proxiedRequest);

        assertThat(authBuckets).containsOnlyKeys("203.0.113.9");
    }

    @Test
    void completeAuthScopesTheTotpCodeToTheCurrentMember() throws Exception {
        when(service.completeAuth("process-123", "123456", MEMBER_ID))
            .thenReturn(new DegiroSyncService.SessionStatusResponse(true, DegiroSessionStatus.ACTIVE, null));

        mockMvc.perform(post("/api/degiro/auth/complete")
                .with(req -> {
                    req.setRemoteAddr("127.0.0.1");
                    return req;
                })
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"processId\":\"process-123\",\"code\":\"123456\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("ACTIVE"));

        verify(service).completeAuth("process-123", "123456", MEMBER_ID);
    }

    /**
     * The TOTP endpoint is throttled like /auth/initiate: the 6-digit space is only 1M,
     * so an unthrottled verify endpoint is brute-forceable once a processId is known.
     */
    @Test
    void completeAuthIsRateLimitedPerIp() {
        var proxiedRequest = new MockHttpServletRequest();
        proxiedRequest.setRemoteAddr("172.18.0.2");
        proxiedRequest.addHeader("X-Real-IP", "203.0.113.9");
        when(service.completeAuth("process", "123456", MEMBER_ID))
            .thenReturn(new DegiroSyncService.SessionStatusResponse(true, DegiroSessionStatus.ACTIVE, null));

        controller.completeAuth(new DegiroController.CompleteAuthRequest("process", "123456"), proxiedRequest);

        assertThat(authBuckets).containsOnlyKeys("203.0.113.9");
    }

    @Test
    void authenticationPayloadValidationRejectsOversizedUsernameAndMalformedTotpCode() throws Exception {
        mockMvc.perform(post("/api/degiro/auth/initiate")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"" + "x".repeat(101) + "\",\"password\":\"secret\"}"))
            .andExpect(status().isUnprocessableEntity())
            .andExpect(jsonPath("$.errors.username").exists());

        mockMvc.perform(post("/api/degiro/auth/complete")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"processId\":\"process\",\"code\":\"12ab\"}"))
            .andExpect(status().isUnprocessableEntity())
            .andExpect(jsonPath("$.errors.code").exists());

        verify(service, times(0)).initiateAuth(anyString(), anyString(), anyLong());
        verify(service, times(0)).completeAuth(anyString(), anyString(), anyLong());
    }

    @Test
    void syncAndStatusResolveTheMemberFromTheUserContext() {
        controller.getStatus();
        verify(service).getSessionStatus(MEMBER_ID);

        controller.clearSession();
        verify(service).clearSession(MEMBER_ID);
    }
}

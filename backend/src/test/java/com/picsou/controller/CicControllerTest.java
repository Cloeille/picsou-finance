package com.picsou.controller;

import com.picsou.config.RateLimitConfig;
import com.picsou.service.CicSyncService;
import com.picsou.service.UserContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class CicControllerTest {
    private static final Long MEMBER_ID = 7L;
    private static final String LOGIN = "{\"login\":\"12345678\",\"password\":\"secret\"}";
    private static final String COMPLETION = "{\"processId\":\"pending\",\"code\":\"123456\"}";

    @Mock CicSyncService service;
    @Mock UserContext userContext;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        RateLimitConfig config = new RateLimitConfig();
        CicController controller = new CicController(service, userContext,
            config.cicAuthBuckets(), config.cicCompleteBuckets(), config.syncBuckets());
        mvc = MockMvcBuilders.standaloneSetup(controller).build();
        when(userContext.currentMemberId()).thenReturn(MEMBER_ID);
    }

    @Test
    void fiveCombinedInitiateAndReconnectRequestsLeaveCompletionAvailable() throws Exception {
        for (int attempt = 0; attempt < 5; attempt++) {
            request(attempt % 2 == 0 ? "initiate" : "reconnect", LOGIN).andExpect(status().isOk());
        }

        request("initiate", LOGIN).andExpect(status().isTooManyRequests());
        request("reconnect", "").andExpect(status().isTooManyRequests());
        request("complete", COMPLETION).andExpect(status().isOk());

        verify(service, times(3)).initiateAuth("12345678", "secret", MEMBER_ID);
        verify(service, times(2)).reconnect(MEMBER_ID);
        verify(service).completeAuth("pending", "123456", MEMBER_ID);
    }

    @Test
    void tenCompletionsArePermittedThenTheEleventhIsRateLimited() throws Exception {
        for (int attempt = 0; attempt < 10; attempt++) {
            request("complete", COMPLETION).andExpect(status().isOk());
        }

        request("complete", COMPLETION).andExpect(status().isTooManyRequests());

        verify(service, times(10)).completeAuth("pending", "123456", MEMBER_ID);
    }

    @Test
    void completionRequestsLeaveAllFivePasswordAttemptsAvailable() throws Exception {
        for (int attempt = 0; attempt < 10; attempt++) {
            request("complete", COMPLETION).andExpect(status().isOk());
        }
        for (int attempt = 0; attempt < 5; attempt++) {
            request("initiate", LOGIN).andExpect(status().isOk());
        }

        request("initiate", LOGIN).andExpect(status().isTooManyRequests());

        verify(service, times(5)).initiateAuth("12345678", "secret", MEMBER_ID);
        verify(service, times(10)).completeAuth("pending", "123456", MEMBER_ID);
    }

    private ResultActions request(String action, String body) throws Exception {
        return mvc.perform(post("/api/cic/auth/" + action)
            .with(request -> {
                request.setRemoteAddr("192.0.2.1");
                return request;
            })
            .contentType(MediaType.APPLICATION_JSON)
            .content(body));
    }
}

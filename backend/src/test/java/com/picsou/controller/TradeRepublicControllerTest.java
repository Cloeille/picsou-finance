package com.picsou.controller;

import com.picsou.config.RateLimitConfig;
import com.picsou.service.TradeRepublicSyncService;
import com.picsou.service.UserContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class TradeRepublicControllerTest {
    private static final Long MEMBER_ID = 7L;

    @Mock TradeRepublicSyncService service;
    @Mock UserContext userContext;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        RateLimitConfig config = new RateLimitConfig();
        TradeRepublicController controller = new TradeRepublicController(service, userContext,
            config.trAuthBuckets(), config.syncBuckets());
        mvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    @Test
    void tenSyncsArePermittedThenTheEleventhIsRateLimited() throws Exception {
        when(userContext.currentMemberId()).thenReturn(MEMBER_ID);
        for (int attempt = 0; attempt < 10; attempt++) {
            sync().andExpect(status().isOk());
        }

        sync().andExpect(status().isTooManyRequests());

        verify(service, times(10)).queueSync(MEMBER_ID);
    }

    private ResultActions sync() throws Exception {
        return mvc.perform(post("/api/tr/sync").with(request -> {
            request.setRemoteAddr("192.0.2.1");
            return request;
        }));
    }
}

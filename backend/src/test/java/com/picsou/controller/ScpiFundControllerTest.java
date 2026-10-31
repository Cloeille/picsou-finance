package com.picsou.controller;

import com.picsou.exception.GlobalExceptionHandler;
import com.picsou.model.ScpiProvider;
import com.picsou.service.ScpiFundService;
import com.picsou.service.UserContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class ScpiFundControllerTest {

    @Mock ScpiFundService service;
    @Mock UserContext userContext;
    MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.standaloneSetup(new ScpiFundController(service, userContext))
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();
    }

    @Test
    void ignore_actsOnTheCurrentMemberOnly() throws Exception {
        when(userContext.currentMemberId()).thenReturn(7L);

        mvc.perform(post("/api/scpi/funds/CORUM/XL/ignore")).andExpect(status().isNoContent());

        verify(service).ignore(7L, ScpiProvider.CORUM, "XL");
    }

    @Test
    void anUnknownProviderIsRefusedBeforeTheService() throws Exception {
        mvc.perform(post("/api/scpi/funds/AMUNDI/XL/ignore")).andExpect(status().is4xxClientError());

        verify(service, never()).ignore(any(), any(), any());
    }
}

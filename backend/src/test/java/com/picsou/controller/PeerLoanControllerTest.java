package com.picsou.controller;

import com.picsou.service.PeerLoanService;
import com.picsou.service.UserContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The controller's job is thin: resolve the member, validate the body, delegate. These
 * tests pin that wiring, including the request bounds that keep the calculator finite.
 */
@ExtendWith(MockitoExtension.class)
class PeerLoanControllerTest {

    @Mock PeerLoanService peerLoanService;
    @Mock UserContext userContext;

    MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders
            .standaloneSetup(new PeerLoanController(peerLoanService, userContext))
            .build();
    }

    @Test
    void getDelegatesToTheCurrentMember() throws Exception {
        when(userContext.currentMemberId()).thenReturn(7L);
        when(peerLoanService.get(16L, 7L)).thenReturn(null);

        mvc.perform(get("/api/accounts/16/peer-loan"))
            .andExpect(status().isOk());

        verify(peerLoanService).get(16L, 7L);
    }

    @Test
    void saveTermsRejectsAnInvalidBody() throws Exception {
        mvc.perform(put("/api/accounts/16/peer-loan")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"principal\":0}"))
            .andExpect(status().isBadRequest());

        verifyNoInteractions(peerLoanService);
    }

    @Test
    void addRepaymentReturnsCreatedAndDelegates() throws Exception {
        when(userContext.currentMemberId()).thenReturn(7L);
        when(peerLoanService.addRepayment(org.mockito.ArgumentMatchers.eq(16L),
            org.mockito.ArgumentMatchers.eq(7L), org.mockito.ArgumentMatchers.any()))
            .thenReturn(null);

        mvc.perform(post("/api/accounts/16/peer-loan/repayments")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"paidOn\":\"2026-01-15\",\"amount\":100}"))
            .andExpect(status().isCreated());

        verify(peerLoanService).addRepayment(org.mockito.ArgumentMatchers.eq(16L),
            org.mockito.ArgumentMatchers.eq(7L), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void simulateRejectsAnUnboundedMonthCount() throws Exception {
        mvc.perform(post("/api/peer-loans/simulate")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"principal\":1000,\"months\":1201}"))
            .andExpect(status().isBadRequest());

        verifyNoInteractions(peerLoanService);
    }
}

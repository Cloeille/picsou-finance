package com.picsou.controller;

import com.picsou.exception.GlobalExceptionHandler;
import com.picsou.exception.ResourceNotFoundException;
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
import static org.assertj.core.api.Assertions.assertThat;
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
            .setControllerAdvice(new GlobalExceptionHandler())
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
            .andExpect(status().isUnprocessableEntity());

        verifyNoInteractions(peerLoanService);
    }

    @Test
    void getMapsAnotherMembersAccountTo404() throws Exception {
        when(userContext.currentMemberId()).thenReturn(7L);
        when(peerLoanService.get(16L, 7L)).thenThrow(new ResourceNotFoundException("Account not found"));

        mvc.perform(get("/api/accounts/16/peer-loan"))
            .andExpect(status().isNotFound());
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
    void simulateAcceptsAnUnroundedRate() throws Exception {
        when(peerLoanService.simulate(org.mockito.ArgumentMatchers.any())).thenReturn(null);

        // A 1.1 % rate arrives as a floating-point double with 17 fraction digits; the
        // service owns the rounding, so validation must not reject it as a 422.
        mvc.perform(post("/api/peer-loans/simulate")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"principal\":1000,\"months\":12,\"annualRate\":0.011000000000000001}"))
            .andExpect(status().isOk());
    }

    @Test
    void simulateRejectsAnUnboundedMonthCount() throws Exception {
        mvc.perform(post("/api/peer-loans/simulate")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"principal\":1000,\"months\":1201}"))
            .andExpect(status().isUnprocessableEntity());

        verifyNoInteractions(peerLoanService);
    }

    @Test
    void saveTermsRejectsATinyExponentRateFast() throws Exception {
        // Same guard as simulate: saveTerms rounds the rate inside a transaction, so a tiny
        // exponent must be refused by validation before the service is called.
        long start = System.currentTimeMillis();
        mvc.perform(put("/api/accounts/16/peer-loan")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"principal\":1000,\"lentAt\":\"2026-01-01\",\"linkedAccountId\":3,"
                    + "\"annualRate\":1E-100000000}"))
            .andExpect(status().isUnprocessableEntity());
        assertThat(System.currentTimeMillis() - start).isLessThan(5000);

        verifyNoInteractions(peerLoanService);
    }

    @Test
    void simulateRejectsATinyExponentRateFast() throws Exception {
        // A tiny exponent would pin a thread in setScale if it reached the calculator:
        // validation must reject it before the service is ever called.
        long start = System.currentTimeMillis();
        mvc.perform(post("/api/peer-loans/simulate")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"principal\":1000,\"months\":12,\"annualRate\":1E-100000000}"))
            .andExpect(status().isUnprocessableEntity());
        assertThat(System.currentTimeMillis() - start).isLessThan(5000);

        verifyNoInteractions(peerLoanService);
    }
}

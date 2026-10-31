package com.picsou.controller;

import com.picsou.dto.CashflowPeriod;
import com.picsou.service.UserContext;
import com.picsou.service.budget.CashflowFlowService;
import com.picsou.service.budget.CashflowService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests proving the controller forwards a supplied {@code anchor} to the service,
 * and falls back to today when the param is absent.
 */
@ExtendWith(MockitoExtension.class)
class CashflowControllerTest {

    @Mock CashflowService cashflowService;
    @Mock CashflowFlowService cashflowFlowService;
    @Mock UserContext userContext;

    CashflowController controller;

    private static final Long MEMBER_ID = 7L;
    private static final LocalDate ANCHOR = LocalDate.of(2024, 3, 10);

    @BeforeEach
    void setUp() {
        controller = new CashflowController(cashflowService, cashflowFlowService, userContext);
        when(userContext.currentMemberId()).thenReturn(MEMBER_ID);
    }

    @Test
    void cashflow_withAnchor_forwardsAnchorToService() {
        when(cashflowService.compute(any(), any(), any())).thenReturn(null);

        controller.cashflow(CashflowPeriod.CYCLE, ANCHOR);

        verify(cashflowService).compute(MEMBER_ID, CashflowPeriod.CYCLE, ANCHOR);
    }

    @Test
    void cashflow_withoutAnchor_forwardsTodayToService() {
        when(cashflowService.compute(any(), any(), any())).thenReturn(null);
        ArgumentCaptor<LocalDate> dateCaptor = ArgumentCaptor.forClass(LocalDate.class);

        controller.cashflow(CashflowPeriod.CYCLE, null);

        verify(cashflowService).compute(eq(MEMBER_ID), eq(CashflowPeriod.CYCLE), dateCaptor.capture());
        assertThat(dateCaptor.getValue()).isEqualTo(LocalDate.now());
    }

    @Test
    void flow_withAnchor_forwardsAnchorToService() {
        when(cashflowFlowService.flow(any(), any(), any())).thenReturn(null);

        controller.flow(CashflowPeriod.CYCLE, ANCHOR);

        verify(cashflowFlowService).flow(MEMBER_ID, CashflowPeriod.CYCLE, ANCHOR);
    }
}

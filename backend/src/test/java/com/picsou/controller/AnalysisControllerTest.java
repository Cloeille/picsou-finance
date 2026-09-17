package com.picsou.controller;

import com.picsou.dto.AllocationTargetsRequest;
import com.picsou.dto.AllocationTargetsResponse;
import com.picsou.dto.EssentialExpenseEstimateResponse;
import com.picsou.dto.WealthPyramidResponse;
import com.picsou.service.AllocationTargetService;
import com.picsou.service.EssentialExpenseEstimator;
import com.picsou.service.UserContext;
import com.picsou.service.WealthPyramidService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AnalysisControllerTest {

    private static final Long MEMBER = 42L;

    @Mock WealthPyramidService pyramidService;
    @Mock AllocationTargetService allocationTargetService;
    @Mock EssentialExpenseEstimator expenseEstimator;
    @Mock UserContext userContext;

    @InjectMocks AnalysisController controller;

    @BeforeEach
    void scopeToCurrentMember() {
        when(userContext.currentMemberId()).thenReturn(MEMBER);
    }

    @Test
    void pyramidIsScopedToTheMemberFromTheUserContext() {
        WealthPyramidResponse expected = new WealthPyramidResponse(
            BigDecimal.TEN, BigDecimal.TEN,
            new WealthPyramidResponse.SafetyNet(
                BigDecimal.ZERO, BigDecimal.ZERO, null, null, BigDecimal.ZERO, false, null),
            List.of(),
            new WealthPyramidResponse.Score(70, 70, BigDecimal.ZERO, BigDecimal.ZERO,
                BigDecimal.ZERO, null, null),
            List.of());
        when(pyramidService.pyramid(MEMBER)).thenReturn(expected);

        assertThat(controller.pyramid()).isSameAs(expected);
        // The scoping contract at this layer: the member id never comes from the request body
        // or a path variable, only from UserContext.
        verify(pyramidService).pyramid(MEMBER);
    }

    @Test
    void targetsAreScopedToTheMemberFromTheUserContext() {
        AllocationTargetsResponse expected = new AllocationTargetsResponse(
            null, (short) 6, new BigDecimal("30"), new BigDecimal("50"),
            new BigDecimal("10"), new BigDecimal("10"));
        when(allocationTargetService.get(MEMBER)).thenReturn(expected);

        assertThat(controller.targets()).isSameAs(expected);
    }

    @Test
    void replacingTargetsPassesTheMemberAndTheBodyThrough() {
        AllocationTargetsRequest request = new AllocationTargetsRequest(
            new BigDecimal("1850"), (short) 6, new BigDecimal("30"), new BigDecimal("50"),
            new BigDecimal("10"), new BigDecimal("10"));
        AllocationTargetsResponse expected = new AllocationTargetsResponse(
            new BigDecimal("1850"), (short) 6, new BigDecimal("30"), new BigDecimal("50"),
            new BigDecimal("10"), new BigDecimal("10"));
        when(allocationTargetService.replace(MEMBER, request)).thenReturn(expected);

        assertThat(controller.replaceTargets(request)).isSameAs(expected);
        verify(allocationTargetService).replace(MEMBER, request);
    }

    @Test
    void expenseEstimateIsScopedToTheMemberFromTheUserContext() {
        EssentialExpenseEstimateResponse expected =
            new EssentialExpenseEstimateResponse(new BigDecimal("1912.40"), 6, 11);
        when(expenseEstimator.estimate(MEMBER)).thenReturn(expected);

        assertThat(controller.expenseEstimate()).isSameAs(expected);
        verify(expenseEstimator).estimate(MEMBER);
    }
}

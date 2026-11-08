package com.picsou.mcp.tools;

import com.picsou.dto.AccountResponse;
import com.picsou.dto.DashboardResponse;
import com.picsou.dto.PnlResponse;
import com.picsou.service.AccountService;
import com.picsou.service.DashboardService;
import com.picsou.service.FamilyViewService;
import com.picsou.service.HistoryService;
import com.picsou.service.PriceService;
import com.picsou.service.UserContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Read-only "insight" tools (dashboard, net-worth history, PnL, family view, price). Each resolves
 * the caller's member via {@link UserContext} and delegates to the relevant already member-scoped
 * service. History/PnL default their {@code accountIds} to <em>all</em> of the member's accounts
 * when the caller omits them — mirroring "show me my whole net worth".
 */
@ExtendWith(MockitoExtension.class)
class InsightToolsTest {

    private static final long MID = 7L;

    @Mock DashboardService dashboardService;
    @Mock HistoryService historyService;
    @Mock PriceService priceService;
    @Mock FamilyViewService familyViewService;
    @Mock AccountService accountService;
    @Mock UserContext userContext;
    @InjectMocks InsightTools tools;

    @Test
    void getDashboard_delegatesScopedToCurrentMember() {
        DashboardResponse r = mock(DashboardResponse.class);
        when(userContext.currentMemberId()).thenReturn(MID);
        when(dashboardService.getDashboard(MID, "1Y")).thenReturn(r);

        assertThat(tools.getDashboard("1Y")).isSameAs(r);
    }

    @Test
    void getNetWorthHistory_defaultsToAllMemberAccountsAndMonths() {
        AccountResponse a1 = mock(AccountResponse.class);
        AccountResponse a2 = mock(AccountResponse.class);
        when(a1.id()).thenReturn(1L);
        when(a2.id()).thenReturn(2L);
        DashboardResponse.NetWorthPoint p = mock(DashboardResponse.NetWorthPoint.class);
        when(userContext.currentMemberId()).thenReturn(MID);
        when(accountService.findAll(MID)).thenReturn(List.of(a1, a2));
        when(historyService.buildHistory(List.of(1L, 2L), 12, MID)).thenReturn(List.of(p));

        assertThat(tools.getNetWorthHistory(null, null)).containsExactly(p);
    }

    @Test
    void getNetWorthHistory_defaultLeavesOutPocketsTheirWalletAlreadyCarries() {
        // Wallet 1 carries pocket 2's balance; pocket 3's wallet (99) is not in the list.
        AccountResponse wallet = mock(AccountResponse.class);
        AccountResponse pocket = mock(AccountResponse.class);
        AccountResponse orphanPocket = mock(AccountResponse.class);
        when(wallet.id()).thenReturn(1L);
        when(pocket.id()).thenReturn(2L);
        when(pocket.parentAccountId()).thenReturn(1L);
        when(orphanPocket.id()).thenReturn(3L);
        when(orphanPocket.parentAccountId()).thenReturn(99L);
        when(userContext.currentMemberId()).thenReturn(MID);
        when(accountService.findAll(MID)).thenReturn(List.of(wallet, pocket, orphanPocket));

        tools.getNetWorthHistory(null, null);

        verify(historyService).buildHistory(List.of(1L, 3L), 12, MID);
    }

    @Test
    void getProfitAndLoss_defaultLeavesOutPocketsTheirWalletAlreadyCarries() {
        AccountResponse wallet = mock(AccountResponse.class);
        AccountResponse pocket = mock(AccountResponse.class);
        when(wallet.id()).thenReturn(1L);
        when(pocket.id()).thenReturn(2L);
        when(pocket.parentAccountId()).thenReturn(1L);
        when(userContext.currentMemberId()).thenReturn(MID);
        when(accountService.findAll(MID)).thenReturn(List.of(wallet, pocket));

        tools.getProfitAndLoss(null, null);

        verify(historyService).buildPnl(List.of(1L), MID, null);
    }

    @Test
    void getNetWorthHistory_passesExplicitAccountIdsAndMonths() {
        DashboardResponse.NetWorthPoint p = mock(DashboardResponse.NetWorthPoint.class);
        when(userContext.currentMemberId()).thenReturn(MID);
        when(historyService.buildHistory(List.of(9L), 6, MID)).thenReturn(List.of(p));

        assertThat(tools.getNetWorthHistory(List.of(9L), 6)).containsExactly(p);
        verifyNoInteractions(accountService);   // explicit ids → no need to resolve all accounts
    }

    @Test
    void getProfitAndLoss_defaultsToAllMemberAccounts_nullFromDate() {
        AccountResponse a1 = mock(AccountResponse.class);
        when(a1.id()).thenReturn(1L);
        PnlResponse pnl = mock(PnlResponse.class);
        when(userContext.currentMemberId()).thenReturn(MID);
        when(accountService.findAll(MID)).thenReturn(List.of(a1));
        when(historyService.buildPnl(List.of(1L), MID, null)).thenReturn(pnl);

        assertThat(tools.getProfitAndLoss(null, null)).isSameAs(pnl);
    }

    @Test
    void getProfitAndLoss_passesExplicitAccountIdsAndFromDate() {
        PnlResponse pnl = mock(PnlResponse.class);
        LocalDate from = LocalDate.of(2026, 1, 1);
        when(userContext.currentMemberId()).thenReturn(MID);
        when(historyService.buildPnl(List.of(9L), MID, from)).thenReturn(pnl);

        assertThat(tools.getProfitAndLoss(List.of(9L), from)).isSameAs(pnl);
        verifyNoInteractions(accountService);
    }

    @Test
    void getFamilyDashboard_delegatesScopedToCurrentMember() {
        com.picsou.dto.FamilyDashboardResponse r = mock(com.picsou.dto.FamilyDashboardResponse.class);
        when(userContext.currentMemberId()).thenReturn(MID);
        when(familyViewService.getFamilyDashboard(MID)).thenReturn(r);

        assertThat(tools.getFamilyDashboard()).isSameAs(r);
    }

    @Test
    void getPrice_delegatesToGlobalPriceService() {
        when(priceService.getPriceEur("AAPL")).thenReturn(new BigDecimal("180.50"));

        assertThat(tools.getPrice("AAPL")).isEqualByComparingTo("180.50");
        verifyNoInteractions(userContext);   // global price, no member data involved
    }
}

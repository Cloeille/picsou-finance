package com.picsou.service;

import com.picsou.model.Account;
import com.picsou.model.AccountType;
import com.picsou.model.BalanceSnapshot;
import com.picsou.model.FamilyMember;
import com.picsou.repository.AccountHoldingRepository;
import com.picsou.repository.AccountRepository;
import com.picsou.repository.BalanceSnapshotRepository;
import com.picsou.repository.FamilyMemberRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SchedulerServiceTest {

    @Mock AccountRepository accountRepository;
    @Mock AccountHoldingRepository holdingRepository;
    @Mock BalanceSnapshotRepository snapshotRepository;
    @Mock AccountService accountService;
    @Mock PriceService priceService;
    @Mock InstrumentLogoService instrumentLogoService;
    @Mock DividendHistoryService dividendHistoryService;
    @Mock FamilyMemberRepository familyMemberRepository;
    @Mock MemberSyncService memberSyncService;
    @InjectMocks SchedulerService scheduler;

    private static Account perAccount(long id, String provider, FamilyMember member) {
        return Account.builder()
            .id(id).name("Retirement plan").type(AccountType.PER)
            .provider(provider).currency("EUR").currentBalance(new BigDecimal("1200"))
            .member(member).build();
    }

    @Test
    void dailyBankSync_delegatesOncePerMemberAndContinuesAfterOneFailure() {
        when(familyMemberRepository.findAllByOrderByCreatedAtAsc()).thenReturn(List.of(
            FamilyMember.builder().id(7L).displayName("first").build(),
            FamilyMember.builder().id(8L).displayName("second").build()));
        when(memberSyncService.resyncScheduled(7L)).thenThrow(new IllegalStateException("sync failed"));
        when(memberSyncService.resyncScheduled(8L)).thenReturn(List.of());

        scheduler.dailyBankSync();

        InOrder order = inOrder(memberSyncService);
        order.verify(memberSyncService).resyncScheduled(7L);
        order.verify(memberSyncService).resyncScheduled(8L);
        verifyNoMoreInteractions(memberSyncService);
    }

    @Test
    void refreshPrices_asksForShareLogosOnlyAfterThePricesAreRecorded() {
        when(holdingRepository.findDistinctTickers()).thenReturn(Set.of("AAPL"));

        scheduler.refreshPrices();

        // The order is the contract: a ticker becomes a logo candidate once this pass has recorded
        // a price for it, and logo lookups must never get ahead of the price requests.
        InOrder order = inOrder(priceService, instrumentLogoService);
        order.verify(priceService).refreshPrices(Set.of("AAPL"));
        order.verify(instrumentLogoService).requestResolution();
    }

    @Test
    void refreshPrices_stillQueuesLogos_whenThePriceRefreshFails() {
        when(holdingRepository.findDistinctTickers()).thenReturn(Set.of("AAPL"));
        doThrow(new IllegalStateException("boom")).when(priceService).refreshPrices(any());

        scheduler.refreshPrices();

        // Tickers priced on an earlier pass are still due; the pass itself picks what is ready.
        verify(instrumentLogoService).requestResolution();
    }

    @Test
    void refreshPrices_queuesNothing_whenNothingIsHeld() {
        when(holdingRepository.findDistinctTickers()).thenReturn(Set.of());
        when(holdingRepository.findDistinctTickersByAccountType(AccountType.CRYPTO)).thenReturn(Set.of());

        scheduler.refreshPrices();

        verify(instrumentLogoService, never()).requestResolution();
    }

    @Test
    void dailySnapshots_refreshesOnlyPricedCaisseEpargnePerSnapshots() {
        LocalDate today = LocalDate.now();
        FamilyMember member = FamilyMember.builder().id(7L).displayName("member").build();
        Account valuedPer = perAccount(1L, CaisseEpargneSyncService.PROVIDER, member);
        Account unpricedPer = perAccount(2L, CaisseEpargneSyncService.PROVIDER, member);
        Account otherProviderPer = perAccount(3L, "Amundi", member);
        BalanceSnapshot valuedSnapshot = snapshot(valuedPer, today, "900");
        BalanceSnapshot unpricedSnapshot = snapshot(unpricedPer, today, "800");
        BalanceSnapshot otherSnapshot = snapshot(otherProviderPer, today, "700");

        when(familyMemberRepository.findAllByOrderByCreatedAtAsc()).thenReturn(List.of(member));
        when(accountRepository.findAllByMemberIdOrderByCreatedAtAsc(7L))
            .thenReturn(List.of(valuedPer, unpricedPer, otherProviderPer));
        when(snapshotRepository.findByAccountIdAndDate(1L, today)).thenReturn(Optional.of(valuedSnapshot));
        when(snapshotRepository.findByAccountIdAndDate(2L, today)).thenReturn(Optional.of(unpricedSnapshot));
        when(snapshotRepository.findByAccountIdAndDate(3L, today)).thenReturn(Optional.of(otherSnapshot));
        lenient().when(accountService.valuation(valuedPer)).thenReturn(new AccountService.Valuation(
            new BigDecimal("1200"), new BigDecimal("1100"), true, true, false));
        lenient().when(accountService.valuation(unpricedPer)).thenReturn(new AccountService.Valuation(
            BigDecimal.ZERO, BigDecimal.ZERO, false, false, true));

        scheduler.dailySnapshots();

        assertThat(valuedSnapshot.getBalance()).isEqualByComparingTo("1200");
        assertThat(valuedSnapshot.getInvestedAmount()).isEqualByComparingTo("1100");
        assertThat(unpricedSnapshot.getBalance()).isEqualByComparingTo("800");
        assertThat(otherSnapshot.getBalance()).isEqualByComparingTo("700");
        verify(snapshotRepository).save(valuedSnapshot);
        verify(snapshotRepository, never()).save(unpricedSnapshot);
        verify(snapshotRepository, never()).save(otherSnapshot);
        verify(accountService).valuation(valuedPer);
        verify(accountService).valuation(unpricedPer);
        verify(accountService, never()).valuation(otherProviderPer);
    }

    @Test
    void refreshDividendHistory_skipsCryptoAndCapsTheBatch() {
        when(holdingRepository.findDistinctTickers()).thenReturn(Set.of("TTE.PA", "BTC", "btc"));
        when(holdingRepository.findDistinctTickersByAccountType(AccountType.CRYPTO)).thenReturn(Set.of("btc"));

        scheduler.refreshDividendHistory();

        verify(dividendHistoryService).refreshStale(Set.of("TTE.PA"), 40);
    }

    @Test
    void refreshDividendHistory_swallowsAFailureSoTheNextCycleStillRuns() {
        when(holdingRepository.findDistinctTickers()).thenReturn(Set.of("TTE.PA"));
        when(holdingRepository.findDistinctTickersByAccountType(AccountType.CRYPTO)).thenReturn(Set.of());
        when(dividendHistoryService.refreshStale(any(), anyInt())).thenThrow(new IllegalStateException("yahoo down"));

        scheduler.refreshDividendHistory();

        verify(dividendHistoryService).refreshStale(Set.of("TTE.PA"), 40);
    }

    @Test
    void refreshDividendHistory_doesNothingWhenNothingIsHeld() {
        when(holdingRepository.findDistinctTickers()).thenReturn(Set.of());

        scheduler.refreshDividendHistory();

        verify(dividendHistoryService, never()).refreshStale(any(), anyInt());
    }

    private static BalanceSnapshot snapshot(Account account, LocalDate date, String balance) {
        return BalanceSnapshot.builder()
            .account(account).date(date).balance(new BigDecimal(balance))
            .investedAmount(new BigDecimal(balance)).build();
    }

}

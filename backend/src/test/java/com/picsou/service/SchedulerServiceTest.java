package com.picsou.service;

import com.picsou.model.AccountType;
import com.picsou.model.FamilyMember;
import com.picsou.repository.AccountHoldingRepository;
import com.picsou.repository.AccountRepository;
import com.picsou.repository.FamilyMemberRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Set;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SchedulerServiceTest {

    @Mock AccountRepository accountRepository;
    @Mock AccountHoldingRepository holdingRepository;
    @Mock PriceService priceService;
    @Mock InstrumentLogoService instrumentLogoService;
    @Mock FamilyMemberRepository familyMemberRepository;
    @Mock MemberSyncService memberSyncService;
    @InjectMocks SchedulerService scheduler;

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
}

package com.picsou.controller;

import com.picsou.exception.GlobalExceptionHandler;
import com.picsou.exception.ResourceNotFoundException;
import com.picsou.model.Account;
import com.picsou.model.AccountHolding;
import com.picsou.model.AccountType;
import com.picsou.model.DividendEvent;
import com.picsou.model.DividendSource;
import com.picsou.model.DividendSourceStatus;
import com.picsou.repository.AccountHoldingRepository;
import com.picsou.repository.DividendSourceRepository;
import com.picsou.repository.ScpiPositionRepository;
import com.picsou.repository.TransactionRepository;
import com.picsou.service.AccountAccessResolver;
import com.picsou.service.DividendForecastService;
import com.picsou.service.DividendReceiptService;
import com.picsou.service.UserContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class DividendControllerAuthorizationTest {

    private static final long MEMBER = 7L;

    @Mock UserContext userContext;
    @Mock DividendReceiptService receiptService;
    @Mock AccountAccessResolver access;
    @Mock AccountHoldingRepository holdings;
    @Mock DividendSourceRepository sources;
    @Mock ScpiPositionRepository positions;
    @Mock TransactionRepository transactions;

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        DividendForecastService forecast = new DividendForecastService(
            access, holdings, sources, positions, transactions);
        mvc = MockMvcBuilders.standaloneSetup(new DividendController(forecast, receiptService, userContext))
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();
    }

    @Test
    void anUnreadableAccountIsNotFound() throws Exception {
        when(userContext.currentMemberId()).thenReturn(MEMBER);
        when(access.requireReadable(99L, MEMBER)).thenThrow(ResourceNotFoundException.account(99L));

        mvc.perform(get("/api/dividends/upcoming").param("accountId", "99"))
            .andExpect(status().isNotFound());

        verify(holdings, never()).findPositiveByAccountIdIn(any());
    }

    @Test
    void anotherMembersAccountIsAbsentFromTheList() throws Exception {
        Account mine = Account.builder().id(4L).name("Mine").type(AccountType.COMPTE_TITRES).currency("EUR").build();
        AccountHolding holding = AccountHolding.builder()
            .account(mine).ticker("TTE.PA").name("Total").quantity(new BigDecimal("10")).build();
        DividendSource source = DividendSource.builder()
            .ticker("TTE.PA").currency("EUR").status(DividendSourceStatus.OK).events(new ArrayList<>()).build();
        source.getEvents().add(DividendEvent.builder()
            .exDate(LocalDate.of(2026, 7, 20)).amount(new BigDecimal("1")).amountEur(new BigDecimal("1")).build());
        source.getEvents().add(DividendEvent.builder()
            .exDate(LocalDate.of(2026, 8, 20)).amount(new BigDecimal("1")).amountEur(new BigDecimal("1")).build());
        source.getEvents().add(DividendEvent.builder()
            .exDate(LocalDate.of(2026, 9, 20)).amount(new BigDecimal("1")).amountEur(new BigDecimal("1")).build());
        when(userContext.currentMemberId()).thenReturn(MEMBER);
        when(access.readableAccounts(MEMBER)).thenReturn(List.of(mine));
        when(access.sharesFor(any(), eq(MEMBER))).thenReturn(Map.of(4L, new BigDecimal("100")));
        when(holdings.findPositiveByAccountIdIn(any())).thenReturn(List.of(holding));
        when(sources.findAllWithEventsByTickerIn(any())).thenReturn(List.of(source));

        mvc.perform(get("/api/dividends/upcoming"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].accountId").value(4))
            .andExpect(jsonPath("$[?(@.accountId == 50)]").doesNotExist());

        verify(holdings).findPositiveByAccountIdIn(argThat(ids -> ids.contains(4L) && !ids.contains(50L)));
    }
}

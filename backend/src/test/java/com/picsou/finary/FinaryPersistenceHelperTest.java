package com.picsou.finary;

import com.picsou.model.Account;
import com.picsou.model.BalanceSnapshot;
import com.picsou.model.Transaction;
import com.picsou.repository.BalanceSnapshotRepository;
import com.picsou.repository.TransactionRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The rebuilt history records, under a transaction's date, the balance at the END of that day.
 * Walking backwards from today's balance, that is the running balance before the day's
 * transactions are subtracted; subtracting first stored the balance before the day's movements,
 * so every rebuilt point was one day's transactions off.
 */
@ExtendWith(MockitoExtension.class)
class FinaryPersistenceHelperTest {

    @Mock BalanceSnapshotRepository balanceSnapshotRepository;
    @Mock TransactionRepository transactionRepository;

    @InjectMocks FinaryPersistenceHelper helper;

    @Test
    void reconstructSnapshotsFromDb_recordsTheEndOfDayBalanceUnderEachDay() {
        LocalDate today = LocalDate.now();
        LocalDate twoDaysAgo = today.minusDays(2);
        LocalDate yesterday = today.minusDays(1);
        Account account = Account.builder().id(1L).name("Livret").currency("EUR")
            .currentBalance(new BigDecimal("1000")).build();
        // Newest first, as the repository returns them: -50 yesterday, +200 two days ago.
        when(transactionRepository.findByAccountIdOrderByDateDesc(1L)).thenReturn(List.of(
            Transaction.builder().account(account).date(yesterday).amount(new BigDecimal("-50")).build(),
            Transaction.builder().account(account).date(twoDaysAgo).amount(new BigDecimal("200")).build()));
        when(balanceSnapshotRepository.findRecentByAccountId(1L, LocalDate.of(2000, 1, 1))).thenReturn(List.of());

        helper.reconstructSnapshotsFromDb(account);

        ArgumentCaptor<BalanceSnapshot> saved = ArgumentCaptor.forClass(BalanceSnapshot.class);
        verify(balanceSnapshotRepository, atLeastOnce()).save(saved.capture());
        Map<LocalDate, BigDecimal> byDate = saved.getAllValues().stream()
            .collect(Collectors.toMap(BalanceSnapshot::getDate, BalanceSnapshot::getBalance));
        assertThat(byDate.get(today)).isEqualByComparingTo("1000");
        // End of yesterday: after the -50, i.e. today's balance.
        assertThat(byDate.get(yesterday)).isEqualByComparingTo("1000");
        // End of two days ago: before the -50, after the +200.
        assertThat(byDate.get(twoDaysAgo)).isEqualByComparingTo("1050");
    }
}

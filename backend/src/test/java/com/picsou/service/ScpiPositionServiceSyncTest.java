package com.picsou.service;

import com.picsou.model.Account;
import com.picsou.model.AccountType;
import com.picsou.model.FamilyMember;
import com.picsou.model.ScpiPosition;
import com.picsou.model.ScpiValuationStatus;
import com.picsou.repository.AccountRepository;
import com.picsou.repository.ScpiPositionRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The CORUM sync writes through {@link ScpiPositionService#applySyncedPosition},
 * so these pin the one rule that matters: a synchronised share is valued at the
 * withdrawal price, never at CORUM's own displayed figure, which is the
 * subscription-side value and carries the entry fee inside it.
 */
@ExtendWith(MockitoExtension.class)
class ScpiPositionServiceSyncTest {

    private static final LocalDate VALUED_ON = LocalDate.of(2026, 9, 26);

    @Mock AccountRepository accountRepository;
    @Mock ScpiPositionRepository positionRepository;
    @InjectMocks ScpiPositionService service;

    private static ScpiPosition position(AccountType type) {
        Account account = Account.builder().id(1L).type(type).currency("EUR").build();
        return ScpiPosition.builder().id(1L).account(account).build();
    }

    @Test
    void sync_valuesTheAccountAtWithdrawalPriceNotTheDisplayedValue() {
        // CORUM shows 2.67183 x 200 = 534.37, because its displayed figure is
        // the subscription price. The withdrawal price is 176, so the balance
        // is 470.24208 -- the 64 euro difference is the entry fee and must not
        // count as net worth.
        ScpiPosition scpi = position(AccountType.SCPI);

        service.applySyncedPosition(
            scpi, new BigDecimal("2.67183"), new BigDecimal("200"), new BigDecimal("176"), VALUED_ON
        );

        assertThat(scpi.getAccount().getCurrentBalance()).isEqualByComparingTo("470.24208");
        assertThat(scpi.getValuationStatus()).isEqualTo(ScpiValuationStatus.OK);
    }

    @Test
    void sync_keepsTheSubscriptionPriceBesideTheBalanceWithoutBecomingIt() {
        ScpiPosition scpi = position(AccountType.SCPI);

        service.applySyncedPosition(
            scpi, new BigDecimal("2.5"), new BigDecimal("200"), new BigDecimal("176"), VALUED_ON
        );

        assertThat(scpi.getSubscriptionPriceEur()).isEqualByComparingTo("200");
        assertThat(scpi.getAccount().getCurrentBalance()).isEqualByComparingTo("440");
    }

    @Test
    void sync_withFractionalSharesKeepsTheFraction() {
        ScpiPosition scpi = position(AccountType.SCPI);

        service.applySyncedPosition(
            scpi, new BigDecimal("1.10256"), new BigDecimal("195"), new BigDecimal("171.6"), VALUED_ON
        );

        assertThat(scpi.getShareCount()).isEqualByComparingTo("1.10256");
    }

    @Test
    void sync_withoutWithdrawalPriceKeepsThePreviousBalance() {
        Account account = Account.builder().id(1L).type(AccountType.SCPI).currency("EUR")
            .currentBalance(new BigDecimal("400")).build();
        ScpiPosition scpi = ScpiPosition.builder().id(1L).account(account).build();

        // CORUM is not quoting that fund today. The share count is still real
        // and gets written, but the balance stays and the status explains why.
        service.applySyncedPosition(
            scpi, new BigDecimal("2.67183"), new BigDecimal("200"), null, VALUED_ON
        );

        assertThat(account.getCurrentBalance()).isEqualByComparingTo("400");
        assertThat(scpi.getValuationStatus()).isEqualTo(ScpiValuationStatus.PRICE_INCOMPLETE);
        assertThat(scpi.getShareCount()).isEqualByComparingTo("2.67183");
    }

    @Test
    void sync_withZeroWithdrawalPriceWritesARealZero() {
        // Zero shares sold out is a real value, not a missing price. A null
        // would have kept the previous balance and reported PRICE_INCOMPLETE.
        ScpiPosition scpi = position(AccountType.SCPI);

        service.applySyncedPosition(
            scpi, BigDecimal.ZERO, new BigDecimal("200"), BigDecimal.ZERO, VALUED_ON
        );

        assertThat(scpi.getAccount().getCurrentBalance()).isEqualByComparingTo("0");
        assertThat(scpi.getValuationStatus()).isEqualTo(ScpiValuationStatus.OK);
    }

    @Test
    void sync_forcesTheCurrencyToEuro() {
        Account account = Account.builder().id(1L).type(AccountType.SCPI).currency("USD").build();
        ScpiPosition scpi = ScpiPosition.builder().id(1L).account(account).build();

        service.applySyncedPosition(
            scpi, new BigDecimal("2"), new BigDecimal("200"), new BigDecimal("176"), VALUED_ON
        );

        // The withdrawal value is already euros; converting again would
        // double-count the exchange rate.
        assertThat(account.getCurrency()).isEqualTo("EUR");
    }

    @Test
    void sync_keepsTheExistingJouissanceDateWhenUpstreamHasNone() {
        LocalDate manual = LocalDate.of(2025, 3, 15);
        ScpiPosition scpi = position(AccountType.SCPI);
        scpi.setJouissanceDate(manual);

        // A missing date is an absence, not a reset: the date the user entered
        // by hand is still the best information available.
        service.applySyncedPosition(
            scpi, new BigDecimal("2"), new BigDecimal("200"), new BigDecimal("176"), null
        );

        assertThat(scpi.getJouissanceDate()).isEqualTo(manual);
    }

    /**
     * A sold position is worth zero whatever price is quoted. Reading "no shares
     * and no price" as unknown would leave the sold balance standing in the net
     * worth, which is the one thing a reconciliation has to get right.
     */
    @Test
    void sync_zeroesTheBalanceOfASoldPositionEvenWithoutAPrice() {
        Account account = Account.builder()
            .id(1L).type(AccountType.SCPI).currency("EUR")
            .currentBalance(new BigDecimal("627.20"))
            .build();
        ScpiPosition scpi = ScpiPosition.builder().id(1L).account(account).build();

        service.applySyncedPosition(scpi, BigDecimal.ZERO, null, null, VALUED_ON);

        assertThat(account.getCurrentBalance()).isEqualByComparingTo("0");
        assertThat(scpi.getValuationStatus()).isEqualTo(ScpiValuationStatus.OK);
    }

    /**
     * Sofidy quotes no subscription price, so every sync passes null. Writing
     * that through would delete a price the user typed by hand.
     */
    @Test
    void sync_keepsAManuallyEnteredSubscriptionPriceWhenUpstreamHasNone() {
        ScpiPosition scpi = position(AccountType.SCPI);
        scpi.setSubscriptionPriceEur(new BigDecimal("298.75"));

        service.applySyncedPosition(scpi, new BigDecimal("2"), null, new BigDecimal("176"), VALUED_ON);

        assertThat(scpi.getSubscriptionPriceEur()).isEqualByComparingTo("298.75");
    }

    @Test
    void sync_refusesAnAccountThatIsNotAScpi() {
        ScpiPosition mislinked = position(AccountType.LOAN);

        assertThatThrownBy(() -> service.applySyncedPosition(
            mislinked, new BigDecimal("2"), new BigDecimal("200"), new BigDecimal("176"), VALUED_ON
        )).isInstanceOf(IllegalArgumentException.class);
    }
}

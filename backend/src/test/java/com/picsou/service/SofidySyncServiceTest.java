package com.picsou.service;

import com.picsou.config.CryptoEncryption;
import com.picsou.model.Account;
import com.picsou.model.AccountType;
import com.picsou.model.FamilyMember;
import com.picsou.model.ScpiPosition;
import com.picsou.model.SofidySession;
import com.picsou.model.SofidySyncStatus;
import com.picsou.port.SofidyErrorCode;
import com.picsou.port.SofidyPort;
import com.picsou.repository.FamilyMemberRepository;
import com.picsou.repository.ScpiPositionRepository;
import com.picsou.repository.SofidySessionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Covers the snapshot reconciliation, which is where a Sofidy sync can do real
 * damage: it writes balances onto positions the member owns.
 */
@ExtendWith(MockitoExtension.class)
@SuppressWarnings("unchecked")
class SofidySyncServiceTest {

    @Mock SofidyPort port;
    @Mock SofidySessionRepository sessionRepository;
    @Mock ScpiPositionRepository positionRepository;
    @Mock ScpiPositionService positionService;
    @Mock FamilyMemberRepository memberRepository;
    @Mock CryptoEncryption encryption;
    @Mock TransactionTemplate txTemplate;
    @Mock TransactionStatus transactionStatus;

    SofidySyncService service;

    @BeforeEach
    void setUp() {
        lenient().doAnswer(invocation -> {
            TransactionCallback<Object> callback = invocation.getArgument(0);
            return callback.doInTransaction(transactionStatus);
        }).when(txTemplate).execute(any(TransactionCallback.class));
        lenient().doAnswer(invocation -> {
            Consumer<TransactionStatus> callback = invocation.getArgument(0);
            callback.accept(transactionStatus);
            return null;
        }).when(txTemplate).executeWithoutResult(any());
        service = new SofidySyncService(
            port,
            sessionRepository,
            positionRepository,
            positionService,
            memberRepository,
            encryption,
            txTemplate,
            Runnable::run
        );
    }

    @Test
    void queueSync_appliesASnapshotToTheLinkedPosition() {
        arrangeActiveSession();
        ScpiPosition linked = position("DY", "0", "0");
        when(port.fetchSnapshot("plain-state"))
            .thenReturn(snapshot(List.of(holding("DY", "2.00000", "313.60"))));
        when(positionRepository.findByMemberIdAndSofidyFundCode(7L, "DY"))
            .thenReturn(Optional.of(linked));
        when(positionRepository.findByAccountMemberIdAndSofidyFundCodeIsNotNull(7L))
            .thenReturn(List.of(linked));

        SofidySyncService.SessionStatusResponse result = service.queueSync(7L);

        assertThat(result.syncStatus()).isEqualTo(SofidySyncStatus.SUCCESS);
        verify(positionService).applySyncedPosition(
            linked,
            new BigDecimal("2.00000"),
            null,
            new BigDecimal("313.60"),
            LocalDate.of(2026, 9, 25)
        );
    }

    /**
     * A member who sold everything has an empty portfolio, and Sofidy reports
     * that as complete. Rejecting it would make a full exit impossible to sync
     * and every later sync would fail the same way.
     */
    @Test
    void queueSync_acceptsACompleteEmptyPortfolioAsAFullExit() {
        arrangeActiveSession();
        when(port.fetchSnapshot("plain-state"))
            .thenReturn(snapshot(List.of()));

        SofidySyncService.SessionStatusResponse result = service.queueSync(7L);

        assertThat(result.syncStatus()).isEqualTo(SofidySyncStatus.SUCCESS);
    }

    /**
     * The regression this pins: a fund Sofidy no longer lists was sold. Leaving
     * the position at its last balance keeps a holding in the net worth the
     * member exited, and no manual step exists to correct it.
     */
    @Test
    void queueSync_zeroesAPositionWhoseFundIsNoLongerListed() {
        arrangeActiveSession();
        ScpiPosition sold = position("DY", "2.00000", "627.20");
        when(port.fetchSnapshot("plain-state")).thenReturn(snapshot(List.of()));
        when(positionRepository.findByAccountMemberIdAndSofidyFundCodeIsNotNull(7L))
            .thenReturn(List.of(sold));

        service.queueSync(7L);

        verify(positionService).applySyncedPosition(
            sold,
            BigDecimal.ZERO,
            null,
            new BigDecimal("313.60"),
            null
        );
    }

    /** A fund still listed keeps its position: only the absent ones are closed. */
    @Test
    void queueSync_leavesAStillListedFundAlone() {
        arrangeActiveSession();
        ScpiPosition kept = position("DY", "2.00000", "627.20");
        when(port.fetchSnapshot("plain-state"))
            .thenReturn(snapshot(List.of(holding("DY", "2.00000", "313.60"))));
        when(positionRepository.findByMemberIdAndSofidyFundCode(7L, "DY"))
            .thenReturn(Optional.of(kept));
        when(positionRepository.findByAccountMemberIdAndSofidyFundCodeIsNotNull(7L))
            .thenReturn(List.of(kept));

        service.queueSync(7L);

        verify(positionService).applySyncedPosition(
            kept,
            new BigDecimal("2.00000"),
            null,
            new BigDecimal("313.60"),
            LocalDate.of(2026, 9, 25)
        );
    }

    /** A partial read must never close anything: it is missing rows, not sales. */
    @Test
    void queueSync_refusesAnIncompleteSnapshotWithoutClosingAnything() {
        arrangeActiveSession();
        when(port.fetchSnapshot("plain-state"))
            .thenReturn(new SofidyPort.Snapshot(
                "EUR", new BigDecimal("627.20"), LocalDate.of(2026, 9, 25), false, List.of()
            ));

        SofidySyncService.SessionStatusResponse result = service.queueSync(7L);

        assertThat(result.syncStatus()).isEqualTo(SofidySyncStatus.FAILED);
        assertThat(result.lastSyncError()).isEqualTo(SofidyErrorCode.PORTFOLIO_INCOMPLETE);
        verify(positionRepository, org.mockito.Mockito.never())
            .findByAccountMemberIdAndSofidyFundCodeIsNotNull(any());
    }

    /**
     * The snapshot declares more than the funds add up to, so the read is
     * partial and nothing may be written.
     */
    @Test
    void queueSync_refusesASnapshotThatDoesNotAddUp() {
        arrangeActiveSession();
        when(port.fetchSnapshot("plain-state")).thenReturn(
            new SofidyPort.Snapshot(
                "EUR", new BigDecimal("9999.00"), LocalDate.of(2026, 9, 25), true,
                List.of(holding("DY", "2.00000", "313.60"))
            )
        );

        SofidySyncService.SessionStatusResponse result = service.queueSync(7L);

        assertThat(result.syncStatus()).isEqualTo(SofidySyncStatus.FAILED);
        assertThat(result.lastSyncError()).isEqualTo(SofidyErrorCode.PORTFOLIO_INCOMPLETE);
        verify(positionService, org.mockito.Mockito.never())
            .applySyncedPosition(any(), any(), any(), any(), any());
    }

    /**
     * Sofidy quotes no subscription price at all, so every sync passed null.
     * Writing that through deleted a price the user typed by hand.
     */
    @Test
    void queueSync_doesNotClearAManuallyEnteredSubscriptionPrice() {
        arrangeActiveSession();
        ScpiPosition linked = position("DY", "0", "0");
        linked.setSubscriptionPriceEur(new BigDecimal("298.75"));
        when(port.fetchSnapshot("plain-state"))
            .thenReturn(snapshot(List.of(holding("DY", "2.00000", "313.60"))));
        when(positionRepository.findByMemberIdAndSofidyFundCode(7L, "DY"))
            .thenReturn(Optional.of(linked));
        when(positionRepository.findByAccountMemberIdAndSofidyFundCodeIsNotNull(7L))
            .thenReturn(List.of(linked));

        service.queueSync(7L);

        assertThat(linked.getSubscriptionPriceEur()).isEqualByComparingTo("298.75");
    }

    /**
     * A sold position is worth zero whatever price is quoted, so a missing
     * withdrawal price must not read as "unknown" and leave the balance
     * standing.
     */
    @Test
    void queueSync_zeroesTheBalanceOfASoldPositionEvenWithoutAPrice() {
        arrangeActiveSession();
        ScpiPosition sold = position("DY", "2.00000", "627.20");
        sold.setWithdrawalPriceEur(null);
        when(port.fetchSnapshot("plain-state")).thenReturn(snapshot(List.of()));
        when(positionRepository.findByAccountMemberIdAndSofidyFundCodeIsNotNull(7L))
            .thenReturn(List.of(sold));

        service.queueSync(7L);

        verify(positionService).applySyncedPosition(
            sold, BigDecimal.ZERO, null, null, null
        );
        // The balance itself is positionService's job; with a mock here, the
        // assertion that matters is that zero shares reach it, and that
        // ScpiPositionServiceSyncTest covers what zero shares mean.
        assertThat(ScpiPositionService.withdrawalValue(BigDecimal.ZERO, null))
            .isEqualByComparingTo("0");
    }

    private void arrangeActiveSession() {
        FamilyMember member = FamilyMember.builder().id(7L).displayName("Owner").build();
        SofidySession session = SofidySession.builder()
            .id(3L)
            .member(member)
            .sessionState("encrypted")
            .active(true)
            .syncStatus(SofidySyncStatus.IDLE)
            .build();
        lenient().when(sessionRepository.findByMemberIdForUpdate(7L))
            .thenReturn(Optional.of(session));
        lenient().when(sessionRepository.findByIdAndMemberIdForUpdate(3L, 7L))
            .thenReturn(Optional.of(session));
        lenient().when(sessionRepository.findByMemberId(7L)).thenReturn(Optional.of(session));
        lenient().when(encryption.decrypt(anyString())).thenReturn("plain-state");
    }

    /**
     * The declared total follows the holdings, so a test asking for SUCCESS is
     * never handed a portfolio that does not add up to its own total. The
     * inconsistent case has its own test, where the mismatch is the point.
     */
    private SofidyPort.Snapshot snapshot(List<SofidyPort.Holding> holdings) {
        BigDecimal total = holdings.stream()
            .map(SofidyPort.Holding::withdrawalValue)
            .reduce(BigDecimal.ZERO, BigDecimal::add);
        return new SofidyPort.Snapshot(
            "EUR", total, LocalDate.of(2026, 9, 25), true, holdings
        );
    }

    private SofidyPort.Holding holding(String fundCode, String quantity, String withdrawalPrice) {
        return new SofidyPort.Holding(
            fundCode, "Fond de test", new BigDecimal(quantity), new BigDecimal(withdrawalPrice), null
        );
    }

    private ScpiPosition position(String fundCode, String quantity, String balance) {
        Account account = Account.builder()
            .id(5L)
            .type(AccountType.SCPI)
            .currency("EUR")
            .currentBalance(new BigDecimal(balance))
            .build();
        ScpiPosition position = new ScpiPosition();
        position.setId(9L);
        position.setAccount(account);
        position.setSofidyFundCode(fundCode);
        position.setShareCount(new BigDecimal(quantity));
        position.setWithdrawalPriceEur(new BigDecimal("313.60"));
        return position;
    }
}

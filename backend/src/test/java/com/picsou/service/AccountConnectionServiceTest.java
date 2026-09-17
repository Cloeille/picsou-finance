package com.picsou.service;

import com.picsou.model.Account;
import com.picsou.model.Chain;
import com.picsou.model.CryptoExchangeSession;
import com.picsou.model.ExchangeType;
import com.picsou.model.Requisition;
import com.picsou.model.WalletAddress;
import com.picsou.repository.AccountRepository;
import com.picsou.repository.CryptoExchangeSessionRepository;
import com.picsou.repository.RequisitionRepository;
import com.picsou.repository.WalletAddressRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Deleting an account takes its connection with it — but only once nothing else is left on
 * that connection, which is the whole subtlety: a bank requisition can back several accounts,
 * and Amundi or Trade Republic routinely back two or three from one session.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT) // one connector is exercised per test
class AccountConnectionServiceTest {

    private static final Long MEMBER_ID = 1L;

    @Mock AccountRepository accountRepository;
    @Mock AccountService accountService;
    @Mock WalletAddressRepository walletRepository;
    @Mock CryptoExchangeSessionRepository exchangeSessionRepository;
    @Mock RequisitionRepository requisitionRepository;
    @Mock WalletSyncService walletSyncService;
    @Mock CryptoExchangeSyncService cryptoExchangeSyncService;
    @Mock AmundiSyncService amundiSyncService;
    @Mock TradeRepublicSyncService tradeRepublicSyncService;
    @Mock BourseDirectSyncService bourseDirectSyncService;
    @Mock BoursoSyncService boursoSyncService;
    @Mock FortuneoSyncService fortuneoSyncService;
    @Mock DegiroSyncService degiroSyncService;
    @Mock IbkrSyncService ibkrSyncService;
    @Mock SyncService syncService;

    private AccountConnectionService service() {
        return new AccountConnectionService(
            accountRepository, accountService, walletRepository, exchangeSessionRepository,
            requisitionRepository, walletSyncService, cryptoExchangeSyncService, amundiSyncService,
            tradeRepublicSyncService, bourseDirectSyncService, boursoSyncService,
            fortuneoSyncService, degiroSyncService, ibkrSyncService, syncService);
    }

    private static Account account(long id, String externalId) {
        Account a = new Account();
        a.setId(id);
        a.setExternalAccountId(externalId);
        return a;
    }

    /** Registers `account` as the deletion target and `others` as the member's remaining rows. */
    private void given(Account target, Account... others) {
        when(accountRepository.findByIdAndMemberId(target.getId(), MEMBER_ID))
            .thenReturn(Optional.of(target));
        when(accountRepository.findAllByMemberIdOrderByCreatedAtAsc(MEMBER_ID))
            .thenReturn(java.util.stream.Stream.concat(
                java.util.stream.Stream.of(target), java.util.Arrays.stream(others)).toList());
    }

    @Test
    void deletingAWalletAccountRemovesTheWallet() {
        given(account(10L, "wallet_bitcoin_2"));
        WalletAddress wallet = WalletAddress.builder().id(2L).chain(Chain.BITCOIN).address("bc1q").build();
        when(walletRepository.findByIdAndMemberId(2L, MEMBER_ID)).thenReturn(Optional.of(wallet));

        service().deleteAccount(10L, MEMBER_ID);

        verify(accountService).delete(10L, MEMBER_ID);
        verify(walletSyncService).removeWallet(2L, MEMBER_ID);
    }

    @Test
    void deletingAnExchangeAccountRemovesTheSession() {
        given(account(10L, "crypto_exchange_meria"));
        CryptoExchangeSession session = new CryptoExchangeSession();
        session.setId(7L);
        when(exchangeSessionRepository.findByExchangeTypeAndMemberId(ExchangeType.MERIA, MEMBER_ID))
            .thenReturn(Optional.of(session));

        service().deleteAccount(10L, MEMBER_ID);

        verify(cryptoExchangeSyncService).removeExchange(7L, MEMBER_ID);
    }

    @Test
    void deletingTheLastBankAccountRemovesItsRequisition() {
        Account target = account(10L, "0f7a1c2e-uuid-from-the-bank");
        target.setRequisitionId(3L);
        given(target);

        service().deleteAccount(10L, MEMBER_ID);

        verify(syncService).deleteRequisition(3L, MEMBER_ID);
    }

    /** One requisition, several accounts: removing it would kill the ones still in use. */
    @Test
    void keepsTheRequisitionWhileAnotherAccountStillUsesIt() {
        Account target = account(10L, "uuid-a");
        target.setRequisitionId(3L);
        Account sibling = account(11L, "uuid-b");
        sibling.setRequisitionId(3L);
        given(target, sibling);

        service().deleteAccount(10L, MEMBER_ID);

        verify(accountService).delete(10L, MEMBER_ID);
        verify(syncService, never()).deleteRequisition(anyLong(), anyLong());
    }

    /** Amundi routinely holds several plans on one session — deleting one must not log you out. */
    @Test
    void keepsTheAmundiSessionWhileAnotherPlanRemains() {
        given(account(10L, "amundi_0001655730"), account(11L, "amundi_024806"));

        service().deleteAccount(10L, MEMBER_ID);

        verify(amundiSyncService, never()).clearSession(anyLong());
    }

    @Test
    void clearsTheAmundiSessionWithItsLastPlan() {
        given(account(10L, "amundi_0001655730"));

        service().deleteAccount(10L, MEMBER_ID);

        verify(amundiSyncService).clearSession(MEMBER_ID);
    }

    /** Trade Republic writes a cash and a securities account from a single session. */
    @Test
    void keepsTheTradeRepublicSessionWhileTheOtherHalfRemains() {
        given(account(10L, "tr_cash"), account(11L, "tr_securities"));

        service().deleteAccount(10L, MEMBER_ID);

        verify(tradeRepublicSyncService, never()).clearSession(anyLong());
    }

    /** Fortuneo writes its PEA, CTO and cash pockets from a single session. */
    @Test
    void keepsTheFortuneoSessionWhileAnotherAccountRemains() {
        given(account(10L, "ft_pea_12345"), account(11L, "ft_cash_12345"));

        service().deleteAccount(10L, MEMBER_ID);

        verify(fortuneoSyncService, never()).clearSession(anyLong());
    }

    @Test
    void clearsTheFortuneoSessionWithItsLastAccount() {
        given(account(10L, "ft_cto_12345"));

        service().deleteAccount(10L, MEMBER_ID);

        verify(fortuneoSyncService).clearSession(MEMBER_ID);
    }

    @Test
    void clearsEachRemainingConnectorOnItsLastAccount() {
        given(account(10L, "bd_12345"));
        service().deleteAccount(10L, MEMBER_ID);
        verify(bourseDirectSyncService).clearSession(MEMBER_ID);

        given(account(15L, "bourso_a8a23172b7e7c91c538831578242112e"));
        service().deleteAccount(15L, MEMBER_ID);
        verify(boursoSyncService).clearSession(MEMBER_ID);

        given(account(20L, "degiro-portfolio"));
        service().deleteAccount(20L, MEMBER_ID);
        verify(degiroSyncService).clearSession(MEMBER_ID);

        given(account(30L, "ibkr_U1234567"));
        service().deleteAccount(30L, MEMBER_ID);
        verify(ibkrSyncService).deleteConnection(MEMBER_ID);
    }

    /**
     * A manual account has no connection, and an Enable Banking row the V76 backfill could not
     * attribute has none recorded. Both must delete cleanly rather than fall through to some
     * connector by accident.
     */
    @Test
    void deletesAccountsWithNoConnectionWithoutTouchingAnyConnector() {
        given(account(10L, null));

        service().deleteAccount(10L, MEMBER_ID);

        verify(accountService).delete(10L, MEMBER_ID);
        verify(walletSyncService, never()).removeWallet(anyLong(), anyLong());
        verify(syncService, never()).deleteRequisition(anyLong(), anyLong());
        verify(amundiSyncService, never()).clearSession(anyLong());
    }

    /**
     * An unattributed bank account (no requisition_id) must not be matched to a connector by its
     * opaque id — 'bd_' and friends are prefixes of real UUIDs often enough to matter.
     */
    @Test
    void doesNotMistakeAnOpaqueBankIdForAnotherConnector() {
        given(account(10L, "bd0f7a1c-2e3b-4c5d-8e9f-0a1b2c3d4e5f"));

        service().deleteAccount(10L, MEMBER_ID);

        verify(bourseDirectSyncService, never()).clearSession(anyLong());
        verify(syncService, never()).deleteRequisition(anyLong(), anyLong());
    }

    /**
     * A bank's account id is its own opaque string; nothing stops one from looking like another
     * connector's namespace. Resolving it by prefix would delete an unrelated wallet of the same
     * member and leave the requisition linked — so the recorded requisition wins over the guess.
     */
    @Test
    void prefersTheRecordedRequisitionOverAnExternalIdThatLooksLikeAnotherConnector() {
        Account target = account(10L, "wallet_bitcoin_2");
        target.setRequisitionId(3L);
        given(target);

        service().deleteAccount(10L, MEMBER_ID);

        verify(syncService).deleteRequisition(3L, MEMBER_ID);
        verify(walletSyncService, never()).removeWallet(anyLong(), anyLong());
    }

    @Test
    void describeDeletionNamesTheConnectionAboutToGo() {
        given(account(10L, "wallet_bitcoin_2"));
        when(walletRepository.findByIdAndMemberId(2L, MEMBER_ID)).thenReturn(Optional.of(
            WalletAddress.builder().id(2L).chain(Chain.BITCOIN).address("bc1q").label("Ledger BTC").build()));

        AccountConnectionService.DeletionImpact impact = service().describeDeletion(10L, MEMBER_ID);

        assertThat(impact.removesConnection()).isTrue();
        assertThat(impact.connectionLabel()).isEqualTo("Ledger BTC");
    }

    @Test
    void describeDeletionReportsNothingWhenTheConnectionSurvives() {
        Account target = account(10L, "uuid-a");
        target.setRequisitionId(3L);
        Account sibling = account(11L, "uuid-b");
        sibling.setRequisitionId(3L);
        given(target, sibling);

        AccountConnectionService.DeletionImpact impact = service().describeDeletion(10L, MEMBER_ID);

        assertThat(impact.removesConnection()).isFalse();
        assertThat(impact.connectionLabel()).isNull();
    }

    @Test
    void describeDeletionUsesTheInstitutionNameForABankConnection() {
        Account target = account(10L, "uuid-a");
        target.setRequisitionId(3L);
        given(target);
        when(requisitionRepository.findByIdAndMemberId(3L, MEMBER_ID)).thenReturn(Optional.of(
            Requisition.builder().id(3L).institutionName("Boursorama Banque").build()));

        assertThat(service().describeDeletion(10L, MEMBER_ID).connectionLabel())
            .isEqualTo("Boursorama Banque");
    }

    /** Reading the impact must not change anything — it backs a dialog the user may cancel. */
    @Test
    void describeDeletionRemovesNothing() {
        given(account(10L, "wallet_bitcoin_2"));
        when(walletRepository.findByIdAndMemberId(2L, MEMBER_ID)).thenReturn(Optional.of(
            WalletAddress.builder().id(2L).chain(Chain.BITCOIN).address("bc1q").build()));

        service().describeDeletion(10L, MEMBER_ID);

        verify(accountService, never()).delete(anyLong(), anyLong());
        verify(walletSyncService, never()).removeWallet(anyLong(), anyLong());
    }

    /** An external id naming an exchange this build no longer knows must not trap the account. */
    @Test
    void deletesCleanlyWhenTheExchangeTypeIsUnknown() {
        given(account(10L, "crypto_exchange_defunctexchange"));

        service().deleteAccount(10L, MEMBER_ID);

        verify(accountService).delete(10L, MEMBER_ID);
        verify(cryptoExchangeSyncService, never()).removeExchange(any(), any());
    }

    /** Sanity check on the ordering the class documents: the account goes first. */
    @Test
    void softDeletesTheAccountBeforeRemovingTheConnection() {
        given(account(10L, "wallet_bitcoin_2"));
        when(walletRepository.findByIdAndMemberId(2L, MEMBER_ID)).thenReturn(Optional.of(
            WalletAddress.builder().id(2L).chain(Chain.BITCOIN).address("bc1q").build()));

        service().deleteAccount(10L, MEMBER_ID);

        var inOrder = org.mockito.Mockito.inOrder(accountService, walletSyncService);
        inOrder.verify(accountService).delete(10L, MEMBER_ID);
        inOrder.verify(walletSyncService).removeWallet(2L, MEMBER_ID);
    }

    @Test
    void listsOnlyLiveAccountsWhenDecidingIfAConnectionIsIdle() {
        // findAllByMemberIdOrderByCreatedAtAsc is filtered by @SQLRestriction, so a soft-deleted
        // sibling is simply absent -- the connection is idle and must go.
        given(account(10L, "amundi_0001655730"));
        when(accountRepository.findAllByMemberIdOrderByCreatedAtAsc(MEMBER_ID))
            .thenReturn(List.of(account(10L, "amundi_0001655730")));

        service().deleteAccount(10L, MEMBER_ID);

        verify(amundiSyncService).clearSession(MEMBER_ID);
    }
}

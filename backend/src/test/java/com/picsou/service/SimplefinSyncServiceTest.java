package com.picsou.service;

import com.picsou.config.CryptoEncryption;
import com.picsou.dto.AccountResponse;
import com.picsou.exception.SyncException;
import com.picsou.model.Account;
import com.picsou.model.AccountType;
import com.picsou.model.FamilyMember;
import com.picsou.model.SimplefinConnection;
import com.picsou.port.BankConnectorPort.TransactionData;
import com.picsou.port.SimplefinPort;
import com.picsou.port.SimplefinPort.SimplefinAccount;
import com.picsou.port.SimplefinPort.SimplefinAccountSet;
import com.picsou.port.SimplefinPort.SimplefinTransaction;
import com.picsou.repository.AccountRepository;
import com.picsou.repository.FamilyMemberRepository;
import com.picsou.repository.SimplefinConnectionRepository;
import com.picsou.service.sync.SourceSyncResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SimplefinSyncServiceTest {

    private static final Long MEMBER_ID = 7L;
    private static final String ACCESS = "https://user1234:secret@beta-bridge.simplefin.org/simplefin";

    @Mock SimplefinPort simplefinPort;
    @Mock SimplefinConnectionRepository connectionRepository;
    @Mock AccountRepository accountRepository;
    @Mock FamilyMemberRepository familyMemberRepository;
    @Mock AccountService accountService;
    @Mock BankTransactionImportService transactionImportService;
    @Mock CryptoEncryption encryption;
    @Mock SimplefinStatusWriter statusWriter;

    private SimplefinSyncService service;

    @BeforeEach
    void setUp() {
        service = new SimplefinSyncService(
            simplefinPort, connectionRepository, accountRepository, familyMemberRepository,
            accountService, transactionImportService, encryption, statusWriter);
    }

    @Test
    void connectStoresTheEncryptedAccessUrl() {
        when(simplefinPort.claim("token")).thenReturn(ACCESS);
        when(encryption.encrypt(ACCESS)).thenReturn("ciphertext");
        FamilyMember member = new FamilyMember();
        member.setId(MEMBER_ID);
        when(familyMemberRepository.findById(MEMBER_ID)).thenReturn(Optional.of(member));
        when(connectionRepository.findByMemberId(MEMBER_ID)).thenReturn(Optional.empty());

        service.connect(" token ", MEMBER_ID);

        ArgumentCaptor<SimplefinConnection> saved = ArgumentCaptor.forClass(SimplefinConnection.class);
        verify(connectionRepository).save(saved.capture());
        assertThat(saved.getValue().getAccessUrl()).isEqualTo("ciphertext");
        assertThat(saved.getValue().getStatus()).isEqualTo("CONNECTED");
        assertThat(saved.getValue().getMember()).isSameAs(member);
    }

    @Test
    void syncCreatesAccountsSkipsNonIsoAndDoesNotResurrectDeletedOnes() {
        SimplefinConnection connection = connection();
        when(connectionRepository.findByMemberId(MEMBER_ID)).thenReturn(Optional.of(connection));
        when(encryption.decrypt("ciphertext")).thenReturn(ACCESS);
        LocalDate start = LocalDate.now().minusDays(30);
        when(transactionImportService.sharedHistoryStart()).thenReturn(start);
        FamilyMember member = new FamilyMember();
        member.setId(MEMBER_ID);
        when(familyMemberRepository.findById(MEMBER_ID)).thenReturn(Optional.of(member));
        when(simplefinPort.fetchAccounts(ACCESS, start)).thenReturn(new SimplefinAccountSet(
            List.of(),
            List.of(
                account("sfin_CON-1_chk", "Chase", "Checking", "USD", "10.00",
                    new SimplefinTransaction("tx-1", LocalDate.of(2026, 1, 2), new BigDecimal("-4.50"), "Coffee")),
                account("sfin_CON-1_sav", "Chase", "Savings", "usd", "80.00"),
                account("sfin_CON-1_miles", "Chase", "Rewards", "https://example.com/miles", "12"),
                account("sfin_CON-1_gone", "Chase", "Old", "USD", "1.00"),
                account("sfin_CON-1_huge", "Chase", "Overflow", "USD", "1000000000000")
            )));
        when(accountRepository.findByExternalAccountIdAndMemberId("sfin_CON-1_chk", MEMBER_ID))
            .thenReturn(Optional.empty());
        when(accountRepository.findByExternalAccountIdAndMemberId("sfin_CON-1_sav", MEMBER_ID))
            .thenReturn(Optional.empty());
        when(accountRepository.findByExternalAccountIdAndMemberId("sfin_CON-1_gone", MEMBER_ID))
            .thenReturn(Optional.empty());
        when(accountRepository.existsSoftDeletedByExternalAccountIdAndMemberId(any(), eq(MEMBER_ID)))
            .thenAnswer(invocation -> "sfin_CON-1_gone".equals(invocation.getArgument(0)));
        when(accountRepository.save(any())).thenAnswer(invocation -> {
            Account saved = invocation.getArgument(0);
            if (saved.getId() == null) saved.setId(saved.getExternalAccountId().endsWith("sav") ? 2L : 1L);
            return saved;
        });
        when(accountService.toResponse(any())).thenReturn(org.mockito.Mockito.mock(AccountResponse.class));

        List<AccountResponse> synced = service.sync(MEMBER_ID);

        assertThat(synced).hasSize(2);
        ArgumentCaptor<Account> accounts = ArgumentCaptor.forClass(Account.class);
        verify(accountRepository, org.mockito.Mockito.times(2)).save(accounts.capture());
        Account checking = accounts.getAllValues().get(0);
        assertThat(checking.getName()).isEqualTo("Chase — Checking");
        assertThat(checking.getType()).isEqualTo(AccountType.CHECKING);
        assertThat(checking.getProvider()).isEqualTo("SimpleFIN");
        assertThat(checking.getCurrency()).isEqualTo("USD");
        assertThat(checking.isManual()).isFalse();
        Account savings = accounts.getAllValues().get(1);
        assertThat(savings.getType()).isEqualTo(AccountType.CHECKING);
        assertThat(savings.getName()).isEqualTo("Chase — Savings");

        ArgumentCaptor<List<TransactionData>> imported = ArgumentCaptor.forClass(List.class);
        verify(transactionImportService).importProvided(eq(checking), imported.capture());
        assertThat(imported.getValue()).singleElement().satisfies(tx -> {
            assertThat(tx.externalId()).isEqualTo("tx-1");
            assertThat(tx.amount()).isEqualByComparingTo("-4.50");
        });
        verify(accountService).upsertSnapshotFromNative(eq(checking), eq(new BigDecimal("10.00")), any());
        verify(accountRepository, never()).save(org.mockito.ArgumentMatchers.argThat(
            account -> "sfin_CON-1_gone".equals(account.getExternalAccountId())));
        assertThat(connection.getStatus()).isEqualTo("CONNECTED");
        assertThat(connection.getLastSyncedAt()).isNotNull();
    }

    @Test
    void revokedAccessMarksTheConnectionInError() {
        SimplefinConnection connection = connection();
        when(connectionRepository.findByMemberId(MEMBER_ID)).thenReturn(Optional.of(connection));
        when(encryption.decrypt("ciphertext")).thenReturn(ACCESS);
        when(transactionImportService.sharedHistoryStart()).thenReturn(LocalDate.of(2025, 10, 1));
        when(simplefinPort.fetchAccounts(any(), any()))
            .thenThrow(new SyncException("SimpleFIN refused the stored access. It may have been revoked."));

        assertThatThrownBy(() -> service.sync(MEMBER_ID))
            .isInstanceOf(SyncException.class)
            .hasMessageContaining("revoked");
        verify(statusWriter).markError(42L);
    }

    @Test
    void maskKeepsOnlyTheLastFourCharactersOfTheUsername() {
        assertThat(SimplefinSyncService.mask(ACCESS)).isEqualTo("••••1234");
        assertThat(SimplefinSyncService.accountName("Chase", "Checking")).isEqualTo("Chase — Checking");
        assertThat(SimplefinSyncService.accountName("", "x".repeat(99) + "\uD83D\uDE00")).isEqualTo("x".repeat(99));
        LocalDate today = LocalDate.of(2026, 10, 5);
        assertThat(SimplefinSyncService.bridgeStart(today.minusDays(90), today)).isEqualTo(today.minusDays(89));
        assertThat(SimplefinSyncService.bridgeStart(today.minusDays(30), today)).isEqualTo(today.minusDays(30));
        assertThat(SimplefinSyncService.isIsoCurrency("https://example.com/miles")).isFalse();
    }

    @Test
    void aResyncKeepsTheTypeTheMemberChose() {
        SimplefinConnection connection = connection();
        when(connectionRepository.findByMemberId(MEMBER_ID)).thenReturn(Optional.of(connection));
        when(encryption.decrypt("ciphertext")).thenReturn(ACCESS);
        LocalDate start = LocalDate.now(ZoneOffset.UTC).minusDays(30);
        when(transactionImportService.sharedHistoryStart()).thenReturn(start);
        FamilyMember member = new FamilyMember();
        member.setId(MEMBER_ID);
        when(familyMemberRepository.findById(MEMBER_ID)).thenReturn(Optional.of(member));
        when(simplefinPort.fetchAccounts(ACCESS, start)).thenReturn(new SimplefinAccountSet(
            List.of(), List.of(account("sfin_CON-1_card", "Chase", "Sapphire Reserve", "USD", "-1146.69"))));
        Account card = new Account();
        card.setId(5L);
        card.setType(AccountType.CREDIT_CARD);
        card.setCurrentBalance(new BigDecimal("-1000.00"));
        card.setExternalAccountId("sfin_CON-1_card");
        when(accountRepository.findByExternalAccountIdAndMemberId("sfin_CON-1_card", MEMBER_ID))
            .thenReturn(Optional.of(card));
        when(accountRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(accountService.toResponse(any())).thenReturn(org.mockito.Mockito.mock(AccountResponse.class));

        service.sync(MEMBER_ID);

        assertThat(card.getType()).isEqualTo(AccountType.CREDIT_CARD);
        assertThat(card.getCurrentBalance()).isEqualByComparingTo("-1146.69");
    }

    @Test
    void theHistoryWindowIsClampedOnTheUtcDate() {
        SimplefinConnection connection = connection();
        when(connectionRepository.findByMemberId(MEMBER_ID)).thenReturn(Optional.of(connection));
        when(encryption.decrypt("ciphertext")).thenReturn(ACCESS);
        when(transactionImportService.sharedHistoryStart()).thenReturn(LocalDate.of(2020, 1, 1));
        when(simplefinPort.fetchAccounts(any(), any())).thenThrow(new SyncException("stop"));

        assertThatThrownBy(() -> service.sync(MEMBER_ID)).isInstanceOf(SyncException.class);

        verify(simplefinPort).fetchAccounts(ACCESS, LocalDate.now(ZoneOffset.UTC).minusDays(89));
    }

    @Test
    void theDailyJobReportsInsteadOfThrowing() {
        when(connectionRepository.findByMemberId(MEMBER_ID)).thenReturn(Optional.empty());
        assertThat(service.resyncReporting(MEMBER_ID).status()).isEqualTo(SourceSyncResult.Status.SKIPPED_NOT_CONNECTED);

        SimplefinConnection connection = connection();
        when(connectionRepository.findByMemberId(MEMBER_ID)).thenReturn(Optional.of(connection));
        when(encryption.decrypt("ciphertext")).thenReturn(ACCESS);
        when(transactionImportService.sharedHistoryStart()).thenReturn(LocalDate.of(2025, 10, 1));
        when(simplefinPort.fetchAccounts(any(), any())).thenThrow(new SyncException("revoked"));

        SourceSyncResult failed = service.resyncReporting(MEMBER_ID);

        assertThat(failed.source()).isEqualTo("simplefin");
        assertThat(failed.status()).isEqualTo(SourceSyncResult.Status.FAILED);
        verify(statusWriter).markError(42L);
    }

    private static SimplefinConnection connection() {
        SimplefinConnection connection = new SimplefinConnection();
        connection.setId(42L);
        connection.setAccessUrl("ciphertext");
        connection.setStatus("CONNECTED");
        return connection;
    }

    private static SimplefinAccount account(
        String externalId, String bank, String name, String currency, String balance, SimplefinTransaction... txs
    ) {
        return new SimplefinAccount(externalId, bank, name, currency, new BigDecimal(balance), List.of(txs));
    }
}

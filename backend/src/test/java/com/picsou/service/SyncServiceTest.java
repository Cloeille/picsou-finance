package com.picsou.service;

import com.picsou.dto.AccountResponse;
import com.picsou.model.Account;
import com.picsou.model.FamilyMember;
import com.picsou.model.Requisition;
import com.picsou.model.RequisitionStatus;
import com.picsou.port.BankConnectorPort;
import com.picsou.port.BankConnectorPort.AccountData;
import com.picsou.port.BankConnectorPort.InstitutionData;
import com.picsou.repository.AccountRepository;
import com.picsou.repository.FamilyMemberRepository;
import com.picsou.repository.RequisitionRepository;
import com.picsou.repository.TransactionRepository;
import com.picsou.service.budget.CategorizationService;
import com.picsou.service.budget.RecurringDetectionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SyncServiceTest {

    @Mock BankConnectorPort bankConnector;
    @Mock AccountRepository accountRepository;
    @Mock RequisitionRepository requisitionRepository;
    @Mock FamilyMemberRepository familyMemberRepository;
    @Mock AccountService accountService;
    @Mock TransactionRepository transactionRepository;
    @Mock CategorizationService categorizationService;
    @Mock RecurringDetectionService recurringDetectionService;
    @Mock RequisitionLifecycleWriter requisitionLifecycleWriter;

    SyncService syncService;

    /**
     * The real resolver over the mocked connector, not a mock of it: the logo assertions below
     * are about which institution the sync path ends up matching, and stubbing the resolver
     * would assert nothing but that SyncService calls it.
     */
    @BeforeEach
    void wireSyncService() {
        syncService = new SyncService(
            bankConnector,
            accountRepository,
            requisitionRepository,
            familyMemberRepository,
            accountService,
            transactionRepository,
            categorizationService,
            recurringDetectionService,
            requisitionLifecycleWriter,
            new BankLogoResolver(bankConnector)
        );
    }

    /**
     * initiateConnection resolves the logo itself from the server-side institution
     * catalog (matched by exact institutionId) -- there is no client-supplied logoUrl
     * to trust or persist.
     */
    @Test
    void initiateConnection_resolvesLogoUrlServerSideByExactInstitutionId() {
        Long memberId = 5L;
        FamilyMember member = FamilyMember.builder().id(memberId).displayName("Owner").build();
        when(familyMemberRepository.findById(memberId)).thenReturn(Optional.of(member));

        when(bankConnector.initiateConnection(
                org.mockito.ArgumentMatchers.eq("BNP Paribas::FR::personal"), any(String.class)))
            .thenReturn(new BankConnectorPort.InitiateResult("auth-1", "https://auth.example/link"));

        InstitutionData wrongCountry = new InstitutionData("BNP Paribas::BE::personal", "BNP Paribas", "GEBABEBB",
            "https://logos.example/bnp-be.png", "BE", "personal");
        InstitutionData exact = new InstitutionData("BNP Paribas::FR::personal", "BNP Paribas", "BNPAFRPP",
            "https://logos.example/bnp-fr.png", "FR", "personal");
        when(bankConnector.searchInstitutions("BNP Paribas", "FR")).thenReturn(List.of(wrongCountry, exact));

        when(requisitionRepository.save(any(Requisition.class))).thenAnswer(inv -> inv.getArgument(0));

        syncService.initiateConnection("BNP Paribas::FR::personal", "BNP Paribas", memberId);

        ArgumentCaptor<Requisition> captor = ArgumentCaptor.forClass(Requisition.class);
        verify(requisitionRepository).save(captor.capture());
        assertThat(captor.getValue().getLogoUrl()).isEqualTo("https://logos.example/bnp-fr.png");
    }

    /** New accounts created from a requisition that already carries a logo get it copied over. */
    @Test
    void completeConnection_copiesLogoUrlFromRequisitionOntoNewAccount() {
        Long memberId = 1L;
        FamilyMember member = FamilyMember.builder().id(memberId).displayName("Owner").build();

        Requisition requisition = Requisition.builder()
            .id(10L)
            .member(member)
            .requisitionId("code-123")
            .institutionId("BNP_PARIBAS::FR")
            .institutionName("BNP Paribas")
            .logoUrl("https://logos.example/bnp.png")
            .status(RequisitionStatus.CREATED)
            .build();

        when(requisitionRepository.findByStatusAndMemberIdOrderByCreatedAtDesc(RequisitionStatus.CREATED, memberId))
            .thenReturn(List.of(requisition));
        when(bankConnector.exchangeCode("oauth-code")).thenReturn("session-1");

        AccountData accountData = new AccountData("ext-1", "Compte Courant", "FR76...", "EUR", new BigDecimal("100"));
        when(bankConnector.fetchBalances("session-1")).thenReturn(List.of(accountData));

        when(accountRepository.findByExternalAccountIdAndMemberId("ext-1", memberId)).thenReturn(Optional.empty());
        lenient().when(accountRepository.existsSoftDeletedByExternalAccountIdAndMemberId("ext-1", memberId))
            .thenReturn(false);
        when(accountRepository.save(any(Account.class))).thenAnswer(inv -> {
            Account a = inv.getArgument(0);
            a.setId(99L);
            return a;
        });
        lenient().when(accountService.toResponse(any(Account.class)))
            .thenReturn(new AccountResponse(99L, "Compte Courant", null, "BNP Paribas", "EUR",
                new BigDecimal("100"), new BigDecimal("100"), null, null, false, "#6366f1", null,
                "https://logos.example/bnp.png", null, null, null, null, null, null, null, false, null, null));
        // Merged in from 1.1.0: completeConnection now ingests transactions per account.
        when(bankConnector.fetchTransactions(any(), any(), any())).thenReturn(List.of());

        syncService.completeConnection("oauth-code", null, memberId);

        ArgumentCaptor<Account> captor = ArgumentCaptor.forClass(Account.class);
        verify(accountRepository).save(captor.capture());
        assertThat(captor.getValue().getLogoUrl()).isEqualTo("https://logos.example/bnp.png");
    }

    /** A retry that still sees no accounts must stay retryable instead of becoming a false success. */
    @Test
    void retrySync_emptyAccountListMarksRequisitionFailed() {
        Long memberId = 4L;
        FamilyMember member = FamilyMember.builder().id(memberId).displayName("Owner").build();

        Requisition requisition = Requisition.builder()
            .id(40L)
            .member(member)
            .requisitionId("session-40")
            .institutionId("REVOLUT::FR")
            .institutionName("Revolut")
            .status(RequisitionStatus.FAILED)
            .build();

        when(requisitionRepository.findByIdAndMemberId(40L, memberId)).thenReturn(Optional.of(requisition));
        when(bankConnector.fetchBalances("session-40")).thenReturn(List.of());

        List<AccountResponse> responses = syncService.retrySync(40L, memberId);

        assertThat(responses).isEmpty();
        assertThat(requisition.getStatus()).isEqualTo(RequisitionStatus.FAILED);
        assertThat(requisition.getLastSyncedAt()).isNull();
        verify(requisitionRepository).save(requisition);
        verify(accountRepository, never()).save(any(Account.class));
    }

    /** A requisition created before logos existed gets backfilled on the next resync. */
    @Test
    void resyncAll_backfillsMissingLogoUrlFromInstitutionSearch() {
        Long memberId = 2L;
        FamilyMember member = FamilyMember.builder().id(memberId).displayName("Owner").build();

        Requisition requisition = Requisition.builder()
            .id(20L)
            .member(member)
            .requisitionId("session-2")
            .institutionId("BoursoBank::FR::personal")
            .institutionName("BoursoBank")
            .logoUrl(null)
            .status(RequisitionStatus.LINKED)
            .build();

        when(requisitionRepository.findByStatusAndMemberIdOrderByCreatedAtDesc(RequisitionStatus.LINKED, memberId))
            .thenReturn(List.of(requisition));

        InstitutionData match = new InstitutionData("BoursoBank::FR::personal", "BoursoBank", "BNPAFRPP",
            "https://logos.example/bourso.png", "FR", "personal");
        when(bankConnector.searchInstitutions("BoursoBank", "FR")).thenReturn(List.of(match));

        when(bankConnector.fetchBalances("session-2")).thenReturn(List.of());

        syncService.resyncAll(memberId);

        assertThat(requisition.getLogoUrl()).isEqualTo("https://logos.example/bourso.png");
        assertThat(requisition.getLogoBackfillAttemptedAt()).isNotNull();
    }

    /** Once a backfill attempt has run (hit or miss), it must not be retried on every subsequent sync. */
    @Test
    void resyncAll_doesNotRetryBackfillOnceAttempted() {
        Long memberId = 21L;
        FamilyMember member = FamilyMember.builder().id(memberId).displayName("Owner").build();

        Requisition requisition = Requisition.builder()
            .id(21L)
            .member(member)
            .requisitionId("session-21")
            .institutionId("RENAMED_BANK::FR")
            .institutionName("Renamed Bank")
            .logoUrl(null)
            .logoBackfillAttemptedAt(java.time.Instant.now())
            .status(RequisitionStatus.LINKED)
            .build();

        when(requisitionRepository.findByStatusAndMemberIdOrderByCreatedAtDesc(RequisitionStatus.LINKED, memberId))
            .thenReturn(List.of(requisition));
        when(bankConnector.fetchBalances("session-21")).thenReturn(List.of());

        syncService.resyncAll(memberId);

        verify(bankConnector, org.mockito.Mockito.never()).searchInstitutions(any(), any());
        assertThat(requisition.getLogoUrl()).isNull();
    }

    /** When both an exact id match and a same-named institution from another country are returned, the id wins. */
    @Test
    void resyncAll_backfillPrefersExactIdMatchOverName() {
        Long memberId = 22L;
        FamilyMember member = FamilyMember.builder().id(memberId).displayName("Owner").build();

        Requisition requisition = Requisition.builder()
            .id(22L)
            .member(member)
            .requisitionId("session-22")
            .institutionId("Revolut::FR::personal")
            .institutionName("Revolut")
            .logoUrl(null)
            .status(RequisitionStatus.LINKED)
            .build();

        when(requisitionRepository.findByStatusAndMemberIdOrderByCreatedAtDesc(RequisitionStatus.LINKED, memberId))
            .thenReturn(List.of(requisition));

        InstitutionData wrongCountryMatch = new InstitutionData("Revolut::LT::personal", "Revolut", "REVOLT21",
            "https://logos.example/revolut-lt.png", "LT", "personal");
        InstitutionData exactMatch = new InstitutionData("Revolut::FR::personal", "Revolut", "REVOLT21",
            "https://logos.example/revolut-fr.png", "FR", "personal");
        when(bankConnector.searchInstitutions("Revolut", "FR")).thenReturn(List.of(wrongCountryMatch, exactMatch));
        when(bankConnector.fetchBalances("session-22")).thenReturn(List.of());

        syncService.resyncAll(memberId);

        assertThat(requisition.getLogoUrl()).isEqualTo("https://logos.example/revolut-fr.png");
    }

    /**
     * Requisitions linked before PSU types were modelled store the two-segment
     * "Revolut::FR", while the catalog now returns three-segment ids. The country
     * preference must survive that mismatch rather than degrading to a bare name
     * match, which would pick whichever entry the provider happened to list first.
     */
    @Test
    void resyncAll_backfillMatchesLegacyTwoSegmentIdOnNameAndCountry() {
        Long memberId = 23L;
        FamilyMember member = FamilyMember.builder().id(memberId).displayName("Owner").build();

        Requisition requisition = Requisition.builder()
            .id(23L)
            .member(member)
            .requisitionId("session-23")
            .institutionId("Revolut::FR")
            .institutionName("Revolut")
            .logoUrl(null)
            .status(RequisitionStatus.LINKED)
            .build();

        when(requisitionRepository.findByStatusAndMemberIdOrderByCreatedAtDesc(RequisitionStatus.LINKED, memberId))
            .thenReturn(List.of(requisition));

        InstitutionData wrongCountry = new InstitutionData("Revolut::LT::personal", "Revolut", "REVOLT21",
            "https://logos.example/revolut-lt.png", "LT", "personal");
        InstitutionData rightCountry = new InstitutionData("Revolut::FR::personal", "Revolut", "REVOLT21",
            "https://logos.example/revolut-fr.png", "FR", "personal");
        when(bankConnector.searchInstitutions("Revolut", "FR")).thenReturn(List.of(wrongCountry, rightCountry));
        when(bankConnector.fetchBalances("session-23")).thenReturn(List.of());

        syncService.resyncAll(memberId);

        assertThat(requisition.getLogoUrl()).isEqualTo("https://logos.example/revolut-fr.png");
    }

    /**
     * Same tier as the test above, with the stored name in a different case than the
     * catalog now returns. A stored id was written from the catalog name of the day, so
     * casing drift on the provider side is ordinary; matching it case-sensitively drops
     * to the name-only tier, which takes the first result and so can hand back a logo
     * from another country -- LT here, for an FR requisition.
     */
    @Test
    void resyncAll_backfillMatchesLegacyIdWhoseNameCaseDrifted() {
        Long memberId = 24L;
        FamilyMember member = FamilyMember.builder().id(memberId).displayName("Owner").build();

        Requisition requisition = Requisition.builder()
            .id(24L)
            .member(member)
            .requisitionId("session-24")
            .institutionId("revolut::FR")
            .institutionName("Revolut")
            .logoUrl(null)
            .status(RequisitionStatus.LINKED)
            .build();

        when(requisitionRepository.findByStatusAndMemberIdOrderByCreatedAtDesc(RequisitionStatus.LINKED, memberId))
            .thenReturn(List.of(requisition));

        InstitutionData wrongCountry = new InstitutionData("Revolut::LT::personal", "Revolut", "REVOLT21",
            "https://logos.example/revolut-lt.png", "LT", "personal");
        InstitutionData rightCountry = new InstitutionData("Revolut::FR::personal", "Revolut", "REVOLT21",
            "https://logos.example/revolut-fr.png", "FR", "personal");
        when(bankConnector.searchInstitutions("Revolut", "FR")).thenReturn(List.of(wrongCountry, rightCountry));
        when(bankConnector.fetchBalances("session-24")).thenReturn(List.of());

        syncService.resyncAll(memberId);

        assertThat(requisition.getLogoUrl()).isEqualTo("https://logos.example/revolut-fr.png");
    }

    /** A failed institution search during backfill must not break the resync loop. */
    @Test
    void resyncAll_backfillFailureDoesNotBreakSync() {
        Long memberId = 3L;
        FamilyMember member = FamilyMember.builder().id(memberId).displayName("Owner").build();

        Requisition requisition = Requisition.builder()
            .id(30L)
            .member(member)
            .requisitionId("session-3")
            .institutionId("UNKNOWN::FR")
            .institutionName("Unknown Bank")
            .logoUrl(null)
            .status(RequisitionStatus.LINKED)
            .build();

        when(requisitionRepository.findByStatusAndMemberIdOrderByCreatedAtDesc(RequisitionStatus.LINKED, memberId))
            .thenReturn(List.of(requisition));
        when(bankConnector.searchInstitutions("Unknown Bank", "FR"))
            .thenThrow(new RuntimeException("provider unavailable"));
        when(bankConnector.fetchBalances("session-3")).thenReturn(List.of());

        syncService.resyncAll(memberId);

        // The failed logo lookup is swallowed inside ensureLogoUrl. The empty
        // balance response is not a successful sync, but an already-LINKED
        // session must not flap to FAILED on a transient provider gap.
        assertThat(requisition.getLogoUrl()).isNull();
        assertThat(requisition.getStatus()).isEqualTo(RequisitionStatus.LINKED);
        assertThat(requisition.getLastSyncedAt()).isNull();
        verify(requisitionRepository, never()).save(requisition);
    }

    // --- Reconnect: re-initiate OAuth on an existing (dead) requisition ---

    @Test
    void reconnect_reinitiatesAuthOnExistingRequisition() {
        Long memberId = 4L;
        FamilyMember member = FamilyMember.builder().id(memberId).displayName("Owner").build();

        Requisition requisition = Requisition.builder()
            .id(40L)
            .member(member)
            .requisitionId("dead-authorization-id")
            .institutionId("REVOLUT::FR")
            .institutionName("Revolut")
            .status(RequisitionStatus.FAILED)
            .build();

        when(requisitionRepository.findByIdAndMemberId(40L, memberId)).thenReturn(Optional.of(requisition));
        when(bankConnector.initiateConnection(org.mockito.ArgumentMatchers.eq("REVOLUT::FR"), any(String.class)))
            .thenReturn(new BankConnectorPort.InitiateResult("new-auth-id", "https://auth.example/new"));
        when(requisitionRepository.save(any(Requisition.class))).thenAnswer(inv -> inv.getArgument(0));

        SyncService.InitiateResponse response = syncService.reconnect(40L, memberId);

        assertThat(response.requisitionId()).isEqualTo("new-auth-id");
        assertThat(response.authLink()).isEqualTo("https://auth.example/new");
        assertThat(requisition.getRequisitionId()).isEqualTo("new-auth-id");
        assertThat(requisition.getAuthLink()).isEqualTo("https://auth.example/new");
        assertThat(requisition.getStatus()).isEqualTo(RequisitionStatus.CREATED);
        verify(requisitionRepository).save(requisition);
    }

    @Test
    void reconnect_unknownRequisition_throwsNotFound() {
        when(requisitionRepository.findByIdAndMemberId(99L, 1L)).thenReturn(Optional.empty());

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> syncService.reconnect(99L, 1L))
            .isInstanceOf(com.picsou.exception.ResourceNotFoundException.class);

        verify(bankConnector, never()).initiateConnection(any(), any());
    }

    /** A LINKED requisition holds a working session id — reconnect must refuse to clobber it. */
    @Test
    void reconnect_refusesLinkedRequisition() {
        Long memberId = 4L;
        FamilyMember member = FamilyMember.builder().id(memberId).displayName("Owner").build();

        Requisition linked = Requisition.builder()
            .id(41L)
            .member(member)
            .requisitionId("live-session-id")
            .institutionId("BNP_PARIBAS::FR")
            .institutionName("BNP Paribas")
            .status(RequisitionStatus.LINKED)
            .build();
        when(requisitionRepository.findByIdAndMemberId(41L, memberId)).thenReturn(Optional.of(linked));

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> syncService.reconnect(41L, memberId))
            .isInstanceOf(com.picsou.exception.SyncException.class)
            .hasMessageContaining("still active");

        verify(bankConnector, never()).initiateConnection(any(), any());
        assertThat(linked.getRequisitionId()).isEqualTo("live-session-id");
        assertThat(linked.getStatus()).isEqualTo(RequisitionStatus.LINKED);
    }

    /**
     * The exchanged session id must survive a balance-fetch failure: the OAuth
     * code is consumed at Enable Banking, so if the id were lost the requisition
     * would keep pointing at the stale authorization id — permanently unretryable.
     */
    @Test
    void completeConnection_persistsSessionIdBeforeFetch() {
        Long memberId = 1L;
        FamilyMember member = FamilyMember.builder().id(memberId).displayName("Owner").build();

        Requisition requisition = Requisition.builder()
            .id(10L)
            .member(member)
            .requisitionId("authorization-id")
            .institutionId("REVOLUT::FR")
            .institutionName("Revolut")
            .logoUrl("https://logos.example/revolut.png")
            .status(RequisitionStatus.CREATED)
            .build();

        when(requisitionRepository.findByStatusAndMemberIdOrderByCreatedAtDesc(RequisitionStatus.CREATED, memberId))
            .thenReturn(List.of(requisition));
        when(bankConnector.exchangeCode("oauth-code")).thenReturn("sess-1");
        when(bankConnector.fetchBalances("sess-1"))
            .thenThrow(new com.picsou.exception.SyncException("Failed to fetch session: boom"));

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> syncService.completeConnection("oauth-code", null, memberId))
            .isInstanceOf(com.picsou.exception.SyncException.class);

        org.mockito.InOrder order = org.mockito.Mockito.inOrder(
            bankConnector,
            requisitionLifecycleWriter
        );
        order.verify(bankConnector).exchangeCode("oauth-code");
        order.verify(requisitionLifecycleWriter).checkpointSession(10L, memberId, "sess-1");
        order.verify(bankConnector).fetchBalances("sess-1");
        order.verify(requisitionLifecycleWriter).markFailed(10L, memberId);
    }

    @Test
    void completeConnection_upsertFailureKeepsCheckpointAndMarksRetryable() {
        Long memberId = 1L;
        FamilyMember member = FamilyMember.builder().id(memberId).displayName("Owner").build();
        Requisition requisition = createdRequisition(10L, member, "REVOLUT::FR", "Revolut", "state-x");
        AccountData accountData = new AccountData("ext-1", "Compte", "FR76...", "EUR", new BigDecimal("10"));

        when(requisitionRepository.findByOauthState("state-x")).thenReturn(Optional.of(requisition));
        when(bankConnector.exchangeCode("oauth-code")).thenReturn("sess-1");
        when(bankConnector.fetchBalances("sess-1")).thenReturn(List.of(accountData));
        when(accountRepository.findByExternalAccountIdAndMemberId("ext-1", memberId)).thenReturn(Optional.empty());
        when(accountRepository.existsSoftDeletedByExternalAccountIdAndMemberId("ext-1", memberId)).thenReturn(false);
        when(accountRepository.save(any(Account.class))).thenAnswer(invocation -> {
            Account account = invocation.getArgument(0);
            account.setId(99L);
            return account;
        });
        when(accountService.toResponse(any(Account.class))).thenAnswer(invocation ->
            AccountResponse.from(invocation.getArgument(0), new BigDecimal("10")));
        org.mockito.Mockito.doThrow(new DataIntegrityViolationException("snapshot constraint"))
            .when(accountRepository).flush();

        org.assertj.core.api.Assertions.assertThatThrownBy(
            () -> syncService.completeConnection("oauth-code", "state-x", memberId)
        )
            .isInstanceOf(com.picsou.exception.SyncException.class)
            .hasMessageContaining("could not be saved")
            .hasCauseInstanceOf(DataIntegrityViolationException.class);

        verify(requisitionLifecycleWriter).checkpointSession(10L, memberId, "sess-1");
        verify(requisitionLifecycleWriter).markFailed(10L, memberId);
        verify(requisitionRepository, never()).save(requisition);
    }

    @Test
    void completeConnection_exchangeFailureRetainsNonceAndMarksFailed() {
        Long memberId = 1L;
        FamilyMember member = FamilyMember.builder().id(memberId).displayName("Owner").build();
        Requisition requisition = createdRequisition(10L, member, "REVOLUT::FR", "Revolut", "state-x");
        when(requisitionRepository.findByOauthState("state-x")).thenReturn(Optional.of(requisition));
        when(bankConnector.exchangeCode("oauth-code"))
            .thenThrow(new com.picsou.exception.SyncException("temporary exchange failure"));

        org.assertj.core.api.Assertions.assertThatThrownBy(
            () -> syncService.completeConnection("oauth-code", "state-x", memberId)
        )
            .isInstanceOf(com.picsou.exception.SyncException.class)
            .hasMessageContaining("temporary exchange failure");

        verify(requisitionLifecycleWriter).markFailed(10L, memberId);
        verify(requisitionLifecycleWriter, never()).checkpointSession(any(), any(), any());
        assertThat(requisition.getOauthState()).isEqualTo("state-x");
    }

    // --- OAuth state correlation ---

    private Requisition createdRequisition(Long id, FamilyMember member, String institutionId, String name, String state) {
        return Requisition.builder()
            .id(id)
            .member(member)
            .requisitionId("auth-" + id)
            .institutionId(institutionId)
            .institutionName(name)
            .status(RequisitionStatus.CREATED)
            .oauthState(state)
            .build();
    }

    /** The state nonce must pick the exact requisition, not the newest CREATED one. */
    @Test
    void completeConnection_resolvesByState() {
        Long memberId = 1L;
        FamilyMember member = FamilyMember.builder().id(memberId).displayName("Owner").build();
        Requisition revolut = createdRequisition(10L, member, "REVOLUT::FR", "Revolut", "state-revolut");

        when(requisitionRepository.findByOauthState("state-revolut")).thenReturn(Optional.of(revolut));
        when(bankConnector.exchangeCode("oauth-code")).thenReturn("sess-1");
        when(bankConnector.fetchBalances("sess-1")).thenReturn(List.of());

        syncService.completeConnection("oauth-code", "state-revolut", memberId);

        // Resolved by state — the latest-CREATED guess must not even be consulted.
        verify(requisitionRepository, never())
            .findByStatusAndMemberIdOrderByCreatedAtDesc(RequisitionStatus.CREATED, memberId);
        verify(requisitionLifecycleWriter).checkpointSession(10L, memberId, "sess-1");
        verify(requisitionLifecycleWriter).markFailed(10L, memberId);
    }

    @Test
    void completeConnection_unknownState_throwsWhenNoLegacyRow() {
        when(requisitionRepository.findByOauthState("bogus")).thenReturn(Optional.empty());
        when(requisitionRepository.findByStatusAndMemberIdOrderByCreatedAtDesc(RequisitionStatus.CREATED, 1L))
            .thenReturn(List.of());

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> syncService.completeConnection("oauth-code", "bogus", 1L))
            .isInstanceOf(com.picsou.exception.SyncException.class)
            .hasMessageContaining("Unknown or expired");

        verify(bankConnector, never()).exchangeCode(any());
    }

    /**
     * Pre-nonce requisitions sent an old-format state that was never persisted
     * (oauth_state NULL). An unknown state must fall back to them — but never
     * to a post-migration row, whose stored nonce is the only way in.
     */
    @Test
    void completeConnection_unknownState_fallsBackToLegacyNullStateRowOnly() {
        Long memberId = 1L;
        FamilyMember member = FamilyMember.builder().id(memberId).displayName("Owner").build();
        Requisition postMigration = createdRequisition(11L, member, "BNP_PARIBAS::FR", "BNP Paribas", "state-bnp");
        Requisition legacy = createdRequisition(10L, member, "REVOLUT::FR", "Revolut", null);

        when(requisitionRepository.findByOauthState("picsou-app_1720000000")).thenReturn(Optional.empty());
        when(requisitionRepository.findByStatusAndMemberIdOrderByCreatedAtDesc(RequisitionStatus.CREATED, memberId))
            .thenReturn(List.of(postMigration, legacy)); // newest first — nonce row must be skipped
        when(bankConnector.exchangeCode("oauth-code")).thenReturn("sess-1");
        when(bankConnector.fetchBalances("sess-1")).thenReturn(List.of());

        syncService.completeConnection("oauth-code", "picsou-app_1720000000", memberId);

        verify(requisitionLifecycleWriter).checkpointSession(10L, memberId, "sess-1");
        verify(requisitionLifecycleWriter).markFailed(10L, memberId);
        assertThat(postMigration.getRequisitionId()).isEqualTo("auth-11"); // untouched
    }

    /** A replayed callback (code already used) must only resync the SAME institution. */
    @Test
    void alreadyAuthorized_scopedToSameInstitution() {
        Long memberId = 1L;
        FamilyMember member = FamilyMember.builder().id(memberId).displayName("Owner").build();
        Requisition revolut = createdRequisition(10L, member, "REVOLUT::FR", "Revolut", "state-revolut");

        when(requisitionRepository.findByOauthState("state-revolut")).thenReturn(Optional.of(revolut));
        when(bankConnector.exchangeCode("oauth-code"))
            .thenThrow(new com.picsou.exception.SyncException("Enable Banking code exchange failed: ALREADY_AUTHORIZED"));
        // A BNP session is LINKED, but no Revolut one: the fallback must not touch BNP.
        when(requisitionRepository.findByStatusAndMemberIdAndInstitutionIdOrderByCreatedAtDesc(
            RequisitionStatus.LINKED, memberId, "REVOLUT::FR")).thenReturn(List.of());

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> syncService.completeConnection("oauth-code", "state-revolut", memberId))
            .isInstanceOf(com.picsou.exception.SyncException.class)
            .hasMessageContaining("No linked session");

        verify(bankConnector, never()).fetchBalances(any());
    }

    /** Admin impersonation: the requisition's own member wins over the caller context. */
    @Test
    void completeConnection_usesRequisitionMember() {
        Long managedMemberId = 2L;
        FamilyMember managed = FamilyMember.builder().id(managedMemberId).displayName("Managed").build();
        Requisition requisition = createdRequisition(10L, managed, "REVOLUT::FR", "Revolut", "state-x");

        when(requisitionRepository.findByOauthState("state-x")).thenReturn(Optional.of(requisition));
        when(bankConnector.exchangeCode("oauth-code")).thenReturn("sess-1");
        AccountData accountData = new AccountData("ext-1", "Compte", "FR76...", "EUR", new BigDecimal("10"));
        when(bankConnector.fetchBalances("sess-1")).thenReturn(List.of(accountData));
        when(accountRepository.findByExternalAccountIdAndMemberId("ext-1", managedMemberId)).thenReturn(Optional.empty());
        lenient().when(accountRepository.existsSoftDeletedByExternalAccountIdAndMemberId("ext-1", managedMemberId))
            .thenReturn(false);
        when(accountRepository.save(any(Account.class))).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(accountService.toResponse(any(Account.class)))
            .thenReturn(new AccountResponse(1L, "Compte", null, "Revolut", "EUR",
                new BigDecimal("10"), new BigDecimal("10"), null, null, false, "#6366f1", null, null,
                null, null, null, null, null, null, null, false, null, null));

        // Caller context is member 1 (the admin), requisition belongs to member 2.
        syncService.completeConnection("oauth-code", "state-x", 1L);

        ArgumentCaptor<Account> captor = ArgumentCaptor.forClass(Account.class);
        verify(accountRepository).save(captor.capture());
        assertThat(captor.getValue().getMember().getId()).isEqualTo(managedMemberId);
    }
}

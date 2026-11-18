package com.picsou.service;

import com.picsou.adapter.LouveAdapter;
import com.picsou.adapter.LouveMcpClient;
import com.picsou.adapter.LouveOAuthClient;
import com.picsou.config.CryptoEncryption;
import com.picsou.exception.SyncException;
import com.picsou.model.FamilyMember;
import com.picsou.model.LouveConnection;
import com.picsou.model.ScpiProvider;
import com.picsou.port.SidecarErrorCode;
import com.picsou.repository.FamilyMemberRepository;
import com.picsou.repository.LouveConnectionRepository;
import com.picsou.service.sync.SourceSyncResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The OAuth connection and the sync against an in-memory {@code louve_connection} row. Synthetic
 * codes, tokens and figures only.
 */
@SuppressWarnings("unchecked")
class LouveSyncServiceTest {

    private static final long MEMBER = 7L;
    private static final String REDIRECT = "https://picsou.example/sync/louve/callback";
    private static final LouveAdapter.Snapshot PORTFOLIO = new LouveAdapter.Snapshot(new BigDecimal("960"),
        List.of(new LouveAdapter.Position("aurore", "Aurore", new BigDecimal("12"), new BigDecimal("960"),
            List.of("Pleine propriété"))), Map.of());

    private final MutableClock clock = new MutableClock(Instant.parse("2026-11-17T08:00:00Z"));
    private LouveConnection row;

    private LouveAdapter adapter;
    private LouveOAuthClient oauth;
    private LouveClientRegistry clients;
    private LouveConnectionRepository connections;
    private FamilyMemberRepository members;
    private ScpiFundReconciler reconciler;
    private LouveSyncService service;

    @BeforeEach
    void setUp() {
        adapter = mock(LouveAdapter.class);
        oauth = mock(LouveOAuthClient.class);
        clients = mock(LouveClientRegistry.class);
        connections = mock(LouveConnectionRepository.class);
        reconciler = mock(ScpiFundReconciler.class);
        members = mock(FamilyMemberRepository.class);
        when(members.findByIdForUpdate(MEMBER)).thenReturn(Optional.of(FamilyMember.builder().id(MEMBER).build()));

        CryptoEncryption encryption = mock(CryptoEncryption.class);
        lenient().when(encryption.encrypt(anyString())).thenAnswer(inv -> "enc:" + inv.getArgument(0));
        lenient().when(encryption.decrypt(anyString())).thenAnswer(inv -> {
            String value = inv.getArgument(0);
            if (!value.startsWith("enc:")) throw new IllegalStateException("bad tag");
            return value.substring(4);
        });

        lenient().when(connections.findByMemberId(MEMBER)).thenAnswer(inv -> Optional.ofNullable(row));
        lenient().when(connections.findByMemberIdForUpdate(MEMBER)).thenAnswer(inv -> Optional.ofNullable(row));
        lenient().when(connections.findById(any())).thenAnswer(inv -> Optional.ofNullable(row));
        lenient().when(connections.save(any())).thenAnswer(inv -> {
            LouveConnection saved = inv.getArgument(0);
            if (saved.getId() == null) ReflectionTestUtils.setField(saved, "id", 1L);
            row = saved;
            return saved;
        });
        lenient().doAnswer(inv -> {
            if (row == inv.getArgument(0)) row = null;
            return null;
        }).when(connections).delete(any());

        lenient().when(clients.clientIdFor(REDIRECT)).thenReturn("client-1");
        lenient().when(oauth.authorizeUri(anyString(), anyString(), anyString(), anyString()))
            .thenAnswer(inv -> URI.create("https://app.louveinvest.com/mcp/oauth/authorize?state=" + inv.getArgument(2)
                + "&code_challenge=" + inv.getArgument(3)));
        lenient().when(reconciler.reconcile(eq(MEMBER), eq(ScpiProvider.LOUVE), anyList(), anyList(), any()))
            .thenReturn(Map.of());

        service = new LouveSyncService(adapter, oauth, connections, members, encryption, inlineTransactions(),
            reconciler, clients, clock);
    }

    private static TransactionTemplate inlineTransactions() {
        TransactionTemplate tx = mock(TransactionTemplate.class);
        TransactionStatus status = mock(TransactionStatus.class);
        lenient().doAnswer(inv -> ((TransactionCallback<Object>) inv.getArgument(0)).doInTransaction(status))
            .when(tx).execute(any());
        return tx;
    }

    private LouveConnection connected(String tokens, String status) {
        row = LouveConnection.builder().id(1L).member(FamilyMember.builder().id(MEMBER).build())
            .tokenState("enc:" + tokens).status(status).build();
        return row;
    }

    private static String stateOf(LouveSyncService.StartResponse start) {
        String url = start.authorizeUrl();
        return url.substring(url.indexOf("state=") + 6, url.indexOf('&'));
    }

    // -- connection ------------------------------------------------------------------------

    @Test
    void startRecordsAnAttemptWhoseVerifierMatchesTheChallengeAndKeepsTheTokens() {
        connected("old-tokens", LouveConnection.STATUS_CONNECTED);

        LouveSyncService.StartResponse start = service.startConnection(MEMBER, REDIRECT);

        assertThat(row.getOauthState()).isEqualTo(stateOf(start));
        assertThat(row.getOauthRedirectUri()).isEqualTo(REDIRECT);
        assertThat(row.getOauthClientId()).isEqualTo("client-1");
        String verifier = row.getOauthVerifier().substring(4);
        assertThat(start.authorizeUrl()).contains("code_challenge=" + LouveOAuthClient.challenge(verifier));
        // An abandoned reconnect leaves the member connected.
        assertThat(row.getTokenState()).isEqualTo("enc:old-tokens");
        assertThat(service.getStatus(MEMBER).connected()).isTrue();
        verify(adapter, never()).revoke(anyString());
    }

    @Test
    void aFirstStartCreatesAPendingRowThatIsNotConnected() {
        service.startConnection(MEMBER, REDIRECT);

        assertThat(row.getStatus()).isEqualTo(LouveConnection.STATUS_PENDING);
        assertThat(service.getStatus(MEMBER).connected()).isFalse();
        assertThat(service.resyncReporting(MEMBER).status()).isEqualTo(SourceSyncResult.Status.SKIPPED_NOT_CONNECTED);
    }

    @Test
    void aValidCallbackStoresTheTokensSpendsTheAttemptAndRunsTheFirstSync() {
        LouveSyncService.StartResponse start = service.startConnection(MEMBER, REDIRECT);
        String verifier = row.getOauthVerifier().substring(4);
        when(adapter.exchangeCode("client-1", REDIRECT, "code-1", verifier)).thenReturn("tokens");
        when(adapter.read("tokens")).thenReturn(PORTFOLIO);

        LouveSyncService.ConnectionStatusResponse status =
            service.completeConnection(MEMBER, "code-1", stateOf(start), LouveOAuthClient.ISSUER);

        assertThat(status.connected()).isTrue();
        assertThat(status.status()).isEqualTo(LouveConnection.STATUS_CONNECTED);
        assertThat(row.getTokenState()).isEqualTo("enc:tokens");
        assertThat(row.getOauthState()).isNull();
        assertThat(row.getOauthVerifier()).isNull();
        verify(reconciler).reconcile(eq(MEMBER), eq(ScpiProvider.LOUVE), anyList(), anyList(), any());
    }

    @Test
    void aNewConsentRevokesTheGrantItReplaces() {
        connected("old-tokens", LouveConnection.STATUS_REAUTH_REQUIRED);
        LouveSyncService.StartResponse start = service.startConnection(MEMBER, REDIRECT);
        when(adapter.exchangeCode(anyString(), anyString(), anyString(), anyString())).thenReturn("new-tokens");
        when(adapter.read("new-tokens")).thenReturn(PORTFOLIO);

        service.completeConnection(MEMBER, "c", stateOf(start), LouveOAuthClient.ISSUER);

        verify(adapter).revoke("old-tokens");
        assertThat(row.getStatus()).isEqualTo(LouveConnection.STATUS_CONNECTED);
    }

    @Test
    void aFailingFirstSyncStillLeavesTheMemberConnected() {
        LouveSyncService.StartResponse start = service.startConnection(MEMBER, REDIRECT);
        when(adapter.exchangeCode(anyString(), anyString(), anyString(), anyString())).thenReturn("tokens");
        when(adapter.read("tokens")).thenThrow(new SyncException("down", null, SidecarErrorCode.UPSTREAM_UNAVAILABLE.name()));

        LouveSyncService.ConnectionStatusResponse status =
            service.completeConnection(MEMBER, "c", stateOf(start), LouveOAuthClient.ISSUER);

        assertThat(status.connected()).isTrue();
        assertThat(status.status()).isEqualTo(LouveConnection.STATUS_ERROR);
    }

    @Test
    void aCallbackFromAnotherIssuerIsRefusedBeforeAnything() {
        LouveSyncService.StartResponse start = service.startConnection(MEMBER, REDIRECT);

        assertCode(() -> service.completeConnection(MEMBER, "c", stateOf(start), "https://evil.example"),
            SidecarErrorCode.AUTH_ATTEMPT_EXPIRED);
        assertCode(() -> service.completeConnection(MEMBER, "c", stateOf(start), null),
            SidecarErrorCode.AUTH_ATTEMPT_EXPIRED);
        verify(adapter, never()).exchangeCode(anyString(), anyString(), anyString(), anyString());
        assertThat(row.getOauthState()).isEqualTo(stateOf(start));
    }

    @Test
    void aWrongStateIsRefused() {
        service.startConnection(MEMBER, REDIRECT);

        assertCode(() -> service.completeConnection(MEMBER, "c", "forged", LouveOAuthClient.ISSUER),
            SidecarErrorCode.AUTH_ATTEMPT_EXPIRED);
        verify(adapter, never()).exchangeCode(anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void anAttemptOlderThanItsTtlIsRefused() {
        LouveSyncService.StartResponse start = service.startConnection(MEMBER, REDIRECT);
        clock.advance(Duration.ofMinutes(LouveSyncService.ATTEMPT_TTL_MINUTES + 1));

        assertCode(() -> service.completeConnection(MEMBER, "c", stateOf(start), LouveOAuthClient.ISSUER),
            SidecarErrorCode.AUTH_ATTEMPT_EXPIRED);
    }

    @Test
    void anAttemptIsSpentBeforeTheExchangeSoAReplayFindsNothing() {
        LouveSyncService.StartResponse start = service.startConnection(MEMBER, REDIRECT);
        when(adapter.exchangeCode(anyString(), anyString(), anyString(), anyString()))
            .thenThrow(new SyncException("refused", null, SidecarErrorCode.AUTH_ATTEMPT_EXPIRED.name()));

        assertCode(() -> service.completeConnection(MEMBER, "c", stateOf(start), LouveOAuthClient.ISSUER),
            SidecarErrorCode.AUTH_ATTEMPT_EXPIRED);
        assertCode(() -> service.completeConnection(MEMBER, "c", stateOf(start), LouveOAuthClient.ISSUER),
            SidecarErrorCode.AUTH_ATTEMPT_EXPIRED);
        verify(adapter, times(1)).exchangeCode(anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void anUnknownClientOnTheExchangeDropsThatRegistration() {
        LouveSyncService.StartResponse start = service.startConnection(MEMBER, REDIRECT);
        when(adapter.exchangeCode(anyString(), anyString(), anyString(), anyString()))
            .thenThrow(new LouveOAuthClient.InvalidClientException("refused", SidecarErrorCode.AUTH_ATTEMPT_EXPIRED,
                "client-1", REDIRECT));

        assertThatThrownBy(() -> service.completeConnection(MEMBER, "c", stateOf(start), LouveOAuthClient.ISSUER))
            .isInstanceOf(LouveOAuthClient.InvalidClientException.class);
        verify(clients).forget(REDIRECT, "client-1");
    }

    @Test
    void aFailingForgetNeverHidesARefusedClientOnTheExchange() {
        LouveSyncService.StartResponse start = service.startConnection(MEMBER, REDIRECT);
        when(adapter.exchangeCode(anyString(), anyString(), anyString(), anyString()))
            .thenThrow(new LouveOAuthClient.InvalidClientException("refused", SidecarErrorCode.AUTH_ATTEMPT_EXPIRED,
                "client-1", REDIRECT));
        doAnswer(inv -> {
            throw new jakarta.persistence.OptimisticLockException("concurrent forget");
        }).when(clients).forget(anyString(), anyString());

        assertThatThrownBy(() -> service.completeConnection(MEMBER, "c", stateOf(start), LouveOAuthClient.ISSUER))
            .isInstanceOf(LouveOAuthClient.InvalidClientException.class);
    }

    @Test
    void aConnectionRemovedDuringTheExchangeKeepsNothingAndRevokesTheGrant() {
        LouveSyncService.StartResponse start = service.startConnection(MEMBER, REDIRECT);
        when(adapter.exchangeCode(anyString(), anyString(), anyString(), anyString())).thenAnswer(inv -> {
            row = null; // the member disconnected meanwhile
            return "tokens";
        });

        assertCode(() -> service.completeConnection(MEMBER, "c", stateOf(start), LouveOAuthClient.ISSUER),
            SidecarErrorCode.AUTH_ATTEMPT_EXPIRED);
        verify(adapter).revoke("tokens");
        assertThat(row).isNull();
    }

    // -- sync ------------------------------------------------------------------------------

    @Test
    void aFreshTokenReadsWithoutRefreshingAndWritesThroughTheReconciler() {
        connected("tokens", LouveConnection.STATUS_CONNECTED);
        when(adapter.read("tokens")).thenReturn(PORTFOLIO);

        LouveSyncService.ConnectionStatusResponse status = service.sync(MEMBER);

        verify(adapter, never()).refresh(anyString());
        verify(reconciler).reconcile(eq(MEMBER), eq(ScpiProvider.LOUVE), anyList(), anyList(), eq(row.getLastSyncedAt()));
        assertThat(status.status()).isEqualTo(LouveConnection.STATUS_CONNECTED);
        assertThat(status.lastSyncedAt()).isEqualTo(clock.instant());
        verify(members, atLeastOnce()).findByIdForUpdate(MEMBER);
    }

    @Test
    void anExpiringTokenIsRefreshedUnderTheRowLockAndStoredBeforeItIsUsed() {
        connected("tokens", LouveConnection.STATUS_CONNECTED);
        when(adapter.needsRefresh("tokens")).thenReturn(true);
        when(adapter.refresh("tokens")).thenReturn("rotated");
        List<String> storedWhenRead = new ArrayList<>();
        when(adapter.read("rotated")).thenAnswer(inv -> {
            storedWhenRead.add(row.getTokenState());
            return PORTFOLIO;
        });

        service.sync(MEMBER);

        assertThat(storedWhenRead).containsExactly("enc:rotated");
        verify(connections, atLeastOnce()).findByMemberIdForUpdate(MEMBER);
        verify(adapter, never()).read("tokens");
    }

    @Test
    void aRefusedAccessTokenIsRefreshedOnceThenReadAgain() {
        connected("tokens", LouveConnection.STATUS_CONNECTED);
        when(adapter.read("tokens")).thenThrow(new LouveMcpClient.Unauthorized());
        when(adapter.refresh("tokens")).thenReturn("rotated");
        when(adapter.read("rotated")).thenReturn(PORTFOLIO);

        service.sync(MEMBER);

        verify(adapter, times(1)).refresh("tokens");
        assertThat(row.getTokenState()).isEqualTo("enc:rotated");
    }

    @Test
    void aTokenRefusedRightAfterARefreshAsksForANewConsent() {
        connected("tokens", LouveConnection.STATUS_CONNECTED);
        when(adapter.read("tokens")).thenThrow(new LouveMcpClient.Unauthorized());
        when(adapter.refresh("tokens")).thenReturn("rotated");
        when(adapter.read("rotated")).thenThrow(new LouveMcpClient.Unauthorized());

        assertCode(() -> service.sync(MEMBER), SidecarErrorCode.SESSION_EXPIRED);

        assertThat(row.getStatus()).isEqualTo(LouveConnection.STATUS_REAUTH_REQUIRED);
        assertThat(row.getTokenState()).isEqualTo("enc:rotated");
        verify(adapter, times(1)).refresh(anyString());
    }

    @Test
    void aTokenSetRotatedByAnotherRunMeanwhileIsUsedAsItIsNotRefreshedAgain() {
        connected("rotated-by-the-other-run", LouveConnection.STATUS_CONNECTED);

        assertThat(service.freshState(new LouveSyncService.Run(MEMBER, 1L, 0), "tokens-this-run-used"))
            .isEqualTo("rotated-by-the-other-run");

        verify(adapter, never()).refresh(anyString());
    }

    @Test
    void aClientRefusedOnRefreshIsForgottenAndAsksForANewConsent() {
        connected("tokens", LouveConnection.STATUS_CONNECTED);
        when(adapter.needsRefresh("tokens")).thenReturn(true);
        when(adapter.refresh("tokens")).thenThrow(new LouveOAuthClient.InvalidClientException(
            "refused", SidecarErrorCode.SESSION_EXPIRED, "client-old", REDIRECT));

        assertCode(() -> service.sync(MEMBER), SidecarErrorCode.SESSION_EXPIRED);

        verify(clients).forget(REDIRECT, "client-old");
        assertThat(row.getStatus()).isEqualTo(LouveConnection.STATUS_REAUTH_REQUIRED);
    }

    @Test
    void aFailingForgetNeverHidesTheRefusedClientOnRefresh() {
        connected("tokens", LouveConnection.STATUS_CONNECTED);
        when(adapter.needsRefresh("tokens")).thenReturn(true);
        when(adapter.refresh("tokens")).thenThrow(new LouveOAuthClient.InvalidClientException(
            "refused", SidecarErrorCode.SESSION_EXPIRED, "client-old", REDIRECT));
        doAnswer(inv -> {
            throw new jakarta.persistence.OptimisticLockException("concurrent forget");
        }).when(clients).forget(anyString(), anyString());

        assertCode(() -> service.sync(MEMBER), SidecarErrorCode.SESSION_EXPIRED);

        assertThat(row.getStatus()).isEqualTo(LouveConnection.STATUS_REAUTH_REQUIRED);
    }

    @Test
    void anUnreadableStoredStateAsksForANewConsent() {
        row = LouveConnection.builder().id(1L).member(FamilyMember.builder().id(MEMBER).build())
            .tokenState("garbage").status(LouveConnection.STATUS_CONNECTED).build();

        assertCode(() -> service.sync(MEMBER), SidecarErrorCode.INVALID_SESSION_STATE);

        assertThat(row.getStatus()).isEqualTo(LouveConnection.STATUS_REAUTH_REQUIRED);
    }

    @Test
    void anIncompletePortfolioWritesNothingAndKeepsTheConnection() {
        connected("tokens", LouveConnection.STATUS_CONNECTED);
        when(adapter.read("tokens")).thenReturn(new LouveAdapter.Snapshot(new BigDecimal("5000"),
            PORTFOLIO.positions(), Map.of()));

        assertCode(() -> service.sync(MEMBER), SidecarErrorCode.PORTFOLIO_INCOMPLETE);

        assertThat(row.getStatus()).isEqualTo(LouveConnection.STATUS_ERROR);
        assertThat(row.getTokenState()).isEqualTo("enc:tokens");
        verify(reconciler, never()).reconcile(any(), any(), anyList(), anyList(), any());
    }

    @Test
    void anUnexpectedFailureIsAnInternalErrorWithoutItsMessage() {
        connected("tokens", LouveConnection.STATUS_CONNECTED);
        when(adapter.read("tokens")).thenThrow(new IllegalStateException("payload fragment"));

        assertThatThrownBy(() -> service.sync(MEMBER))
            .isInstanceOfSatisfying(SyncException.class, ex -> {
                assertThat(ex.getCode()).isEqualTo(SidecarErrorCode.INTERNAL_ERROR.name());
                assertThat(ex.getMessage()).doesNotContain("payload");
            });
        assertThat(row.getStatus()).isEqualTo(LouveConnection.STATUS_ERROR);
    }

    @Test
    void aConnectionRemovedDuringTheReadWritesNothing() {
        connected("tokens", LouveConnection.STATUS_CONNECTED);
        when(adapter.read("tokens")).thenAnswer(inv -> {
            row = null;
            return PORTFOLIO;
        });

        assertThatThrownBy(() -> service.sync(MEMBER)).isInstanceOf(LouveSyncService.Transient.class);

        verify(reconciler, never()).reconcile(any(), any(), anyList(), anyList(), any());
    }

    @Test
    void aSyncOnTheOldGrantNeverCommitsOverANewConsent() {
        connected("tokens-a", LouveConnection.STATUS_CONNECTED);
        when(adapter.read("tokens-a")).thenAnswer(inv -> {
            // A reconnect completes while this run reads with the old grant.
            row.setTokenState("enc:tokens-b");
            row.setGrantGeneration(row.getGrantGeneration() + 1);
            row.setLastSyncedAt(Instant.parse("2026-11-17T07:59:00Z"));
            return PORTFOLIO;
        });

        assertThatThrownBy(() -> service.sync(MEMBER))
            .isInstanceOfSatisfying(LouveSyncService.Transient.class,
                ex -> assertThat(ex.getCode()).isEqualTo(SidecarErrorCode.UPSTREAM_UNAVAILABLE.name()));

        verify(reconciler, never()).reconcile(any(), any(), anyList(), anyList(), any());
        assertThat(row.getTokenState()).isEqualTo("enc:tokens-b");
        assertThat(row.getStatus()).isEqualTo(LouveConnection.STATUS_CONNECTED);
        assertThat(row.getLastSyncedAt()).isEqualTo(Instant.parse("2026-11-17T07:59:00Z"));
    }

    @Test
    void aFailureOnTheOldGrantNeverMarksTheNewConnection() {
        connected("tokens-a", LouveConnection.STATUS_CONNECTED);
        when(adapter.read("tokens-a")).thenAnswer(inv -> {
            row.setGrantGeneration(row.getGrantGeneration() + 1);
            throw new LouveMcpClient.Unauthorized();
        });

        assertThatThrownBy(() -> service.sync(MEMBER)).isInstanceOf(LouveSyncService.Transient.class);

        assertThat(row.getStatus()).isEqualTo(LouveConnection.STATUS_CONNECTED);
        verify(adapter, never()).refresh(anyString());
    }

    @Test
    void aRotationWithinTheSameGrantIsNotANewGeneration() {
        connected("tokens", LouveConnection.STATUS_CONNECTED);
        when(adapter.needsRefresh("tokens")).thenReturn(true);
        when(adapter.refresh("tokens")).thenReturn("rotated");
        when(adapter.read("rotated")).thenReturn(PORTFOLIO);

        service.sync(MEMBER);

        assertThat(row.getGrantGeneration()).isZero();
        verify(reconciler).reconcile(eq(MEMBER), eq(ScpiProvider.LOUVE), anyList(), anyList(), any());
    }

    @Test
    void aStaleCallbackNeverOverwritesOrRevokesANewerConnection() {
        LouveSyncService.StartResponse first = service.startConnection(MEMBER, REDIRECT);
        when(adapter.exchangeCode(anyString(), anyString(), eq("code-a"), anyString())).thenAnswer(inv -> {
            // Meanwhile a second attempt starts and completes.
            LouveSyncService.StartResponse second = service.startConnection(MEMBER, REDIRECT);
            service.completeConnection(MEMBER, "code-b", stateOf(second), LouveOAuthClient.ISSUER);
            return "tokens-a";
        });
        when(adapter.exchangeCode(anyString(), anyString(), eq("code-b"), anyString())).thenReturn("tokens-b");
        when(adapter.read("tokens-b")).thenReturn(PORTFOLIO);

        assertCode(() -> service.completeConnection(MEMBER, "code-a", stateOf(first), LouveOAuthClient.ISSUER),
            SidecarErrorCode.AUTH_ATTEMPT_EXPIRED);

        assertThat(row.getTokenState()).isEqualTo("enc:tokens-b");
        assertThat(row.getGrantGeneration()).isEqualTo(1);
        verify(adapter).revoke("tokens-a");
        verify(adapter, never()).revoke("tokens-b");
    }

    @Test
    void aBusyRowLockIsATemporaryContentionThatLeavesTheConnectionAlone() {
        connected("tokens", LouveConnection.STATUS_CONNECTED);
        when(connections.findByMemberIdForUpdate(MEMBER))
            .thenThrow(new org.springframework.dao.CannotAcquireLockException("lock timeout"));

        assertThatThrownBy(() -> service.sync(MEMBER))
            .isInstanceOfSatisfying(LouveSyncService.Transient.class,
                ex -> assertThat(ex.getCode()).isEqualTo(SidecarErrorCode.UPSTREAM_UNAVAILABLE.name()));

        assertThat(row.getStatus()).isEqualTo(LouveConnection.STATUS_CONNECTED);
        assertThat(row.getTokenState()).isEqualTo("enc:tokens");
        verify(adapter, never()).read(anyString());
        assertThat(service.resyncReporting(MEMBER).status()).isEqualTo(SourceSyncResult.Status.FAILED);
    }

    // -- daily sync, disconnect --------------------------------------------------------------

    @Test
    void theDailySyncSkipsAMemberWithoutTokensAndNeverRetriesARefusedGrant() {
        assertThat(service.resyncReporting(MEMBER).status()).isEqualTo(SourceSyncResult.Status.SKIPPED_NOT_CONNECTED);

        connected("tokens", LouveConnection.STATUS_REAUTH_REQUIRED);
        SourceSyncResult result = service.resyncReporting(MEMBER);

        assertThat(result.status()).isEqualTo(SourceSyncResult.Status.NEEDS_REAUTH);
        verify(adapter, never()).read(anyString());
    }

    @Test
    void theDailySyncReportsASyncAndClassifiesAFailure() {
        connected("tokens", LouveConnection.STATUS_CONNECTED);
        when(adapter.read("tokens")).thenReturn(PORTFOLIO);
        assertThat(service.resyncReporting(MEMBER).status()).isEqualTo(SourceSyncResult.Status.SYNCED);

        org.mockito.Mockito.doThrow(new SyncException("down", null, SidecarErrorCode.UPSTREAM_UNAVAILABLE.name()))
            .when(adapter).read("tokens");
        assertThat(service.resyncReporting(MEMBER).status()).isEqualTo(SourceSyncResult.Status.FAILED);

        org.mockito.Mockito.doThrow(new LouveMcpClient.Unauthorized()).when(adapter).read("tokens");
        when(adapter.refresh("tokens")).thenThrow(new SyncException("revoked", null, SidecarErrorCode.SESSION_EXPIRED.name()));
        row.setStatus(LouveConnection.STATUS_CONNECTED);
        assertThat(service.resyncReporting(MEMBER).status()).isEqualTo(SourceSyncResult.Status.NEEDS_REAUTH);
    }

    @Test
    void disconnectingRevokesTheGrantWhateverItsStatus() {
        connected("tokens", LouveConnection.STATUS_REAUTH_REQUIRED);

        assertThat(service.deleteConnection(MEMBER)).isTrue();

        assertThat(row).isNull();
        verify(adapter).revoke("tokens");
        assertThat(service.deleteConnection(MEMBER)).isFalse();
    }

    @Test
    void aRowWithOnlyAPendingAttemptRevokesNothing() {
        service.startConnection(MEMBER, REDIRECT);

        assertThat(service.deleteConnection(MEMBER)).isTrue();

        verify(adapter, never()).revoke(anyString());
    }

    private static void assertCode(Runnable call, SidecarErrorCode code) {
        assertThatThrownBy(call::run).isInstanceOfSatisfying(SyncException.class,
            ex -> assertThat(ex.getCode()).isEqualTo(code.name()));
    }

    private static final class MutableClock extends Clock {
        private Instant now;

        MutableClock(Instant now) {
            this.now = now;
        }

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}

package com.picsou.service;

import com.picsou.adapter.OpenFigiIsinConverter;
import com.picsou.adapter.OpenFigiIsinConverter.TickerResult;
import com.picsou.config.CryptoEncryption;
import com.picsou.dto.AccountResponse;
import com.picsou.model.Account;
import com.picsou.model.AccountHolding;
import com.picsou.model.AccountType;
import com.picsou.model.FamilyMember;
import com.picsou.model.IbkrConnection;
import com.picsou.port.IbkrFlexPort;
import com.picsou.port.IbkrFlexPort.IbkrAccountData;
import com.picsou.port.IbkrFlexPort.IbkrPosition;
import com.picsou.repository.AccountHoldingRepository;
import com.picsou.repository.AccountRepository;
import com.picsou.repository.FamilyMemberRepository;
import com.picsou.repository.IbkrConnectionRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class IbkrSyncServiceTest {

    @Mock IbkrFlexPort ibkrFlexPort;
    @Mock IbkrConnectionRepository connectionRepository;
    @Mock AccountRepository accountRepository;
    @Mock AccountHoldingRepository holdingRepository;
    @Mock FamilyMemberRepository familyMemberRepository;
    @Mock AccountService accountService;
    @Mock OpenFigiIsinConverter isinConverter;
    @Mock SecurityIdentityService identityService;
    @Mock CryptoEncryption encryption;
    @Mock IbkrStatusWriter statusWriter;

    @InjectMocks IbkrSyncService service;

    /**
     * IBKR reports cost basis in the security's native currency. The stored
     * {@code averageBuyIn} must be converted to the account base currency (≈EUR) via
     * {@code fxRateToBase}, and per-tax-lot ("LOT") duplicate rows must be dropped so a
     * position is not double-counted.
     *
     * Scenario: 10 AAPL, costBasisPrice=150 USD, fxRateToBase=0.92, plus a LOT duplicate.
     * Expected single holding: quantity=10, averageBuyIn = 150 * 0.92 = 138.
     */
    @Test
    void sync_convertsCostBasisToBaseCurrencyAndDropsLotRows() {
        Long memberId = 7L;
        FamilyMember member = FamilyMember.builder().id(memberId).displayName("Owner").build();

        IbkrConnection connection = IbkrConnection.builder()
            .member(member)
            .token("enc-token")
            .queryId("enc-query")
            .status("CONNECTED")
            .build();
        when(connectionRepository.findByMemberId(memberId)).thenReturn(Optional.of(connection));
        when(encryption.decrypt("enc-token")).thenReturn("plain-token");
        when(encryption.decrypt("enc-query")).thenReturn("plain-query");

        IbkrPosition summary = new IbkrPosition(
            "U123", "US0378331005", "AAPL", "APPLE INC", "USD", "STK", "SUMMARY",
            bd("10"), bd("200"), bd("150"), bd("0.92"));
        IbkrPosition lotDuplicate = new IbkrPosition(
            "U123", "US0378331005", "AAPL", "APPLE INC", "USD", "STK", "LOT",
            bd("10"), bd("200"), bd("150"), bd("0.92"));
        IbkrAccountData accountData = new IbkrAccountData("U123", List.of(summary, lotDuplicate));
        when(ibkrFlexPort.fetchOpenPositions("plain-token", "plain-query"))
            .thenReturn(List.of(accountData));

        when(isinConverter.resolve("US0378331005")).thenReturn(new TickerResult("AAPL", "Apple"));

        when(accountRepository.findByExternalAccountIdAndMemberId("ibkr_U123", memberId))
            .thenReturn(Optional.empty());
        lenient().when(accountRepository.existsSoftDeletedByExternalAccountIdAndMemberId("ibkr_U123", memberId))
            .thenReturn(false);
        when(familyMemberRepository.findById(memberId)).thenReturn(Optional.of(member));
        when(accountRepository.save(any(Account.class))).thenAnswer(inv -> {
            Account a = inv.getArgument(0);
            a.setId(1L);
            return a;
        });
        when(accountService.liveBalanceEur(any(Account.class))).thenReturn(bd("1380"));
        AccountResponse dummy = AccountResponse.from(
            Account.builder().id(1L).name("IBKR U123").type(AccountType.COMPTE_TITRES).build(),
            BigDecimal.ZERO);
        when(accountService.toResponse(any(Account.class))).thenReturn(dummy);

        List<AccountResponse> result = service.sync(memberId);

        assertThat(result).hasSize(1);

        // The LOT row must be filtered → exactly one holding saved.
        ArgumentCaptor<AccountHolding> holdingCaptor = ArgumentCaptor.forClass(AccountHolding.class);
        verify(holdingRepository, times(1)).save(holdingCaptor.capture());
        AccountHolding saved = holdingCaptor.getValue();

        assertThat(saved.getTicker()).isEqualTo("AAPL");
        assertThat(saved.getQuantity()).isEqualByComparingTo("10");
        // 150 USD × 0.92 = 138 EUR — the core currency-conversion contract.
        assertThat(saved.getAverageBuyIn()).isEqualByComparingTo("138");

        // Connection is marked freshly synced.
        assertThat(connection.getStatus()).isEqualTo("CONNECTED");
        assertThat(connection.getLastSyncedAt()).isNotNull();
        // Happy path never touches the error-status writer.
        verifyNoInteractions(statusWriter);
    }

    /**
     * Positive connect() path: credentials are trimmed, encrypted before storage, and the
     * connection lands with status CONNECTED — nothing plaintext ever reaches the repository.
     */
    @Test
    void connect_encryptsTrimmedCredentials_andStoresConnectedConnection() {
        Long memberId = 7L;
        FamilyMember member = FamilyMember.builder().id(memberId).displayName("Owner").build();
        when(familyMemberRepository.findById(memberId)).thenReturn(Optional.of(member));
        when(connectionRepository.findByMemberId(memberId)).thenReturn(Optional.empty());
        when(encryption.encrypt("flex-token")).thenReturn("enc-token");
        when(encryption.encrypt("1234567")).thenReturn("enc-query");

        service.connect("  flex-token  ", " 1234567 ", memberId);

        ArgumentCaptor<IbkrConnection> captor = ArgumentCaptor.forClass(IbkrConnection.class);
        verify(connectionRepository).save(captor.capture());
        IbkrConnection saved = captor.getValue();
        assertThat(saved.getToken()).isEqualTo("enc-token");
        assertThat(saved.getQueryId()).isEqualTo("enc-query");
        assertThat(saved.getStatus()).isEqualTo("CONNECTED");
        assertThat(saved.getMember()).isSameAs(member);
    }

    @Test
    void sync_withoutConnection_throws() {
        when(connectionRepository.findByMemberId(99L)).thenReturn(Optional.empty());

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.sync(99L))
            .isInstanceOf(com.picsou.exception.SyncException.class)
            .hasMessageContaining("No Interactive Brokers connection");
    }

    /**
     * When the Flex port throws (network error, expired token, ...), the manual sync
     * path must still end up with the connection's status persisted as ERROR. The
     * naive fix (save inside the @Transactional method, then rethrow) does not survive
     * on the manual path: the rethrow marks the transaction rollback-only and the save
     * is lost. {@link IbkrStatusWriter} runs in its own REQUIRES_NEW transaction instead
     * — this test pins that it is invoked with the right connection id, and that the
     * original exception still propagates to the caller (the controller surfaces it).
     */
    @Test
    void sync_whenPortThrows_marksErrorViaStatusWriterAndPropagates() {
        Long memberId = 7L;
        FamilyMember member = FamilyMember.builder().id(memberId).displayName("Owner").build();
        IbkrConnection connection = IbkrConnection.builder()
            .id(42L)
            .member(member).token("enc-token").queryId("enc-query").status("CONNECTED").build();
        when(connectionRepository.findByMemberId(memberId)).thenReturn(Optional.of(connection));
        when(encryption.decrypt("enc-token")).thenReturn("plain-token");
        when(encryption.decrypt("enc-query")).thenReturn("plain-query");

        RuntimeException boom = new RuntimeException("IBKR: token expired");
        when(ibkrFlexPort.fetchOpenPositions("plain-token", "plain-query")).thenThrow(boom);

        assertThatThrownBy(() -> service.sync(memberId)).isSameAs(boom);

        verify(statusWriter, times(1)).markError(42L);
        // The old (broken) approach saved through connectionRepository directly inside
        // the failing transaction; that must no longer happen on this path.
        verify(connectionRepository, never()).save(any(IbkrConnection.class));
    }

    /**
     * A credential that no longer decrypts (rotated CRYPTO_ENCRYPTION_KEY, corrupted row)
     * must not bubble up as a raw crypto RuntimeException: the user gets an actionable
     * SyncException telling them to reconnect, and the connection status flips to ERROR
     * exactly like any other sync failure.
     */
    @Test
    void sync_whenDecryptFails_marksErrorAndThrowsActionableSyncException() {
        Long memberId = 7L;
        FamilyMember member = FamilyMember.builder().id(memberId).displayName("Owner").build();
        IbkrConnection connection = IbkrConnection.builder()
            .id(42L)
            .member(member).token("enc-token").queryId("enc-query").status("CONNECTED").build();
        when(connectionRepository.findByMemberId(memberId)).thenReturn(Optional.of(connection));
        when(encryption.decrypt("enc-token")).thenThrow(new IllegalStateException("bad key"));

        assertThatThrownBy(() -> service.sync(memberId))
            .isInstanceOf(com.picsou.exception.SyncException.class)
            .hasMessageContaining("decrypt")
            .hasMessageContaining("reconnect");

        verify(statusWriter, times(1)).markError(42L);
        verifyNoInteractions(ibkrFlexPort);
    }

    /**
     * The read-only status endpoint must never 500 because a stored token stopped
     * decrypting: it degrades to connected=true with an ERROR status and a fully
     * masked token, so the Sync page can tell the user to reconnect.
     */
    @Test
    void getConnectionStatus_whenDecryptFails_reportsErrorInsteadOfThrowing() {
        Long memberId = 7L;
        FamilyMember member = FamilyMember.builder().id(memberId).displayName("Owner").build();
        IbkrConnection connection = IbkrConnection.builder()
            .id(42L)
            .member(member).token("enc-token").queryId("enc-query").status("CONNECTED").build();
        when(connectionRepository.findByMemberId(memberId)).thenReturn(Optional.of(connection));
        when(encryption.decrypt("enc-token")).thenThrow(new IllegalStateException("bad key"));

        var status = service.getConnectionStatus(memberId);

        assertThat(status.connected()).isTrue();
        assertThat(status.status()).isEqualTo("ERROR");
        assertThat(status.maskedToken()).doesNotContain("enc-token");
    }

    /**
     * Hardening against real-world Flex variety in one account: an ISIN that OpenFIGI
     * resolves, an ISIN that OpenFIGI misses (→ symbol fallback), a symbol-only line, a
     * cash line, a zero-quantity line, and an over-long derivative symbol. Only the three
     * price-able equities become holdings; cash / zero-qty / over-long are dropped, and a
     * null fxRateToBase on a NON-EUR position leaves the cost basis unknown (null) —
     * treating the missing rate as 1 would store a USD price as if it were EUR.
     */
    @Test
    void sync_handlesMixedAssetClassesAndSkipsUnsupportedRows() {
        Long memberId = 7L;
        FamilyMember member = FamilyMember.builder().id(memberId).displayName("Owner").build();
        IbkrConnection connection = IbkrConnection.builder()
            .member(member).token("enc-token").queryId("enc-query").status("CONNECTED").build();
        when(connectionRepository.findByMemberId(memberId)).thenReturn(Optional.of(connection));
        when(encryption.decrypt("enc-token")).thenReturn("plain-token");
        when(encryption.decrypt("enc-query")).thenReturn("plain-query");

        String longSymbol = "AAAAAAAAAABBBBBBBBBBCCCCCCCCCCDDDDD"; // 35 chars > 30
        IbkrPosition hit     = pos("US0378331005", "AAPL", "STK", bd("10"), bd("150"), bd("0.90"));
        IbkrPosition miss    = pos("US88160R1014", "TSLA", "STK", bd("3"),  bd("200"), bd("1"));
        IbkrPosition noIsin  = pos(null,           "PLTR", "STK", bd("8"),  bd("40"),  null);
        IbkrPosition cash    = pos(null,           "USD.CASH", "CASH", bd("500"), bd("1"), bd("1"));
        IbkrPosition zeroQty = pos("US0000000000", "ZRO", "STK", bd("0"),  bd("5"),   bd("1"));
        IbkrPosition longSym = pos(null,           longSymbol, "OPT", bd("1"), bd("2"), bd("1"));
        when(ibkrFlexPort.fetchOpenPositions("plain-token", "plain-query"))
            .thenReturn(List.of(new IbkrAccountData("U1", List.of(hit, miss, noIsin, cash, zeroQty, longSym))));

        when(isinConverter.resolve("US0378331005")).thenReturn(new TickerResult("AAPL", "Apple"));
        // OpenFIGI miss: returns the ISIN unchanged → mapping must fall back to the symbol.
        when(isinConverter.resolve("US88160R1014")).thenReturn(new TickerResult("US88160R1014", null));

        when(accountRepository.findByExternalAccountIdAndMemberId("ibkr_U1", memberId)).thenReturn(Optional.empty());
        lenient().when(accountRepository.existsSoftDeletedByExternalAccountIdAndMemberId("ibkr_U1", memberId)).thenReturn(false);
        when(familyMemberRepository.findById(memberId)).thenReturn(Optional.of(member));
        when(accountRepository.save(any(Account.class))).thenAnswer(inv -> { Account a = inv.getArgument(0); a.setId(1L); return a; });
        when(accountService.liveBalanceEur(any(Account.class))).thenReturn(bd("0"));
        when(accountService.toResponse(any(Account.class))).thenReturn(
            AccountResponse.from(Account.builder().id(1L).name("IBKR U1").type(AccountType.COMPTE_TITRES).build(), BigDecimal.ZERO));

        service.sync(memberId);

        ArgumentCaptor<AccountHolding> captor = ArgumentCaptor.forClass(AccountHolding.class);
        verify(holdingRepository, times(3)).save(captor.capture());
        var byTicker = captor.getAllValues().stream()
            .collect(java.util.stream.Collectors.toMap(AccountHolding::getTicker, h -> h));

        // Cash, zero-qty and the over-long option symbol are all dropped.
        assertThat(byTicker.keySet()).containsExactlyInAnyOrder("AAPL", "TSLA", "PLTR");
        // ISIN hit: 150 × 0.90 = 135 EUR cost basis.
        assertThat(byTicker.get("AAPL").getAverageBuyIn()).isEqualByComparingTo("135");
        // No fxRateToBase on a USD position → cost basis is unknown, not "40 EUR".
        assertThat(byTicker.get("PLTR").getAverageBuyIn()).isNull();
    }

    /**
     * Derivatives allowlist: an OPT position must never become a holding, even with a
     * short OCC symbol ("SPY 240119C00470000" is 19 chars, well under the 30-char
     * length guard that used to be the only defense) — same for FUT. A position with no
     * assetCategory attribute at all is kept (conservative default for statements that
     * omit the field, unchanged from today's behavior).
     */
    @Test
    void sync_skipsDerivativeAssetCategoriesButKeepsAbsentCategory() {
        Long memberId = 7L;
        FamilyMember member = FamilyMember.builder().id(memberId).displayName("Owner").build();
        IbkrConnection connection = IbkrConnection.builder()
            .member(member).token("enc-token").queryId("enc-query").status("CONNECTED").build();
        when(connectionRepository.findByMemberId(memberId)).thenReturn(Optional.of(connection));
        when(encryption.decrypt("enc-token")).thenReturn("plain-token");
        when(encryption.decrypt("enc-query")).thenReturn("plain-query");

        IbkrPosition option = pos(null, "SPY 240119C00470000", "OPT", bd("1"), bd("5"), bd("1"));
        IbkrPosition future = pos(null, "ES FUT", "FUT", bd("1"), bd("100"), bd("1"));
        IbkrPosition absentCategory = pos(null, "PLTR", null, bd("8"), bd("40"), bd("1"));
        when(ibkrFlexPort.fetchOpenPositions("plain-token", "plain-query"))
            .thenReturn(List.of(new IbkrAccountData("U1", List.of(option, future, absentCategory))));

        when(accountRepository.findByExternalAccountIdAndMemberId("ibkr_U1", memberId)).thenReturn(Optional.empty());
        lenient().when(accountRepository.existsSoftDeletedByExternalAccountIdAndMemberId("ibkr_U1", memberId)).thenReturn(false);
        when(familyMemberRepository.findById(memberId)).thenReturn(Optional.of(member));
        when(accountRepository.save(any(Account.class))).thenAnswer(inv -> { Account a = inv.getArgument(0); a.setId(1L); return a; });
        when(accountService.liveBalanceEur(any(Account.class))).thenReturn(bd("0"));
        when(accountService.toResponse(any(Account.class))).thenReturn(
            AccountResponse.from(Account.builder().id(1L).name("IBKR U1").type(AccountType.COMPTE_TITRES).build(), BigDecimal.ZERO));

        service.sync(memberId);

        // Only the absent-category row becomes a holding; OPT and FUT are skipped
        // (asserted via the no-holding outcome, not by capturing the WARN log).
        ArgumentCaptor<AccountHolding> captor = ArgumentCaptor.forClass(AccountHolding.class);
        verify(holdingRepository, times(1)).save(captor.capture());
        assertThat(captor.getValue().getTicker()).isEqualTo("PLTR");
    }

    /**
     * Two positions resolving to the same ticker with opposite signs that net to
     * exactly zero (e.g. a short fully covered by an equal long lot) must not persist a
     * stale holding: {@code HoldingDedup.vwapMerge} returns a zero-quantity sentinel,
     * and the post-merge persist loop must skip it rather than saving a phantom
     * zero-quantity row.
     */
    @Test
    void sync_positionsNettingToZero_savesNoHolding() {
        Long memberId = 7L;
        FamilyMember member = FamilyMember.builder().id(memberId).displayName("Owner").build();
        IbkrConnection connection = IbkrConnection.builder()
            .member(member).token("enc-token").queryId("enc-query").status("CONNECTED").build();
        when(connectionRepository.findByMemberId(memberId)).thenReturn(Optional.of(connection));
        when(encryption.decrypt("enc-token")).thenReturn("plain-token");
        when(encryption.decrypt("enc-query")).thenReturn("plain-query");

        IbkrPosition longLeg = pos("US0378331005", "AAPL", "STK", bd("40"), bd("100"), bd("1"));
        IbkrPosition shortLeg = pos("US0378331005", "AAPL", "STK", bd("-40"), bd("120"), bd("1"));
        when(ibkrFlexPort.fetchOpenPositions("plain-token", "plain-query"))
            .thenReturn(List.of(new IbkrAccountData("U1", List.of(longLeg, shortLeg))));

        when(isinConverter.resolve("US0378331005")).thenReturn(new TickerResult("AAPL", "Apple"));

        when(accountRepository.findByExternalAccountIdAndMemberId("ibkr_U1", memberId)).thenReturn(Optional.empty());
        lenient().when(accountRepository.existsSoftDeletedByExternalAccountIdAndMemberId("ibkr_U1", memberId)).thenReturn(false);
        when(familyMemberRepository.findById(memberId)).thenReturn(Optional.of(member));
        when(accountRepository.save(any(Account.class))).thenAnswer(inv -> { Account a = inv.getArgument(0); a.setId(1L); return a; });
        // liveBalanceEur is deliberately NOT stubbed: with zero persisted holdings the
        // sync now sets the balance to 0 directly (the live path's empty-holdings
        // fallback would return the stale stored balance).
        when(accountService.toResponse(any(Account.class))).thenReturn(
            AccountResponse.from(Account.builder().id(1L).name("IBKR U1").type(AccountType.COMPTE_TITRES).build(), BigDecimal.ZERO));

        service.sync(memberId);

        verify(holdingRepository, never()).save(any(AccountHolding.class));
        verify(accountService, never()).liveBalanceEur(any(Account.class));
    }

    /**
     * B: a non-EUR IBKR account base currency must fail that account's sync rather than
     * silently persist a wrong-currency cost basis (averageBuyIn is converted via
     * fxRateToBase into the account's base currency, not necessarily EUR). No holdings
     * and no account row are persisted for it.
     */
    @Test
    void sync_nonEurBaseCurrency_throwsAndPersistsNoHoldings() {
        Long memberId = 7L;
        FamilyMember member = FamilyMember.builder().id(memberId).displayName("Owner").build();
        IbkrConnection connection = IbkrConnection.builder()
            .member(member).token("enc-token").queryId("enc-query").status("CONNECTED").build();
        when(connectionRepository.findByMemberId(memberId)).thenReturn(Optional.of(connection));
        when(encryption.decrypt("enc-token")).thenReturn("plain-token");
        when(encryption.decrypt("enc-query")).thenReturn("plain-query");

        IbkrPosition aapl = pos("US0378331005", "AAPL", "STK", bd("10"), bd("150"), bd("1"));
        IbkrAccountData accountData = new IbkrAccountData("U1", List.of(aapl), "USD");
        when(ibkrFlexPort.fetchOpenPositions("plain-token", "plain-query")).thenReturn(List.of(accountData));

        assertThatThrownBy(() -> service.sync(memberId))
            .isInstanceOf(com.picsou.exception.SyncException.class)
            .hasMessageContaining("IBKR account base currency is USD")
            .hasMessageContaining("EUR-based accounts");

        verify(holdingRepository, never()).save(any(AccountHolding.class));
        verify(accountRepository, never()).save(any(Account.class));
        // The guard failure is a sync failure like any other: the user must see ERROR
        // in the UI, on the scheduled path too (where the exception is swallowed).
        verify(statusWriter, times(1)).markError(any());
    }

    /**
     * A fully liquidated IBKR account — statement present, zero positions — must purge
     * the stale holdings AND zero the balance. Two traps pinned here: (1) the account
     * must still flow through the sync at all (the parser now emits it with an empty
     * position list), and (2) the balance must NOT go through liveBalanceEur, whose
     * empty-holdings fallback resurrects the stored (stale) currentBalance.
     */
    @Test
    void sync_emptiedAccount_purgesHoldingsAndZeroesBalance() {
        Long memberId = 7L;
        FamilyMember member = FamilyMember.builder().id(memberId).displayName("Owner").build();
        IbkrConnection connection = IbkrConnection.builder()
            .member(member).token("enc-token").queryId("enc-query").status("CONNECTED").build();
        when(connectionRepository.findByMemberId(memberId)).thenReturn(Optional.of(connection));
        when(encryption.decrypt("enc-token")).thenReturn("plain-token");
        when(encryption.decrypt("enc-query")).thenReturn("plain-query");

        IbkrAccountData emptied = new IbkrAccountData("U1", List.of(), "EUR");
        when(ibkrFlexPort.fetchOpenPositions("plain-token", "plain-query"))
            .thenReturn(List.of(emptied));

        Account existing = Account.builder()
            .id(31L).member(member).name("IBKR U1").type(AccountType.COMPTE_TITRES)
            .currency("EUR").currentBalance(bd("1234.56")).externalAccountId("ibkr_U1")
            .build();
        when(accountRepository.findByExternalAccountIdAndMemberId("ibkr_U1", memberId))
            .thenReturn(Optional.of(existing));
        when(accountRepository.save(any(Account.class))).thenAnswer(inv -> inv.getArgument(0));
        when(accountService.toResponse(any(Account.class)))
            .thenReturn(AccountResponse.from(existing, BigDecimal.ZERO));

        List<AccountResponse> result = service.sync(memberId);

        assertThat(result).hasSize(1);
        verify(holdingRepository).deleteByAccountId(31L);
        verify(holdingRepository, never()).save(any(AccountHolding.class));
        // Balance forced to zero, NOT recomputed via the live path.
        verify(accountService, never()).liveBalanceEur(any(Account.class));
        assertThat(existing.getCurrentBalance()).isEqualByComparingTo("0");
        verify(accountService).upsertSnapshot(eq(existing), eq(BigDecimal.ZERO), any(LocalDate.class));
    }

    /** B: an explicit "EUR" base currency syncs exactly like today (no behavior change). */
    @Test
    void sync_eurBaseCurrency_syncsNormally() {
        Long memberId = 7L;
        FamilyMember member = FamilyMember.builder().id(memberId).displayName("Owner").build();
        IbkrConnection connection = IbkrConnection.builder()
            .member(member).token("enc-token").queryId("enc-query").status("CONNECTED").build();
        when(connectionRepository.findByMemberId(memberId)).thenReturn(Optional.of(connection));
        when(encryption.decrypt("enc-token")).thenReturn("plain-token");
        when(encryption.decrypt("enc-query")).thenReturn("plain-query");

        IbkrPosition aapl = pos("US0378331005", "AAPL", "STK", bd("10"), bd("150"), bd("1"));
        IbkrAccountData accountData = new IbkrAccountData("U1", List.of(aapl), "EUR");
        when(ibkrFlexPort.fetchOpenPositions("plain-token", "plain-query")).thenReturn(List.of(accountData));

        when(isinConverter.resolve("US0378331005")).thenReturn(new TickerResult("AAPL", "Apple"));
        when(accountRepository.findByExternalAccountIdAndMemberId("ibkr_U1", memberId)).thenReturn(Optional.empty());
        lenient().when(accountRepository.existsSoftDeletedByExternalAccountIdAndMemberId("ibkr_U1", memberId)).thenReturn(false);
        when(familyMemberRepository.findById(memberId)).thenReturn(Optional.of(member));
        when(accountRepository.save(any(Account.class))).thenAnswer(inv -> { Account a = inv.getArgument(0); a.setId(1L); return a; });
        when(accountService.liveBalanceEur(any(Account.class))).thenReturn(bd("0"));
        when(accountService.toResponse(any(Account.class))).thenReturn(
            AccountResponse.from(Account.builder().id(1L).name("IBKR U1").type(AccountType.COMPTE_TITRES).build(), BigDecimal.ZERO));

        service.sync(memberId);

        verify(holdingRepository, times(1)).save(any(AccountHolding.class));
    }

    /**
     * A member who deleted their IBKR account must not have it silently resurrected by
     * the next daily sync. When the account is absent but a soft-deleted row exists, that
     * account is skipped entirely — no account row, no holdings — yet the sync still
     * completes normally and leaves the connection CONNECTED (a deliberate skip is not a
     * failure). Every other test stubs the soft-delete check to false, so this is the
     * only one that exercises the guard branch.
     */
    @Test
    void sync_softDeletedAccount_isSkippedButConnectionStaysConnected() {
        Long memberId = 7L;
        FamilyMember member = FamilyMember.builder().id(memberId).displayName("Owner").build();
        IbkrConnection connection = IbkrConnection.builder()
            .member(member).token("enc-token").queryId("enc-query").status("CONNECTED").build();
        when(connectionRepository.findByMemberId(memberId)).thenReturn(Optional.of(connection));
        when(encryption.decrypt("enc-token")).thenReturn("plain-token");
        when(encryption.decrypt("enc-query")).thenReturn("plain-query");

        IbkrPosition aapl = pos("US0378331005", "AAPL", "STK", bd("10"), bd("150"), bd("1"));
        when(ibkrFlexPort.fetchOpenPositions("plain-token", "plain-query"))
            .thenReturn(List.of(new IbkrAccountData("U1", List.of(aapl))));

        when(accountRepository.findByExternalAccountIdAndMemberId("ibkr_U1", memberId))
            .thenReturn(Optional.empty());
        when(accountRepository.existsSoftDeletedByExternalAccountIdAndMemberId("ibkr_U1", memberId))
            .thenReturn(true);

        List<AccountResponse> result = service.sync(memberId);

        assertThat(result).isEmpty();
        verify(accountRepository, never()).save(any(Account.class));
        verify(holdingRepository, never()).save(any(AccountHolding.class));
        // Deliberate skip, not a failure: connection is left healthy and freshly synced.
        assertThat(connection.getStatus()).isEqualTo("CONNECTED");
        assertThat(connection.getLastSyncedAt()).isNotNull();
        verifyNoInteractions(statusWriter);
    }

    // ─── resyncIfConnected (scheduler entry point) ───────────────────────────
    // NB: these Mockito tests cover the in-method swallowing only. The proxy-level
    // UnexpectedRollbackException (rollback-only marked through a repository proxy,
    // thrown at method exit) cannot exist without Spring proxies — that path is
    // guarded by the call-site wrapper in SchedulerService.

    @Test
    void resyncIfConnected_withoutConnection_isANoOp() {
        when(connectionRepository.findByMemberId(7L)).thenReturn(Optional.empty());

        service.resyncIfConnected(7L);

        verifyNoInteractions(ibkrFlexPort);
        verifyNoInteractions(statusWriter);
    }

    @Test
    void resyncIfConnected_swallowsSyncException_afterMarkingError() {
        FamilyMember member = FamilyMember.builder().id(7L).displayName("Owner").build();
        IbkrConnection connection = IbkrConnection.builder()
            .id(42L).member(member).token("enc-token").queryId("enc-query").status("CONNECTED").build();
        when(connectionRepository.findByMemberId(7L)).thenReturn(Optional.of(connection));
        when(encryption.decrypt("enc-token")).thenThrow(new IllegalStateException("bad key"));

        service.resyncIfConnected(7L);  // must not throw

        verify(statusWriter).markError(42L);
    }

    @Test
    void resyncIfConnected_swallowsUnexpectedRuntimeExceptions() {
        FamilyMember member = FamilyMember.builder().id(7L).displayName("Owner").build();
        IbkrConnection connection = IbkrConnection.builder()
            .id(42L).member(member).token("enc-token").queryId("enc-query").status("CONNECTED").build();
        when(connectionRepository.findByMemberId(7L)).thenReturn(Optional.of(connection));
        when(encryption.decrypt("enc-token")).thenReturn("plain-token");
        when(encryption.decrypt("enc-query")).thenReturn("plain-query");
        when(ibkrFlexPort.fetchOpenPositions("plain-token", "plain-query"))
            .thenThrow(new NullPointerException("bug"));

        service.resyncIfConnected(7L);  // must not throw

        verify(statusWriter).markError(42L);
    }

    /** Convenience builder for an account "U1" SUMMARY position. */
    private static IbkrPosition pos(String isin, String symbol, String assetCategory,
                                    BigDecimal position, BigDecimal costBasisPrice, BigDecimal fxRateToBase) {
        return new IbkrPosition("U1", isin, symbol, symbol, "USD", assetCategory, "SUMMARY",
            position, bd("999"), costBasisPrice, fxRateToBase);
    }

    private static BigDecimal bd(String v) { return new BigDecimal(v); }
}

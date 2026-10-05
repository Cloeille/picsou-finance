package com.picsou.service;

import com.picsou.exception.ResourceNotFoundException;
import com.picsou.exception.SyncException;
import com.picsou.exception.WalletRpcException;
import com.picsou.model.Account;
import com.picsou.model.Chain;
import com.picsou.model.FamilyMember;
import com.picsou.model.WalletAddress;
import com.picsou.port.WalletPort;
import com.picsou.port.WalletPort.WalletBalance;
import com.picsou.repository.AccountRepository;
import com.picsou.repository.FamilyMemberRepository;
import com.picsou.repository.WalletAddressRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WalletSyncServiceTest {

    private static final Long MEMBER_ID = 1L;

    @Mock WalletAddressRepository walletRepository;
    @Mock AccountRepository accountRepository;
    @Mock FamilyMemberRepository familyMemberRepository;
    @Mock AccountService accountService;
    @Mock PriceService priceService;

    private WalletSyncService serviceWith(WalletPort... adapters) {
        return new WalletSyncService(
            List.of(adapters), walletRepository, accountRepository,
            familyMemberRepository, accountService, priceService);
    }

    private static WalletAddress wallet(Long id, Chain chain, String address) {
        return WalletAddress.builder().id(id).chain(chain).address(address).build();
    }

    @Test
    void sync_wrapsRpcErrorInSyncException_andDoesNotMarkWalletSynced() {
        WalletAddress wallet = wallet(1L, Chain.EVM, "0xabc");
        when(walletRepository.findByIdAndMemberId(1L, MEMBER_ID)).thenReturn(Optional.of(wallet));

        WalletPort adapter = mock(WalletPort.class);
        when(adapter.chain()).thenReturn(Chain.EVM);
        when(adapter.fetchBalances(any())).thenThrow(new WalletRpcException("Ethereum eth_getBalance: RPC error"));

        WalletSyncService service = serviceWith(adapter);

        assertThatThrownBy(() -> service.sync(1L, MEMBER_ID))
            .isInstanceOf(SyncException.class)
            .hasCauseInstanceOf(WalletRpcException.class);

        // Failure surfaced before the wallet was persisted as synced.
        verify(walletRepository, never()).save(any());
    }

    @Test
    void sync_persistsHoldingsAndSnapshot_onSuccess() {
        WalletAddress wallet = wallet(1L, Chain.SOLANA, "SoLaNa");
        when(walletRepository.findByIdAndMemberId(1L, MEMBER_ID)).thenReturn(Optional.of(wallet));

        WalletPort adapter = mock(WalletPort.class);
        when(adapter.chain()).thenReturn(Chain.SOLANA);
        when(adapter.fetchBalances(any())).thenReturn(List.of(
            new WalletBalance("SOL", BigDecimal.ONE),
            new WalletBalance("USDC", new BigDecimal("50"))));

        // 1 SOL @ 20 EUR + 50 USDC @ 1 EUR = 70.00 EUR.
        when(priceService.refreshPrices(any()))
            .thenReturn(Map.of("SOL", new BigDecimal("20"), "USDC", new BigDecimal("1")));
        when(accountRepository.findByExternalAccountIdAndMemberId(any(), any())).thenReturn(Optional.empty());
        when(familyMemberRepository.findById(MEMBER_ID)).thenReturn(Optional.of(mock(FamilyMember.class)));
        Account savedAccount = mock(Account.class);
        when(savedAccount.getId()).thenReturn(100L);
        when(accountRepository.save(any())).thenReturn(savedAccount);

        WalletSyncService service = serviceWith(adapter);

        service.sync(1L, MEMBER_ID);

        // The response is built from the resolved account.
        verify(accountService).toResponse(savedAccount);

        // One holding per priced, positive balance.
        verify(accountService, times(2)).upsertHolding(eq(100L), eq(MEMBER_ID), any(), any(), any(), any());

        // Stale holdings pruned to exactly the tickers still held -- a token that
        // later disappears from the sync must not linger and inflate net worth.
        verify(accountService).pruneHoldings(eq(savedAccount), eq(Set.of("SOL", "USDC")));

        // Snapshot balance is the summed EUR value -- guards the conversion math.
        ArgumentCaptor<BigDecimal> balanceEur = ArgumentCaptor.forClass(BigDecimal.class);
        verify(accountService).upsertSnapshot(eq(savedAccount), balanceEur.capture(), any());
        assertThat(balanceEur.getValue()).isEqualByComparingTo("70.00");

        // lastSyncedAt was stamped and the wallet persisted.
        verify(walletRepository).save(wallet);
        assertThat(wallet.getLastSyncedAt()).isNotNull();
    }

    @Test
    void sync_failsAndWritesNothing_whenNoHeldAssetCanBePriced() {
        // Total CoinGecko outage on a wallet that still holds assets. The balance sums to
        // zero purely because nothing could be priced -- writing it would set the account
        // to 0 EUR and stamp a 0 snapshot for today, silently flattening the net-worth
        // chart for a transient outage. The holdings rows would survive, which is exactly
        // what makes the corruption easy to miss.
        WalletAddress wallet = wallet(1L, Chain.EVM, "0xabc");
        when(walletRepository.findByIdAndMemberId(1L, MEMBER_ID)).thenReturn(Optional.of(wallet));

        WalletPort adapter = mock(WalletPort.class);
        when(adapter.chain()).thenReturn(Chain.EVM);
        when(adapter.fetchBalances(any())).thenReturn(List.of(
            new WalletBalance("ETH", BigDecimal.ONE),
            new WalletBalance("USDC", new BigDecimal("50"))));
        when(priceService.refreshPrices(any())).thenReturn(Map.of()); // price outage

        WalletSyncService service = serviceWith(adapter);

        assertThatThrownBy(() -> service.sync(1L, MEMBER_ID))
            .isInstanceOf(SyncException.class);

        // Nothing persisted: no zero balance, no zero snapshot, no prune, and the wallet
        // is not marked synced. It keeps whatever balance it last had.
        verify(accountRepository, never()).save(any());
        verify(accountService, never()).upsertSnapshot(any(), any(), any());
        verify(accountService, never()).pruneHoldings(any(), any());
        verify(walletRepository, never()).save(any());
    }

    @Test
    void sync_recordsAPartialTotal_whenOnlySomeAssetsArePriced() {
        // The complement of the test above: refusing to snapshot whenever ANY asset is
        // unpriced would block snapshots indefinitely on one obscure token. A partial
        // total is recorded, and the unpriced asset is still kept as a holding.
        WalletAddress wallet = wallet(1L, Chain.EVM, "0xabc");
        when(walletRepository.findByIdAndMemberId(1L, MEMBER_ID)).thenReturn(Optional.of(wallet));

        WalletPort adapter = mock(WalletPort.class);
        when(adapter.chain()).thenReturn(Chain.EVM);
        when(adapter.fetchBalances(any())).thenReturn(List.of(
            new WalletBalance("ETH", BigDecimal.ONE),
            new WalletBalance("SOMETOKEN", new BigDecimal("50"))));
        when(priceService.refreshPrices(any())).thenReturn(Map.of("ETH", new BigDecimal("2000")));

        when(accountRepository.findByExternalAccountIdAndMemberId(any(), any())).thenReturn(Optional.empty());
        when(familyMemberRepository.findById(MEMBER_ID)).thenReturn(Optional.of(mock(FamilyMember.class)));
        Account savedAccount = mock(Account.class);
        when(savedAccount.getId()).thenReturn(100L);
        when(accountRepository.save(any())).thenReturn(savedAccount);

        serviceWith(adapter).sync(1L, MEMBER_ID);

        ArgumentCaptor<BigDecimal> balanceEur = ArgumentCaptor.forClass(BigDecimal.class);
        verify(accountService).upsertSnapshot(eq(savedAccount), balanceEur.capture(), any());
        assertThat(balanceEur.getValue()).isEqualByComparingTo("2000.00");

        // Both are still held, so neither is pruned -- the unpriced one keeps its cost basis.
        verify(accountService).pruneHoldings(eq(savedAccount), eq(Set.of("ETH", "SOMETOKEN")));

        // Only the PRICED asset is upserted. Upserting the unpriced one would write a null
        // or zero price over a holding that still has a real cost basis -- the corruption
        // this whole unpriced-vs-held distinction exists to avoid.
        verify(accountService).upsertHolding(
            eq(100L), eq(MEMBER_ID), eq("ETH"), eq("ETH"), any(), eq(new BigDecimal("2000")));
        verify(accountService, never()).upsertHolding(
            any(), any(), eq("SOMETOKEN"), any(), any(), any());
    }

    @Test
    void sync_writesNoSnapshot_whenAHoldingUpsertFails() {
        // upsertHolding runs before the snapshot, so a failure part-way through must abort
        // the whole sync rather than leave a snapshot recorded against half-written
        // holdings. The method is @Transactional, so throwing is what rolls the rest back.
        WalletAddress wallet = wallet(1L, Chain.EVM, "0xabc");
        when(walletRepository.findByIdAndMemberId(1L, MEMBER_ID)).thenReturn(Optional.of(wallet));

        WalletPort adapter = mock(WalletPort.class);
        when(adapter.chain()).thenReturn(Chain.EVM);
        when(adapter.fetchBalances(any())).thenReturn(List.of(new WalletBalance("ETH", BigDecimal.ONE)));
        when(priceService.refreshPrices(any())).thenReturn(Map.of("ETH", new BigDecimal("2000")));
        when(accountRepository.findByExternalAccountIdAndMemberId(any(), any())).thenReturn(Optional.empty());
        when(familyMemberRepository.findById(MEMBER_ID)).thenReturn(Optional.of(mock(FamilyMember.class)));
        Account savedAccount = mock(Account.class);
        when(savedAccount.getId()).thenReturn(100L);
        when(accountRepository.save(any())).thenReturn(savedAccount);
        doThrow(new org.springframework.dao.DataIntegrityViolationException("holding write failed"))
            .when(accountService).upsertHolding(any(), any(), any(), any(), any(), any());

        WalletSyncService service = serviceWith(adapter);

        assertThatThrownBy(() -> service.sync(1L, MEMBER_ID))
            .isInstanceOf(SyncException.class);

        // No snapshot, and no prune -- a prune here would delete rows while the upserts
        // that should have replaced them never landed.
        verify(accountService, never()).upsertSnapshot(any(), any(), any());
        verify(accountService, never()).pruneHoldings(any(), any());
    }

    @Test
    void sync_succeedsOnAGenuinelyEmptyWallet_withoutPrices() {
        // A zero balance is only suspicious when assets are held. An empty wallet legitimately
        // prices nothing, and must still sync (and snapshot 0) rather than erroring forever.
        WalletAddress wallet = wallet(1L, Chain.EVM, "0xabc");
        when(walletRepository.findByIdAndMemberId(1L, MEMBER_ID)).thenReturn(Optional.of(wallet));

        WalletPort adapter = mock(WalletPort.class);
        when(adapter.chain()).thenReturn(Chain.EVM);
        when(adapter.fetchBalances(any())).thenReturn(List.of(new WalletBalance("ETH", BigDecimal.ZERO)));
        when(priceService.refreshPrices(any())).thenReturn(Map.of());

        when(accountRepository.findByExternalAccountIdAndMemberId(any(), any())).thenReturn(Optional.empty());
        when(familyMemberRepository.findById(MEMBER_ID)).thenReturn(Optional.of(mock(FamilyMember.class)));
        Account savedAccount = mock(Account.class);
        when(accountRepository.save(any())).thenReturn(savedAccount);

        serviceWith(adapter).sync(1L, MEMBER_ID);

        verify(accountService).upsertSnapshot(eq(savedAccount), any(), any());
    }

    @Test
    void sync_throwsSyncException_whenAdapterReturnsNoBalances() {
        WalletAddress wallet = wallet(1L, Chain.EVM, "0xabc");
        when(walletRepository.findByIdAndMemberId(1L, MEMBER_ID)).thenReturn(Optional.of(wallet));

        WalletPort adapter = mock(WalletPort.class);
        when(adapter.chain()).thenReturn(Chain.EVM);
        when(adapter.fetchBalances(any())).thenReturn(List.of());

        WalletSyncService service = serviceWith(adapter);

        assertThatThrownBy(() -> service.sync(1L, MEMBER_ID))
            .isInstanceOf(SyncException.class);
    }

    @Test
    void resyncAll_reportsFailedChain_andKeepsSyncingOthers() {
        WalletAddress eth = wallet(1L, Chain.EVM, "0xabc");
        WalletAddress sol = wallet(2L, Chain.SOLANA, "SoLaNa");
        when(walletRepository.findAllByMemberId(MEMBER_ID)).thenReturn(List.of(eth, sol));
        when(walletRepository.findByIdAndMemberId(1L, MEMBER_ID)).thenReturn(Optional.of(eth));
        when(walletRepository.findByIdAndMemberId(2L, MEMBER_ID)).thenReturn(Optional.of(sol));

        // EVM adapter succeeds; SOL adapter errors.
        WalletPort ethAdapter = mock(WalletPort.class);
        when(ethAdapter.chain()).thenReturn(Chain.EVM);
        when(ethAdapter.fetchBalances(any())).thenReturn(List.of(new WalletBalance("ETH", BigDecimal.ONE)));
        WalletPort solAdapter = mock(WalletPort.class);
        when(solAdapter.chain()).thenReturn(Chain.SOLANA);
        when(solAdapter.fetchBalances(any())).thenThrow(new WalletRpcException("Solana getBalance: RPC error"));

        // Happy-path wiring for the ETH sync.
        when(priceService.refreshPrices(any())).thenReturn(Map.of("ETH", new BigDecimal("2000")));
        when(accountRepository.findByExternalAccountIdAndMemberId(any(), any())).thenReturn(Optional.empty());
        when(familyMemberRepository.findById(MEMBER_ID)).thenReturn(Optional.of(mock(FamilyMember.class)));
        Account savedAccount = mock(Account.class);
        when(savedAccount.getId()).thenReturn(100L);
        when(accountRepository.save(any())).thenReturn(savedAccount);

        WalletSyncService service = serviceWith(ethAdapter, solAdapter);

        WalletSyncService.ResyncSummary summary = service.resyncAll(MEMBER_ID);

        assertThat(summary.total()).isEqualTo(2);
        assertThat(summary.succeeded()).isEqualTo(1);
        assertThat(summary.failed()).containsExactly(Chain.SOLANA);
    }

    @Test
    void addWallet_rejectsMalformedAddress_beforePersistingAnything() {
        WalletPort adapter = mock(WalletPort.class);
        when(adapter.chain()).thenReturn(Chain.EVM);
        doThrow(new IllegalArgumentException("Invalid EVM address '0xnope'"))
            .when(adapter).validateAddress("0xnope");

        WalletSyncService service = serviceWith(adapter);

        // Surfaces as a 400 (IllegalArgumentException), not the 422 of a failed sync.
        assertThatThrownBy(() -> service.addWallet(Chain.EVM, "  0xnope  ", "Ledger", MEMBER_ID))
            .isInstanceOf(IllegalArgumentException.class);

        // The whole point: no row is written, so the unusable wallet can't linger and
        // fail every subsequent resync. Validation also runs on the TRIMMED value.
        verify(walletRepository, never()).save(any());
        verify(familyMemberRepository, never()).findById(any());
        verify(adapter, never()).fetchBalances(any());
    }

    @Test
    void addWallet_rejectsBlankAddress_evenOnChainsWithoutFormatValidation() {
        // BITCOIN has no validateAddress override (its encodings aren't cheaply checked
        // offline), so a blank address would otherwise sail through the gate and only
        // fail at sync -- as a retryable-looking 422, after calling the explorer with an
        // empty address in the URL path.
        // No chain() stub: the blank check fires before findAdapter is even consulted,
        // which is itself the point -- an empty address is rejected chain-agnostically.
        WalletPort adapter = mock(WalletPort.class);

        WalletSyncService service = serviceWith(adapter);

        assertThatThrownBy(() -> service.addWallet(Chain.BITCOIN, "   ", "Cold", MEMBER_ID))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.addWallet(Chain.BITCOIN, null, "Cold", MEMBER_ID))
            .isInstanceOf(IllegalArgumentException.class);

        verify(walletRepository, never()).save(any());
        verify(adapter, never()).fetchBalances(any());
    }

    @Test
    void addWallet_rejectsOverlongAddress_beforeItReachesTheColumn() {
        // wallet_address.address is VARCHAR(200) and BITCOIN has no validateAddress
        // override, so a pasted seed phrase used to reach the insert and come back as a
        // 500 from the constraint violation.
        WalletPort adapter = mock(WalletPort.class);

        WalletSyncService service = serviceWith(adapter);

        assertThatThrownBy(() -> service.addWallet(
            Chain.BITCOIN, "x".repeat(201), "Cold", MEMBER_ID))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("too long");

        verify(walletRepository, never()).save(any());
        verify(adapter, never()).fetchBalances(any());
    }

    @Test
    void addWallet_rejectsNullChain_asABadRequest() {
        // The controller's AddWalletRequest has no @NotNull and no @Valid, so a body that
        // omits "chain" arrives here as null. Without the guard it reaches findAdapter,
        // matches nothing, and surfaces as a 422 claiming the chain "isn't supported yet".
        WalletSyncService service = serviceWith(mock(WalletPort.class));

        assertThatThrownBy(() -> service.addWallet(null, "0xabc", "Ledger", MEMBER_ID))
            .isInstanceOf(IllegalArgumentException.class)
            .isNotInstanceOf(SyncException.class);

        verify(walletRepository, never()).save(any());
    }

    // ─── removeWallet ─────────────────────────────────────────────────────────

    @Test
    void removeWallet_deletesTheWalletAndItsAccount() {
        WalletAddress wallet = wallet(1L, Chain.EVM, "0xabc");
        when(walletRepository.findByIdAndMemberId(1L, MEMBER_ID)).thenReturn(Optional.of(wallet));
        Account account = new Account();
        // The external id must match exactly what sync() builds, or removal orphans the account.
        when(accountRepository.findByExternalAccountIdAndMemberId("wallet_evm_1", MEMBER_ID))
            .thenReturn(Optional.of(account));

        serviceWith().removeWallet(1L, MEMBER_ID);

        // Soft delete: balance_snapshot cascades on account, so a row delete would take the
        // wallet's whole history with it. See removeWallet_softDeletesTheAccount_keepingItsHistory.
        assertThat(account.getDeletedAt()).isNotNull();
        verify(accountRepository).save(account);
        verify(accountRepository, never()).delete(any());
        verify(walletRepository).delete(wallet);
    }

    @Test
    void removeWallet_stillDeletesTheWallet_whenNoAccountExists() {
        // A wallet whose first sync never succeeded has no account. Removing it must not
        // leave the wallet row behind.
        WalletAddress wallet = wallet(2L, Chain.BITCOIN, "bc1q");
        when(walletRepository.findByIdAndMemberId(2L, MEMBER_ID)).thenReturn(Optional.of(wallet));
        when(accountRepository.findByExternalAccountIdAndMemberId("wallet_bitcoin_2", MEMBER_ID))
            .thenReturn(Optional.empty());

        serviceWith().removeWallet(2L, MEMBER_ID);

        verify(walletRepository).delete(wallet);
        verify(accountRepository, never()).delete(any());
    }

    @Test
    void removeWallet_rejectsAnotherMembersWallet_andDeletesNothing() {
        // The member-scoped lookup is the only thing standing between members here.
        when(walletRepository.findByIdAndMemberId(1L, MEMBER_ID)).thenReturn(Optional.empty());

        WalletSyncService service = serviceWith();

        assertThatThrownBy(() -> service.removeWallet(1L, MEMBER_ID))
            .isInstanceOf(ResourceNotFoundException.class);

        verify(walletRepository, never()).delete(any());
        verify(accountRepository, never()).delete(any());
    }

    // ─── listWallets ──────────────────────────────────────────────────────────

    @Test
    void listWallets_mapsEveryFieldOfEachWallet() {
        WalletAddress wallet = WalletAddress.builder()
            .id(3L).chain(Chain.SOLANA).address("SoLaNa").label("Phantom")
            .lastSyncedAt(java.time.Instant.EPOCH).build();
        when(walletRepository.findAllByMemberId(MEMBER_ID)).thenReturn(List.of(wallet));

        List<WalletSyncService.WalletStatusResponse> result = serviceWith().listWallets(MEMBER_ID);

        assertThat(result).singleElement().satisfies(w -> {
            assertThat(w.id()).isEqualTo(3L);
            assertThat(w.chain()).isEqualTo(Chain.SOLANA);
            assertThat(w.address()).isEqualTo("SoLaNa");
            assertThat(w.label()).isEqualTo("Phantom");
            assertThat(w.lastSyncedAt()).isEqualTo(java.time.Instant.EPOCH);
        });
    }

    @Test
    void listWallets_returnsEmpty_whenMemberHasNone() {
        when(walletRepository.findAllByMemberId(MEMBER_ID)).thenReturn(List.of());

        assertThat(serviceWith().listWallets(MEMBER_ID)).isEmpty();
    }

    // ─── multi-wallet isolation ───────────────────────────────────────────────

    @Test
    void sync_touchesOnlyTheRequestedWallet_notTheMembersOtherWallets() {
        // The account key embeds the wallet id, so wallet A can only ever resolve
        // wallet_evm_1. If that suffix were ever dropped, both wallets would collapse onto
        // one account and syncing A would prune B's holdings to zero -- silent data loss
        // that no other test would catch.
        WalletAddress walletA = wallet(1L, Chain.EVM, "0xabc");
        WalletAddress walletB = wallet(2L, Chain.SOLANA, "SoLaNa");
        when(walletRepository.findByIdAndMemberId(1L, MEMBER_ID)).thenReturn(Optional.of(walletA));

        WalletPort evmAdapter = mock(WalletPort.class);
        when(evmAdapter.chain()).thenReturn(Chain.EVM);
        when(evmAdapter.fetchBalances(any())).thenReturn(List.of(new WalletBalance("ETH", BigDecimal.ONE)));
        // Deliberately NOT stubbing solAdapter.chain(): findAdapter short-circuits on the
        // first match, so a stub here would fail Mockito's strict-stub check.
        WalletPort solAdapter = mock(WalletPort.class);

        when(priceService.refreshPrices(any())).thenReturn(Map.of("ETH", new BigDecimal("2000")));
        when(accountRepository.findByExternalAccountIdAndMemberId(any(), any())).thenReturn(Optional.empty());
        when(familyMemberRepository.findById(MEMBER_ID)).thenReturn(Optional.of(mock(FamilyMember.class)));
        Account accountA = mock(Account.class);
        when(accountA.getId()).thenReturn(100L);
        when(accountRepository.save(any())).thenReturn(accountA);

        serviceWith(evmAdapter, solAdapter).sync(1L, MEMBER_ID);

        // Only A's account key was ever resolved.
        verify(accountRepository).findByExternalAccountIdAndMemberId("wallet_evm_1", MEMBER_ID);
        verify(accountRepository, never()).findByExternalAccountIdAndMemberId(eq("wallet_solana_2"), any());

        // B was never fetched, never stamped, and its adapter never contacted.
        verify(solAdapter, never()).fetchBalances(any());
        verify(walletRepository, never()).save(walletB);
        assertThat(walletB.getLastSyncedAt()).isNull();

        // The destructive call landed on A's account only.
        verify(accountService).pruneHoldings(eq(accountA), eq(Set.of("ETH")));
    }

    @Test
    void sync_initialisesTheAccountFields_whenCreatingItForTheFirstTime() {
        // provider/color/isManual are set once at creation and never revisited, so a
        // regression here is invisible until someone looks at the account list. provider
        // carries the native symbol for display; isManual=false is what keeps the balance
        // read-only in the UI (a synced account must not be hand-editable).
        WalletAddress wallet = wallet(1L, Chain.EVM, "0xabc");
        when(walletRepository.findByIdAndMemberId(1L, MEMBER_ID)).thenReturn(Optional.of(wallet));

        WalletPort adapter = mock(WalletPort.class);
        when(adapter.chain()).thenReturn(Chain.EVM);
        when(adapter.fetchBalances(any())).thenReturn(List.of(new WalletBalance("ETH", BigDecimal.ONE)));
        when(priceService.refreshPrices(any())).thenReturn(Map.of("ETH", new BigDecimal("2000")));
        when(accountRepository.findByExternalAccountIdAndMemberId(any(), any())).thenReturn(Optional.empty());
        when(familyMemberRepository.findById(MEMBER_ID)).thenReturn(Optional.of(mock(FamilyMember.class)));
        Account saved = mock(Account.class);
        when(saved.getId()).thenReturn(100L);
        when(accountRepository.save(any())).thenReturn(saved);

        serviceWith(adapter).sync(1L, MEMBER_ID);

        ArgumentCaptor<Account> created = ArgumentCaptor.forClass(Account.class);
        verify(accountRepository).save(created.capture());
        Account account = created.getValue();
        assertThat(account.getProvider()).isEqualTo("ETH");
        assertThat(account.getColor()).isEqualTo("#f59e0b");
        assertThat(account.isManual()).isFalse();
        assertThat(account.getType()).isEqualTo(com.picsou.model.AccountType.CRYPTO);
        assertThat(account.getCurrency()).isEqualTo("EUR");
        assertThat(account.getExternalAccountId()).isEqualTo("wallet_evm_1");
        // No ticker on the account: the balance is already EUR, so a ticker here would
        // make liveBalanceEur convert it a second time.
        assertThat(account.getTicker()).isNull();
        // provider is the native ticker, so the frontend's provider -> logo map can never
        // match a wallet. This key is the only thing that gives it one (V75 backfilled the
        // wallets that predate it).
        assertThat(account.getLogoKey()).isEqualTo(WalletSyncService.DEFAULT_LOGO_KEY);
    }

    @Test
    void sync_keepsTheLogoAUserPicked_whenTheAccountAlreadyExists() {
        // resolveAccount refreshes only balance/lastSyncedAt/ticker on an account it finds --
        // re-seeding the default here would drag every Ledger back to the generic mark on the
        // next scheduled sync, silently and daily.
        WalletAddress wallet = wallet(1L, Chain.EVM, "0xabc");
        when(walletRepository.findByIdAndMemberId(1L, MEMBER_ID)).thenReturn(Optional.of(wallet));

        WalletPort adapter = mock(WalletPort.class);
        when(adapter.chain()).thenReturn(Chain.EVM);
        when(adapter.fetchBalances(any())).thenReturn(List.of(new WalletBalance("ETH", BigDecimal.ONE)));
        when(priceService.refreshPrices(any())).thenReturn(Map.of("ETH", new BigDecimal("2000")));
        Account existing = Account.builder().id(100L).logoKey("ledger").build();
        when(accountRepository.findByExternalAccountIdAndMemberId("wallet_evm_1", MEMBER_ID))
            .thenReturn(Optional.of(existing));
        when(accountRepository.save(any())).thenReturn(existing);

        serviceWith(adapter).sync(1L, MEMBER_ID);

        ArgumentCaptor<Account> saved = ArgumentCaptor.forClass(Account.class);
        verify(accountRepository).save(saved.capture());
        assertThat(saved.getValue().getLogoKey()).isEqualTo("ledger");
    }

    @Test
    void sync_usesTheChainNameForAnUnlabelledWallet_andTheLabelOtherwise() {
        WalletAddress labelled = WalletAddress.builder()
            .id(1L).chain(Chain.EVM).address("0xabc").label("My Ledger").build();
        when(walletRepository.findByIdAndMemberId(1L, MEMBER_ID)).thenReturn(Optional.of(labelled));

        WalletPort adapter = mock(WalletPort.class);
        when(adapter.chain()).thenReturn(Chain.EVM);
        when(adapter.fetchBalances(any())).thenReturn(List.of(new WalletBalance("ETH", BigDecimal.ONE)));
        when(priceService.refreshPrices(any())).thenReturn(Map.of("ETH", new BigDecimal("2000")));
        when(accountRepository.findByExternalAccountIdAndMemberId(any(), any())).thenReturn(Optional.empty());
        when(familyMemberRepository.findById(MEMBER_ID)).thenReturn(Optional.of(mock(FamilyMember.class)));
        Account saved = mock(Account.class);
        when(saved.getId()).thenReturn(100L);
        when(accountRepository.save(any())).thenReturn(saved);

        serviceWith(adapter).sync(1L, MEMBER_ID);

        ArgumentCaptor<Account> created = ArgumentCaptor.forClass(Account.class);
        verify(accountRepository).save(created.capture());
        // The label wins; "EVM Wallet" is only the fallback (and the string V55 rewrites).
        assertThat(created.getValue().getName()).isEqualTo("My Ledger");
    }

    // ─── startup adapter coverage ─────────────────────────────────────────────

    @Test
    void verifyAdapterCoverage_failsStartup_whenAChainHasNoAdapter() {
        // Dispatch is a findFirst over injected beans, so a missing adapter would otherwise
        // only show up as a 422 the first time a user syncs that chain.
        WalletPort evm = mock(WalletPort.class);
        when(evm.chain()).thenReturn(Chain.EVM);

        assertThatThrownBy(() -> serviceWith(evm).verifyAdapterCoverage())
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("BITCOIN")
            .hasMessageContaining("SOLANA");
    }

    @Test
    void verifyAdapterCoverage_failsStartup_whenTwoAdaptersClaimTheSameChain() {
        WalletPort evm = mock(WalletPort.class);
        WalletPort evmAgain = mock(WalletPort.class);
        WalletPort btc = mock(WalletPort.class);
        WalletPort sol = mock(WalletPort.class);
        when(evm.chain()).thenReturn(Chain.EVM);
        when(evmAgain.chain()).thenReturn(Chain.EVM);
        when(btc.chain()).thenReturn(Chain.BITCOIN);
        when(sol.chain()).thenReturn(Chain.SOLANA);

        assertThatThrownBy(() -> serviceWith(evm, evmAgain, btc, sol).verifyAdapterCoverage())
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("EVM");
    }

    @Test
    void verifyAdapterCoverage_passes_whenEveryChainHasExactlyOneAdapter() {
        WalletPort evm = mock(WalletPort.class);
        WalletPort btc = mock(WalletPort.class);
        WalletPort sol = mock(WalletPort.class);
        when(evm.chain()).thenReturn(Chain.EVM);
        when(btc.chain()).thenReturn(Chain.BITCOIN);
        when(sol.chain()).thenReturn(Chain.SOLANA);

        assertThatCode(() -> serviceWith(evm, btc, sol).verifyAdapterCoverage())
            .doesNotThrowAnyException();
    }

    // ─── concurrent account creation ──────────────────────────────────────────

    @Test
    void sync_reusesTheWinningAccount_whenAConcurrentSyncInsertedItFirst() {
        // Two syncs of the same wallet (double-click, or a user sync colliding with the
        // scheduler) both see no account and both insert. The loser must converge on the
        // winner's row -- two accounts for one wallet would split its snapshot history.
        WalletAddress wallet = wallet(1L, Chain.EVM, "0xabc");
        when(walletRepository.findByIdAndMemberId(1L, MEMBER_ID)).thenReturn(Optional.of(wallet));

        WalletPort adapter = mock(WalletPort.class);
        when(adapter.chain()).thenReturn(Chain.EVM);
        when(adapter.fetchBalances(any())).thenReturn(List.of(new WalletBalance("ETH", BigDecimal.ONE)));
        when(priceService.refreshPrices(any())).thenReturn(Map.of("ETH", new BigDecimal("2000")));
        when(familyMemberRepository.findById(MEMBER_ID)).thenReturn(Optional.of(mock(FamilyMember.class)));

        Account winner = mock(Account.class);
        when(winner.getId()).thenReturn(100L);
        // First lookup: nothing. Insert loses the race. Re-lookup: the winner's row.
        when(accountRepository.findByExternalAccountIdAndMemberId("wallet_evm_1", MEMBER_ID))
            .thenReturn(Optional.empty())
            .thenReturn(Optional.of(winner));
        when(accountRepository.save(any()))
            .thenThrow(new org.springframework.dao.DataIntegrityViolationException("duplicate key"));

        serviceWith(adapter).sync(1L, MEMBER_ID);

        // The sync completed against the surviving account rather than failing or duplicating.
        verify(accountService).upsertSnapshot(eq(winner), any(), any());
    }

    /**
     * The regression this whole change exists for: without the guard, every scheduled resync of
     * a wallet whose account the user had deleted inserted a brand new account row — one
     * duplicate per deletion, each starting the balance history over.
     */
    @Test
    void sync_refusesToResurrectAnAccountTheUserDeleted() {
        WalletAddress wallet = wallet(2L, Chain.BITCOIN, "bc1qexample");
        when(walletRepository.findByIdAndMemberId(2L, MEMBER_ID)).thenReturn(Optional.of(wallet));

        WalletPort adapter = mock(WalletPort.class);
        when(adapter.chain()).thenReturn(Chain.BITCOIN);
        when(adapter.fetchBalances(any())).thenReturn(List.of(new WalletBalance("BTC", BigDecimal.ONE)));
        when(priceService.refreshPrices(any())).thenReturn(Map.of("BTC", new BigDecimal("50000")));
        when(accountRepository.findByExternalAccountIdAndMemberId("wallet_bitcoin_2", MEMBER_ID))
            .thenReturn(Optional.empty());
        when(accountRepository.existsSoftDeletedByExternalAccountIdAndMemberId("wallet_bitcoin_2", MEMBER_ID))
            .thenReturn(true);

        assertThatThrownBy(() -> serviceWith(adapter).sync(2L, MEMBER_ID))
            .isInstanceOf(SyncException.class)
            .hasMessageContaining("deleted");

        // Nothing was written: no account, no holding, no snapshot.
        verify(accountRepository, never()).save(any());
        verify(accountService, never()).upsertHolding(any(), any(), any(), any(), any(), any());
        verify(accountService, never()).upsertSnapshot(any(), any(), any());
    }

    /**
     * A deleted account is a choice, not a failure. Counting it as one would put the wallet's
     * chain in {@code failed} and log an ERROR on every scheduled run, forever.
     */
    @Test
    void resyncAll_countsADeletedAccountAsSkipped_neitherSucceededNorFailed() {
        WalletAddress wallet = wallet(2L, Chain.BITCOIN, "bc1qexample");
        when(walletRepository.findAllByMemberId(MEMBER_ID)).thenReturn(List.of(wallet));
        when(walletRepository.findByIdAndMemberId(2L, MEMBER_ID)).thenReturn(Optional.of(wallet));

        WalletPort adapter = mock(WalletPort.class);
        when(adapter.chain()).thenReturn(Chain.BITCOIN);
        when(adapter.fetchBalances(any())).thenReturn(List.of(new WalletBalance("BTC", BigDecimal.ONE)));
        when(priceService.refreshPrices(any())).thenReturn(Map.of("BTC", new BigDecimal("50000")));
        when(accountRepository.findByExternalAccountIdAndMemberId("wallet_bitcoin_2", MEMBER_ID))
            .thenReturn(Optional.empty());
        when(accountRepository.existsSoftDeletedByExternalAccountIdAndMemberId("wallet_bitcoin_2", MEMBER_ID))
            .thenReturn(true);

        WalletSyncService.ResyncSummary summary = serviceWith(adapter).resyncAll(MEMBER_ID);

        assertThat(summary.total()).isEqualTo(1);
        assertThat(summary.succeeded()).isZero();
        assertThat(summary.failed()).isEmpty();
    }

    /**
     * balance_snapshot cascades on account, so deleting the row would take the wallet's whole
     * net-worth history with it — and leave nothing for the guard above to recognise.
     */
    @Test
    void removeWallet_softDeletesTheAccount_keepingItsHistory() {
        WalletAddress wallet = wallet(2L, Chain.BITCOIN, "bc1qexample");
        when(walletRepository.findByIdAndMemberId(2L, MEMBER_ID)).thenReturn(Optional.of(wallet));
        Account account = new Account();
        when(accountRepository.findByExternalAccountIdAndMemberId("wallet_bitcoin_2", MEMBER_ID))
            .thenReturn(Optional.of(account));

        serviceWith().removeWallet(2L, MEMBER_ID);

        assertThat(account.getDeletedAt()).isNotNull();
        verify(accountRepository).save(account);
        verify(accountRepository, never()).delete(any());
        verify(walletRepository).delete(wallet);
    }

    @Test
    void resyncAll_returnsEmptySummary_whenNoWallets() {
        when(walletRepository.findAllByMemberId(MEMBER_ID)).thenReturn(List.of());

        WalletSyncService.ResyncSummary summary = serviceWith().resyncAll(MEMBER_ID);

        assertThat(summary.total()).isZero();
        assertThat(summary.succeeded()).isZero();
        assertThat(summary.failed()).isEmpty();
    }
}

package com.picsou.service;

import com.picsou.exception.ResourceNotFoundException;
import com.picsou.model.Account;
import com.picsou.model.CryptoExchangeSession;
import com.picsou.model.ExchangeType;
import com.picsou.model.WalletAddress;
import com.picsou.repository.AccountRepository;
import com.picsou.repository.CryptoExchangeSessionRepository;
import com.picsou.repository.RequisitionRepository;
import com.picsou.repository.WalletAddressRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Locale;
import java.util.Optional;

/**
 * Deletes an account <em>and</em> the connection that feeds it, once that connection has no
 * live account left.
 *
 * <p>Before this existed the two lifecycles were independent, which read as a bug from the
 * outside: deleting an on-chain wallet's account left the {@code wallet_address} row syncing,
 * and deleting a bank account left its requisition {@code LINKED}. Both kept appearing in the
 * "Sync all" list, and the wallet rebuilt its account on the next scheduled run. See the
 * account-deletion ADR for why removing the connection beats leaving it orphaned: a connection
 * whose account was deleted can never produce one again -- every connector refuses to
 * resurrect a soft-deleted account -- so what survives is a row that syncs forever and can
 * only mislead.
 *
 * <p>Lives outside {@link AccountService} on purpose: the connector services already depend on
 * it, so calling them from there would close a Spring dependency cycle.
 */
@Service
@Transactional
public class AccountConnectionService {

    private static final Logger log = LoggerFactory.getLogger(AccountConnectionService.class);

    private static final String WALLET_PREFIX = "wallet_";
    private static final String EXCHANGE_PREFIX = "crypto_exchange_";
    private static final String AMUNDI_PREFIX = "amundi_";
    private static final String TR_PREFIX = "tr_";
    private static final String BOURSE_DIRECT_PREFIX = "bd_";
    private static final String BOURSO_PREFIX = "bourso_";
    private static final String FORTUNEO_PREFIX = "ft_";
    private static final String IBKR_PREFIX = "ibkr_";
    private static final String DEGIRO_EXTERNAL_ID = "degiro-portfolio";

    /** One connection instance. {@code discriminator} separates two of the same kind. */
    public record ConnectionRef(Kind kind, String discriminator) {}

    public enum Kind { WALLET, EXCHANGE, AMUNDI, TRADE_REPUBLIC, BOURSE_DIRECT, BOURSO, FORTUNEO, IBKR, DEGIRO, ENABLE_BANKING }

    /** What deleting an account is about to cost, for the confirmation dialog. */
    public record DeletionImpact(boolean removesConnection, String connectionLabel) {}

    private final AccountRepository accountRepository;
    private final AccountService accountService;
    private final WalletAddressRepository walletRepository;
    private final CryptoExchangeSessionRepository exchangeSessionRepository;
    private final RequisitionRepository requisitionRepository;
    private final WalletSyncService walletSyncService;
    private final CryptoExchangeSyncService cryptoExchangeSyncService;
    private final AmundiSyncService amundiSyncService;
    private final TradeRepublicSyncService tradeRepublicSyncService;
    private final BourseDirectSyncService bourseDirectSyncService;
    private final BoursoSyncService boursoSyncService;
    private final FortuneoSyncService fortuneoSyncService;
    private final DegiroSyncService degiroSyncService;
    private final IbkrSyncService ibkrSyncService;
    private final SyncService syncService;

    public AccountConnectionService(
        AccountRepository accountRepository,
        AccountService accountService,
        WalletAddressRepository walletRepository,
        CryptoExchangeSessionRepository exchangeSessionRepository,
        RequisitionRepository requisitionRepository,
        WalletSyncService walletSyncService,
        CryptoExchangeSyncService cryptoExchangeSyncService,
        AmundiSyncService amundiSyncService,
        TradeRepublicSyncService tradeRepublicSyncService,
        BourseDirectSyncService bourseDirectSyncService,
        BoursoSyncService boursoSyncService,
        FortuneoSyncService fortuneoSyncService,
        DegiroSyncService degiroSyncService,
        IbkrSyncService ibkrSyncService,
        SyncService syncService
    ) {
        this.accountRepository = accountRepository;
        this.accountService = accountService;
        this.walletRepository = walletRepository;
        this.exchangeSessionRepository = exchangeSessionRepository;
        this.requisitionRepository = requisitionRepository;
        this.walletSyncService = walletSyncService;
        this.cryptoExchangeSyncService = cryptoExchangeSyncService;
        this.amundiSyncService = amundiSyncService;
        this.tradeRepublicSyncService = tradeRepublicSyncService;
        this.bourseDirectSyncService = bourseDirectSyncService;
        this.boursoSyncService = boursoSyncService;
        this.fortuneoSyncService = fortuneoSyncService;
        this.degiroSyncService = degiroSyncService;
        this.ibkrSyncService = ibkrSyncService;
        this.syncService = syncService;
    }

    /**
     * Soft-deletes the account, then removes its connection when nothing else is left on it.
     *
     * <p>Order matters: the account is deleted first so a connector that runs concurrently
     * finds the soft-deleted row and refuses to rebuild it, rather than racing the removal.
     */
    public void deleteAccount(Long accountId, Long memberId) {
        Account account = accountRepository.findByIdAndMemberId(accountId, memberId)
            .orElseThrow(() -> new ResourceNotFoundException("Account not found"));
        Optional<ConnectionRef> connection = resolve(account);

        accountService.delete(accountId, memberId);

        connection.filter(ref -> !hasOtherLiveAccount(ref, accountId, memberId))
            .ifPresent(ref -> removeConnection(ref, memberId));
    }

    /** Whether deleting this account would also remove its connection, and which one. */
    @Transactional(readOnly = true)
    public DeletionImpact describeDeletion(Long accountId, Long memberId) {
        Account account = accountRepository.findByIdAndMemberId(accountId, memberId)
            .orElseThrow(() -> new ResourceNotFoundException("Account not found"));

        return resolve(account)
            .filter(ref -> !hasOtherLiveAccount(ref, accountId, memberId))
            .map(ref -> new DeletionImpact(true, label(ref, account, memberId)))
            .orElseGet(() -> new DeletionImpact(false, null));
    }

    /**
     * Which connection an account came from, if any. Manual accounts have none, and neither do
     * Enable Banking rows that predate V76 whose backfill could not be attributed with
     * certainty -- those simply keep their connection, the safe direction to fail.
     */
    private Optional<ConnectionRef> resolve(Account account) {
        // Checked before the namespaces, not after: requisition_id is recorded by the connector
        // that created the account, so it *knows*, where a prefix only guesses. Enable Banking
        // account ids are the bank's own opaque strings -- nothing stops one from arriving as
        // "wallet_bitcoin_2", and resolving that by prefix would delete an unrelated wallet of
        // the same member while leaving the requisition linked.
        if (account.getRequisitionId() != null) {
            return Optional.of(new ConnectionRef(
                Kind.ENABLE_BANKING, String.valueOf(account.getRequisitionId())));
        }

        String externalId = account.getExternalAccountId();

        if (externalId != null) {
            if (externalId.startsWith(WALLET_PREFIX)) {
                // wallet_{chain}_{id} -- the id is what identifies the row, the chain is display.
                String walletId = externalId.substring(externalId.lastIndexOf('_') + 1);
                return Optional.of(new ConnectionRef(Kind.WALLET, walletId));
            }
            if (externalId.startsWith(EXCHANGE_PREFIX)) {
                return Optional.of(new ConnectionRef(Kind.EXCHANGE,
                    externalId.substring(EXCHANGE_PREFIX.length()).toUpperCase(Locale.ROOT)));
            }
            // The remaining connectors hold at most one session per member, so the kind alone
            // identifies the connection -- several accounts can share it (Amundi plans,
            // TR's cash + securities pair), which is exactly what hasOtherLiveAccount checks.
            if (externalId.startsWith(AMUNDI_PREFIX)) return singleton(Kind.AMUNDI);
            if (externalId.startsWith(TR_PREFIX)) return singleton(Kind.TRADE_REPUBLIC);
            if (externalId.startsWith(BOURSE_DIRECT_PREFIX)) return singleton(Kind.BOURSE_DIRECT);
            if (externalId.startsWith(BOURSO_PREFIX)) return singleton(Kind.BOURSO);
            if (externalId.startsWith(FORTUNEO_PREFIX)) return singleton(Kind.FORTUNEO);
            if (externalId.startsWith(IBKR_PREFIX)) return singleton(Kind.IBKR);
            if (externalId.equals(DEGIRO_EXTERNAL_ID)) return singleton(Kind.DEGIRO);
        }

        return Optional.empty();
    }

    private static Optional<ConnectionRef> singleton(Kind kind) {
        return Optional.of(new ConnectionRef(kind, ""));
    }

    /**
     * Any other live account on the same connection?
     *
     * <p>Resolved through {@link #resolve} rather than a prefix query so "same connection"
     * has exactly one definition. A {@code LIKE 'bd\_%'} would also be a trap: an unescaped
     * underscore is a single-character wildcard, and Enable Banking's opaque ids are free to
     * start with any three characters.
     */
    private boolean hasOtherLiveAccount(ConnectionRef ref, Long deletedAccountId, Long memberId) {
        return accountRepository.findAllByMemberIdOrderByCreatedAtAsc(memberId).stream()
            .filter(other -> !other.getId().equals(deletedAccountId))
            .anyMatch(other -> resolve(other).filter(ref::equals).isPresent());
    }

    private void removeConnection(ConnectionRef ref, Long memberId) {
        log.info("Removing {} connection {} -- its last account was deleted", ref.kind(), ref.discriminator());
        switch (ref.kind()) {
            case WALLET -> walletRepository
                .findByIdAndMemberId(Long.valueOf(ref.discriminator()), memberId)
                .ifPresent(w -> walletSyncService.removeWallet(w.getId(), memberId));
            case EXCHANGE -> exchangeSession(ref, memberId)
                .ifPresent(s -> cryptoExchangeSyncService.removeExchange(s.getId(), memberId));
            case AMUNDI -> amundiSyncService.clearSession(memberId);
            case TRADE_REPUBLIC -> tradeRepublicSyncService.clearSession(memberId);
            case BOURSE_DIRECT -> bourseDirectSyncService.clearSession(memberId);
            case BOURSO -> boursoSyncService.clearSession(memberId);
            case FORTUNEO -> fortuneoSyncService.clearSession(memberId);
            case DEGIRO -> degiroSyncService.clearSession(memberId);
            case IBKR -> ibkrSyncService.deleteConnection(memberId);
            case ENABLE_BANKING -> syncService.deleteRequisition(Long.valueOf(ref.discriminator()), memberId);
        }
    }

    /** Human name for the connection, so the confirmation can say what it is about to remove. */
    private String label(ConnectionRef ref, Account account, Long memberId) {
        return switch (ref.kind()) {
            case WALLET -> walletRepository
                .findByIdAndMemberId(Long.valueOf(ref.discriminator()), memberId)
                .map(AccountConnectionService::walletLabel)
                .orElse(account.getName());
            case EXCHANGE -> ref.discriminator();
            case AMUNDI -> "Amundi";
            case TRADE_REPUBLIC -> "Trade Republic";
            case BOURSE_DIRECT -> "Bourse Direct";
            case BOURSO -> "BoursoBank";
            case FORTUNEO -> "Fortuneo";
            case DEGIRO -> "DEGIRO";
            case IBKR -> "Interactive Brokers";
            case ENABLE_BANKING -> requisitionRepository
                .findByIdAndMemberId(Long.valueOf(ref.discriminator()), memberId)
                .map(r -> r.getInstitutionName())
                .orElseGet(account::getProvider);
        };
    }

    private static String walletLabel(WalletAddress wallet) {
        return wallet.getLabel() != null ? wallet.getLabel() : wallet.getChain().name() + " Wallet";
    }

    private Optional<CryptoExchangeSession> exchangeSession(ConnectionRef ref, Long memberId) {
        try {
            return exchangeSessionRepository
                .findByExchangeTypeAndMemberId(ExchangeType.valueOf(ref.discriminator()), memberId);
        } catch (IllegalArgumentException ex) {
            // An external id naming an exchange this build no longer knows. Nothing to remove,
            // and failing the deletion over it would trap the account.
            log.warn("Unknown exchange type in external id: {}", ref.discriminator());
            return Optional.empty();
        }
    }
}

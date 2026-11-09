package com.picsou.service;

import com.picsou.exception.ResourceNotFoundException;
import com.picsou.model.Account;
import com.picsou.model.CryptoExchangeSession;
import com.picsou.model.ExchangeType;
import com.picsou.model.WalletAddress;
import com.picsou.repository.AccountRepository;
import com.picsou.repository.CryptoExchangeSessionRepository;
import com.picsou.repository.RequisitionRepository;
import com.picsou.repository.ScpiPositionRepository;
import com.picsou.repository.WalletAddressRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

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
    private static final String CMES_PREFIX = "cmes_";
    private static final String CIC_PREFIX = "cic_";
    private static final String NATIXIS_PREFIX = "natixis_";
    private static final String TR_PREFIX = "tr_";
    private static final String BOURSE_DIRECT_PREFIX = "bd_";
    private static final String BOURSO_PREFIX = "bourso_";
    private static final String FORTUNEO_PREFIX = "ft_";
    private static final String IBKR_PREFIX = "ibkr_";
    private static final String SIMPLEFIN_PREFIX = "sfin_";
    private static final String DEGIRO_EXTERNAL_ID = "degiro-portfolio";
    private static final String CORUM_LIFE_PREFIX = Account.CORUM_LIFE_EXTERNAL_ID_PREFIX;
    private static final String LBP_PREFIX = "lbp_";
    private static final String BNP_PREFIX = "bnp_";

    /** One connection instance. {@code discriminator} separates two of the same kind. */
    public record ConnectionRef(Kind kind, String discriminator) {}

    public enum Kind { WALLET, EXCHANGE, AMUNDI, CMES, CIC, TRADE_REPUBLIC, BOURSE_DIRECT, BOURSO, FORTUNEO, IBKR, SIMPLEFIN, WISE, DEGIRO, CORUM, NATIXIS, LBP, BNP, ENABLE_BANKING }

    /**
     * What deleting an account would cost ({@link #describeDeletion}, a prediction) or what it
     * actually removed ({@link #deleteAccount}).
     */
    public record DeletionImpact(boolean removesConnection, String connectionLabel) {}

    private final AccountRepository accountRepository;
    private final AccountService accountService;
    private final WalletAddressRepository walletRepository;
    private final CryptoExchangeSessionRepository exchangeSessionRepository;
    private final RequisitionRepository requisitionRepository;
    private final ScpiPositionRepository scpiPositionRepository;
    private final WalletSyncService walletSyncService;
    private final CryptoExchangeSyncService cryptoExchangeSyncService;
    private final AmundiSyncService amundiSyncService;
    private final CmesSyncService cmesSyncService;
    private final CicSyncService cicSyncService;
    private final NatixisSyncService natixisSyncService;
    private final TradeRepublicSyncService tradeRepublicSyncService;
    private final BourseDirectSyncService bourseDirectSyncService;
    private final BoursoSyncService boursoSyncService;
    private final FortuneoSyncService fortuneoSyncService;
    private final DegiroSyncService degiroSyncService;
    private final IbkrSyncService ibkrSyncService;
    private final SimplefinSyncService simplefinSyncService;
    private final WiseSyncService wiseSyncService;
    private final CorumSyncService corumSyncService;
    private final LbpSyncService lbpSyncService;
    private final BnpSyncService bnpSyncService;

    public AccountConnectionService(
        AccountRepository accountRepository,
        AccountService accountService,
        WalletAddressRepository walletRepository,
        CryptoExchangeSessionRepository exchangeSessionRepository,
        RequisitionRepository requisitionRepository,
        ScpiPositionRepository scpiPositionRepository,
        WalletSyncService walletSyncService,
        CryptoExchangeSyncService cryptoExchangeSyncService,
        AmundiSyncService amundiSyncService,
        CmesSyncService cmesSyncService,
        CicSyncService cicSyncService,
        NatixisSyncService natixisSyncService,
        TradeRepublicSyncService tradeRepublicSyncService,
        BourseDirectSyncService bourseDirectSyncService,
        BoursoSyncService boursoSyncService,
        FortuneoSyncService fortuneoSyncService,
        DegiroSyncService degiroSyncService,
        IbkrSyncService ibkrSyncService,
        SimplefinSyncService simplefinSyncService,
        WiseSyncService wiseSyncService,
        CorumSyncService corumSyncService,
        LbpSyncService lbpSyncService,
        BnpSyncService bnpSyncService
    ) {
        this.accountRepository = accountRepository;
        this.accountService = accountService;
        this.walletRepository = walletRepository;
        this.exchangeSessionRepository = exchangeSessionRepository;
        this.requisitionRepository = requisitionRepository;
        this.scpiPositionRepository = scpiPositionRepository;
        this.walletSyncService = walletSyncService;
        this.cryptoExchangeSyncService = cryptoExchangeSyncService;
        this.amundiSyncService = amundiSyncService;
        this.cmesSyncService = cmesSyncService;
        this.cicSyncService = cicSyncService;
        this.natixisSyncService = natixisSyncService;
        this.tradeRepublicSyncService = tradeRepublicSyncService;
        this.bourseDirectSyncService = bourseDirectSyncService;
        this.boursoSyncService = boursoSyncService;
        this.fortuneoSyncService = fortuneoSyncService;
        this.degiroSyncService = degiroSyncService;
        this.ibkrSyncService = ibkrSyncService;
        this.simplefinSyncService = simplefinSyncService;
        this.wiseSyncService = wiseSyncService;
        this.corumSyncService = corumSyncService;
        this.lbpSyncService = lbpSyncService;
        this.bnpSyncService = bnpSyncService;
    }

    /**
     * Soft-deletes the account, then removes its connection when nothing else is left on it.
     *
     * <p>Order matters: the account is deleted first so a connector that runs concurrently
     * finds the soft-deleted row and refuses to rebuild it, rather than racing the removal.
     *
     * <p>The returned impact reports a removal only when a wallet row, exchange session, stored
     * session or requisition was actually there to remove.
     */
    public DeletionImpact deleteAccount(Long accountId, Long memberId) {
        Account account = accountRepository.findByIdAndMemberId(accountId, memberId)
            .orElseThrow(() -> new ResourceNotFoundException("Account not found"));
        // Read before the delete: AccountService.delete unlinks the SCPI position, and the link
        // is the only thing that ties a CORUM SCPI account to its connection.
        Set<Long> corumScpiAccountIds = corumScpiAccountIds(memberId);
        Optional<ConnectionRef> connection = resolve(account, corumScpiAccountIds);

        if (connection.filter(ref -> ref.kind() == Kind.CORUM).isPresent()) {
            // The CORUM sync takes the member lock, then writes the accounts; taking it here
            // before the account write keeps the same order, so the two cannot deadlock.
            corumSyncService.lockMemberForAccountDeletion(memberId);
            // Re-read under the lock: a sync that committed in between may have linked
            // another SCPI account, which must keep the session.
            corumScpiAccountIds = corumScpiAccountIds(memberId);
        }
        accountService.delete(accountId, memberId);

        if (connection.isEmpty()
                || hasOtherLiveAccount(connection.get(), accountId, memberId, corumScpiAccountIds)) {
            return new DeletionImpact(false, null);
        }

        ConnectionRef ref = connection.get();
        String connectionLabel = label(ref, account, memberId);
        return removeConnection(ref, memberId)
            ? new DeletionImpact(true, connectionLabel)
            : new DeletionImpact(false, null);
    }

    /**
     * Preview: whether deleting this account would also remove its connection, and which one.
     * A prediction, not a promise -- {@link #deleteAccount} reports what actually happened, which
     * differs if a sibling account appears or the connection is already gone in between.
     */
    @Transactional(readOnly = true)
    public DeletionImpact describeDeletion(Long accountId, Long memberId) {
        Account account = accountRepository.findByIdAndMemberId(accountId, memberId)
            .orElseThrow(() -> new ResourceNotFoundException("Account not found"));

        Set<Long> corumScpiAccountIds = corumScpiAccountIds(memberId);
        return resolve(account, corumScpiAccountIds)
            .filter(ref -> !hasOtherLiveAccount(ref, accountId, memberId, corumScpiAccountIds))
            .map(ref -> new DeletionImpact(true, label(ref, account, memberId)))
            .orElseGet(() -> new DeletionImpact(false, null));
    }

    /**
     * Which connection an account came from, if any. Manual accounts have none, and neither do
     * Enable Banking rows that predate V76 whose backfill could not be attributed with
     * certainty -- those simply keep their connection, the safe direction to fail.
     */
    private Optional<ConnectionRef> resolve(Account account, Set<Long> corumScpiAccountIds) {
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
            if (externalId.startsWith(CMES_PREFIX)) return singleton(Kind.CMES);
            if (externalId.startsWith(CIC_PREFIX)) return singleton(Kind.CIC);
            if (externalId.startsWith(NATIXIS_PREFIX)) return singleton(Kind.NATIXIS);
            if (externalId.startsWith(TR_PREFIX)) return singleton(Kind.TRADE_REPUBLIC);
            if (externalId.startsWith(BOURSE_DIRECT_PREFIX)) return singleton(Kind.BOURSE_DIRECT);
            if (externalId.startsWith(BOURSO_PREFIX)) return singleton(Kind.BOURSO);
            if (externalId.startsWith(FORTUNEO_PREFIX)) return singleton(Kind.FORTUNEO);
            if (externalId.startsWith(IBKR_PREFIX)) return singleton(Kind.IBKR);
            if (externalId.startsWith(SIMPLEFIN_PREFIX)) return singleton(Kind.SIMPLEFIN);
            if (externalId.startsWith(WiseSyncService.EXTERNAL_ID_PREFIX)) return singleton(Kind.WISE);
            if (externalId.equals(DEGIRO_EXTERNAL_ID)) return singleton(Kind.DEGIRO);
            if (externalId.startsWith(CORUM_LIFE_PREFIX)) return singleton(Kind.CORUM);
            if (externalId.startsWith(LBP_PREFIX)) return singleton(Kind.LBP);
            if (externalId.startsWith(BNP_PREFIX)) return singleton(Kind.BNP);
        }

        // The CORUM login also syncs SCPI funds, onto manual accounts with no external id:
        // only their position's CORUM fund code ties them to the connection.
        if (corumScpiAccountIds.contains(account.getId())) return singleton(Kind.CORUM);

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
    private boolean hasOtherLiveAccount(
        ConnectionRef ref, Long deletedAccountId, Long memberId, Set<Long> corumScpiAccountIds
    ) {
        return accountRepository.findAllByMemberIdOrderByCreatedAtAsc(memberId).stream()
            .filter(other -> !other.getId().equals(deletedAccountId))
            .anyMatch(other -> resolve(other, corumScpiAccountIds).filter(ref::equals).isPresent());
    }

    /**
     * Ids of the member's accounts whose SCPI position is linked to a CORUM fund. Only matched
     * against the live accounts {@link #hasOtherLiveAccount} loads, so a position left on a
     * soft-deleted account (before the delete cleared its link) keeps nothing alive.
     */
    private Set<Long> corumScpiAccountIds(Long memberId) {
        return scpiPositionRepository.findByAccountMemberIdAndCorumFundCodeIsNotNull(memberId).stream()
            .map(position -> position.getAccount().getId())
            .collect(Collectors.toSet());
    }

    /** Returns whether there was anything left to remove. */
    private boolean removeConnection(ConnectionRef ref, Long memberId) {
        log.info("Removing {} connection {} -- its last account was deleted", ref.kind(), ref.discriminator());
        return switch (ref.kind()) {
            case WALLET -> walletRepository
                .findByIdAndMemberId(Long.valueOf(ref.discriminator()), memberId)
                .map(w -> {
                    walletSyncService.removeWallet(w.getId(), memberId);
                    return true;
                })
                .orElse(false);
            case EXCHANGE -> exchangeSession(ref, memberId)
                .map(s -> {
                    cryptoExchangeSyncService.removeExchange(s.getId(), memberId);
                    return true;
                })
                .orElse(false);
            case AMUNDI -> amundiSyncService.clearSession(memberId);
            case CMES -> cmesSyncService.clearSession(memberId);
            case CIC -> cicSyncService.clearSession(memberId);
            case NATIXIS -> natixisSyncService.clearSession(memberId);
            case TRADE_REPUBLIC -> tradeRepublicSyncService.clearSession(memberId);
            case BOURSE_DIRECT -> bourseDirectSyncService.clearSession(memberId);
            case BOURSO -> boursoSyncService.clearSession(memberId);
            case FORTUNEO -> fortuneoSyncService.clearSession(memberId);
            case DEGIRO -> degiroSyncService.clearSession(memberId);
            case CORUM -> corumSyncService.clearSession(memberId);
            case LBP -> lbpSyncService.clearSession(memberId);
            case BNP -> bnpSyncService.clearSession(memberId);
            case IBKR -> ibkrSyncService.deleteConnection(memberId);
            case SIMPLEFIN -> simplefinSyncService.deleteConnection(memberId);
            case WISE -> wiseSyncService.deleteConnection(memberId);
            // Not SyncService.deleteRequisition: it throws on a missing requisition, and one
            // removed concurrently (DELETE /api/sync/requisitions/{id}) would roll back the
            // account deletion with it.
            case ENABLE_BANKING -> requisitionRepository
                .deleteByIdAndMemberId(Long.valueOf(ref.discriminator()), memberId) > 0;
        };
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
            case CMES -> "Crédit Mutuel / CIC Épargne Salariale";
            case CIC -> "CIC";
            case NATIXIS -> "Natixis Interépargne";
            case TRADE_REPUBLIC -> "Trade Republic";
            case BOURSE_DIRECT -> "Bourse Direct";
            case BOURSO -> "BoursoBank";
            case FORTUNEO -> "Fortuneo";
            case DEGIRO -> "DEGIRO";
            case CORUM -> "CORUM";
            case LBP -> "La Banque Postale";
            case BNP -> "BNP Paribas";
            case IBKR -> "Interactive Brokers";
            case SIMPLEFIN -> "SimpleFIN";
            case WISE -> "Wise Jars";
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

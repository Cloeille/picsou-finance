package com.picsou.service;

import com.picsou.dto.FinaryAutoSyncResponse;
import com.picsou.finary.FinaryApiSyncService;
import com.picsou.model.Account;
import com.picsou.model.AccountType;
import com.picsou.model.BalanceSnapshot;
import com.picsou.model.FamilyMember;
import com.picsou.repository.AccountHoldingRepository;
import com.picsou.repository.AccountRepository;
import com.picsou.repository.BalanceSnapshotRepository;
import com.picsou.repository.FamilyMemberRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

@Service
public class SchedulerService {

    private static final Logger log = LoggerFactory.getLogger(SchedulerService.class);

    private final AccountRepository accountRepository;
    private final AccountHoldingRepository holdingRepository;
    private final BalanceSnapshotRepository snapshotRepository;
    private final FamilyMemberRepository familyMemberRepository;
    private final AccountService accountService;
    private final SyncService syncService;
    private final TradeRepublicSyncService trSyncService;
    private final BoursoSyncService boursoSyncService;
    private final RevolutSyncService revolutSyncService;
    private final BourseDirectSyncService bourseDirectSyncService;
    private final AmundiSyncService amundiSyncService;
    /**
     * How many profiles one weekly pass will resolve. Each one is up to three requests against
     * an unofficial page and a rate-limited free tier, so the pass is bounded rather than
     * proportional to the portfolio; a household's tickers are covered in a week or two, and
     * everything unresolved simply reports as unclassified meanwhile.
     */
    private static final int SECURITY_PROFILE_BATCH = 40;

    private final PriceService priceService;
    private final SecurityProfileService securityProfileService;
    private final CryptoExchangeSyncService cryptoExchangeSyncService;
    private final WalletSyncService walletSyncService;
    private final FinaryApiSyncService finaryApiSyncService;
    private final IbkrSyncService ibkrSyncService;
    private final PropertyValuationService propertyValuationService;

    public SchedulerService(
        AccountRepository accountRepository,
        AccountHoldingRepository holdingRepository,
        BalanceSnapshotRepository snapshotRepository,
        FamilyMemberRepository familyMemberRepository,
        AccountService accountService,
        SyncService syncService,
        TradeRepublicSyncService trSyncService,
        BoursoSyncService boursoSyncService,
        RevolutSyncService revolutSyncService,
        BourseDirectSyncService bourseDirectSyncService,
        AmundiSyncService amundiSyncService,
        PriceService priceService,
        CryptoExchangeSyncService cryptoExchangeSyncService,
        WalletSyncService walletSyncService,
        FinaryApiSyncService finaryApiSyncService,
        IbkrSyncService ibkrSyncService,
        PropertyValuationService propertyValuationService,
                            SecurityProfileService securityProfileService) {
        this.accountRepository = accountRepository;
        this.holdingRepository = holdingRepository;
        this.snapshotRepository = snapshotRepository;
        this.familyMemberRepository = familyMemberRepository;
        this.accountService = accountService;
        this.syncService = syncService;
        this.trSyncService = trSyncService;
        this.boursoSyncService = boursoSyncService;
        this.revolutSyncService = revolutSyncService;
        this.bourseDirectSyncService = bourseDirectSyncService;
        this.amundiSyncService = amundiSyncService;
        this.priceService = priceService;
        this.cryptoExchangeSyncService = cryptoExchangeSyncService;
        this.walletSyncService = walletSyncService;
        this.finaryApiSyncService = finaryApiSyncService;
        this.ibkrSyncService = ibkrSyncService;
        this.propertyValuationService = propertyValuationService;
        this.securityProfileService = securityProfileService;
    }

    /**
     * Monthly on the 1st at 03:30: re-value every property from open data.
     *
     * <p>Monthly, not daily, because the inputs simply do not move faster: DVF is published
     * twice a year and the INSEE index is quarterly. A nightly run would produce identical
     * figures while hammering a service that is still on a preprod host with no documented
     * rate limits. Users who want a figure sooner have the manual refresh button.
     */
    @Scheduled(cron = "0 30 3 1 * *")
    public void monthlyPropertyValuation() {
        log.info("Refreshing property valuations for all members");
        List<FamilyMember> members = familyMemberRepository.findAllByOrderByCreatedAtAsc();

        for (FamilyMember member : members) {
            // Guard per member, like dailyBankSync: one member's unreachable commune must
            // not stop everyone else's properties from being revalued.
            try {
                int refreshed = propertyValuationService.refreshAllForMember(member.getId());
                if (refreshed > 0) {
                    log.info("Revalued {} propertie(s) for member {}", refreshed, member.getId());
                }
            } catch (Exception ex) {
                log.error("Property valuation failed for member {} -- skipping it", member.getId(), ex);
            }
        }
    }

    /**
     * Daily at 08:00: Re-sync all linked bank accounts for every family member
     * (Enable Banking + Trade Republic + Crypto Exchanges + Wallets).
     */
    @Scheduled(cron = "0 0 8 * * *")
    public void dailyBankSync() {
        log.info("Starting daily bank sync for all members");
        List<FamilyMember> members = familyMemberRepository.findAllByOrderByCreatedAtAsc();

        for (FamilyMember member : members) {
            Long memberId = member.getId();
            log.info("Syncing member {}", memberId);

            // Revolut sidecar is the primary source for Revolut assets; it runs first so it owns
            // the IBAN row before Enable Banking syncs (fallback -- matches by IBAN and only
            // refreshes balance/uid, never overwriting provenance -- see SyncService.upsertAccount).
            revolutSyncService.resyncIfSessionActive(memberId);

            try {
                syncService.resyncAll(memberId);
            } catch (Exception ex) {
                log.error("Daily Enable Banking sync failed for member {}", memberId, ex);
            }

            try {
                syncService.retryAllFailed(memberId);
            } catch (Exception ex) {
                log.error("Daily retry of FAILED Enable Banking sessions failed for member {}", memberId, ex);
            }

            trSyncService.resyncIfSessionActive(memberId);
            boursoSyncService.resyncIfSessionActive(memberId);
            bourseDirectSyncService.resyncIfSessionActive(memberId);
            amundiSyncService.resyncIfSessionActive(memberId);

            try {
                ibkrSyncService.resyncIfConnected(memberId);
            } catch (Exception ex) {
                // resyncIfConnected swallows sync failures itself, but Spring can still
                // throw UnexpectedRollbackException AT THE PROXY EXIT: a repository call
                // failing inside the sync marks the shared transaction rollback-only
                // through the repository's own proxy, and the commit attempt happens
                // after the method's internal catch. Without this wrapper that breaks
                // the loop for every remaining member.
                log.error("Daily IBKR auto-sync failed for member {}", memberId, ex);
            }

            try {
                cryptoExchangeSyncService.resyncAll(memberId);
            } catch (Exception ex) {
                log.error("Daily crypto exchange sync failed for member {}", memberId, ex);
            }

            try {
                WalletSyncService.ResyncSummary walletSummary = walletSyncService.resyncAll(memberId);
                if (!walletSummary.failed().isEmpty()) {
                    log.warn("Daily wallet sync for member {}: {}/{} succeeded, failed chains: {}",
                        memberId, walletSummary.succeeded(), walletSummary.total(), walletSummary.failed());
                }
            } catch (Exception ex) {
                log.error("Daily wallet sync failed for member {}", memberId, ex);
            }

            try {
                FinaryAutoSyncResponse finaryResult = finaryApiSyncService.autoSync(memberId);
                if ("NEEDS_MAPPING".equals(finaryResult.status())) {
                    log.info("Finary auto-sync for member {} found new accounts, manual mapping required", memberId);
                } else if ("OK".equals(finaryResult.status())) {
                    log.info("Finary auto-sync completed for member {}: {} accounts synced", memberId, finaryResult.accountsSynced());
                } else if ("TOTP_REQUIRED".equals(finaryResult.status())) {
                    log.warn("Finary auto-sync for member {} requires TOTP — user must re-authenticate", memberId);
                }
            } catch (Exception ex) {
                log.error("Daily Finary auto-sync failed for member {}", memberId, ex);
            }
        }
    }

    /**
     * Daily at 08:05: Take a balance snapshot for all accounts.
     */
    @Scheduled(cron = "0 5 8 * * *")
    @Transactional
    public void dailySnapshots() {
        log.info("Taking daily snapshots for all accounts");
        LocalDate today = LocalDate.now();
        List<FamilyMember> members = familyMemberRepository.findAllByOrderByCreatedAtAsc();

        for (FamilyMember member : members) {
            List<Account> memberAccounts = accountRepository.findAllByMemberIdOrderByCreatedAtAsc(member.getId());

            for (Account account : memberAccounts) {
                // Guard per account. This method is @Transactional, so without it a single
                // failing price lookup would not merely skip one account -- it would abort
                // every remaining account AND member, and roll back the snapshots already
                // saved in this run. A missing snapshot for one account is recoverable;
                // losing the whole day's is not.
                try {
                    Optional<BalanceSnapshot> existing = snapshotRepository.findByAccountIdAndDate(account.getId(), today);
                    if (existing.isEmpty()) {
                        // One valuation, not two: liveBalanceEur and calculateInvestedAmount each
                        // run the whole pass, and two passes can straddle a price-cache change --
                        // the value excluding an asset the cost basis then includes is exactly
                        // the disagreement that reported an untouched account as an 85% loss.
                        AccountService.Valuation valuation = accountService.valuation(account);
                        if (!valuation.anyPriced()) {
                            // Not a small balance -- a blank one. Writing it stamps a permanent
                            // dip into the net-worth chart that no later sync goes back to fix,
                            // for what is usually a transient provider outage. Same refusal as
                            // WalletSyncService and CryptoExchangeSyncService on the write path.
                            log.warn("No EUR price for any holding of account {} (member {}) -- "
                                + "skipping today's snapshot rather than recording a zero",
                                account.getId(), member.getId());
                            continue;
                        }
                        snapshotRepository.save(BalanceSnapshot.builder()
                            .account(account)
                            .date(today)
                            .balance(valuation.liveEur())
                            .investedAmount(valuation.investedEur())
                            .build());
                    }
                } catch (Exception ex) {
                    // ERROR, not WARN: the price adapters swallow expected upstream failures
                    // and return no prices, so anything reaching here is a genuine bug. Logging
                    // it at WARN would re-hide exactly what CoinGeckoPriceProvider now rethrows
                    // to make visible. Skipping still matters -- this method is @Transactional,
                    // so aborting would roll back the snapshots already written this run.
                    log.error("Daily snapshot failed for account {} (member {}) -- skipping it",
                        account.getId(), member.getId(), ex);
                }
            }
        }

        log.debug("Snapshots taken for all members");
    }

    /**
     * Every hour: refresh the prices of everything anyone holds, in one pass.
     *
     * <p>Both halves of that sentence are corrections of earlier behaviour.
     *
     * <p><b>Everything held.</b> This used to warm only {@code account.ticker} — the symbol on
     * accounts that <em>are</em> one asset. A brokerage, exchange or wallet account carries its
     * assets in {@code account_holding} and has no account-level ticker at all, so exactly the
     * tickers the dashboard prices most often were the ones never warmed. Their cache entries
     * expired 15 minutes after each sync and every later page render went back to the network.
     *
     * <p><b>One pass.</b> Prices are global — the cache, {@code price_snapshot} and the providers
     * know nothing about members — so iterating members re-fetched shared tickers once per member.
     * A single set means a single provider round-trip, which matters against a free tier that
     * answers bursts with 429s.
     */
    @Scheduled(fixedDelay = 3600000)
    public void refreshPrices() {
        // Crypto first, and kept apart: refreshPrices sends anything CoinGecko cannot map to
        // Yahoo Finance, which for a coin sharing its symbol with a listed equity (the
        // SUI/ATOM/TIA collision the codebase warns about repeatedly) returns that company's
        // share price — and refreshPrices *records* what it fetches in price_snapshot, so a
        // single hourly tick would poison the table the last-known-price fallback reads from.
        // Before holding tickers were warmed here, no crypto symbol ever reached this method.
        // Account-level tickers too, not just holdings: an account that *is* one asset (a manual
        // crypto account tracking BTC, say) carries its symbol on the account row and has no
        // holdings at all, so reading holdings alone sent it down the Yahoo Finance branch below.
        Set<String> cryptoTickers = new TreeSet<>(
            holdingRepository.findDistinctTickersByAccountType(AccountType.CRYPTO));
        cryptoTickers.addAll(accountRepository.findDistinctTickersByType(AccountType.CRYPTO));

        Set<String> otherTickers = new TreeSet<>(
            accountRepository.findDistinctTickersExcludingType(AccountType.CRYPTO));
        otherTickers.addAll(holdingRepository.findDistinctTickers());
        otherTickers.removeAll(cryptoTickers);

        if (cryptoTickers.isEmpty() && otherTickers.isEmpty()) return;

        log.debug("Refreshing prices for {} crypto and {} other tickers",
            cryptoTickers.size(), otherTickers.size());
        try {
            if (!cryptoTickers.isEmpty()) priceService.refreshCryptoPrices(cryptoTickers);
            if (!otherTickers.isEmpty()) priceService.refreshPrices(otherTickers);
        } catch (Exception ex) {
            // ERROR for the same reason as dailySnapshots above: expected outages never
            // reach here, so this is a bug worth surfacing.
            log.error("Price refresh failed -- skipping this cycle", ex);
        }
    }

    /**
     * Every Sunday night: keep the security profiles the diversification breakdown reads from
     * warm, so nothing is ever scraped while a page is rendering.
     *
     * <p>Shaped like {@link #refreshPrices} and for the same reason — a profile is global, so one
     * pass over the distinct tickers beats one pass per member. It differs in cadence and in
     * caution: a sector does not change, the sources are unofficial HTML and a rate-limited free
     * tier, so the batch is capped and one bad ticker only loses itself.
     *
     * <p>A ticker nobody has warmed yet is not an error. It reports as unclassified in the
     * breakdown, with the uncovered share stated, until the next pass picks it up.
     */
    @Scheduled(cron = "0 45 3 * * SUN")
    public void refreshSecurityProfiles() {
        Set<String> tickers = new TreeSet<>(holdingRepository.findDistinctTickers());
        if (tickers.isEmpty()) return;

        try {
            int refreshed = securityProfileService.refreshStale(tickers, SECURITY_PROFILE_BATCH);
            log.info("Security profiles: refreshed {} of {} known tickers", refreshed, tickers.size());
        } catch (Exception ex) {
            log.error("Security profile refresh failed -- skipping this cycle", ex);
        }
    }
}

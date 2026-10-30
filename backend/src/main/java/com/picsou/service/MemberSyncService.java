package com.picsou.service;

import com.picsou.dto.FinaryAutoSyncResponse;
import com.picsou.finary.FinaryApiSyncService;
import com.picsou.service.sync.SourceSyncResult;
import com.picsou.service.sync.SourceSyncResult.Status;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

@Service
public class MemberSyncService {

    private static final Logger log = LoggerFactory.getLogger(MemberSyncService.class);

    // Revolut owns the IBAN row before Enable Banking. The fallback matches by IBAN
    // and only refreshes balance/uid — SyncService.upsertAccount does not overwrite provenance.
    private static final List<String> CANONICAL_ORDER = List.of(
        "revolut",
        "enable-banking",
        "enable-banking-retry",
        "trade-republic",
        "bourso",
        "bourse-direct",
        "amundi",
        "fortuneo",
        "amex",
        "ibkr",
        SimplefinSyncService.SOURCE,
        "crypto-exchanges",
        "wallets",
        "finary",
        "degiro"
    );

    private final RevolutSyncService revolutSyncService;
    private final SyncService syncService;
    private final TradeRepublicSyncService trSyncService;
    private final BoursoSyncService boursoSyncService;
    private final BourseDirectSyncService bourseDirectSyncService;
    private final AmundiSyncService amundiSyncService;
    private final FortuneoSyncService fortuneoSyncService;
    private final AmexSyncService amexSyncService;
    private final IbkrSyncService ibkrSyncService;
    private final SimplefinSyncService simplefinSyncService;
    private final CryptoExchangeSyncService cryptoExchangeSyncService;
    private final WalletSyncService walletSyncService;
    private final FinaryApiSyncService finaryApiSyncService;
    private final DegiroSyncService degiroSyncService;

    public MemberSyncService(
            RevolutSyncService revolutSyncService,
            SyncService syncService,
            TradeRepublicSyncService trSyncService,
            BoursoSyncService boursoSyncService,
            BourseDirectSyncService bourseDirectSyncService,
            AmundiSyncService amundiSyncService,
            FortuneoSyncService fortuneoSyncService,
            AmexSyncService amexSyncService,
            IbkrSyncService ibkrSyncService,
            SimplefinSyncService simplefinSyncService,
            CryptoExchangeSyncService cryptoExchangeSyncService,
            WalletSyncService walletSyncService,
            FinaryApiSyncService finaryApiSyncService,
            DegiroSyncService degiroSyncService) {
        this.revolutSyncService = revolutSyncService;
        this.syncService = syncService;
        this.trSyncService = trSyncService;
        this.boursoSyncService = boursoSyncService;
        this.bourseDirectSyncService = bourseDirectSyncService;
        this.amundiSyncService = amundiSyncService;
        this.fortuneoSyncService = fortuneoSyncService;
        this.amexSyncService = amexSyncService;
        this.ibkrSyncService = ibkrSyncService;
        this.simplefinSyncService = simplefinSyncService;
        this.cryptoExchangeSyncService = cryptoExchangeSyncService;
        this.walletSyncService = walletSyncService;
        this.finaryApiSyncService = finaryApiSyncService;
        this.degiroSyncService = degiroSyncService;
    }

    public List<SourceSyncResult> resyncScheduled(Long memberId) {
        List<SourceSyncResult> results = new ArrayList<>();
        for (String source : CANONICAL_ORDER) {
            if ("degiro".equals(source)) {
                // never call sync for scheduled; hardcoded skipped
                results.add(new SourceSyncResult("degiro", Status.SKIPPED, "Unattended DEGIRO sync is disabled"));
                continue;
            }
            SourceSyncResult r = executeSource(source, memberId);
            results.add(r);
        }
        return results;
    }

    public List<SourceSyncResult> resyncForUser(Long memberId) {
        List<SourceSyncResult> results = new ArrayList<>();
        for (String source : CANONICAL_ORDER) {
            SourceSyncResult r = executeSource(source, memberId);
            results.add(r);
        }
        return results;
    }

    public List<SourceSyncResult> resyncForUser(Long memberId, Set<String> sources) {
        List<SourceSyncResult> results = new ArrayList<>();
        for (String source : CANONICAL_ORDER) {
            if (sources == null || sources.contains(source)) {
                SourceSyncResult r = executeSource(source, memberId);
                results.add(r);
            }
        }
        return results;
    }

    private SourceSyncResult executeSource(String source, Long memberId) {
        try {
            switch (source) {
                case "revolut":
                    return revolutSyncService.resyncReporting(memberId);
                case "enable-banking":
                    return syncService.resyncAllReporting(memberId);
                case "enable-banking-retry":
                    return syncService.retryFailedReporting(memberId);
                case "trade-republic":
                    return trSyncService.resyncReporting(memberId);
                case "bourso":
                    return boursoSyncService.resyncReporting(memberId);
                case "bourse-direct":
                    return bourseDirectSyncService.resyncReporting(memberId);
                case "amundi":
                    return amundiSyncService.resyncReporting(memberId);
                case "fortuneo":
                    return fortuneoSyncService.resyncReporting(memberId);
                case "amex":
                    return amexSyncService.resyncReporting(memberId);
                case "ibkr":
                    try {
                        return ibkrSyncService.resyncReporting(memberId);
                    } catch (Exception ex) {
                        // extra wrapper in Member for proxy exit exceptions like UnexpectedRollback
                        if (ex instanceof org.springframework.transaction.UnexpectedRollbackException
                                || ex.getClass().getName().contains("UnexpectedRollback")) {
                            log.error("IBKR sync failed unexpectedly for member {}", memberId, ex);
                            return new SourceSyncResult("ibkr", Status.FAILED, "Unexpected rollback");
                        }
                        throw ex;
                    }
                case SimplefinSyncService.SOURCE:
                    return simplefinSyncService.resyncReporting(memberId);
                case "crypto-exchanges":
                    return cryptoExchangeSyncService.resyncAllReporting(memberId);
                case "wallets":
                    WalletSyncService.ResyncSummary summary = walletSyncService.resyncAll(memberId);
                    if (summary.total() == 0) {
                        return new SourceSyncResult("wallets", Status.SKIPPED_NOT_CONNECTED, "No wallets");
                    }
                    if (!summary.failed().isEmpty()) {
                        return new SourceSyncResult("wallets", Status.FAILED, "Some wallet chains failed");
                    }
                    return new SourceSyncResult("wallets", Status.SYNCED, "");
                case "finary":
                    try {
                        FinaryAutoSyncResponse resp = finaryApiSyncService.autoSync(memberId);
                        return mapFinary(resp);
                    } catch (Exception ex) {
                        log.error("Finary sync failed unexpectedly for member {}", memberId, ex);
                        return new SourceSyncResult("finary", Status.FAILED, "Unexpected sync error");
                    }
                case "degiro":
                    // for scheduled we handled above; for user path
                    return degiroSyncService.userSyncReporting(memberId);
                default:
                    return new SourceSyncResult(source, Status.SKIPPED, "Unknown source");
            }
        } catch (Exception ex) {
            // never let one source stop others
            log.error("{} sync failed unexpectedly for member {}", source, memberId, ex);
            return new SourceSyncResult(source, Status.FAILED, "Unexpected sync error");
        }
    }

    private SourceSyncResult mapFinary(FinaryAutoSyncResponse resp) {
        String status = resp.status();
        if ("OK".equals(status)) {
            return new SourceSyncResult("finary", Status.SYNCED, "");
        }
        if ("TOTP_REQUIRED".equals(status)) {
            return new SourceSyncResult("finary", Status.NEEDS_REAUTH, "TOTP required");
        }
        if ("NEEDS_MAPPING".equals(status)) {
            return new SourceSyncResult("finary", Status.SYNCED, "Manual mapping still required");
        }
        if ("NOT_CONNECTED".equals(status)) {
            return new SourceSyncResult("finary", Status.SKIPPED_NOT_CONNECTED, "No connected Finary session");
        }
        return new SourceSyncResult("finary", Status.FAILED, "Sync failed");
    }
}

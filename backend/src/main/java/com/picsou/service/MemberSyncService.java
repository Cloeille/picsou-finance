package com.picsou.service;

import com.picsou.dto.FinaryAutoSyncResponse;
import com.picsou.exception.SyncException;
import com.picsou.finary.FinaryApiSyncService;
import com.picsou.port.SidecarErrorCode;
import com.picsou.service.sync.SourceSyncResult;
import com.picsou.service.sync.SourceSyncResult.Status;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
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
        WiseSyncService.SOURCE,
        "crypto-exchanges",
        "wallets",
        "finary",
        "degiro",
        // Last so the existing sources keep their order. Re-logs in with stored
        // credentials when its short session has expired.
        "cmes",
        "cic",
        // Same: re-logs in with credentials the member chose to store.
        "corum",
        // Never logs in again by itself: an expired session waits for the saver.
        "natixis",
        // On demand only: the session holds no credentials, so an expired one can only
        // be renewed by the user on the LBP tab and reads as needing reauthentication.
        "lbp",
        // On demand and daily: the session holds no credentials, so an expired one waits for the member.
        "bnp"
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
    private final WiseSyncService wiseSyncService;
    private final CryptoExchangeSyncService cryptoExchangeSyncService;
    private final WalletSyncService walletSyncService;
    private final FinaryApiSyncService finaryApiSyncService;
    private final DegiroSyncService degiroSyncService;
    private final ReceivedDividendClassifier dividendClassifier;
    private final CmesSyncService cmesSyncService;
    private final CicSyncService cicSyncService;
    private final CorumSyncService corumSyncService;
    private final NatixisSyncService natixisSyncService;
    private final LbpSyncService lbpSyncService;
    private final BnpSyncService bnpSyncService;

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
            WiseSyncService wiseSyncService,
            CryptoExchangeSyncService cryptoExchangeSyncService,
            WalletSyncService walletSyncService,
            FinaryApiSyncService finaryApiSyncService,
            DegiroSyncService degiroSyncService,
            ReceivedDividendClassifier dividendClassifier,
            CmesSyncService cmesSyncService,
            CicSyncService cicSyncService,
            CorumSyncService corumSyncService,
            NatixisSyncService natixisSyncService,
            LbpSyncService lbpSyncService,
            BnpSyncService bnpSyncService) {
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
        this.wiseSyncService = wiseSyncService;
        this.cryptoExchangeSyncService = cryptoExchangeSyncService;
        this.walletSyncService = walletSyncService;
        this.finaryApiSyncService = finaryApiSyncService;
        this.degiroSyncService = degiroSyncService;
        this.dividendClassifier = dividendClassifier;
        this.cmesSyncService = cmesSyncService;
        this.cicSyncService = cicSyncService;
        this.corumSyncService = corumSyncService;
        this.natixisSyncService = natixisSyncService;
        this.lbpSyncService = lbpSyncService;
        this.bnpSyncService = bnpSyncService;
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
        dividendClassifier.afterImport(memberId);
        return results;
    }

    public List<SourceSyncResult> resyncForUser(Long memberId) {
        List<SourceSyncResult> results = new ArrayList<>();
        for (String source : CANONICAL_ORDER) {
            SourceSyncResult r = executeSource(source, memberId);
            results.add(r);
        }
        dividendClassifier.afterImport(memberId);
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
        dividendClassifier.afterImport(memberId);
        return results;
    }

    /**
     * Runs one source and always returns a result: an unknown source is SKIPPED and an unexpected
     * exception is FAILED, so one source never stops the others.
     */
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
                case "cmes":
                    return cmesSyncService.resyncReporting(memberId);
                case "cic":
                    return cicSyncService.resyncReporting(memberId);
                case "corum":
                    return corumSyncService.resyncReporting(memberId);
                case "natixis":
                    return natixisSyncService.resyncReporting(memberId);
                case "lbp":
                    return lbpResyncReporting(memberId);
                case "bnp":
                    return bnpSyncService.resyncReporting(memberId);
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
                case WiseSyncService.SOURCE:
                    return wiseSyncService.resyncReporting(memberId);
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

    /**
     * The LBP entry, shaped like {@link CorumSyncService#resyncReporting}: the connector owns
     * no reporting of its own, so this maps its status onto the shared contract here. An
     * active session is queued; an inactive one is reported, never retried. When the LBP
     * connector grows its own {@code resyncReporting}, this case collapses to one line.
     */
    private SourceSyncResult lbpResyncReporting(Long memberId) {
        try {
            LbpSyncService.SessionStatusResponse status = lbpSyncService.getStatus(memberId);
            if (!status.isActive()) {
                if (status.lastSyncError() == SidecarErrorCode.SESSION_EXPIRED
                    || status.lastSyncError() == SidecarErrorCode.INVALID_CREDENTIALS) {
                    return new SourceSyncResult("lbp", Status.NEEDS_REAUTH,
                        status.lastSyncError().name());
                }
                if (status.lastSyncError() != null) {
                    return new SourceSyncResult("lbp", Status.FAILED,
                        status.lastSyncError().name());
                }
                return new SourceSyncResult("lbp", Status.SKIPPED_NOT_CONNECTED, "No active session");
            }
            lbpSyncService.queueSync(memberId);
            return new SourceSyncResult("lbp", Status.QUEUED, "");
        } catch (DataAccessException ex) {
            log.error("Database error during scheduled La Banque Postale sync (member={})", memberId, ex);
            return new SourceSyncResult("lbp", Status.FAILED, "Database error");
        } catch (SyncException ex) {
            log.warn("Could not queue scheduled La Banque Postale sync (member={}; code={})", memberId, ex.getCode(), ex);
            SourceSyncResult classified = SourceSyncResult.fromSyncException("lbp", ex);
            return new SourceSyncResult("lbp", classified.status(),
                classified.status() == Status.NEEDS_REAUTH ? ex.getCode() : "Sync failed");
        } catch (Exception ex) {
            log.error("La Banque Postale scheduled sync failed unexpectedly for member {}", memberId, ex);
            return new SourceSyncResult("lbp", Status.FAILED, "Unexpected sync error");
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

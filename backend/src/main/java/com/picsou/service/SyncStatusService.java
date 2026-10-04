package com.picsou.service;

import com.picsou.model.DegiroSession;
import com.picsou.model.DegiroSessionStatus;
import com.picsou.model.FinarySession;
import com.picsou.model.IbkrConnection;
import com.picsou.model.Requisition;
import com.picsou.model.RequisitionStatus;
import com.picsou.model.WalletAddress;
import com.picsou.repository.DegiroSessionRepository;
import com.picsou.repository.FinarySessionRepository;
import com.picsou.repository.IbkrConnectionRepository;
import com.picsou.repository.RequisitionRepository;
import com.picsou.repository.TradeRepublicSessionRepository;
import com.picsou.repository.WalletAddressRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * Read-only view of the connections {@link MemberSyncService} would touch.
 * Never starts a sync and never returns credentials.
 */
@Service
public class SyncStatusService {

    private final RevolutSyncService revolutSyncService;
    private final RequisitionRepository requisitionRepository;
    private final TradeRepublicSyncService tradeRepublicSyncService;
    private final TradeRepublicSessionRepository tradeRepublicSessionRepository;
    private final BoursoSyncService boursoSyncService;
    private final BourseDirectSyncService bourseDirectSyncService;
    private final AmundiSyncService amundiSyncService;
    private final FortuneoSyncService fortuneoSyncService;
    private final AmexSyncService amexSyncService;
    private final IbkrConnectionRepository ibkrConnectionRepository;
    private final CryptoExchangeSyncService cryptoExchangeSyncService;
    private final WalletAddressRepository walletAddressRepository;
    private final FinarySessionRepository finarySessionRepository;
    private final DegiroSessionRepository degiroSessionRepository;

    public SyncStatusService(RevolutSyncService revolutSyncService,
                             RequisitionRepository requisitionRepository,
                             TradeRepublicSyncService tradeRepublicSyncService,
                             TradeRepublicSessionRepository tradeRepublicSessionRepository,
                             BoursoSyncService boursoSyncService,
                             BourseDirectSyncService bourseDirectSyncService,
                             AmundiSyncService amundiSyncService,
                             FortuneoSyncService fortuneoSyncService,
                             AmexSyncService amexSyncService,
                             IbkrConnectionRepository ibkrConnectionRepository,
                             CryptoExchangeSyncService cryptoExchangeSyncService,
                             WalletAddressRepository walletAddressRepository,
                             FinarySessionRepository finarySessionRepository,
                             DegiroSessionRepository degiroSessionRepository) {
        this.revolutSyncService = revolutSyncService;
        this.requisitionRepository = requisitionRepository;
        this.tradeRepublicSyncService = tradeRepublicSyncService;
        this.tradeRepublicSessionRepository = tradeRepublicSessionRepository;
        this.boursoSyncService = boursoSyncService;
        this.bourseDirectSyncService = bourseDirectSyncService;
        this.amundiSyncService = amundiSyncService;
        this.fortuneoSyncService = fortuneoSyncService;
        this.amexSyncService = amexSyncService;
        this.ibkrConnectionRepository = ibkrConnectionRepository;
        this.cryptoExchangeSyncService = cryptoExchangeSyncService;
        this.walletAddressRepository = walletAddressRepository;
        this.finarySessionRepository = finarySessionRepository;
        this.degiroSessionRepository = degiroSessionRepository;
    }

    @Transactional(readOnly = true)
    public String describe(Long memberId) {
        List<String> lines = new ArrayList<>();
        add(lines, "revolut", () -> revolut(memberId));
        add(lines, "enable-banking", () -> banking(memberId));
        add(lines, "trade-republic", () -> tradeRepublic(memberId));
        add(lines, "bourso", () -> {
            BoursoSyncService.SessionStatusResponse s = boursoSyncService.getStatus(memberId);
            return List.of(broker("bourso", s.isActive(), s.syncStatus().name(), s.lastSyncCompletedAt(), s.lastSyncError()));
        });
        add(lines, "bourse-direct", () -> {
            BourseDirectSyncService.SessionStatusResponse s = bourseDirectSyncService.getStatus(memberId);
            return List.of(broker("bourse-direct", s.isActive(), s.syncStatus().name(), s.lastSyncCompletedAt(), s.lastSyncError()));
        });
        add(lines, "amundi", () -> {
            AmundiSyncService.SessionStatusResponse s = amundiSyncService.getStatus(memberId);
            return List.of(broker("amundi", s.isActive(), s.syncStatus().name(), s.lastSyncCompletedAt(), s.lastSyncError()));
        });
        add(lines, "fortuneo", () -> {
            FortuneoSyncService.SessionStatusResponse s = fortuneoSyncService.getStatus(memberId);
            return List.of(broker("fortuneo", s.isActive(), s.syncStatus().name(), s.lastSyncCompletedAt(), s.lastSyncError()));
        });
        add(lines, "amex", () -> {
            AmexSyncService.SessionStatusResponse s = amexSyncService.getStatus(memberId);
            return List.of(broker("amex", s.isActive(), s.syncStatus().name(), s.lastSyncCompletedAt(), s.lastSyncError()));
        });
        add(lines, "ibkr", () -> ibkr(memberId));
        add(lines, "crypto-exchanges", () -> exchanges(memberId));
        add(lines, "wallets", () -> wallets(memberId));
        add(lines, "finary", () -> finary(memberId));
        add(lines, "degiro", () -> degiro(memberId));
        return String.join("\n", lines);
    }

    private void add(List<String> lines, String source, Supplier<List<String>> reader) {
        try {
            List<String> produced = reader.get();
            if (produced == null || produced.isEmpty()) {
                lines.add(line(source, "NOT_CONNECTED", null, false, null));
            } else {
                lines.addAll(produced);
            }
        } catch (RuntimeException ex) {
            lines.add(line(source, "FAILED", null, false, "status unreadable"));
        }
    }

    private List<String> revolut(Long memberId) {
        RevolutSyncService.StatusResponse status = revolutSyncService.getStatus(memberId);
        if (!status.connected()) {
            return List.of(line("revolut", "NOT_CONNECTED", null, false, null));
        }
        String detail = status.remembered() ? null : "credentials not remembered";
        return List.of(line("revolut", "CONNECTED", status.lastSyncedAt(), false, detail));
    }

    private List<String> banking(Long memberId) {
        List<Requisition> rows = requisitionRepository.findAllByMemberId(memberId);
        if (rows.isEmpty()) {
            return List.of();
        }
        List<String> lines = new ArrayList<>();
        for (Requisition row : rows) {
            String label = "enable-banking/" + label(row.getInstitutionName(), row.getInstitutionId());
            boolean reauth = row.getStatus() == RequisitionStatus.EXPIRED;
            String status = switch (row.getStatus()) {
                case EXPIRED -> "NEEDS_REAUTH";
                case FAILED -> "FAILED";
                case LINKED -> "CONNECTED";
                case CREATED -> "NOT_CONNECTED";
            };
            lines.add(line(label, status, row.getLastSyncedAt(), reauth, null));
        }
        return lines;
    }

    private List<String> tradeRepublic(Long memberId) {
        TradeRepublicSyncService.SessionStatusResponse status = tradeRepublicSyncService.getSessionStatus(memberId);
        if (status.isActive()) {
            return List.of(line("trade-republic", "CONNECTED", null, false, null));
        }
        if (tradeRepublicSessionRepository.findByMemberId(memberId).isEmpty()) {
            return List.of(line("trade-republic", "NOT_CONNECTED", null, false, null));
        }
        return List.of(line("trade-republic", "NEEDS_REAUTH", null, true, null));
    }

    private List<String> ibkr(Long memberId) {
        return ibkrConnectionRepository.findByMemberId(memberId)
            .map(this::ibkrLine)
            .map(List::of)
            .orElseGet(List::of);
    }

    private String ibkrLine(IbkrConnection row) {
        boolean reauth = reauthText(row.getStatus());
        String status = reauth ? "NEEDS_REAUTH" : "ERROR".equals(row.getStatus()) ? "FAILED" : "CONNECTED";
        return line("ibkr", status, row.getLastSyncedAt(), reauth, null);
    }

    private List<String> exchanges(Long memberId) {
        List<CryptoExchangeSyncService.ExchangeStatusResponse> rows = cryptoExchangeSyncService.getStatus(memberId);
        if (rows.isEmpty()) {
            return List.of();
        }
        List<String> lines = new ArrayList<>();
        for (CryptoExchangeSyncService.ExchangeStatusResponse row : rows) {
            boolean reauth = reauthText(row.status());
            String status = reauth ? "NEEDS_REAUTH" : "CONNECTED".equals(row.status()) ? "CONNECTED" : row.status();
            lines.add(line("crypto-exchanges/" + row.exchangeType().name(), status, row.lastSyncedAt(), reauth, null));
        }
        return lines;
    }

    private List<String> wallets(Long memberId) {
        List<WalletAddress> rows = walletAddressRepository.findAllByMemberId(memberId);
        if (rows.isEmpty()) {
            return List.of();
        }
        List<String> lines = new ArrayList<>();
        for (WalletAddress row : rows) {
            lines.add(line("wallets/" + row.getChain().name(), "CONNECTED", row.getLastSyncedAt(), false, null));
        }
        return lines;
    }

    private List<String> finary(Long memberId) {
        return finarySessionRepository.findByMemberId(memberId)
            .map(this::finaryLine)
            .map(List::of)
            .orElseGet(List::of);
    }

    private String finaryLine(FinarySession row) {
        boolean reauth = reauthText(row.getStatus());
        String status = reauth ? "NEEDS_REAUTH" : "CONNECTED".equals(row.getStatus()) ? "CONNECTED" : row.getStatus();
        return line("finary", status, row.getLastSyncedAt(), reauth, null);
    }

    private List<String> degiro(Long memberId) {
        return degiroSessionRepository.findByMemberId(memberId)
            .map(this::degiroLine)
            .map(List::of)
            .orElseGet(List::of);
    }

    private String degiroLine(DegiroSession row) {
        boolean reauth = row.getStatus() == DegiroSessionStatus.REAUTH_REQUIRED || reauthText(row.getLastError());
        String status = switch (row.getStatus()) {
            case REAUTH_REQUIRED -> "NEEDS_REAUTH";
            case FAILED -> reauth ? "NEEDS_REAUTH" : "FAILED";
            case ACTIVE -> "CONNECTED";
        };
        return line("degiro", status, row.getLastSyncedAt(), reauth, row.getLastError());
    }

    private static String broker(String source, boolean active, String syncStatus, Instant completed, Enum<?> error) {
        String errorName = error == null ? null : error.name();
        boolean reauth = "SESSION_EXPIRED".equals(errorName)
            || ("bourso".equals(source) && "INVALID_CREDENTIALS".equals(errorName));
        String status;
        if (reauth) {
            status = "NEEDS_REAUTH";
        } else if (!active && errorName == null) {
            status = "NOT_CONNECTED";
        } else if ("FAILED".equals(syncStatus)) {
            status = "FAILED";
        } else if ("QUEUED".equals(syncStatus) || "RUNNING".equals(syncStatus)) {
            status = syncStatus;
        } else if (!active) {
            status = "NOT_CONNECTED";
        } else {
            status = "CONNECTED";
        }
        return line(source, status, completed, reauth, errorName);
    }

    static boolean reauthText(String value) {
        if (value == null || value.isBlank()) {
            return false;
        }
        String name = value.toUpperCase();
        return name.contains("AUTH")
            || name.contains("SESSION")
            || name.contains("CREDENTIAL")
            || name.contains("MFA")
            || name.contains("FRAUD")
            || name.contains("REAUTH")
            || name.contains("TOTP")
            || name.contains("VALIDATION");
    }

    static String line(String source, String status, Instant lastSync, boolean reauth, String detail) {
        String text = source + ": " + status
            + " lastSync=" + (lastSync == null ? "none" : lastSync.toString())
            + " reauth=" + reauth;
        String cleaned = oneLine(detail);
        if (!cleaned.isEmpty()) {
            text += " — " + cleaned;
        }
        return text;
    }

    private static String label(String preferred, String fallback) {
        String cleaned = oneLine(preferred);
        if (!cleaned.isEmpty()) {
            return cleaned;
        }
        cleaned = oneLine(fallback);
        return cleaned.isEmpty() ? "unknown" : cleaned;
    }

    private static String oneLine(String value) {
        if (value == null) {
            return "";
        }
        String cleaned = value.replace('\n', ' ').replace('\r', ' ').trim();
        return cleaned.length() > 80 ? cleaned.substring(0, 80) : cleaned;
    }
}

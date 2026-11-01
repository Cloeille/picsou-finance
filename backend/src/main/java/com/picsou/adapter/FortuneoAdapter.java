package com.picsou.adapter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.picsou.adapter.sidecar.SidecarBank;
import com.picsou.adapter.sidecar.SidecarClient;
import com.picsou.adapter.sidecar.SidecarSession;
import com.picsou.adapter.sidecar.SidecarWebClientFactory;
import com.picsou.model.AccountType;
import com.picsou.port.SidecarErrorCode;
import com.picsou.port.SidecarInitiate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * Client for the isolated, read-only Fortuneo sidecar.
 *
 * <p>This adapter must never expose credentials or browser state in logs. A fetched account
 * collection represents one complete provider snapshot; callers reject the whole collection if
 * any record is incomplete or inconsistent.</p>
 */
@Component
public class FortuneoAdapter {
    private static final Duration DEFAULT_AUTH_TIMEOUT = Duration.ofSeconds(45);
    private static final Duration DEFAULT_PORTFOLIO_TIMEOUT = Duration.ofSeconds(120);

    private final SidecarClient sidecar;
    private final Duration authTimeout;
    private final Duration portfolioTimeout;

    @Autowired
    public FortuneoAdapter(
        SidecarWebClientFactory clients,
        ObjectMapper objectMapper
    ) {
        this(
            clients.create(SidecarBank.FORTUNEO.label(), SidecarBank.FORTUNEO.serviceName()),
            objectMapper,
            DEFAULT_AUTH_TIMEOUT,
            DEFAULT_PORTFOLIO_TIMEOUT
        );
    }

    FortuneoAdapter(WebClient client, ObjectMapper objectMapper) {
        this(client, objectMapper, DEFAULT_AUTH_TIMEOUT, DEFAULT_PORTFOLIO_TIMEOUT);
    }

    FortuneoAdapter(
        WebClient client,
        ObjectMapper objectMapper,
        Duration authTimeout,
        Duration portfolioTimeout
    ) {
        this.sidecar = new SidecarClient(client, objectMapper, SidecarBank.FORTUNEO, SidecarErrorCode.AUTH_ATTEMPT_EXPIRED);
        this.authTimeout = authTimeout;
        this.portfolioTimeout = portfolioTimeout;
    }

    /** Starts browser authentication and returns either an MFA challenge or a completed session. */
    public SidecarInitiate initiateAuth(String login, String password) {
        return sidecar.post("/initiate", Map.of("login", login, "password", password), SidecarInitiate.class,
            authTimeout, "Could not initiate Fortuneo authentication", SidecarErrorCode.INVALID_CREDENTIALS);
    }

    /** Completes a pending MFA challenge and returns the serialized browser session state. */
    public String completeAuth(String processId, String code) {
        SidecarSession response = sidecar.post("/complete", Map.of("processId", processId, "code", code),
            SidecarSession.class, authTimeout, "Could not complete Fortuneo authentication",
            SidecarErrorCode.INVALID_OTP);
        return response.sessionState();
    }

    /** Fetches the complete account snapshot represented by the supplied encrypted-at-rest session. */
    public List<AccountData> fetchAccounts(String sessionState) {
        return sidecar.postForList(
            "/accounts",
            Map.of("sessionState", sessionState),
            AccountData[].class,
            portfolioTimeout,
            "Could not fetch Fortuneo portfolio",
            SidecarErrorCode.PORTFOLIO_INCOMPLETE,
            "Fortuneo returned no complete portfolio accounts"
        );
    }

    /**
     * One Fortuneo holding valued by the provider.
     *
     * @param isin optional ISIN
     * @param symbol provider symbol used as a safe fallback
     * @param label display label
     * @param quantity held quantity
     * @param buyingPriceEur average acquisition price in EUR, when available
     * @param currentPrice native-currency quote, when available
     * @param quoteCurrency ISO currency of {@code currentPrice}, required when that price is present
     * @param currentValueEur authoritative provider valuation in EUR
     * @param pnlEur provider unrealized profit or loss in EUR, when available
     */
    public record Position(String isin, String symbol, String label, BigDecimal quantity,
                           BigDecimal buyingPriceEur, BigDecimal currentPrice, String quoteCurrency,
                           BigDecimal currentValueEur, BigDecimal pnlEur) {}

    /**
     * One cash-ledger entry.
     *
     * @param date accounting date
     * @param label human-readable description
     * @param amount signed amount in EUR
     * @param category provider category, when Fortuneo classified the entry
     * @param externalId stable provider identifier, or {@code null} when this response shape
     *     carries none -- the sync then falls back to the rolling-window import
     * @param type provider's structured operation type, or {@code null} when absent
     * @param txType Picsou transaction type the sidecar could establish from the provider's
     *     own operation label, or {@code null} when the operation is not one it recognises
     * @param quantity traded quantity, for a securities-ledger row
     * @param unitPrice execution price, for a securities-ledger row
     * @param fees brokerage fees and levies charged on the operation
     * @param isin instrument the row concerns, taken from the provider's own label-to-ISIN
     *     table, or {@code null} when the provider does not name one
     */
    public record Transaction(
        LocalDate date,
        String label,
        BigDecimal amount,
        String category,
        String externalId,
        String type,
        String txType,
        BigDecimal quantity,
        BigDecimal unitPrice,
        BigDecimal fees,
        String isin
    ) {}

    /**
     * One account in an all-or-nothing Fortuneo snapshot.
     *
     * @param externalId stable provider identifier
     * @param name display name
     * @param type supported Picsou account type
     * @param balanceEur authoritative total valuation in EUR
     * @param cashBalance cash component in EUR
     * @param positions complete open-position list
     * @param transactions recent cash-ledger entries
     * @param snapshotComplete explicit completeness assertion from the sidecar
     */
    public record AccountData(String externalId, String name, AccountType type, BigDecimal balanceEur,
                              BigDecimal cashBalance, List<Position> positions, List<Transaction> transactions,
                              boolean snapshotComplete) {}
}

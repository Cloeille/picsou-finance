package com.picsou.adapter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.picsou.adapter.sidecar.SidecarErrorTranslator;
import com.picsou.adapter.sidecar.SidecarWebClientFactory;
import com.picsou.port.SofidyErrorCode;
import com.picsou.port.SofidyPort;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;
import java.util.Map;

@Component
public class SofidyAdapter implements SofidyPort {
    private static final Duration DEFAULT_AUTH_TIMEOUT = Duration.ofSeconds(45);
    /**
     * The sidecar holds the pending login open for as long as its own TTL and
     * gives up first, so this has to sit above it or the adapter would time out on
     * a verification about to succeed.
     */
    private static final Duration DEFAULT_VALIDATION_TIMEOUT = Duration.ofSeconds(150);
    private static final Duration DEFAULT_POSITIONS_TIMEOUT = Duration.ofSeconds(90);

    private final SidecarErrorTranslator<SofidyErrorCode> sidecar;
    private final Duration authTimeout;
    private final Duration validationTimeout;
    private final Duration positionsTimeout;

    @Autowired
    public SofidyAdapter(
        SidecarWebClientFactory clients,
        @Value("${app.sofidy-auth.url:http://sofidy-auth:8001}") String url,
        ObjectMapper objectMapper
    ) {
        this(
            clients.create("Sofidy", url),
            objectMapper,
            DEFAULT_AUTH_TIMEOUT,
            DEFAULT_VALIDATION_TIMEOUT,
            DEFAULT_POSITIONS_TIMEOUT
        );
    }

    SofidyAdapter(WebClient client, ObjectMapper objectMapper) {
        this(client, objectMapper, DEFAULT_AUTH_TIMEOUT, DEFAULT_VALIDATION_TIMEOUT, DEFAULT_POSITIONS_TIMEOUT);
    }

    SofidyAdapter(
        WebClient client,
        ObjectMapper objectMapper,
        Duration authTimeout,
        Duration validationTimeout,
        Duration positionsTimeout
    ) {
        this.sidecar = new SidecarErrorTranslator<>(
            client,
            objectMapper,
            SofidyErrorCode.class,
            "Sofidy",
            SofidyErrorCode.UPSTREAM_UNAVAILABLE,
            SofidyErrorCode.AUTH_ATTEMPT_EXPIRED,
            SofidyAdapter::friendlyMessage
        );
        this.authTimeout = authTimeout;
        this.validationTimeout = validationTimeout;
        this.positionsTimeout = positionsTimeout;
    }

    @Override
    public InitiateResult initiateAuth(String associateCode, String password) {
        return sidecar.post(
            "/initiate",
            Map.of("associateCode", associateCode, "password", password),
            InitiateResult.class,
            authTimeout,
            "Could not initiate Sofidy authentication",
            SofidyErrorCode.INVALID_CREDENTIALS
        );
    }

    @Override
    public String completeAuth(String processId, String code) {
        return sidecar.post(
            "/complete",
            Map.of("processId", processId, "code", code),
            SessionResponse.class,
            validationTimeout,
            "Could not complete Sofidy authentication",
            SofidyErrorCode.MFA_INVALID
        ).sessionState();
    }

    @Override
    public Snapshot fetchSnapshot(String sessionState) {
        Snapshot snapshot = sidecar.post(
            "/positions",
            Map.of("sessionState", sessionState),
            Snapshot.class,
            positionsTimeout,
            "Could not fetch the Sofidy portfolio",
            // A bare 401 on this endpoint can only mean the session died: the
            // credentials and the code were already checked at authentication.
            // Without this a 401 whose body carries no code would fall through to
            // UPSTREAM_UNAVAILABLE and read as an outage rather than a reconnect.
            SofidyErrorCode.SESSION_EXPIRED
        );
        if (!snapshot.snapshotComplete()) {
            // The sidecar refuses a portfolio whose lines do not add up to the
            // total Sofidy itself prints, so an incomplete flag means the flag is
            // not trustworthy either. Refuse rather than write a partial portfolio
            // over a real balance.
            throw sidecar.coded(
                SofidyErrorCode.PORTFOLIO_INCOMPLETE,
                "Sofidy returned an incomplete portfolio",
                null
            );
        }
        return snapshot;
    }

    private static String friendlyMessage(SofidyErrorCode code) {
        return switch (code) {
            case INVALID_CREDENTIALS -> "Sofidy rejected the associate code or the password";
            case MFA_INVALID -> "Sofidy rejected the verification code";
            case FIRST_VISIT_PENDING -> "Finish your first visit on the Sofidy client space first";
            case EMAIL_UNREACHABLE -> "No e-mail address is attached to this Sofidy account";
            case ACCOUNT_INACTIVE -> "This Sofidy account is inactive";
            case RATE_LIMITED -> "Sofidy is rate-limiting this login, try again later";
            case AUTH_ATTEMPT_EXPIRED -> "The Sofidy authentication attempt expired";
            case SESSION_EXPIRED -> "The Sofidy session expired";
            case PORTFOLIO_INCOMPLETE -> "Sofidy returned an incomplete portfolio";
            case UPSTREAM_FORMAT_CHANGED -> "The Sofidy website format changed";
            case INVALID_DATA -> "Sofidy returned invalid portfolio data";
            case UPSTREAM_UNAVAILABLE, INTERNAL_ERROR -> "Sofidy is temporarily unavailable";
        };
    }

    private record SessionResponse(String sessionState) {}
}

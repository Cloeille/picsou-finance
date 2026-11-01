package com.picsou.adapter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.picsou.adapter.sidecar.SidecarBank;
import com.picsou.adapter.sidecar.SidecarClient;
import com.picsou.adapter.sidecar.SidecarSession;
import com.picsou.adapter.sidecar.SidecarWebClientFactory;
import com.picsou.port.SidecarInitiate;
import com.picsou.port.SidecarErrorCode;
import com.picsou.port.SofidyPort;
import org.springframework.beans.factory.annotation.Autowired;
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

    private final SidecarClient sidecar;
    private final Duration authTimeout;
    private final Duration validationTimeout;
    private final Duration positionsTimeout;

    @Autowired
    public SofidyAdapter(
        SidecarWebClientFactory clients,
        ObjectMapper objectMapper
    ) {
        this(
            clients.create(SidecarBank.SOFIDY.label(), SidecarBank.SOFIDY.serviceName()),
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
        this.sidecar = new SidecarClient(client, objectMapper, SidecarBank.SOFIDY, SidecarErrorCode.AUTH_ATTEMPT_EXPIRED);
        this.authTimeout = authTimeout;
        this.validationTimeout = validationTimeout;
        this.positionsTimeout = positionsTimeout;
    }

    @Override
    public SidecarInitiate initiateAuth(String associateCode, String password) {
        return sidecar.post(
            "/initiate",
            Map.of("associateCode", associateCode, "password", password),
            SidecarInitiate.class,
            authTimeout,
            "Could not initiate Sofidy authentication",
            SidecarErrorCode.INVALID_CREDENTIALS
        );
    }

    @Override
    public String completeAuth(String processId, String code) {
        return sidecar.post(
            "/complete",
            Map.of("processId", processId, "code", code),
            SidecarSession.class,
            validationTimeout,
            "Could not complete Sofidy authentication",
            SidecarErrorCode.MFA_INVALID
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
            SidecarErrorCode.SESSION_EXPIRED
        );
        if (!snapshot.snapshotComplete()) {
            // The sidecar refuses a portfolio whose lines do not add up to the
            // total Sofidy itself prints, so an incomplete flag means the flag is
            // not trustworthy either. Refuse rather than write a partial portfolio
            // over a real balance.
            throw sidecar.coded(
                SidecarErrorCode.PORTFOLIO_INCOMPLETE,
                "Sofidy returned an incomplete portfolio",
                null
            );
        }
        return snapshot;
    }
}

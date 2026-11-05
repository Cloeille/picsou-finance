package com.picsou.adapter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.picsou.adapter.sidecar.SidecarErrorTranslator;
import com.picsou.adapter.sidecar.SidecarWebClientFactory;
import com.picsou.exception.SyncException;
import com.picsou.port.CorumErrorCode;
import com.picsou.port.CorumPort;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;
import java.util.Map;

@Component
public class CorumAdapter implements CorumPort {
    /** CORUM asks for no second factor, so this is a single round-trip: the
     *  sidecar logs in, harvests the session cookie and hands it back. */
    private static final Duration DEFAULT_AUTH_TIMEOUT = Duration.ofSeconds(60);
    /** Every contract is read, plus one call per real-estate fund, so this
     *  scales with the portfolio rather than being a single request. */
    private static final Duration DEFAULT_POSITIONS_TIMEOUT = Duration.ofSeconds(120);

    private final SidecarErrorTranslator<CorumErrorCode> sidecar;
    private final Duration authTimeout;
    private final Duration positionsTimeout;

    @Autowired
    public CorumAdapter(
        SidecarWebClientFactory clients,
        @Value("${app.corum-auth.url:http://corum-auth:8001}") String url,
        ObjectMapper objectMapper
    ) {
        this(
            clients.create("CORUM", url),
            objectMapper,
            DEFAULT_AUTH_TIMEOUT,
            DEFAULT_POSITIONS_TIMEOUT
        );
    }

    CorumAdapter(
        WebClient client,
        ObjectMapper objectMapper,
        Duration authTimeout,
        Duration positionsTimeout
    ) {
        this.sidecar = new SidecarErrorTranslator<>(
            client,
            objectMapper,
            CorumErrorCode.class,
            "CORUM",
            CorumErrorCode.UPSTREAM_UNAVAILABLE,
            CorumErrorCode.SESSION_EXPIRED,
            CorumAdapter::friendlyMessage
        );
        this.authTimeout = authTimeout;
        this.positionsTimeout = positionsTimeout;
    }

    @Override
    public String authenticate(String login, String password) {
        return sidecar.post(
            "/initiate",
            Map.of("login", login, "password", password),
            SessionResponse.class,
            authTimeout,
            "Could not authenticate with CORUM",
            CorumErrorCode.INVALID_CREDENTIALS
        ).sessionState();
    }

    @Override
    public Portfolio fetchPortfolio(String sessionState) {
        Portfolio portfolio = sidecar.post(
            "/portfolio",
            Map.of("sessionState", sessionState),
            Portfolio.class,
            positionsTimeout,
            "Could not fetch the CORUM portfolio",
            // A bare 401 on this endpoint can only mean the session died: the
            // credentials were already checked at authentication. Without this
            // a 401 whose body carries no code would fall through to
            // UPSTREAM_UNAVAILABLE and read as an outage rather than as a
            // reconnect.
            CorumErrorCode.SESSION_EXPIRED
        );
        // The sidecar refuses a contract whose lines do not add up, so an
        // incomplete flag means the flag is not trustworthy either. Refuse the
        // whole read rather than write a partial portfolio over a real balance:
        // one bad contract fails the sync, it never drops out of it silently.
        if (portfolio.realEstate() != null && !portfolio.realEstate().snapshotComplete()) {
            throw incomplete();
        }
        if (portfolio.life() != null) {
            for (LifeContract contract : portfolio.life()) {
                if (contract != null && !contract.snapshotComplete()) {
                    throw incomplete();
                }
            }
        }
        return portfolio;
    }

    private SyncException incomplete() {
        return sidecar.coded(
            CorumErrorCode.PORTFOLIO_INCOMPLETE,
            "CORUM returned an incomplete portfolio",
            null
        );
    }

    private static String friendlyMessage(CorumErrorCode code) {
        return switch (code) {
            case INVALID_CREDENTIALS -> "CORUM rejected the credentials";
            case SESSION_EXPIRED -> "The CORUM session expired";
            case MULTIPLE_CONTRACTS -> "This CORUM login holds more than one real-estate contract";
            case PORTFOLIO_INCOMPLETE -> "CORUM returned an incomplete portfolio";
            case UPSTREAM_FORMAT_CHANGED -> "The CORUM website format changed";
            case INVALID_DATA -> "CORUM returned invalid portfolio data";
            case UPSTREAM_UNAVAILABLE, INTERNAL_ERROR -> "CORUM is temporarily unavailable";
        };
    }

    private record SessionResponse(String sessionState) {}
}

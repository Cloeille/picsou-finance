package com.picsou.adapter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.picsou.exception.SyncException;
import com.picsou.port.TradeRepublicPort.TrTokens;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import reactor.core.publisher.Mono;
import reactor.netty.DisposableServer;
import reactor.netty.http.server.HttpServer;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

class TradeRepublicAdapterTest {

    private static final Duration RESPONSE_TIMEOUT = Duration.ofSeconds(2);

    private DisposableServer server;

    @Test
    void productionConstructorIsExplicitSpringInjectionPoint() throws NoSuchMethodException {
        assertThat(TradeRepublicAdapter.class
            .getConstructor(ObjectMapper.class, String.class)
            .isAnnotationPresent(Autowired.class))
            .isTrue();
    }

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.disposeNow();
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {401, 403})
    void refreshSession_rejectedStatusMapsToSessionExpired(int status) {
        TradeRepublicAdapter adapter = adapterReturning(status, "{\"detail\":\"rejected\"}");

        Throwable thrown = catchThrowable(() -> adapter.refreshSession("refresh-token"));

        assertThat(thrown)
            .isInstanceOf(SyncException.class)
            .hasMessage("SESSION_EXPIRED");
    }

    @ParameterizedTest
    @ValueSource(ints = {429, 500, 503})
    void refreshSession_transientStatusMapsToUnavailable(int status) {
        TradeRepublicAdapter adapter = adapterReturning(status, "{\"detail\":\"temporary\"}");

        Throwable thrown = catchThrowable(() -> adapter.refreshSession("refresh-token"));

        assertThat(thrown)
            .isInstanceOf(SyncException.class)
            .hasMessageContaining("unavailable");
        assertThat(thrown.getMessage()).isNotEqualTo("SESSION_EXPIRED");
    }

    @Test
    void refreshSession_timeoutMapsToUnavailable() {
        server = HttpServer.create()
            .host("127.0.0.1")
            .port(0)
            .handle((request, response) -> Mono.never())
            .bindNow();
        TradeRepublicAdapter adapter = adapterFor(server, Duration.ofMillis(25));

        Throwable thrown = catchThrowable(() -> adapter.refreshSession("refresh-token"));

        assertThat(thrown)
            .isInstanceOf(SyncException.class)
            .hasMessageContaining("unavailable");
        assertThat(thrown.getMessage()).isNotEqualTo("SESSION_EXPIRED");
    }

    @Test
    void refreshSession_emptySuccessBodyIsNotSessionExpired() {
        TradeRepublicAdapter adapter = adapterReturning(200, "");

        Throwable thrown = catchThrowable(() -> adapter.refreshSession("refresh-token"));

        assertThat(thrown)
            .isInstanceOf(SyncException.class)
            .hasMessageContaining("empty response");
        assertThat(thrown.getMessage()).isNotEqualTo("SESSION_EXPIRED");
    }

    @Test
    void refreshSession_missingSessionTokenIsNotSessionExpired() {
        TradeRepublicAdapter adapter = adapterReturning(200, "{\"refreshToken\":\"rotated\"}");

        Throwable thrown = catchThrowable(() -> adapter.refreshSession("refresh-token"));

        assertThat(thrown)
            .isInstanceOf(SyncException.class)
            .hasMessageContaining("empty response");
        assertThat(thrown.getMessage()).isNotEqualTo("SESSION_EXPIRED");
    }

    @Test
    void refreshSession_validResponseKeepsPreviousRefreshTokenWhenNotRotated() {
        TradeRepublicAdapter adapter = adapterReturning(200, "{\"sessionToken\":\"new-session\"}");

        TrTokens tokens = adapter.refreshSession("refresh-token");

        assertThat(tokens.sessionToken()).isEqualTo("new-session");
        assertThat(tokens.refreshToken()).isEqualTo("refresh-token");
    }

    @Test
    void refreshSession_validResponseUsesRotatedRefreshToken() {
        TradeRepublicAdapter adapter = adapterReturning(
            200,
            "{\"sessionToken\":\"new-session\",\"refreshToken\":\"rotated\"}"
        );

        TrTokens tokens = adapter.refreshSession("refresh-token");

        assertThat(tokens.sessionToken()).isEqualTo("new-session");
        assertThat(tokens.refreshToken()).isEqualTo("rotated");
    }

    private TradeRepublicAdapter adapterReturning(int status, String body) {
        server = HttpServer.create()
            .host("127.0.0.1")
            .port(0)
            .handle((request, response) -> response
                .status(status)
                .header("Content-Type", "application/json")
                .sendString(Mono.just(body)))
            .bindNow();
        return adapterFor(server, RESPONSE_TIMEOUT);
    }

    private static TradeRepublicAdapter adapterFor(DisposableServer server, Duration timeout) {
        return new TradeRepublicAdapter(
            new ObjectMapper(),
            "http://127.0.0.1:" + server.port(),
            timeout
        );
    }

    // ─── shouldPersist: what an answer must be before it replaces the previous holdings ────

    /**
     * The case that wiped a PEA: the cash subscription answered (500 EUR), the portfolio
     * subscription answered with an error frame. Persisting it stored an account worth its
     * cash with no positions, so the service deleted every holding.
     */
    @Test
    void shouldPersist_anErroredPortfolioWithNoPositions_isSkipped_evenWithCash() {
        assertThat(TradeRepublicAdapter.shouldPersist(new java.math.BigDecimal("500"), true, true, true)).isFalse();
    }

    @Test
    void shouldPersist_aGenuinelyEmptiedPortfolio_isPersisted() {
        assertThat(TradeRepublicAdapter.shouldPersist(java.math.BigDecimal.ZERO, true, true, false)).isTrue();
    }

    @Test
    void shouldPersist_positionsWinOverAnErrorOnALaterFrame() {
        assertThat(TradeRepublicAdapter.shouldPersist(new java.math.BigDecimal("900"), false, true, true)).isTrue();
    }

    @Test
    void shouldPersist_anAccountThatNeverAnsweredAndHasNothing_isSkipped() {
        assertThat(TradeRepublicAdapter.shouldPersist(java.math.BigDecimal.ZERO, true, false, false)).isFalse();
    }
}

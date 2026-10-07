package com.picsou.telemetry;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.picsou.model.AppSetting;
import com.picsou.repository.AppSettingRepository;
import io.sentry.Sentry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Flow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TelemetryServiceTest {

    private static final String DSN = "https://publickey@glitch.example.org/42";
    private static final String EVIL_DSN = "https://evil@evil.example.net/9";

    @Mock AppSettingRepository settings;
    @Mock TelemetrySdk sdk;
    @Mock HttpClient httpClient;
    @Mock HttpResponse<Void> response;

    private final ObjectMapper mapper = new ObjectMapper();

    private TelemetryService service(String dsn) {
        return new TelemetryService(settings, sdk, mapper, dsn, "production", "1.2.3", httpClient);
    }

    private void consent(String value) {
        lenient().when(settings.findByKey(TelemetryService.KEY_CONSENT))
            .thenReturn(Optional.of(AppSetting.builder().key(TelemetryService.KEY_CONSENT).value(value).build()));
    }

    private static String envelope() {
        String event = "{\"event_id\":\"9b1deb4d3b7d4bad9bdd2b0d7b3dcb6d\",\"level\":\"error\","
            + "\"exception\":{\"values\":[{\"type\":\"Error\",\"value\":\"boom FR7630006000011234567890189\"}]},"
            + "\"user\":{\"email\":\"jane.doe@example.com\"},\"request\":{\"url\":\"https://app/x\"}}";
        return "{\"event_id\":\"9b1deb4d3b7d4bad9bdd2b0d7b3dcb6d\",\"dsn\":\"" + EVIL_DSN
            + "\",\"sent_at\":\"2026-10-05T10:00:00.000Z\",\"sdk\":{\"name\":\"x\"}}\n"
            + "{\"type\":\"session\"}\n{\"sid\":\"abc\",\"status\":\"ok\"}\n"
            + "{\"type\":\"event\",\"length\":" + event.getBytes(StandardCharsets.UTF_8).length + "}\n" + event + "\n"
            + "{\"type\":\"replay_event\"}\n{\"replay_id\":\"r1\"}\n"
            + "{\"type\":\"attachment\",\"length\":5,\"filename\":\"a.txt\"}\nhello\n"
            + "{\"type\":\"client_report\"}\n{\"discarded_events\":[]}\n";
    }

    private static String bodyOf(HttpRequest request) {
        List<byte[]> chunks = new ArrayList<>();
        request.bodyPublisher().orElseThrow().subscribe(new Flow.Subscriber<>() {
            @Override public void onSubscribe(Flow.Subscription s) { s.request(Long.MAX_VALUE); }
            @Override public void onNext(ByteBuffer item) {
                byte[] b = new byte[item.remaining()];
                item.get(b);
                chunks.add(b);
            }
            @Override public void onError(Throwable t) { throw new IllegalStateException(t); }
            @Override public void onComplete() { }
        });
        StringBuilder sb = new StringBuilder();
        chunks.forEach(c -> sb.append(new String(c, StandardCharsets.UTF_8)));
        return sb.toString();
    }

    // ---- (1) nothing without consent / without DSN ---------------------------------------------

    @Test
    void noConsent_tunnelForwardsNothing() {
        when(settings.findByKey(TelemetryService.KEY_CONSENT)).thenReturn(Optional.empty());

        service(DSN).tunnel(envelope().getBytes(StandardCharsets.UTF_8));

        verifyNoInteractions(httpClient);
    }

    @Test
    void consentDisabled_tunnelForwardsNothing() {
        consent("DISABLED");

        service(DSN).tunnel(envelope().getBytes(StandardCharsets.UTF_8));

        verifyNoInteractions(httpClient);
    }

    @Test
    void emptyDsn_evenWithConsent_tunnelForwardsNothing_andIsNotAvailable() {
        consent("ENABLED");
        TelemetryService service = service("");

        service.tunnel(envelope().getBytes(StandardCharsets.UTF_8));

        verifyNoInteractions(httpClient);
        assertThat(service.isAvailable()).isFalse();
        assertThat(service.isEnabled()).isFalse();
    }

    @Test
    void malformedDsn_isTreatedAsUnavailable() {
        consent("ENABLED");

        assertThat(service("not a dsn").isEnabled()).isFalse();
        assertThat(service("ftp://k@h/1").isEnabled()).isFalse();
        assertThat(service("https://host-without-key/1").isEnabled()).isFalse();
    }

    @Test
    void startup_withoutConsent_doesNotInitialiseSdk_andClosesAnyLeftover() {
        when(settings.findByKey(TelemetryService.KEY_CONSENT)).thenReturn(Optional.empty());

        service(DSN).initOnStartup();

        verify(sdk, never()).init(any(), any(), any());
        verify(sdk).close();
        assertThat(Sentry.isEnabled()).isFalse();
    }

    @Test
    void startup_emptyDsn_doesNotInitialiseSdk() {
        consent("ENABLED");

        service("").initOnStartup();

        verify(sdk, never()).init(any(), any(), any());
        assertThat(Sentry.isEnabled()).isFalse();
    }

    @Test
    void startup_withConsentAndDsn_initialisesSdkWithReleaseAndEnvironment() {
        consent("ENABLED");

        service(DSN).initOnStartup();

        verify(sdk).init(DSN, "production", "1.2.3");
    }

    @Test
    void captureServerError_isNoOpWithoutConsent() {
        lenient().when(sdk.isInitialized()).thenReturn(true);
        when(settings.findByKey(TelemetryService.KEY_CONSENT)).thenReturn(Optional.empty());

        service(DSN).captureServerError(new RuntimeException("boom"));

        verify(sdk, never()).capture(any());
    }

    @Test
    void captureServerError_capturesWhenEnabledAndInitialised() {
        consent("ENABLED");
        when(sdk.isInitialized()).thenReturn(true);
        RuntimeException error = new RuntimeException("boom");

        service(DSN).captureServerError(error);

        verify(sdk).capture(error);
    }

    // ---- consent changes -----------------------------------------------------------------------

    @Test
    void setEnabled_true_persistsAndInitialisesSdk() {
        when(settings.findByKey(TelemetryService.KEY_CONSENT)).thenReturn(Optional.empty());

        service(DSN).setEnabled(true);

        ArgumentCaptor<AppSetting> saved = ArgumentCaptor.forClass(AppSetting.class);
        verify(settings).save(saved.capture());
        assertThat(saved.getValue().getKey()).isEqualTo("telemetry.consent");
        assertThat(saved.getValue().getValue()).isEqualTo("ENABLED");
        verify(sdk).init(DSN, "production", "1.2.3");
    }

    @Test
    void setEnabled_false_persistsAndClosesSdk() {
        AppSetting existing = AppSetting.builder().key(TelemetryService.KEY_CONSENT).value("ENABLED").build();
        when(settings.findByKey(TelemetryService.KEY_CONSENT)).thenReturn(Optional.of(existing));

        service(DSN).setEnabled(false);

        assertThat(existing.getValue()).isEqualTo("DISABLED");
        verify(settings).save(existing);
        verify(sdk).close();
        verify(sdk, never()).init(any(), any(), any());
    }

    @Test
    void setEnabled_true_withoutDsn_isRejected_andNothingPersisted() {
        assertThatThrownBy(() -> service("").setEnabled(true)).isInstanceOf(IllegalStateException.class);

        verify(settings, never()).save(any());
        verifyNoInteractions(sdk);
    }

    @Test
    void consent_mapsStoredValues() {
        consent("ENABLED");
        assertThat(service(DSN).consent()).isEqualTo(TelemetryService.Consent.ENABLED);
        consent("DISABLED");
        assertThat(service(DSN).consent()).isEqualTo(TelemetryService.Consent.DISABLED);
        consent("garbage");
        assertThat(service(DSN).consent()).isEqualTo(TelemetryService.Consent.UNSET);
    }

    // ---- (6) config endpoint payload ------------------------------------------------------------

    @Test
    void config_hidesDsnWhenDisabled() {
        when(settings.findByKey(TelemetryService.KEY_CONSENT)).thenReturn(Optional.empty());

        var config = service(DSN).config();

        assertThat(config.enabled()).isFalse();
        assertThat(config.available()).isTrue();
        assertThat(config.consent()).isEqualTo("UNSET");
        assertThat(config.dsn()).isNull();
        assertThat(config.environment()).isEqualTo("production");
        assertThat(config.release()).isEqualTo("1.2.3");
    }

    @Test
    void config_exposesDsnWhenEnabled() {
        consent("ENABLED");

        var config = service(DSN).config();

        assertThat(config.enabled()).isTrue();
        assertThat(config.available()).isTrue();
        assertThat(config.consent()).isEqualTo("ENABLED");
        assertThat(config.dsn()).isEqualTo(DSN);
    }

    @Test
    void config_reportsExplicitDisabledConsent() {
        consent("DISABLED");

        var config = service(DSN).config();

        assertThat(config.available()).isTrue();
        assertThat(config.enabled()).isFalse();
        assertThat(config.consent()).isEqualTo("DISABLED");
        assertThat(config.dsn()).isNull();
    }

    @Test
    void config_emptyDsn_isDisabledWithNullDsn() {
        consent("ENABLED");

        var config = service("").config();

        assertThat(config.enabled()).isFalse();
        assertThat(config.available()).isFalse();
        assertThat(config.consent()).isEqualTo("ENABLED");
        assertThat(config.dsn()).isNull();
    }

    // ---- (4) tunnel forwarding -------------------------------------------------------------------

    @Test
    @SuppressWarnings("unchecked")
    void tunnel_forwardsOnlyScrubbedEvents_toTheConfiguredHost() throws Exception {
        consent("ENABLED");
        when(httpClient.sendAsync(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
            .thenReturn(CompletableFuture.completedFuture(response));
        when(response.statusCode()).thenReturn(200);

        service(DSN).tunnel(envelope().getBytes(StandardCharsets.UTF_8));

        ArgumentCaptor<HttpRequest> captor = ArgumentCaptor.forClass(HttpRequest.class);
        verify(httpClient).sendAsync(captor.capture(), any(HttpResponse.BodyHandler.class));
        HttpRequest sent = captor.getValue();
        assertThat(sent.uri().toString()).isEqualTo("https://glitch.example.org/api/42/envelope/");
        assertThat(sent.method()).isEqualTo("POST");
        assertThat(sent.headers().firstValue("X-Sentry-Auth").orElseThrow())
            .isEqualTo("Sentry sentry_version=7, sentry_key=publickey, sentry_client=picsou-tunnel");

        String body = bodyOf(sent);
        assertThat(body).doesNotContain("evil.example").doesNotContain("session").doesNotContain("replay")
            .doesNotContain("attachment").doesNotContain("hello").doesNotContain("client_report")
            .doesNotContain("jane.doe").doesNotContain("FR7630006000011234567890189")
            .doesNotContain("\"user\"").doesNotContain("\"request\"").doesNotContain("\"sdk\"");
        String[] lines = body.split("\n");
        assertThat(lines).hasSize(3);
        assertThat(lines[0]).contains("\"dsn\":\"" + DSN + "\"").contains("\"sent_at\":\"2026-10-05T10:00:00.000Z\"");
        assertThat(lines[1]).contains("\"type\":\"event\"");
        assertThat(lines[2]).doesNotContain("\"value\"").doesNotContain("\"message\"")
            .contains("\"type\":\"Error\"").contains("\"level\":\"error\"");
        assertThat(lines[1]).contains("\"length\":" + lines[2].getBytes(StandardCharsets.UTF_8).length);
    }

    @Test
    @SuppressWarnings("unchecked")
    void tunnel_usesAsyncSend_andReturnsWhileCollectorFutureIsIncomplete() throws Exception {
        consent("ENABLED");
        CompletableFuture<HttpResponse<Void>> pending = new CompletableFuture<>();
        when(httpClient.sendAsync(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenReturn(pending);
        TelemetryService telemetry = service(DSN);

        telemetry.tunnel(envelope().getBytes(StandardCharsets.UTF_8));

        assertThat(pending).isNotCompleted();
        verify(httpClient).sendAsync(any(HttpRequest.class), any(HttpResponse.BodyHandler.class));
        verify(httpClient, never()).send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class));
    }

    @Test
    @SuppressWarnings("unchecked")
    void tunnel_envelopeWithoutEvents_forwardsNothing() throws Exception {
        consent("ENABLED");
        String onlySession = "{\"dsn\":\"" + EVIL_DSN + "\"}\n{\"type\":\"session\"}\n{\"sid\":\"x\"}\n";

        service(DSN).tunnel(onlySession.getBytes(StandardCharsets.UTF_8));

        verify(httpClient, never()).send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class));
    }

    @Test
    @SuppressWarnings("unchecked")
    void tunnel_garbageBody_isSwallowed() throws Exception {
        consent("ENABLED");

        service(DSN).tunnel("not an envelope".getBytes(StandardCharsets.UTF_8));
        service(DSN).tunnel(new byte[0]);

        verify(httpClient, never()).send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class));
    }

    @Test
    @SuppressWarnings("unchecked")
    void tunnel_forwardFailure_isSwallowed() throws Exception {
        consent("ENABLED");
        when(httpClient.sendAsync(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
            .thenReturn(CompletableFuture.<HttpResponse<Void>>failedFuture(
                new java.io.IOException("connection refused")));

        service(DSN).tunnel(envelope().getBytes(StandardCharsets.UTF_8));

        verify(httpClient).sendAsync(any(HttpRequest.class), any(HttpResponse.BodyHandler.class));
    }

    @Test
    @SuppressWarnings("unchecked")
    void tunnel_rejectsWhenInFlightLimitIsReached() {
        consent("ENABLED");
        when(httpClient.sendAsync(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
            .thenAnswer(ignored -> new CompletableFuture<>());
        TelemetryService telemetry = service(DSN);
        byte[] envelope = envelope().getBytes(StandardCharsets.UTF_8);

        for (int i = 0; i < TelemetryService.MAX_IN_FLIGHT_SENDS; i++) {
            assertThat(telemetry.tunnel(envelope)).isTrue();
        }

        assertThat(telemetry.tunnel(envelope)).isFalse();
        verify(httpClient, org.mockito.Mockito.times(TelemetryService.MAX_IN_FLIGHT_SENDS))
            .sendAsync(any(HttpRequest.class), any(HttpResponse.BodyHandler.class));
    }

    @Test
    @SuppressWarnings("unchecked")
    void disablingTelemetry_cancelsPendingTunnelSends() {
        consent("ENABLED");
        CompletableFuture<HttpResponse<Void>> pending = new CompletableFuture<>();
        when(httpClient.sendAsync(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenReturn(pending);
        TelemetryService telemetry = service(DSN);
        telemetry.tunnel(envelope().getBytes(StandardCharsets.UTF_8));

        AppSetting existing = AppSetting.builder().key(TelemetryService.KEY_CONSENT).value("ENABLED").build();
        when(settings.findByKey(TelemetryService.KEY_CONSENT)).thenReturn(Optional.of(existing));
        telemetry.setEnabled(false);

        assertThat(pending).isCancelled();
    }

    @Test
    @SuppressWarnings("unchecked")
    void shutdown_cancelsPendingTunnelSends() {
        consent("ENABLED");
        CompletableFuture<HttpResponse<Void>> pending = new CompletableFuture<>();
        when(httpClient.sendAsync(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenReturn(pending);
        TelemetryService telemetry = service(DSN);
        telemetry.tunnel(envelope().getBytes(StandardCharsets.UTF_8));

        telemetry.shutdown();

        assertThat(pending).isCancelled();
        verify(sdk).close();
    }
}

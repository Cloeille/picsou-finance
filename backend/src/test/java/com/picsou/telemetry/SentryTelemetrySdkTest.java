package com.picsou.telemetry;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.sentry.Breadcrumb;
import io.sentry.ISerializer;
import io.sentry.SentryEvent;
import io.sentry.SentryLevel;
import io.sentry.SentryOptions;
import io.sentry.protocol.Message;
import io.sentry.protocol.Request;
import io.sentry.protocol.SentryException;
import io.sentry.protocol.SentryStackFrame;
import io.sentry.protocol.SentryStackTrace;
import io.sentry.protocol.User;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import java.io.IOException;
import java.io.StringWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;

/** Backend first layer: the SDK's {@code beforeSend} rebuilds the event through the allowlist. */
class SentryTelemetrySdkTest {

    private static final String IBAN = "FR7630006000011234567890189";
    private static final String EMAIL = "jane.doe@example.com";

    private final ObjectMapper mapper = new ObjectMapper();
    private final SentryOptions options = new SentryOptions();

    @Test
    void sdkIsNotInitialisedByDefault() {
        assertThat(new SentryTelemetrySdk(mapper).isInitialized()).isFalse();
    }

    @Test
    void initAndClose_toggleTheRealSdk_withoutAnyNetworkCall() {
        SentryTelemetrySdk sdk = new SentryTelemetrySdk(mapper);
        try {
            sdk.init("https://publickey@glitch.invalid/42", "test", "1.2.3");
            assertThat(sdk.isInitialized()).isTrue();
            sdk.init("https://publickey@glitch.invalid/42", "test", "1.2.3"); // idempotent
            assertThat(sdk.isInitialized()).isTrue();

            sdk.close();
            assertThat(sdk.isInitialized()).isFalse();
            sdk.capture(new RuntimeException("ignored: SDK closed")); // no-op, must not throw
        } finally {
            sdk.close();
        }
    }

    @Test
    void beforeSend_dropsRequestUserBreadcrumbsExtra_andScrubsStrings() throws Exception {
        SentryEvent event = new SentryEvent();
        event.setLevel(SentryLevel.ERROR);
        event.setRelease("1.2.3");
        event.setEnvironment("production");
        event.setServerName("jane-laptop");
        event.setUser(user());
        Request request = new Request();
        request.setUrl("https://picsou.example.org/api/accounts/123?token=zzz");
        request.setCookies("access_token=secret");
        event.setRequest(request);
        event.setBreadcrumbs(List.of(new Breadcrumb("clicked " + IBAN)));
        event.setExtra("balance", "1 234,56 €");
        event.setTag("route", "/accounts/123?x=1");
        event.setTag("user_email", EMAIL);
        event.setMessage(message("Key (name)=(Livret A Jean Dupont) already exists"));

        SentryException ex = new SentryException();
        ex.setType("IllegalStateException");
        ex.setValue("Key (name)=(Livret A Jean Dupont) already exists");
        SentryStackFrame frame = new SentryStackFrame();
        frame.setFilename("AccountService.java");
        frame.setFunction("load");
        frame.setLineno(42);
        frame.setInApp(true);
        SentryStackTrace trace = new SentryStackTrace();
        trace.setFrames(List.of(frame));
        ex.setStacktrace(trace);
        event.setExceptions(List.of(ex));

        SentryEvent out = SentryTelemetrySdk.scrub(event, options.getSerializer(), mapper);

        assertThat(out).isNotNull();
        assertThat(out.getRequest()).isNull();
        assertThat(out.getUser()).isNull();
        assertThat(out.getBreadcrumbs()).isNullOrEmpty();
        assertThat(out.getExtras()).isNullOrEmpty();
        assertThat(out.getServerName()).isNull();
        assertThat(out.getTags()).containsEntry("route", "/accounts/:id").doesNotContainKey("user_email");
        assertThat(out.getMessage()).isNull();
        SentryException scrubbed = out.getExceptions().get(0);
        assertThat(scrubbed.getType()).isEqualTo("IllegalStateException");
        assertThat(scrubbed.getValue()).isNull();
        SentryStackFrame f = scrubbed.getStacktrace().getFrames().get(0);
        assertThat(f.getFilename()).isEqualTo("AccountService.java");
        assertThat(f.getLineno()).isEqualTo(42);

        StringWriter json = new StringWriter();
        options.getSerializer().serialize(out, json);
        assertThat(json.toString()).doesNotContain(IBAN).doesNotContain(EMAIL)
            .doesNotContain("Livret A Jean Dupont").doesNotContain("jane")
            .doesNotContain("secret").doesNotContain("zzz");
    }

    @Test
    @ExtendWith(OutputCaptureExtension.class)
    void malformedEnvelope_logsSafeTypeWithoutSourceExcerpt(CapturedOutput output) {
        byte[] raw = "{\"private\":Livret A Jean Dupont}".getBytes(StandardCharsets.UTF_8);

        assertThat(TelemetryEnvelopeSanitizer.sanitize(raw, "https://key@glitch.invalid/42", mapper))
            .isEmpty();

        assertThat(output.getAll()).contains("ERROR").contains("telemetry.envelope.parse.failed")
            .contains("type=JsonParseException").doesNotContain("Livret").doesNotContain("Jean Dupont");
    }

    @Test
    @ExtendWith(OutputCaptureExtension.class)
    void scrubFailure_logsSafeTypeWithoutEventContent(CapturedOutput output) throws Exception {
        ISerializer serializer = mock(ISerializer.class);
        SentryEvent event = new SentryEvent();
        doThrow(new IOException("Livret A Jean Dupont"))
            .when(serializer).serialize(eq(event), any(Writer.class));

        assertThat(SentryTelemetrySdk.scrub(event, serializer, mapper)).isNull();

        assertThat(output.getAll()).contains("ERROR").contains("telemetry.sdk.scrub.failed")
            .contains("type=IOException").doesNotContain("Livret A Jean Dupont");
    }

    private static User user() {
        User user = new User();
        user.setEmail(EMAIL);
        user.setIpAddress("1.2.3.4");
        user.setId("42");
        return user;
    }

    private static Message message(String text) {
        Message message = new Message();
        message.setFormatted(text);
        return message;
    }
}

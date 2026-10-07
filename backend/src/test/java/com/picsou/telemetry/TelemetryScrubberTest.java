package com.picsou.telemetry;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class TelemetryScrubberTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String IBAN = "FR7630006000011234567890189";
    private static final String AMOUNT = "1 234,56 €";
    private static final String EMAIL = "jane.doe@example.com";
    private static final String JWT = "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxMjM0NTY3ODkwIn0.dBjftJeZ4CVPmB92K27uhbUJU1p1r";
    private static final String BEARER = "Bearer abc123.def456-ghi";

    @SuppressWarnings("unchecked")
    private static Map<String, Object> json(String s) throws Exception {
        return MAPPER.readValue(s, new TypeReference<Map<String, Object>>() { });
    }

    @Test
    void scrubText_removesIbanAmountEmailBearerAndJwt() {
        String out = TelemetryScrubber.scrubText(
            "iban " + IBAN + " paid " + AMOUNT + " by " + EMAIL + " with " + BEARER + " and " + JWT);

        assertThat(out)
            .doesNotContain(IBAN).doesNotContain("1 234").doesNotContain("234,56")
            .doesNotContain(EMAIL).doesNotContain("abc123").doesNotContain("eyJ")
            .contains("[iban]").contains("[amount]").contains("[email]").contains("[token]");
    }

    @Test
    void scrubText_amountVariants() {
        assertThat(TelemetryScrubber.scrubText("total €12.50 ok")).isEqualTo("total [amount] ok");
        assertThat(TelemetryScrubber.scrubText("total 12.50 EUR ok")).isEqualTo("total [amount] ok");
        assertThat(TelemetryScrubber.scrubText("total 99,90 ok")).isEqualTo("total [amount] ok");
    }

    @Test
    void scrubText_cardsLongNumbersAndSecretPairs() {
        assertThat(TelemetryScrubber.scrubText("card 4111 1111 1111 1111 x")).isEqualTo("card [card] x");
        assertThat(TelemetryScrubber.scrubText("card 4111-1111-1111-1111")).isEqualTo("card [card]");
        assertThat(TelemetryScrubber.scrubText("account 123456 failed")).isEqualTo("account [n] failed");
        assertThat(TelemetryScrubber.scrubText("call?api_key=sekret&x=1")).doesNotContain("sekret");
        assertThat(TelemetryScrubber.scrubText("password=hunter2")).isEqualTo("password=[token]");
        assertThat(TelemetryScrubber.scrubText("ip 192.168.1.20 seen")).isEqualTo("ip [ip] seen");
    }

    @Test
    void scrubText_keepsHarmlessText() {
        assertThat(TelemetryScrubber.scrubText("NullPointerException in step 3")).isEqualTo("NullPointerException in step 3");
        assertThat(TelemetryScrubber.scrubText(null)).isNull();
    }

    @Test
    void scrubText_urlsLoseQueryFragmentAndIds() {
        String out = TelemetryScrubber.scrubText(
            "GET https://user:pw@bank.example.org/api/accounts/123/transactions/9b1deb4d-3b7d-4bad-9bdd-2b0d7b3dcb6d?token=abc&x=1#frag failed");

        assertThat(out).isEqualTo("GET https://bank.example.org/api/accounts/:id/transactions/:id failed");
    }

    @Test
    void routeTemplate_replacesIdsAndDropsQuery() {
        assertThat(TelemetryScrubber.routeTemplate("/accounts/123?x=1")).isEqualTo("/accounts/:id");
        assertThat(TelemetryScrubber.routeTemplate("/accounts/123/transactions/456#top")).isEqualTo("/accounts/:id/transactions/:id");
        assertThat(TelemetryScrubber.routeTemplate("/goals/9b1deb4d-3b7d-4bad-9bdd-2b0d7b3dcb6d")).isEqualTo("/goals/:id");
        assertThat(TelemetryScrubber.routeTemplate("/files/deadbeefdeadbeef01")).isEqualTo("/files/:id");
        assertThat(TelemetryScrubber.routeTemplate("https://app.example.org/accounts/7?a=b")).isEqualTo("/accounts/:id");
        assertThat(TelemetryScrubber.routeTemplate("/budget/categories")).isEqualTo("/budget/categories");
    }

    @Test
    void scrubEvent_dropsExceptionValueAndFreeTextMessageButKeepsFramesAndTags() throws Exception {
        Map<String, Object> raw = json("""
            {
              "event_id": "9b1deb4d3b7d4bad9bdd2b0d7b3dcb6d",
              "timestamp": "2026-10-05T10:00:00.000Z",
              "platform": "javascript",
              "level": "error",
              "release": "1.1.0",
              "environment": "production",
              "message": "Key (name)=(Livret A Jean Dupont) already exists",
              "exception": {"values": [{
                "type": "Error",
                "module": "com.picsou.errors",
                "value": "Key (name)=(Livret A Jean Dupont) already exists",
                "mechanism": {"type": "onerror", "handled": false, "data": {"secret": "x"}},
                "stacktrace": {"frames": [{
                  "filename": "https://app.example.org/accounts/123/chunk.js?token=zzz",
                  "function": "load",
                  "lineno": 12, "colno": 3, "in_app": true,
                  "vars": {"password": "p"}, "context_line": "const iban = 'FR76'", "pre_context": ["a"], "abs_path": "/home/jane/x.js"
                }]}
              }]},
              "tags": {"route": "/accounts/123?x=1", "feature": "sync %EMAIL%", "event": "page_view",
                       "ab.variant": "b", "user_email": "%EMAIL%", "random": "x"},
              "contexts": {"os": {"name": "Linux", "version": "6.1", "kernel_version": "k"},
                           "browser": {"name": "Firefox", "version": "130"},
                           "runtime": {"name": "node", "version": "22"},
                           "device": {"name": "iPhone of Jane"}},
              "fingerprint": ["page_view", "/accounts/:id"],
              "request": {"url": "https://app/x", "cookies": "a=b", "headers": {"Authorization": "x"}},
              "user": {"id": "1", "email": "%EMAIL%", "ip_address": "1.2.3.4"},
              "breadcrumbs": [{"message": "clicked %IBAN%"}],
              "extra": {"balance": "%AMOUNT%"},
              "server_name": "jane-laptop",
              "modules": {"x": "1"},
              "debug_meta": {"images": []},
              "sdk": {"name": "sentry.javascript.react", "version": "9.0.0", "packages": [{"name": "npm:@sentry/react"}]}
            }
            """.replace("%IBAN%", IBAN).replace("%AMOUNT%", AMOUNT).replace("%EMAIL%", EMAIL)
                .replace("%BEARER%", BEARER).replace("%JWT%", JWT));

        Map<String, Object> out = TelemetryScrubber.scrubEvent(raw);
        String serialized = MAPPER.writeValueAsString(out);

        assertThat(serialized)
            .doesNotContain("Livret A Jean Dupont").doesNotContain("1 234").doesNotContain(EMAIL)
            .doesNotContain("abc123").doesNotContain("eyJ").doesNotContain("zzz")
            .doesNotContain("jane").doesNotContain("Jane").doesNotContain("iPhone");
        assertThat(out.keySet()).doesNotContain("request", "user", "breadcrumbs", "extra",
            "server_name", "modules", "debug_meta");

        @SuppressWarnings("unchecked")
        Map<String, Object> exc = ((List<Map<String, Object>>) ((Map<String, Object>) out.get("exception")).get("values")).get(0);
        assertThat(exc).containsEntry("type", "Error").containsEntry("module", "com.picsou.errors");
        assertThat(exc).doesNotContainKey("value");
        assertThat(out).doesNotContainKey("message");
        assertThat(exc.get("mechanism")).isEqualTo(Map.of("type", "onerror", "handled", false));

        @SuppressWarnings("unchecked")
        Map<String, Object> frame = ((List<Map<String, Object>>) ((Map<String, Object>) exc.get("stacktrace")).get("frames")).get(0);
        assertThat(frame).containsEntry("filename", "/accounts/:id/chunk.js")
            .containsEntry("function", "load").containsEntry("lineno", 12)
            .containsEntry("colno", 3).containsEntry("in_app", true);
        assertThat(frame.keySet()).doesNotContain("vars", "context_line", "pre_context", "abs_path");

        @SuppressWarnings("unchecked")
        Map<String, Object> tags = (Map<String, Object>) out.get("tags");
        assertThat(tags).containsEntry("route", "/accounts/:id").containsEntry("event", "page_view")
            .containsEntry("ab.variant", "b");
        assertThat(tags.keySet()).doesNotContain("user_email", "random");
        assertThat((String) tags.get("feature")).contains("[email]");

        @SuppressWarnings("unchecked")
        Map<String, Object> contexts = (Map<String, Object>) out.get("contexts");
        assertThat(contexts.keySet()).containsExactlyInAnyOrder("os", "browser", "runtime");
        assertThat(contexts.get("os")).isEqualTo(Map.of("name", "Linux"));
        assertThat(contexts.get("browser")).isEqualTo(Map.of("name", "Firefox"));
        assertThat(contexts.get("runtime")).isEqualTo(Map.of("name", "node", "version", "22"));
        assertThat(out.get("fingerprint")).isEqualTo(List.of("page_view", "/accounts/:id"));
        assertThat(out.get("sdk")).isEqualTo(Map.of("name", "sentry.javascript.react", "version", "9.0.0"));
    }

    @Test
    void scrubEvent_keepsOnlyFixedUsageMessages() {
        assertThat(TelemetryScrubber.scrubEvent(Map.of("message", "page_view")))
            .containsEntry("message", "page_view");
        assertThat(TelemetryScrubber.scrubEvent(Map.of("message", "feature_used")))
            .containsEntry("message", "feature_used");
        assertThat(TelemetryScrubber.scrubEvent(Map.of(
            "message", "Key (name)=(Livret A Jean Dupont) already exists")))
            .doesNotContainKey("message");
    }

    @Test
    void scrubEvent_truncatesTagValuesAndRejectsBadScalars() {
        Map<String, Object> out = TelemetryScrubber.scrubEvent(Map.of(
            "level", "<script>",
            "event_id", "not an id!",
            "tags", Map.of("feature", "x".repeat(200))));

        assertThat(out).doesNotContainKeys("level", "event_id");
        @SuppressWarnings("unchecked")
        Map<String, Object> tags = (Map<String, Object>) out.get("tags");
        assertThat((String) tags.get("feature")).hasSize(64);
    }

    @Test
    void scrubEvent_nullInput_returnsEmpty() {
        assertThat(TelemetryScrubber.scrubEvent(null)).isEmpty();
    }
}

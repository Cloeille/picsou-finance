package com.picsou.adapter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.picsou.exception.SyncException;
import com.picsou.support.WebClientStubs;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AmundiAdapterTest {

    @Test
    void fetchPlans_mapsTheStrictSidecarContract() {
        AmundiAdapter adapter = adapterReturning(HttpStatus.OK, """
            [{
              "externalId":"PEG001",
              "name":"Plan d'Épargne Groupe",
              "planKind":"PEG",
              "employer":"ACME SA",
              "balanceEur":1234.56,
              "positions":[{
                "isin":"FR0010405035",
                "label":"Amundi Label Actions Solidaires",
                "quantity":12.3456,
                "unitValue":100.00,
                "valueEur":1234.56,
                "pnlEur":34.56
              }],
              "snapshotComplete":true
            }]
            """);

        var plans = adapter.fetchPlans("encrypted-browser-state");

        assertThat(plans).singleElement().satisfies(plan -> {
            assertThat(plan.externalId()).isEqualTo("PEG001");
            assertThat(plan.planKind()).isEqualTo("PEG");
            assertThat(plan.employer()).isEqualTo("ACME SA");
            assertThat(plan.balanceEur()).isEqualByComparingTo("1234.56");
            assertThat(plan.snapshotComplete()).isTrue();
            assertThat(plan.positions()).singleElement().satisfies(position -> {
                assertThat(position.isin()).isEqualTo("FR0010405035");
                assertThat(position.quantity()).isEqualByComparingTo("12.3456");
                assertThat(position.unitValue()).isEqualByComparingTo("100.00");
                assertThat(position.valueEur()).isEqualByComparingTo("1234.56");
                assertThat(position.pnlEur()).isEqualByComparingTo("34.56");
            });
        });
    }

    @Test
    void completeAuth_sendsAnExplicitNullCodeForAnAppPush() {
        List<String> bodies = new ArrayList<>();
        AmundiAdapter adapter = adapterRecording(bodies, """
            {"sessionState":"state"}
            """);

        assertThat(adapter.completeAuth("process-1", null)).isEqualTo("state");
        // The sidecar forbids unknown fields but needs the key present, so an
        // omitted `code` would be rejected outright rather than read as a push.
        assertThat(bodies).singleElement().satisfies(body -> {
            assertThat(body).contains("\"processId\":\"process-1\"");
            assertThat(body).contains("\"code\":null");
        });
    }

    /**
     * An app push waits on a human, so the completion call must outlive the
     * plain auth budget -- otherwise a validation that was about to succeed
     * would be cut off client-side.
     */
    @Test
    void completeAuth_waitsLongerThanAPlainAuthenticationCall() {
        AmundiAdapter adapter = new AmundiAdapter(
            WebClientStubs.neverResponding(),
            new ObjectMapper(),
            Duration.ofMillis(20),
            Duration.ofMillis(600),
            Duration.ofMillis(20)
        );

        long startedAt = System.nanoTime();
        assertThatThrownBy(() -> adapter.completeAuth("process", null))
            .isInstanceOf(SyncException.class);
        long elapsedMillis = (System.nanoTime() - startedAt) / 1_000_000;

        assertThat(elapsedMillis).isGreaterThan(100);
    }

    private AmundiAdapter adapterReturning(HttpStatus status, String body) {
        return new AmundiAdapter(WebClientStubs.returning(status, body), new ObjectMapper());
    }

    private AmundiAdapter adapterRecording(List<String> bodies, String responseBody) {
        return new AmundiAdapter(WebClientStubs.recordingBodies(bodies, HttpStatus.OK, responseBody), new ObjectMapper());
    }
}

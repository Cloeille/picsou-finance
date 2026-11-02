package com.picsou.adapter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.picsou.exception.SyncException;
import com.picsou.model.AccountType;
import com.picsou.support.WebClientStubs;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BoursoAdapterTest {

    @Test
    void fetchAccounts_mapsTheStrictSidecarContract() {
        BoursoAdapter adapter = adapterReturning(HttpStatus.OK, """
            [{
              "externalId":"bourso_e2f509c466f5294f15abd873dbbf8a62",
              "name":"BoursoBank",
              "type":"CHECKING",
              "balanceEur":20810.50,
              "cashBalance":null,
              "positions":[],
              "iban":"FR7630006000011234567890189",
              "transactions":[{"externalId":"move-1","date":"2026-09-20","description":"VIREMENT",
                "amount":-25.00,"counterparty":null,"kind":"TRANSFER"}],
              "snapshotComplete":true
            },{
              "externalId":"bourso_9651d8edd5975de1b9eff3865505f15f",
              "name":"PEA DOE",
              "type":"PEA",
              "balanceEur":143088.89,
              "cashBalance":3088.89,
              "positions":[{
                "isin":"IE00B4L5Y983",
                "symbol":"1rTCW8",
                "label":"iShares Core MSCI World",
                "quantity":1000,
                "buyingPriceEur":128.00,
                "currentPrice":140.00,
                "quoteCurrency":"EUR",
                "currentValueEur":140000.00,
                "pnlEur":12000.00
              }],
              "snapshotComplete":true
            }]
            """);

        var accounts = adapter.fetchAccounts("encrypted-cookies");

        assertThat(accounts).hasSize(2);
        assertThat(accounts.get(0)).satisfies(account -> {
            assertThat(account.type()).isEqualTo(AccountType.CHECKING);
            assertThat(account.balanceEur()).isEqualByComparingTo("20810.50");
            assertThat(account.cashBalance()).isNull();
            assertThat(account.positions()).isEmpty();
            assertThat(account.snapshotComplete()).isTrue();
            assertThat(account.iban()).isEqualTo("FR7630006000011234567890189");
            assertThat(account.transactions()).singleElement().satisfies(transaction -> {
                assertThat(transaction.externalId()).isEqualTo("move-1");
                assertThat(transaction.kind()).isEqualTo("TRANSFER");
                assertThat(transaction.counterparty()).isNull();
            });
        });
        assertThat(accounts.get(1)).satisfies(account -> {
            assertThat(account.type()).isEqualTo(AccountType.PEA);
            assertThat(account.cashBalance()).isEqualByComparingTo("3088.89");
            assertThat(account.positions()).singleElement().satisfies(position -> {
                assertThat(position.isin()).isEqualTo("IE00B4L5Y983");
                assertThat(position.symbol()).isEqualTo("1rTCW8");
                assertThat(position.quantity()).isEqualByComparingTo("1000");
                assertThat(position.buyingPriceEur()).isEqualByComparingTo("128.00");
                assertThat(position.quoteCurrency()).isEqualTo("EUR");
                assertThat(position.currentValueEur()).isEqualByComparingTo("140000.00");
                assertThat(position.pnlEur()).isEqualByComparingTo("12000.00");
            });
        });
    }

    @Test
    void fetchAccounts_mapsTheValuationLineCountOfAnAssuranceVie() {
        BoursoAdapter adapter = adapterReturning(HttpStatus.OK, """
            [{
              "externalId":"bourso_0a1b2c3d4e5f60718293a4b5c6d7e8f9",
              "name":"Contrat N° 12345678",
              "type":"ASSURANCE_VIE",
              "balanceEur":12000.06,
              "cashBalance":12000.12,
              "positions":[],
              "valuationLineCount":12,
              "snapshotComplete":true
            }]
            """);

        var accounts = adapter.fetchAccounts("encrypted-cookies");

        assertThat(accounts).singleElement()
            .satisfies(account -> assertThat(account.valuationLineCount()).isEqualTo(12));
    }

    @Test
    void initiateAuth_reportsAPendingAppPush() {
        BoursoAdapter adapter = adapterReturning(HttpStatus.OK, """
            {"processId":"p-1","mfaRequired":true,"mfaType":"APP_PUSH","sessionState":null}
            """);

        var result = adapter.initiateAuth("12345678", "123456");

        assertThat(result.mfaRequired()).isTrue();
        assertThat(result.mfaType()).isEqualTo("APP_PUSH");
        assertThat(result.sessionState()).isNull();
    }

    @Test
    void completeAuth_sendsAnExplicitNullCodeBecauseTheSidecarForbidsUnknownFields() {
        List<String> bodies = new ArrayList<>();
        BoursoAdapter adapter = adapterRecording(bodies, """
            {"sessionState":"cookies"}
            """);

        assertThat(adapter.completeAuth("process-1")).isEqualTo("cookies");
        assertThat(bodies).singleElement().satisfies(body -> {
            assertThat(body).contains("\"processId\":\"process-1\"");
            assertThat(body).contains("\"code\":null");
        });
    }

    /**
     * The push waits on a human unlocking their phone, so the completion call
     * must outlive the plain auth budget -- otherwise a validation that was
     * about to succeed would be cut off client-side.
     */
    @Test
    void completeAuth_waitsLongerThanAPlainAuthenticationCall() {
        BoursoAdapter adapter = new BoursoAdapter(
            WebClientStubs.neverResponding(),
            new ObjectMapper(),
            Duration.ofMillis(20),
            Duration.ofMillis(600),
            Duration.ofMillis(20)
        );

        long startedAt = System.nanoTime();
        assertThatThrownBy(() -> adapter.completeAuth("process"))
            .isInstanceOf(SyncException.class);
        long elapsedMillis = (System.nanoTime() - startedAt) / 1_000_000;

        assertThat(elapsedMillis).isGreaterThan(100);
    }

    private BoursoAdapter adapterReturning(HttpStatus status, String body) {
        return new BoursoAdapter(WebClientStubs.returning(status, body), new ObjectMapper());
    }

    private BoursoAdapter adapterRecording(List<String> bodies, String responseBody) {
        return new BoursoAdapter(WebClientStubs.recordingBodies(bodies, HttpStatus.OK, responseBody), new ObjectMapper());
    }
}

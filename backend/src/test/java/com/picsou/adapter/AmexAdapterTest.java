package com.picsou.adapter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.picsou.support.WebClientStubs;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AmexAdapterTest {

    @Test
    void initiateAuth_sendsTheChosenOtpMethod() {
        List<String> bodies = new ArrayList<>();
        AmexAdapter adapter = adapterRecording(bodies, """
            {"processId":"p-1","mfaRequired":true,"mfaType":"OTP","sessionState":null}
            """);

        var result = adapter.initiateAuth("user", "secret", "email");

        assertThat(result.mfaRequired()).isTrue();
        assertThat(result.mfaType()).isEqualTo("OTP");
        assertThat(bodies).singleElement().satisfies(body ->
            assertThat(body).contains("\"method\":\"email\"")
        );
    }

    @Test
    void completeAuth_sendsTheOtpField() {
        List<String> bodies = new ArrayList<>();
        AmexAdapter adapter = adapterRecording(bodies, """
            {"sessionState":"cookies"}
            """);

        assertThat(adapter.completeAuth("process-1", "123456")).isEqualTo("cookies");
        assertThat(bodies).singleElement().satisfies(body -> {
            assertThat(body).contains("\"processId\":\"process-1\"");
            assertThat(body).contains("\"otp\":\"123456\"");
        });
    }

    @Test
    void fetchAccounts_mapsTheStrictSidecarContract() {
        AmexAdapter adapter = adapterReturning(HttpStatus.OK, """
            [{
              "externalId":"amex_e2f509c466f5294f15abd873dbbf8a62",
              "name":"American Express",
              "type":"CREDIT_CARD",
              "balanceEur":-1250.75,
              "statementBalance":-1250.75,
              "paymentDueDate":"2025-11-10",
              "minimumPayment":-50.00,
              "rewardsPoints":12000,
              "directDebit":null,
              "transactions":[
                {"date":"2025-11-01","description":"Grocer","amount":-30.00,"status":"pending","category":null}
              ],
              "pendingComplete":true,
              "snapshotComplete":true
            }]
            """);

        var accounts = adapter.fetchAccounts("encrypted-cookies");

        assertThat(accounts).hasSize(1);
        assertThat(accounts.get(0)).satisfies(account -> {
            assertThat(account.balanceEur()).isEqualByComparingTo("-1250.75");
            assertThat(account.pendingComplete()).isTrue();
            assertThat(account.snapshotComplete()).isTrue();
            assertThat(account.transactions()).singleElement().satisfies(tx -> {
                assertThat(tx.label()).isEqualTo("Grocer");
                assertThat(tx.pending()).isTrue();
            });
        });
    }

    private AmexAdapter adapterReturning(HttpStatus status, String body) {
        return new AmexAdapter(WebClientStubs.returning(status, body), new ObjectMapper());
    }

    private AmexAdapter adapterRecording(List<String> bodies, String responseBody) {
        return new AmexAdapter(WebClientStubs.recordingBodies(bodies, HttpStatus.OK, responseBody), new ObjectMapper());
    }
}

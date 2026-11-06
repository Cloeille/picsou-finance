package com.picsou.adapter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.picsou.model.AccountType;
import com.picsou.support.WebClientStubs;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;


import static org.assertj.core.api.Assertions.assertThat;

class BourseDirectAdapterTest {

    @Test
    void fetchAccounts_mapsTheStrictSidecarContract() {
        BourseDirectAdapter adapter = adapterReturning(HttpStatus.OK, """
            [{
              "externalId":"pea-42",
              "name":"PEA principal",
              "type":"PEA",
              "balanceEur":1250.50,
              "cashBalance":250.50,
              "positions":[{
                "isin":"FR0000000001",
                "symbol":"ACME",
                "label":"Acme SA",
                "quantity":10,
                "buyingPriceEur":80,
                "currentPrice":100,
                "quoteCurrency":"EUR",
                "currentValueEur":1000,
                "pnlEur":200
              }],
              "snapshotComplete":true
            }]
            """);

        var accounts = adapter.fetchAccounts("encrypted-browser-state");

        assertThat(accounts).singleElement().satisfies(account -> {
            assertThat(account.externalId()).isEqualTo("pea-42");
            assertThat(account.type()).isEqualTo(AccountType.PEA);
            assertThat(account.balanceEur()).isEqualByComparingTo("1250.50");
            assertThat(account.snapshotComplete()).isTrue();
            assertThat(account.positions()).singleElement().satisfies(position -> {
                assertThat(position.symbol()).isEqualTo("ACME");
                assertThat(position.quoteCurrency()).isEqualTo("EUR");
                assertThat(position.currentValueEur()).isEqualByComparingTo("1000");
            });
        });
    }

    private BourseDirectAdapter adapterReturning(HttpStatus status, String body) {
        return new BourseDirectAdapter(WebClientStubs.returning(status, body), new ObjectMapper());
    }
}

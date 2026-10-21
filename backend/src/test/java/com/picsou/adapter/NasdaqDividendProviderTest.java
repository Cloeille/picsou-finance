package com.picsou.adapter;

import com.picsou.port.NasdaqDividend;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.ExchangeFunction;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class NasdaqDividendProviderTest {

    @Test
    void parsesAnMsftShapedPageIncludingDollarAmountsAndNaDates() {
        List<String> urls = new ArrayList<>();
        List<HttpHeaders> headers = new ArrayList<>();
        ExchangeFunction exchange = request -> {
            urls.add(request.url().toString());
            headers.add(request.headers());
            return Mono.just(ClientResponse.create(HttpStatus.OK)
                .header("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                .body("""
                    {"data":{"dividends":{"headers":{"exOrEffDate":"Ex/Eff Date","amount":"Amount",
                      "declarationDate":"Declaration Date","recordDate":"Record Date","paymentDate":"Payment Date"},
                      "rows":[
                        {"exOrEffDate":"11/19/2026","type":"Cash","amount":"$0.98",
                         "declarationDate":"09/15/2026","recordDate":"11/19/2026","paymentDate":"12/10/2026",
                         "currency":"USD"},
                        {"exOrEffDate":"08/20/2026","type":"Cash","amount":"$1,234.50",
                         "declarationDate":"N/A","recordDate":"N/A","paymentDate":"N/A","currency":"USD"}
                      ]}},"status":{"rCode":200}}
                    """)
                .build());
        };
        var provider = new NasdaqDividendProvider(
            WebClient.builder().exchangeFunction(exchange).build());

        List<NasdaqDividend> rows = provider.fetchDividends("MSFT");

        assertThat(urls).singleElement().asString()
            .contains("/api/quote/MSFT/dividends")
            .contains("assetclass=stocks");
        assertThat(headers.get(0).getFirst(HttpHeaders.ACCEPT)).isEqualTo("application/json, text/plain, */*");
        assertThat(headers.get(0).getFirst(HttpHeaders.ORIGIN)).isEqualTo("https://www.nasdaq.com");
        assertThat(headers.get(0).getFirst(HttpHeaders.REFERER)).isEqualTo("https://www.nasdaq.com/");
        assertThat(headers.get(0).getFirst(HttpHeaders.USER_AGENT)).contains("Chrome/");
        assertThat(headers.get(0).get(HttpHeaders.USER_AGENT)).hasSize(1);
        assertThat(headers.get(0).get(HttpHeaders.ORIGIN)).hasSize(1);
        assertThat(rows).hasSize(2);
        assertThat(rows.get(0).exDate()).isEqualTo(java.time.LocalDate.of(2026, 11, 19));
        assertThat(rows.get(0).paymentDate()).isEqualTo(java.time.LocalDate.of(2026, 12, 10));
        assertThat(rows.get(0).recordDate()).isEqualTo(java.time.LocalDate.of(2026, 11, 19));
        assertThat(rows.get(0).declarationDate()).isEqualTo(java.time.LocalDate.of(2026, 9, 15));
        assertThat(rows.get(0).amount()).isEqualByComparingTo("0.98");
        assertThat(rows.get(0).currency()).isEqualTo("USD");
        assertThat(rows.get(1).amount()).isEqualByComparingTo("1234.50");
        assertThat(rows.get(1).paymentDate()).isNull();
        assertThat(rows.get(1).recordDate()).isNull();
        assertThat(rows.get(1).declarationDate()).isNull();
    }

    @Test
    void emptyRowsAreNoComplement() {
        var provider = new NasdaqDividendProvider(WebClient.builder().exchangeFunction(request ->
            Mono.just(ClientResponse.create(HttpStatus.OK)
                .header("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                .body("""
                    {"data":{"dividends":{"rows":[]}},"status":{"rCode":200}}
                    """)
                .build())).build());

        assertThat(provider.fetchDividends("KO")).isEmpty();
        assertThat(provider.parse("""
            {"data":{"dividends":{"rows":[]}},"status":{"rCode":200}}
            """)).isEmpty();
    }

    @Test
    void anUnknownSymbolCodeIsAFailure() {
        var provider = new NasdaqDividendProvider(WebClient.builder().build());

        assertThatThrownBy(() -> provider.parse("""
            {"data":null,"status":{"rCode":400}}
            """)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void parseKeepsOnlyTheMostRecentTwoHundredRows() {
        DateTimeFormatter mdy = DateTimeFormatter.ofPattern("MM/dd/yyyy");
        StringBuilder rows = new StringBuilder();
        LocalDate first = LocalDate.of(2020, 1, 1);
        for (int i = 0; i < 201; i++) {
            if (i > 0) {
                rows.append(',');
            }
            rows.append("{\"exOrEffDate\":\"")
                .append(first.plusDays(i).format(mdy))
                .append("\",\"amount\":\"$0.10\",\"currency\":\"USD\"}");
        }
        var provider = new NasdaqDividendProvider(WebClient.builder().build());

        List<NasdaqDividend> parsed = provider.parse(
            "{\"data\":{\"dividends\":{\"rows\":[" + rows + "]}},\"status\":{\"rCode\":200}}");

        assertThat(parsed).hasSize(200);
        assertThat(parsed).extracting(NasdaqDividend::exDate).doesNotContain(first);
        assertThat(parsed).extracting(NasdaqDividend::exDate).contains(first.plusDays(200));
    }
}

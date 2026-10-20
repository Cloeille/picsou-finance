package com.picsou.adapter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.picsou.port.NasdaqDividend;
import com.picsou.port.NasdaqDividendPort;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.ExchangeStrategies;
import org.springframework.web.reactive.function.client.WebClient;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Nasdaq's public dividend table for a Nasdaq-listed symbol.
 *
 * <p>The endpoint refuses a request that does not look like the quote page, so the headers
 * are the ones that page sends. Empty rows are normal (a NYSE name or an ETF often has
 * none) and are an empty list, not an error. {@code status.rCode} other than 200 throws,
 * so the weekly pass can keep the Yahoo result it already stored.
 */
@Component
public class NasdaqDividendProvider implements NasdaqDividendPort {

    static final String BASE_URL = "https://api.nasdaq.com";
    static final Duration TIMEOUT = Duration.ofSeconds(10);
    /** Explicit bound. The default 256 KB is already a cap; 512 KB is the one this client uses. */
    static final int MAX_BODY_BYTES = 512 * 1024;
    /** Parsed rows kept per symbol. The newest ex-dates are the ones a forecast can use. */
    static final int MAX_ROWS = 200;
    static final ExchangeStrategies STRATEGIES = ExchangeStrategies.builder()
        .codecs(codecs -> codecs.defaultCodecs().maxInMemorySize(MAX_BODY_BYTES))
        .build();
    private static final DateTimeFormatter MDY = DateTimeFormatter.ofPattern("MM/dd/yyyy");
    private static final String USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) "
            + "Chrome/120.0.0.0 Safari/537.36";

    private final WebClient webClient;
    private final ObjectMapper mapper = new ObjectMapper();

    public NasdaqDividendProvider() {
        this(WebClient.builder()
            .baseUrl(BASE_URL)
            .defaultHeader(HttpHeaders.ACCEPT, "application/json, text/plain, */*")
            .defaultHeader(HttpHeaders.ORIGIN, "https://www.nasdaq.com")
            .defaultHeader(HttpHeaders.REFERER, "https://www.nasdaq.com/")
            .defaultHeader(HttpHeaders.USER_AGENT, USER_AGENT)
            .exchangeStrategies(STRATEGIES)
            .build());
    }

    /** Package-private so a test can hand a client that never leaves the process. */
    NasdaqDividendProvider(WebClient webClient) {
        this.webClient = webClient;
    }

    @Override
    public List<NasdaqDividend> fetchDividends(String symbol) {
        if (symbol == null || symbol.isBlank()) {
            return List.of();
        }
        String body = webClient.get()
            .uri("/api/quote/{symbol}/dividends?assetclass=stocks", symbol)
            .header(HttpHeaders.ACCEPT, "application/json, text/plain, */*")
            .header(HttpHeaders.ORIGIN, "https://www.nasdaq.com")
            .header(HttpHeaders.REFERER, "https://www.nasdaq.com/")
            .header(HttpHeaders.USER_AGENT, USER_AGENT)
            .retrieve()
            .bodyToMono(String.class)
            .timeout(TIMEOUT)
            .block();
        return parse(body);
    }

    List<NasdaqDividend> parse(String body) {
        if (body == null || body.isBlank()) {
            throw new IllegalStateException("nasdaq returned an empty body");
        }
        final JsonNode root;
        try {
            root = mapper.readTree(body);
        } catch (Exception ex) {
            throw new IllegalStateException("nasdaq returned a body that is not JSON", ex);
        }
        JsonNode code = root.path("status").path("rCode");
        if (code.isNumber() && code.asInt() != 200) {
            throw new IllegalStateException("nasdaq rCode " + code.asInt());
        }
        JsonNode rows = root.path("data").path("dividends").path("rows");
        if (!rows.isArray() || rows.isEmpty()) {
            return List.of();
        }
        List<NasdaqDividend> parsed = new ArrayList<>();
        for (JsonNode row : rows) {
            NasdaqDividend dividend = rowOf(row);
            if (dividend != null) {
                parsed.add(dividend);
            }
        }
        if (parsed.size() > MAX_ROWS) {
            parsed.sort(Comparator.comparing(NasdaqDividend::exDate));
            parsed = new ArrayList<>(parsed.subList(parsed.size() - MAX_ROWS, parsed.size()));
        }
        return List.copyOf(parsed);
    }

    private static NasdaqDividend rowOf(JsonNode row) {
        LocalDate exDate = date(row.path("exOrEffDate").asText(null));
        BigDecimal amount = amount(row.path("amount").asText(null));
        if (exDate == null || amount == null) {
            return null;
        }
        String currency = text(row.path("currency"));
        return new NasdaqDividend(
            exDate,
            date(row.path("paymentDate").asText(null)),
            date(row.path("recordDate").asText(null)),
            date(row.path("declarationDate").asText(null)),
            amount,
            currency
        );
    }

    private static LocalDate date(String raw) {
        String value = blankToNull(raw);
        if (value == null || "N/A".equalsIgnoreCase(value)) {
            return null;
        }
        try {
            return LocalDate.parse(value, MDY);
        } catch (DateTimeParseException ex) {
            return null;
        }
    }

    private static BigDecimal amount(String raw) {
        String value = blankToNull(raw);
        if (value == null || "N/A".equalsIgnoreCase(value)) {
            return null;
        }
        String number = value.replace("$", "").replace(",", "").trim();
        if (number.isEmpty()) {
            return null;
        }
        try {
            return new BigDecimal(number);
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private static String text(JsonNode node) {
        return node == null || node.isMissingNode() || node.isNull() ? null : blankToNull(node.asText());
    }

    private static String blankToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}

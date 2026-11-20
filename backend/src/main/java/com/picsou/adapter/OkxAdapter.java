package com.picsou.adapter;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import com.picsou.exception.SyncException;
import com.picsou.model.ExchangeType;
import com.picsou.port.CryptoExchangePort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import reactor.core.Exceptions;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.math.BigDecimal;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.time.Clock;
import java.time.Duration;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.TimeoutException;

/** Reads all six OKX balance collections before exposing any positions. */
@Component
public class OkxAdapter implements CryptoExchangePort {

    private static final Logger log = LoggerFactory.getLogger(OkxAdapter.class);
    private static final Map<String, URI> REGION_HOSTS = Map.of(
        "GLOBAL", URI.create("https://www.okx.com"),
        "EEA", URI.create("https://eea.okx.com"),
        "US", URI.create("https://us.okx.com"));
    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(10);
    private static final DateTimeFormatter TIMESTAMP = new DateTimeFormatterBuilder().appendInstant(3).toFormatter();
    private static final String TRADING = "/api/v5/account/balance";
    private static final String FUNDING = "/api/v5/asset/balances";
    private static final String SAVINGS = "/api/v5/finance/savings/balance";
    private static final String ORDERS = "/api/v5/finance/staking-defi/orders-active";
    private static final String ETH_STAKING = "/api/v5/finance/staking-defi/eth/balance";
    private static final String SOL_STAKING = "/api/v5/finance/staking-defi/sol/balance";

    private final WebClient client;
    private final Map<String, URI> regionHosts;
    private final ObjectReader jsonReader;
    private final Clock clock;
    private final Duration timeout;

    @Autowired
    public OkxAdapter(ObjectMapper objectMapper, Clock clock) {
        this(WebClient.create(), REGION_HOSTS, objectMapper, clock, DEFAULT_TIMEOUT);
    }

    OkxAdapter(WebClient client, Map<String, URI> regionHosts, ObjectMapper objectMapper, Clock clock, Duration timeout) {
        this.client = client;
        this.regionHosts = Map.copyOf(regionHosts);
        this.jsonReader = objectMapper.readerFor(JsonNode.class)
            .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .with(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
        this.clock = clock;
        this.timeout = timeout;
    }

    @Override
    public String exchangeName() {
        return ExchangeType.OKX.name();
    }

    @Override
    public boolean requiresApiPassphrase() {
        return true;
    }

    @Override
    public List<String> regions() {
        return List.of("GLOBAL", "EEA", "US");
    }

    URI hostForRegion(String region) {
        URI host = region == null ? null : regionHosts.get(region);
        if (host == null) throw new IllegalArgumentException("Missing or unsupported OKX region.");
        return host;
    }

    @Override
    public List<ExchangePosition> fetchPositions(ExchangeCredentials credentials) {
        Map<String, BigDecimal> spot = fetchTradingBalances(credentials);
        addBalances(spot, fetch(FUNDING, credentials), "bal", FUNDING);

        Map<String, BigDecimal> lending = new TreeMap<>();
        addBalances(lending, fetch(SAVINGS, credentials), "amt", SAVINGS);

        Map<String, BigDecimal> staking = new TreeMap<>();
        Set<String> orderIds = new HashSet<>();
        for (JsonNode order : fetch(ORDERS, credentials)) {
            String orderId = order.path("ordId").textValue();
            if (orderId == null || orderId.isBlank()) throw invalidShape(ORDERS, "ordId");
            if (!orderIds.add(orderId)) throw invalidShape(ORDERS, "duplicate ordId");
            JsonNode investments = requireArray(order, "investData", ORDERS);
            if (investments.isEmpty()) throw invalidShape(ORDERS, "investData");
            addBalances(staking, investments, "amt", ORDERS);
        }
        addStakingReceipts(spot, staking, credentials, ETH_STAKING, "BETH", "ETH");
        addStakingReceipts(spot, staking, credentials, SOL_STAKING, "OKSOL", "SOL");

        List<ExchangePosition> positions = new ArrayList<>();
        spot.forEach((symbol, quantity) -> positions.add(ExchangePosition.spot(symbol, quantity)));
        staking.forEach((symbol, quantity) -> positions.add(new ExchangePosition(Product.STAKING, symbol, quantity, null, null)));
        lending.forEach((symbol, quantity) -> positions.add(new ExchangePosition(Product.LENDING, symbol, quantity, null, null)));
        log.info("OKX: fetched {} non-zero positions", positions.size());
        return positions;
    }

    @Override
    public boolean testConnection(ExchangeCredentials credentials) {
        fetchTradingBalances(credentials);
        return true;
    }

    private Map<String, BigDecimal> fetchTradingBalances(ExchangeCredentials credentials) {
        JsonNode trading = fetch(TRADING, credentials);
        // OKX always answers with exactly one account row. A missing row, or an empty detail list
        // whose total does not say zero, would otherwise read as "nothing held" and write a short
        // balance into today's snapshot.
        if (trading.size() != 1) throw invalidShape(TRADING, "data");
        Map<String, BigDecimal> balances = new TreeMap<>();

        JsonNode account = trading.get(0);
        JsonNode details = requireArray(account, "details", TRADING);
        if (details.isEmpty() && requireAmount(account, "totalEq", TRADING).signum() != 0) {
            throw new SyncException("OKX returned empty details with non-zero totalEq for " + TRADING + ".");
        }
        addBalances(balances, details, "eq", TRADING);
        return balances;
    }

    private void addStakingReceipts(Map<String, BigDecimal> spot, Map<String, BigDecimal> staking,
                                    ExchangeCredentials credentials, String path, String receipt, String underlying) {
        Map<String, BigDecimal> receipts = new TreeMap<>();
        for (JsonNode row : fetch(path, credentials)) {
            if (!requireCurrency(row, path).equals(receipt)) throw invalidShape(path, "ccy");
            addBalance(receipts, row, receipt, "amt", path);
        }
        BigDecimal quantity = receipts.get(receipt);
        if (quantity != null && spot.containsKey(receipt)) {
            throw new SyncException("OKX reported " + receipt + " in both spot and staking. "
                + "Picsou cannot determine whether these balances overlap.");
        }
        BigDecimal spotQuantity = spot.remove(receipt);
        if (spotQuantity != null) spot.merge(underlying, spotQuantity, BigDecimal::add);
        if (quantity == null) return;
        staking.merge(underlying, quantity, BigDecimal::add);
    }

    private static void addBalances(Map<String, BigDecimal> totals, JsonNode rows, String field, String path) {
        for (JsonNode row : rows) {
            addBalance(totals, row, requireCurrency(row, path), field, path);
        }
    }

    private static void addBalance(Map<String, BigDecimal> totals, JsonNode row, String currency, String field, String path) {
        BigDecimal quantity = requireAmount(row, field, path);
        if (quantity.signum() < 0) {
            throw new SyncException("OKX returned a negative " + field + " for " + path
                + ". Picsou does not support exchange liabilities.");
        }
        if (quantity.signum() > 0) totals.merge(currency, quantity, BigDecimal::add);
    }

    private static String requireCurrency(JsonNode row, String path) {
        String currency = row.path("ccy").textValue();
        if (currency == null || currency.isBlank()) throw invalidShape(path, "ccy");
        return currency.strip().toUpperCase(Locale.ROOT);
    }

    private static BigDecimal requireAmount(JsonNode row, String field, String path) {
        String amount = row.path(field).textValue();
        if (amount == null) throw invalidShape(path, field);
        try {
            return new BigDecimal(amount);
        } catch (NumberFormatException ex) {
            throw invalidShape(path, field);
        }
    }

    private JsonNode fetch(String path, ExchangeCredentials credentials) {
        URI uri = hostForRegion(credentials.region()).resolve(path);
        RawResponse raw;
        try {
            String timestamp = TIMESTAMP.format(clock.instant());
            raw = client.get()
                .uri(uri)
                .header("OK-ACCESS-KEY", credentials.apiKey())
                .header("OK-ACCESS-SIGN", sign(credentials.apiSecret(), timestamp + "GET" + path))
                .header("OK-ACCESS-TIMESTAMP", timestamp)
                .header("OK-ACCESS-PASSPHRASE", credentials.apiPassphrase())
                .header(HttpHeaders.USER_AGENT, "Picsou")
                .accept(MediaType.APPLICATION_JSON)
                .exchangeToMono(response -> response.bodyToMono(byte[].class)
                    .defaultIfEmpty(new byte[0])
                    .map(bytes -> new RawResponse(response.statusCode().value(), bytes)))
                .timeout(timeout)
                .block();
        } catch (RuntimeException | GeneralSecurityException ex) {
            Throwable cause = Exceptions.unwrap(ex);
            if (cause instanceof TimeoutException) {
                throw new SyncException("OKX request for " + path + " timed out.");
            }
            if (cause instanceof WebClientRequestException) {
                throw new SyncException("Could not reach OKX for " + path + ".");
            }
            // Client errors can carry request headers, and parsing errors can echo the body.
            throw new SyncException("Could not read " + path + " from OKX.");
        }
        if (raw == null) throw new SyncException("OKX returned no response for " + path + ".");

        JsonNode envelope;
        try {
            envelope = jsonReader.readValue(raw.body());
        } catch (IOException | RuntimeException ex) {
            throw new SyncException("OKX returned unreadable JSON for " + path + " (HTTP " + raw.status() + ").");
        }
        String code = envelope.path("code").textValue();
        if (code != null && !code.equals("0")) throw apiFailure(path, raw.status(), code);
        if (raw.status() != HttpStatus.OK.value()) throw apiFailure(path, raw.status(), "");
        if (code == null) throw invalidShape(path, "code");
        return requireArray(envelope, "data", path);
    }

    private static JsonNode requireArray(JsonNode node, String field, String path) {
        JsonNode array = node.path(field);
        if (!array.isArray()) throw invalidShape(path, field);
        return array;
    }

    private static SyncException invalidShape(String path, String field) {
        return new SyncException("OKX returned unexpected " + field + " for " + path + ".");
    }

    private static SyncException apiFailure(String path, int status, String code) {
        // Report only recognised codes and local text. Upstream messages may echo credentials.
        String message = switch (code) {
            case "50119" -> "The OKX API key does not exist in the selected region (50119). "
                + "Use Add exchange to submit OKX credentials with the region where the key was created "
                + "(Global = okx.com, EEA = my.okx.com, US = app.okx.com). "
                + "This updates the existing connection if you have one.";
            case "50102" -> "OKX rejected the request timestamp (50102). Check the server clock.";
            case "50113" -> "OKX rejected the request signature (50113).";
            case "50111" -> "OKX rejected the API key (50111).";
            case "50011" -> "OKX rate-limited the sync (50011). Please try again later.";
            case "51024" -> "OKX refused the request for this account (51024): check the account's status and region on OKX.";
            default -> status == 429 ? "OKX rate-limited " + path + " (HTTP 429). Please try again later."
                : "OKX rejected " + path + " (HTTP " + status + ").";
        };
        return new SyncException(message);
    }

    private static String sign(String secret, String prehash) throws GeneralSecurityException {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return Base64.getEncoder().encodeToString(mac.doFinal(prehash.getBytes(StandardCharsets.UTF_8)));
    }

    private record RawResponse(int status, byte[] body) {}
}

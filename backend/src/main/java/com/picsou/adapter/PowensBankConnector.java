package com.picsou.adapter;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.picsou.exception.SyncException;
import com.picsou.port.BankConnectorPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.ExchangeStrategies;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.math.BigDecimal;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Powens (Budget Insight) bank connector.
 * https://docs.powens.com/
 *
 * ⚠ EXPERIMENTAL — UNTESTED IN 1.0.0. Do not enable on production.
 *
 * Unlike Enable Banking (PSD2-only), Powens uses screen scraping + direct bank agreements,
 * giving access to LEP, PEA, Livrets, and other non-payment accounts.
 *
 * Auth: OAuth webview → code → access_token (stored as session ID in Requisition table).
 * Activated only when POWENS_CLIENT_ID is set to a non-empty value. The {@code @Primary}
 * annotation was removed in 1.0.0 so Enable Banking remains the canonical provider even if
 * the env vars are set by mistake — until this adapter has been tested end-to-end against a
 * real Powens tenant, treat it as code that ships disabled.
 */
@ConditionalOnExpression("'${app.powens.client-id:}'.length() > 0")
@Component
public class PowensBankConnector implements BankConnectorPort {

    private static final Logger log = LoggerFactory.getLogger(PowensBankConnector.class);
    private static final Duration TIMEOUT = Duration.ofSeconds(30);

    private final String clientId;
    private final String clientSecret;
    private final String domain;
    private final String redirectUri;
    private final WebClient webClient;

    public PowensBankConnector(
        @Value("${app.powens.client-id:}") String clientId,
        @Value("${app.powens.client-secret:}") String clientSecret,
        @Value("${app.powens.domain:}") String domain,
        @Value("${app.powens.redirect-uri:http://localhost:5173/sync/callback}") String redirectUri
    ) {
        if (clientId.isBlank() || clientSecret.isBlank() || domain.isBlank()) {
            throw new IllegalStateException(
                "Powens is misconfigured. Set POWENS_CLIENT_ID, POWENS_CLIENT_SECRET and POWENS_DOMAIN."
            );
        }
        this.clientId = clientId;
        this.clientSecret = clientSecret;
        this.domain = domain;
        this.redirectUri = redirectUri;
        this.webClient = WebClient.builder()
            .baseUrl("https://" + domain + ".biapi.pro/2.0")
            .defaultHeader("Accept", "application/json")
            .defaultHeader("Content-Type", "application/json")
            // WebClient's default in-memory buffer limit is 256 KB — listCountries() fetches
            // the full unfiltered /connectors catalog (potentially thousands of entries), which
            // can exceed it. See EnableBankingBankConnector for the same fix and rationale.
            .exchangeStrategies(ExchangeStrategies.builder()
                .codecs(configurer -> configurer.defaultCodecs().maxInMemorySize(8 * 1024 * 1024))
                .build())
            .build();
    }

    // ─── BankConnectorPort ────────────────────────────────────────────────────

    /**
     * Builds the Powens webview URL — no HTTP call needed.
     * The webview handles bank selection and credential entry.
     * If connectorId is a numeric Powens connector ID, the webview opens directly on that bank.
     */
    @Override
    public InitiateResult initiateConnection(String connectorId, String callerState) {
        // Forward the caller's correlation nonce when provided; keep a local
        // random state otherwise (experimental adapter, disabled in 1.0.0).
        String state = callerState != null && !callerState.isBlank() ? callerState : UUID.randomUUID().toString();
        try {
            StringBuilder url = new StringBuilder("https://")
                .append(domain).append(".biapi.pro/2.0/auth/webview/redirect")
                .append("?client_id=").append(URLEncoder.encode(clientId, StandardCharsets.UTF_8))
                .append("&redirect_uri=").append(URLEncoder.encode(redirectUri, StandardCharsets.UTF_8))
                .append("&response_type=code")
                .append("&state=").append(state);

            if (connectorId != null && connectorId.matches("\\d+")) {
                url.append("&connector_ids[]=").append(connectorId);
            }

            log.info("Powens webview initiated, state={}", state);
            return new InitiateResult(state, url.toString());
        } catch (Exception ex) {
            throw new SyncException("Failed to build Powens webview URL: " + ex.getMessage(), ex);
        }
    }

    /** Exchanges the OAuth code for a permanent Powens access token. */
    @Override
    public String exchangeCode(String code) {
        log.info("Exchanging Powens OAuth code for access token");
        TokenResponse token = webClient.post()
            .uri("/auth/token/access")
            .header("Authorization", basicAuth())
            .bodyValue(Map.of(
                "code", code,
                "client_id", clientId,
                "client_secret", clientSecret
            ))
            .retrieve()
            .bodyToMono(TokenResponse.class)
            .timeout(TIMEOUT)
            .onErrorMap(WebClientResponseException.class,
                ex -> new SyncException("Powens token exchange failed: " + ex.getResponseBodyAsString(), ex))
            .onErrorMap(ex -> !(ex instanceof SyncException),
                ex -> new SyncException("Powens token exchange error: " + ex.getMessage(), ex))
            .block();

        if (token == null || token.accessToken() == null) {
            throw new SyncException("Empty token response from Powens /auth/token/access");
        }
        log.info("Powens access token obtained");
        return token.accessToken();
    }

    /** Fetches all accounts linked to this access token. */
    @Override
    public List<AccountData> fetchBalances(String accessToken) {
        log.info("Fetching Powens accounts");
        AccountsResponse response = webClient.get()
            .uri("/accounts?all_accounts=true")
            .header("Authorization", "Bearer " + accessToken)
            .retrieve()
            .bodyToMono(AccountsResponse.class)
            .timeout(TIMEOUT)
            .onErrorMap(WebClientResponseException.class,
                ex -> new SyncException("Powens account fetch failed: " + ex.getResponseBodyAsString(), ex))
            .onErrorMap(ex -> !(ex instanceof SyncException),
                ex -> new SyncException("Powens account fetch error: " + ex.getMessage(), ex))
            .block();

        List<PowensAccount> accounts = (response != null && response.accounts() != null)
            ? response.accounts() : List.of();

        log.info("Powens returned {} accounts", accounts.size());
        return accounts.stream()
            .filter(a -> a.balance() != null)
            .map(this::toAccountData)
            .toList();
    }

    /** Fetches booked transactions for one account since {@code from}. */
    @Override
    public List<TransactionData> fetchTransactions(String accessToken, String externalAccountId, LocalDate from) {
        TransactionsResponse response;
        try {
            response = webClient.get()
                .uri(uriBuilder -> uriBuilder.path("/accounts/{id}/transactions")
                    .queryParam("min_date", from.toString())
                    .queryParam("limit", 500)
                    .build(externalAccountId))
                .header("Authorization", "Bearer " + accessToken)
                .retrieve()
                .bodyToMono(TransactionsResponse.class)
                .timeout(TIMEOUT)
                .block();
        } catch (WebClientResponseException ex) {
            log.warn("Powens transaction fetch failed for account {} ({}): {}",
                externalAccountId, ex.getStatusCode(), ex.getResponseBodyAsString());
            return List.of();
        }

        List<PowensTransaction> transactions = (response != null && response.transactions() != null)
            ? response.transactions() : List.of();

        return transactions.stream()
            .filter(t -> t.value() != null && t.date() != null)
            .map(t -> {
                String counterparty = t.simplifiedWording() != null ? t.simplifiedWording() : t.wording();
                String description = t.wording() != null ? t.wording()
                    : t.originalWording() != null ? t.originalWording() : counterparty;
                return new TransactionData(
                    t.id() != null ? String.valueOf(t.id()) : null,
                    LocalDate.parse(t.date()),
                    description,
                    BigDecimal.valueOf(t.value()), // Powens already signs the amount
                    "EUR",
                    counterparty // mapped to category slot
                );
            })
            .toList();
    }

    /** Searches Powens connectors (banks) by name and country. */
    @Override
    public List<InstitutionData> searchInstitutions(String query, String country) {
        log.info("Searching Powens connectors: query='{}' country='{}'", query, country);
        ConnectorsResponse response = webClient.get()
            .uri(uriBuilder -> {
                var b = uriBuilder.path("/connectors");
                if (query != null && !query.isBlank()) b.queryParam("search", query);
                if (country != null && !country.isBlank()) b.queryParam("countries[]", country.toLowerCase());
                return b.build();
            })
            .header("Authorization", basicAuth())
            .retrieve()
            .bodyToMono(ConnectorsResponse.class)
            .timeout(TIMEOUT)
            .onErrorMap(WebClientResponseException.class,
                ex -> new SyncException("Powens connector search failed: " + ex.getResponseBodyAsString(), ex))
            .onErrorMap(ex -> !(ex instanceof SyncException),
                ex -> new SyncException("Powens connector search error: " + ex.getMessage(), ex))
            .block();

        List<PowensConnector> connectors = (response != null && response.connectors() != null)
            ? response.connectors() : List.of();

        String q = query != null ? query.toLowerCase() : "";
        return connectors.stream()
            .filter(c -> q.isEmpty() || (c.name() != null && c.name().toLowerCase().contains(q)))
            .map(c -> new InstitutionData(
                String.valueOf(c.id()),
                c.name(),
                null,
                null,
                c.country() != null ? c.country().toUpperCase() : null,
                // Screen scraping has no PSU-type concept: the user's own credentials
                // decide which space they land in.
                "personal"
            ))
            .limit(20)
            .toList();
    }

    /** Distinct countries covered by Powens' connector catalog. */
    @Override
    public List<String> listCountries() {
        ConnectorsResponse response = webClient.get()
            .uri("/connectors")
            .header("Authorization", basicAuth())
            .retrieve()
            .bodyToMono(ConnectorsResponse.class)
            .timeout(TIMEOUT)
            .onErrorMap(WebClientResponseException.class,
                ex -> new SyncException("Failed to fetch Powens connector countries: " + ex.getResponseBodyAsString(), ex))
            .onErrorMap(ex -> !(ex instanceof SyncException),
                ex -> new SyncException("Failed to fetch Powens connector countries: " + ex.getMessage(), ex))
            .block();

        List<PowensConnector> connectors = (response != null && response.connectors() != null)
            ? response.connectors() : List.of();
        return connectors.stream()
            .map(PowensConnector::country)
            .filter(c -> c != null && !c.isBlank())
            .map(String::toUpperCase)
            .distinct()
            .sorted()
            .toList();
    }

    // ─── Private helpers ──────────────────────────────────────────────────────

    private AccountData toAccountData(PowensAccount a) {
        String accountName = a.name() != null ? a.name() : a.originalName();
        String currency = (a.currency() != null && a.currency().id() != null) ? a.currency().id() : "EUR";
        BigDecimal balance = BigDecimal.valueOf(a.balance());
        String product = mapProduct(a.type(), accountName);

        log.debug("[Powens] account id={} name='{}' type={} → product={} balance={}",
            a.id(), accountName, a.type(), product, balance);

        return new AccountData(
            String.valueOf(a.id()),
            accountName != null ? accountName : "Account",
            a.iban(),
            currency,
            balance
        );
    }

    /**
     * Maps Powens account type to a product string that SyncService.detectType() understands.
     * LEP is a savings account — detected by checking the account name.
     */
    private String mapProduct(String powensType, String name) {
        if (powensType == null) return null;
        return switch (powensType.toLowerCase()) {
            case "pea"                                          -> "pea";
            case "market"                                       -> "market";
            case "savings", "deposit", "life_insurance",
                 "madelin", "per", "perco", "perp"             -> {
                String n = name != null ? name.toLowerCase() : "";
                yield n.contains("lep") ? "lep" : "savings";
            }
            default -> powensType;
        };
    }

    private String basicAuth() {
        return "Basic " + Base64.getEncoder()
            .encodeToString((clientId + ":" + clientSecret).getBytes(StandardCharsets.UTF_8));
    }

    // ─── Powens API response types ────────────────────────────────────────────

    @JsonIgnoreProperties(ignoreUnknown = true)
    record TokenResponse(@JsonProperty("access_token") String accessToken) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record AccountsResponse(List<PowensAccount> accounts) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record PowensAccount(
        Long id,
        String name,
        @JsonProperty("original_name") String originalName,
        Double balance,
        String iban,
        String type,
        PowensCurrency currency
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record PowensCurrency(String id) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record TransactionsResponse(List<PowensTransaction> transactions) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record PowensTransaction(
        Long id,
        String date,
        Double value,
        String wording,
        @JsonProperty("simplified_wording") String simplifiedWording,
        @JsonProperty("original_wording") String originalWording
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record ConnectorsResponse(List<PowensConnector> connectors) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record PowensConnector(Long id, String name, String slug, String country) {}
}

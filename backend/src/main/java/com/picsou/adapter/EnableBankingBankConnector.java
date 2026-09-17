package com.picsou.adapter;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.picsou.config.EnableBankingConfigProvider;
import com.picsou.exception.SyncException;
import com.picsou.port.BankConnectorPort;
import com.picsou.util.LogSanitizer;
import io.jsonwebtoken.Jwts;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.ExchangeStrategies;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.security.PrivateKey;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Enable Banking Bank Account Data API connector.
 * https://enablebanking.com/docs/api/reference/
 *
 * Auth: JWT signed with your RSA private key (RS256).
 * Sessions are initiated via OAuth and tracked in the requisition table.
 */
@Component
public class EnableBankingBankConnector implements BankConnectorPort {

    private static final Logger log = LoggerFactory.getLogger(EnableBankingBankConnector.class);
    private static final Duration TIMEOUT = Duration.ofSeconds(30);
    // Bank coverage per country changes rarely; this avoids re-fetching the full
    // ~2400-institution catalog (no country filter) on every "Add Account" open.
    private static final long COUNTRIES_CACHE_TTL_SECONDS = 21_600; // 6 hours

    private final EnableBankingConfigProvider configProvider;
    private final WebClient webClient;
    private volatile CachedCountries countriesCache;

    public EnableBankingBankConnector(
        EnableBankingConfigProvider configProvider,
        @Value("${app.enablebanking.base-url:https://api.enablebanking.com}") String baseUrl
    ) {
        this.configProvider = configProvider;
        this.webClient = WebClient.builder()
            .baseUrl(baseUrl)
            .defaultHeader("Accept", "application/json")
            .defaultHeader("Content-Type", "application/json")
            // WebClient's default in-memory buffer limit is 256 KB — too small for
            // searchInstitutions() on a large single-country result (e.g. Germany alone is
            // ~1.4 MB across ~1100 institutions), a latent bug independent of this change.
            .exchangeStrategies(ExchangeStrategies.builder()
                .codecs(configurer -> configurer.defaultCodecs().maxInMemorySize(8 * 1024 * 1024))
                .build())
            .build();
    }

    private String applicationId() {
        return configProvider.applicationId()
            .orElseThrow(() -> new SyncException(ebMissingMessage("Application ID")));
    }

    private String keyId() {
        return configProvider.keyId()
            .orElseThrow(() -> new SyncException(ebMissingMessage("Key ID")));
    }

    private String redirectUri() {
        return configProvider.redirectUri()
            .orElseThrow(() -> new SyncException(ebMissingMessage("redirect URL")));
    }

    private PrivateKey privateKey() {
        return configProvider.privateKey()
            .orElseThrow(() -> new SyncException(
                "Enable Banking private key is missing on the server. Open Settings → Integrations → " +
                "Enable Banking and generate or import the key pair (the Application ID, Key ID and " +
                "redirect URL are not enough — a private key is also required)."));
    }

    /**
     * Maps every failure of an Enable Banking call to {@link SyncException}:
     * HTTP errors carry the response body, everything else (timeouts,
     * connection errors) the exception message. This keeps external failures on
     * the service layer's stable sync-error contract; retry-critical requisition
     * state is persisted independently by {@code RequisitionLifecycleWriter}.
     */
    private static <T> Mono<T> mapToSyncException(Mono<T> mono, String context) {
        return mono
            .onErrorMap(WebClientResponseException.class,
                ex -> new SyncException(context + ": " + ex.getResponseBodyAsString(), ex))
            .onErrorMap(ex -> !(ex instanceof SyncException),
                ex -> new SyncException(context + ": " + ex.getMessage(), ex));
    }

    /**
     * Names the single missing credential rather than claiming Enable Banking is
     * entirely unconfigured — the four pieces (Application ID, Key ID, redirect
     * URL, private key) are stored/loaded independently, so a generic message
     * misleads operators who have set everything but the one missing field.
     */
    private static String ebMissingMessage(String field) {
        return "Enable Banking is not fully configured: " + field + " is missing. " +
               "Open Settings → Integrations → Enable Banking in the app, or re-run the setup wizard. " +
               "Free registration: https://enablebanking.com/";
    }

    // ─── BankConnectorPort ────────────────────────────────────────────────────

    @Override
    public InitiateResult initiateConnection(String institutionId, String state) {
        InstitutionRef ref = parseInstitutionId(institutionId);

        var body = Map.of(
            "access", Map.of("valid_until", Instant.now().plus(90, ChronoUnit.DAYS).toString()),
            "aspsp", Map.of("name", ref.bankName(), "country", ref.country()),
            "state", state,
            "redirect_url", redirectUri(),
            // Must match the ASPSP's own psu_types, otherwise the bank presents the
            // wrong login page (or rejects the request outright).
            "psu_type", ref.psuType()
        );

        AuthStartResponse auth = mapToSyncException(
            webClient.post()
                .uri("/auth")
                .header("Authorization", "Bearer " + buildJwt())
                .bodyValue(body)
                .retrieve()
                .bodyToMono(AuthStartResponse.class)
                .timeout(TIMEOUT),
            "Enable Banking auth failed")
            .block();

        if (auth == null || auth.url() == null) {
            throw new SyncException("Empty response from Enable Banking /auth");
        }

        log.info("Enable Banking auth initiated");
        return new InitiateResult(auth.authorizationId(), auth.url());
    }

    @Override
    public String exchangeCode(String oauthCode) {
        log.info("Exchanging OAuth code for Enable Banking session");
        SessionCreateResponse session = mapToSyncException(
            webClient.post()
                .uri("/sessions")
                .header("Authorization", "Bearer " + buildJwt())
                .bodyValue(Map.of("code", oauthCode))
                .retrieve()
                .bodyToMono(SessionCreateResponse.class)
                .timeout(TIMEOUT),
            "Enable Banking code exchange failed")
            .block();

        if (session == null || session.sessionId() == null) {
            throw new SyncException("Empty session response from Enable Banking /sessions");
        }

        log.info("Enable Banking session created: {}", LogSanitizer.fingerprint(session.sessionId()));
        return session.sessionId();
    }

    @Override
    public List<AccountData> fetchBalances(String sessionId) {
        List<String> accounts = fetchSessionAccountsWithRetry(sessionId);
        return accounts.stream()
            .map(accountId -> fetchAccountData(accountId))
            .toList();
    }

    /**
     * Polls GET /sessions/{id} until accounts are populated. Enable Banking
     * links accounts asynchronously after OAuth — usually a few seconds, but
     * occasionally longer.
     *
     * <p>Total worst-case wall time is bounded to ~4.5 s (3 attempts × 1.5 s)
     * so the request stays well under any reverse-proxy {@code proxy_read_timeout}.
     * If the session still hasn't been populated by then, we return an empty
     * list rather than throw: the caller keeps the requisition retryable so the
     * user (and the scheduler) can retry from the UI without losing the session
     * id. Throwing here turned the legitimate "still linking" case into a 502
     * in production.
     */
    private List<String> fetchSessionAccountsWithRetry(String sessionId) {
        int maxAttempts = 3;
        int delayMs = 1_500;

        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            SessionResponse session = mapToSyncException(
                webClient.get()
                    .uri("/sessions/{id}", sessionId)
                    .header("Authorization", "Bearer " + buildJwt())
                    .retrieve()
                    .bodyToMono(SessionResponse.class)
                    .timeout(TIMEOUT),
                "Failed to fetch session")
                .block();

            if (session != null && session.accounts() != null && !session.accounts().isEmpty()) {
                log.info("Session {} has {} accounts (attempt {}, status={})",
                    LogSanitizer.fingerprint(sessionId), session.accounts().size(), attempt, session.status());
                return session.accounts();
            }

            log.info("Session {} has no accounts yet (attempt {}/{}, status={})",
                LogSanitizer.fingerprint(sessionId), attempt, maxAttempts,
                session != null ? session.status() : "null");

            if (attempt < maxAttempts) {
                try { Thread.sleep(delayMs); } catch (InterruptedException e) { Thread.currentThread().interrupt(); break; }
            }
        }

        log.warn("Session {} still has no accounts after {} attempts — returning empty so the caller can retry asynchronously",
            LogSanitizer.fingerprint(sessionId), maxAttempts);
        return List.of();
    }

    /**
     * Fetches the ASPSP catalog <strong>unfiltered by PSU type</strong>. Asking for
     * {@code psu_type=personal} hid every business-oriented bank (Swan, and other
     * BaaS providers, are published under {@code business} only) — so instead we
     * read each ASPSP's own {@code psu_types} and carry the resolved value through
     * to {@link #initiateConnection}.
     */
    @Override
    public List<InstitutionData> searchInstitutions(String query, String country) {
        log.info("Searching institutions: query='{}' country='{}'", query, country);
        AspspsResponse response = webClient.get()
            .uri(uriBuilder -> {
                var b = uriBuilder.path("/aspsps");
                if (country != null && !country.isBlank()) b.queryParam("country", country);
                return b.build();
            })
            .header("Authorization", "Bearer " + buildJwt())
            .retrieve()
            .bodyToMono(AspspsResponse.class)
            .timeout(TIMEOUT)
            .onErrorMap(WebClientResponseException.class,
                ex -> new SyncException("Failed to fetch institutions: [" + ex.getStatusCode() + "] " + ex.getResponseBodyAsString(), ex))
            .onErrorMap(ex -> !(ex instanceof SyncException),
                ex -> new SyncException("Failed to fetch institutions: " + ex.getMessage(), ex))
            .block();

        int count = (response != null && response.aspsps() != null) ? response.aspsps().size() : 0;
        log.info("Enable Banking returned {} ASPSPs", count);

        List<AspspResponse> aspsps = (response != null && response.aspsps() != null) ? response.aspsps() : List.of();

        return toInstitutions(aspsps, query, country);
    }

    // ─── Institution mapping (package-private: unit-tested without HTTP) ──────

    private static final String PSU_PERSONAL = "personal";
    private static final String PSU_BUSINESS = "business";

    /**
     * Caps the response size. Raised from 20 when the psu_type filter was dropped:
     * the unfiltered catalog is larger, and {@code SyncService} re-searches by full
     * bank name to resolve logos — an exact match pushed past the cut would silently
     * yield no logo.
     */
    private static final int MAX_INSTITUTION_RESULTS = 30;

    /** Segments of the composite institution id: name, country, PSU type. */
    record InstitutionRef(String bankName, String country, String psuType) {}

    /**
     * Picks the single PSU type Picsou will authorize with. Prefers {@code personal}
     * whenever the bank offers it — this is a personal-finance app, and it keeps the
     * behaviour of every retail bank identical to before — then {@code business},
     * which is what makes Swan and other business-only providers connectable.
     *
     * <p>A bank offering neither surfaces its own first value rather than a
     * hardcoded {@code "business"}, so it still appears in the picker (badged as
     * non-retail) instead of silently claiming to be a retail bank. Picsou only
     * knows how to drive the two documented flows, so {@link #parseInstitutionId}
     * falls back to {@code personal} if such an id ever comes back for
     * authorization.
     */
    static String resolvePsuType(List<String> psuTypes) {
        if (psuTypes == null || psuTypes.isEmpty()) return PSU_PERSONAL;
        if (psuTypes.contains(PSU_PERSONAL)) return PSU_PERSONAL;
        if (psuTypes.contains(PSU_BUSINESS)) return PSU_BUSINESS;
        return psuTypes.get(0);
    }

    /** e.g. {@code "Swan::FR::business"}. */
    static String buildInstitutionId(String name, String country, String psuType) {
        return name + "::" + (country != null ? country : "") + "::" + psuType;
    }

    /**
     * Parses the composite id back apart, accepting the legacy two-segment form
     * ({@code "BoursoBank::FR"}) written before PSU types were modelled — those
     * requisitions predate business support and are therefore {@code personal}.
     *
     * <p>The id is client-supplied and its PSU segment ends up in an outbound
     * provider request, so anything outside the provider's enum is coerced back to
     * {@code personal}.
     */
    static InstitutionRef parseInstitutionId(String institutionId) {
        String[] parts = institutionId.split("::");
        String country = parts.length > 1 && !parts[1].isBlank() ? parts[1] : DEFAULT_COUNTRY;
        String psuType = parts.length > 2 && !parts[2].isBlank() ? parts[2] : PSU_PERSONAL;
        if (!PSU_PERSONAL.equals(psuType) && !PSU_BUSINESS.equals(psuType)) psuType = PSU_PERSONAL;
        return new InstitutionRef(parts[0], country, psuType);
    }

    /**
     * Maps the raw catalog to port records: case-insensitive name filter, PSU-type
     * resolution, then de-duplication by composite id — Enable Banking can list the
     * same bank twice (different auth methods), which would otherwise render as a
     * duplicated row with a duplicate React key.
     */
    static List<InstitutionData> toInstitutions(List<AspspResponse> aspsps, String query, String fallbackCountry) {
        String q = query != null ? query.toLowerCase() : "";
        Map<String, InstitutionData> byId = new LinkedHashMap<>();

        for (AspspResponse a : aspsps) {
            if (!q.isEmpty() && (a.name() == null || !a.name().toLowerCase().contains(q))) continue;

            String country = a.country() != null ? a.country() : fallbackCountry;
            String psuType = resolvePsuType(a.psuTypes());
            String id = buildInstitutionId(a.name(), country, psuType);

            InstitutionData candidate = new InstitutionData(id, a.name(), a.bic(), a.logo(), country, psuType);
            byId.merge(id, candidate, (existing, duplicate) ->
                existing.logoUrl() == null && duplicate.logoUrl() != null ? duplicate : existing);
            if (byId.size() == MAX_INSTITUTION_RESULTS) break;
        }

        return List.copyOf(byId.values());
    }

    /**
     * {@code GET /application} returns the countries this application is actually
     * registered/active for — a small, static-ish payload, and more correct than
     * deriving coverage from the full ASPSP catalog (which could list countries this
     * particular app isn't licensed for, or omit the distinction entirely).
     */
    @Override
    public List<String> listCountries() {
        CachedCountries cached = countriesCache;
        if (cached != null && !cached.isExpired()) {
            return cached.countries();
        }

        ApplicationResponse response = webClient.get()
            .uri("/application")
            .header("Authorization", "Bearer " + buildJwt())
            .retrieve()
            .bodyToMono(ApplicationResponse.class)
            .timeout(TIMEOUT)
            .onErrorMap(WebClientResponseException.class,
                ex -> new SyncException("Failed to fetch application countries: [" + ex.getStatusCode() + "] " + ex.getResponseBodyAsString(), ex))
            .onErrorMap(ex -> !(ex instanceof SyncException),
                ex -> new SyncException("Failed to fetch application countries: " + ex.getMessage(), ex))
            .block();

        List<String> countries;
        if (response == null || response.countries() == null) {
            log.warn("Enable Banking /application returned {} — not caching this result",
                response == null ? "an empty body" : "no countries field");
            countries = List.of();
        } else {
            countries = response.countries().stream().sorted().toList();
        }

        // Don't cache a null/empty result for the full 6h TTL — a transient blip would
        // otherwise silently pin the country picker to the France-only fallback for hours.
        // Serve (and keep) the last good cached value a little longer instead, if we have one.
        if (!countries.isEmpty()) {
            countriesCache = new CachedCountries(countries, Instant.now());
            return countries;
        }
        return cached != null ? cached.countries() : countries;
    }

    // ─── Private helpers ──────────────────────────────────────────────────────

    private record CachedCountries(List<String> countries, Instant cachedAt) {
        boolean isExpired() {
            return Instant.now().isAfter(cachedAt.plusSeconds(COUNTRIES_CACHE_TTL_SECONDS));
        }
    }

    private AccountData fetchAccountData(String accountId) {
        BalancesResponse balances = mapToSyncException(
            webClient.get()
                .uri("/accounts/{id}/balances", accountId)
                .header("Authorization", "Bearer " + buildJwt())
                .retrieve()
                .bodyToMono(BalancesResponse.class)
                .timeout(TIMEOUT),
            "Failed to fetch account balances")
            .block();

        AccountDetailsResponse details = mapToSyncException(
            webClient.get()
                .uri("/accounts/{id}/details", accountId)
                .header("Authorization", "Bearer " + buildJwt())
                .retrieve()
                .bodyToMono(AccountDetailsResponse.class)
                .timeout(TIMEOUT),
            "Failed to fetch account details")
            .block();

        BigDecimal balance = BigDecimal.ZERO;
        String currency = "EUR";
        String name = "Account";
        String iban = null;

        // Keep per-account visibility at INFO (operators need it to diagnose Enable
        // Banking's async linking), but log only the balance count — never the full
        // balances object, which carries the account's amounts (financial PII).
        log.info("Fetched {} balances for account {}",
            balances != null && balances.balances() != null ? balances.balances().size() : 0, accountId);
        if (balances != null && balances.balances() != null && !balances.balances().isEmpty()) {
            var b = balances.balances().stream()
                .filter(bl -> "closingBooked".equals(bl.balanceType()) || "expected".equals(bl.balanceType()))
                .findFirst()
                .orElse(balances.balances().get(0));
            if (b.balanceAmount() != null) {
                balance = new BigDecimal(b.balanceAmount().amount());
                currency = b.balanceAmount().currency();
            }
        }

        if (details != null && details.account() != null) {
            name = details.account().name() != null ? details.account().name() :
                   details.account().product() != null ? details.account().product() : "Account";
            iban = details.account().iban();
        }

        return new AccountData(accountId, name, iban, currency, balance);
    }

    /**
     * Build a short-lived RS256 JWT to authenticate API calls.
     * https://enablebanking.com/docs/api/reference/#section/Authentication
     */
    String buildJwt() {
        Instant now = Instant.now();
        return Jwts.builder()
            .header().keyId(keyId()).and()
            .issuer(applicationId())
            .claim("aud", "api.enablebanking.com")
            .issuedAt(Date.from(now))
            .expiration(Date.from(now.plusSeconds(3600)))
            .signWith(privateKey(), Jwts.SIG.RS256)
            .compact();
    }

    // ─── Enable Banking API response types ───────────────────────────────────

    @JsonIgnoreProperties(ignoreUnknown = true)
    record AuthStartResponse(
        String url,
        @com.fasterxml.jackson.annotation.JsonProperty("authorization_id") String authorizationId
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record SessionCreateResponse(
        @com.fasterxml.jackson.annotation.JsonProperty("session_id") String sessionId
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record SessionResponse(
        @com.fasterxml.jackson.annotation.JsonProperty("session_id") String sessionId,
        String url,
        String status,
        List<String> accounts
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record AspspsResponse(List<AspspResponse> aspsps) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record ApplicationResponse(List<String> countries) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record AspspResponse(
        String name,
        String bic,
        String logo,
        String country,
        @com.fasterxml.jackson.annotation.JsonProperty("psu_types") List<String> psuTypes
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record BalancesResponse(List<BalanceItem> balances) {
        @JsonIgnoreProperties(ignoreUnknown = true)
        record BalanceItem(
            @com.fasterxml.jackson.annotation.JsonProperty("balance_amount") BalanceAmount balanceAmount,
            @com.fasterxml.jackson.annotation.JsonProperty("balance_type") String balanceType
        ) {}
        @JsonIgnoreProperties(ignoreUnknown = true)
        record BalanceAmount(String amount, String currency) {}
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record AccountDetailsResponse(AccountDetail account) {
        @JsonIgnoreProperties(ignoreUnknown = true)
        record AccountDetail(
            String iban,
            String name,
            String product,
            @com.fasterxml.jackson.annotation.JsonProperty("cash_account_type") String cashAccountType
        ) {}
    }
}

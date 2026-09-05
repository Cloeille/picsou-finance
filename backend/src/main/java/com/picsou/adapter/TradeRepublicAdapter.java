package com.picsou.adapter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.picsou.exception.SyncException;
import com.picsou.model.AccountType;
import com.picsou.port.TradeRepublicPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import org.springframework.web.reactive.socket.client.ReactorNettyWebSocketClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Adapter for Trade Republic's unofficial API.
 *
 * Auth (HTTP) is delegated to the tr-auth Python sidecar, which handles
 * the AWS WAF browser challenge that cannot be solved from plain Java HTTP.
 *
 * Data fetching uses the TR WebSocket API directly (no WAF needed).
 * Protocol version: 31. Session token is passed in each subscription payload.
 *
 * WebSocket subscriptions used:
 *   - availableCash      → cash balance
 *   - compactPortfolioByType → list of positions (isin, netSize, averageBuyIn), grouped by category
 *   - ticker             → current price per instrument (subscribed dynamically)
 *
 * Portfolio value = sum(ticker.last.price × position.netSize) for each position.
 */
@Component
public class TradeRepublicAdapter implements TradeRepublicPort {

    private static final Logger log = LoggerFactory.getLogger(TradeRepublicAdapter.class);

    private static final String WS_URL     = "wss://api.traderepublic.com/";
    private static final int    WS_VERSION = 31;
    private static final Duration DEFAULT_REFRESH_TIMEOUT = Duration.ofSeconds(15);

    private record SecAccount(
        String wrapper,
        String accountNumber,
        String cashAccountNumber,
        String externalId,
        String name,
        AccountType type
    ) {}

    private final WebClient    sidecarClient;
    private final ObjectMapper objectMapper;
    private final Duration     refreshTimeout;

    @Autowired
    public TradeRepublicAdapter(
        ObjectMapper objectMapper,
        @Value("${app.tr-auth.url:http://tr-auth:8001}") String trAuthUrl
    ) {
        this(objectMapper, trAuthUrl, DEFAULT_REFRESH_TIMEOUT);
    }

    TradeRepublicAdapter(ObjectMapper objectMapper, String trAuthUrl, Duration refreshTimeout) {
        this.objectMapper   = objectMapper;
        this.sidecarClient  = WebClient.builder()
            .baseUrl(trAuthUrl)
            .build();
        this.refreshTimeout = refreshTimeout;
    }

    // ─── Auth (delegated to Python sidecar) ───────────────────────────────────

    @Override
    public String initiateAuth(String phoneNumber, String pin) {
        log.info("Delegating TR auth initiation to tr-auth sidecar");

        JsonNode response = sidecarClient.post()
            .uri("/initiate")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(Map.of("phoneNumber", phoneNumber, "pin", pin))
            .retrieve()
            .bodyToMono(JsonNode.class)
            .onErrorResume(WebClientResponseException.class, ex -> {
                String body = ex.getResponseBodyAsString();
                log.error("tr-auth sidecar /initiate failed ({}) : {}", ex.getStatusCode(), body);
                return Mono.error(mapAuthError(body,
                    "Trade Republic authentication failed. Please check your credentials and try again."));
            })
            .timeout(Duration.ofSeconds(60)) // headless browser takes time
            .onErrorMap(ex -> !(ex instanceof SyncException), ex -> new SyncException(
                "Trade Republic authentication service is unavailable. Please make sure tr-auth is running on port 8001.",
                ex))
            .blockOptional()
            .orElseThrow(() -> new SyncException("No response from the Trade Republic service. Please try again later."));

        String processId = response.path("processId").asText(null);
        if (processId == null || processId.isBlank()) {
            throw new SyncException("Trade Republic did not return a valid session. Please try again.");
        }
        return processId;
    }

    @Override
    public TrTokens completeAuth(String processId, String tan) {
        log.info("Delegating TR 2FA completion to tr-auth sidecar, processId={}", processId);

        JsonNode response = sidecarClient.post()
            .uri("/complete")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(Map.of("processId", processId, "tan", tan))
            .retrieve()
            .bodyToMono(JsonNode.class)
            .onErrorResume(WebClientResponseException.class, ex -> {
                String body = ex.getResponseBodyAsString();
                log.error("tr-auth sidecar /complete failed ({}) : {}", ex.getStatusCode(), body);
                return Mono.error(mapAuthError(body,
                    "The verification code is invalid or has expired. Please request a new one."));
            })
            .timeout(Duration.ofSeconds(60))
            .onErrorMap(ex -> !(ex instanceof SyncException), ex -> new SyncException(
                "Trade Republic authentication service is unavailable. Please make sure tr-auth is running on port 8001.",
                ex))
            .blockOptional()
            .orElseThrow(() -> new SyncException("No response from the Trade Republic service. Please try again later."));

        String sessionToken = response.path("sessionToken").asText(null);
        if (sessionToken == null || sessionToken.isBlank()) {
            throw new SyncException("Trade Republic verification did not complete. Please try again.");
        }
        String refreshToken = response.path("refreshToken").asText(null);
        return new TrTokens(sessionToken, refreshToken);
    }

    @Override
    public TrTokens refreshSession(String refreshToken) {
        log.info("Refreshing TR session via tr-auth sidecar");

        JsonNode response = sidecarClient.post()
            .uri("/refresh")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(Map.of("refreshToken", refreshToken))
            .retrieve()
            .bodyToMono(JsonNode.class)
            .onErrorResume(WebClientResponseException.class, ex -> {
                log.error("tr-auth sidecar /refresh failed ({}) : {}", ex.getStatusCode(), ex.getResponseBodyAsString());
                // The sidecar relays TR's status verbatim: only 401/403 mean the
                // refresh token was actually rejected. Anything else (TR 429
                // rate-limit, sidecar 5xx) is transient and must not destroy the
                // stored session.
                int status = ex.getStatusCode().value();
                if (status == 401 || status == 403) {
                    return Mono.error(new SyncException("SESSION_EXPIRED"));
                }
                return Mono.error(new SyncException(
                    "Trade Republic authentication service is unavailable. Please make sure tr-auth is running on port 8001.",
                    ex));
            })
            .timeout(refreshTimeout)
            .onErrorMap(ex -> !(ex instanceof SyncException), ex -> new SyncException(
                "Trade Republic authentication service is unavailable. Please make sure tr-auth is running on port 8001.",
                ex))
            .blockOptional()
            // An empty 2xx body is a sidecar/proxy defect, not a TR rejection —
            // it must not carry the SESSION_EXPIRED sentinel that destroys the
            // stored session. Only a 4xx above means TR refused the token.
            .orElseThrow(() -> new SyncException(
                "Trade Republic authentication service returned an empty response. Please try again later."));

        String newSession = response.path("sessionToken").asText(null);
        if (newSession == null || newSession.isBlank()) {
            throw new SyncException(
                "Trade Republic authentication service returned an empty response. Please try again later.");
        }
        String newRefresh = response.path("refreshToken").asText(null);
        log.info("TR session refreshed successfully");
        return new TrTokens(newSession, newRefresh != null ? newRefresh : refreshToken);
    }

    // ─── Data (WebSocket, no WAF needed) ──────────────────────────────────────

    @Override
    public List<TrAccountData> fetchAccounts(String sessionToken) {
        log.info("Fetching TR portfolio via WebSocket (protocol v{})", WS_VERSION);

        List<SecAccount> secAccounts = extractSecAccounts(sessionToken);
        log.info("TR JWT sec accounts: {}",
            secAccounts.stream()
                .map(acc -> acc.wrapper() + ":" + acc.name())
                .toList());

        AtomicReference<String> cashJson = new AtomicReference<>();
        ConcurrentHashMap<String, String> scopedCashJsonByAccount = new ConcurrentHashMap<>();
        ConcurrentHashMap<String, ConcurrentHashMap<String, JsonNode>> positionsByAccount = new ConcurrentHashMap<>();
        ConcurrentHashMap<String, BigDecimal> tickerPrices = new ConcurrentHashMap<>();
        ConcurrentHashMap<Integer, String> tickerSubToIsin = new ConcurrentHashMap<>();
        ConcurrentHashMap<Integer, SecAccount> portfolioSubIds = new ConcurrentHashMap<>();
        ConcurrentHashMap<Integer, SecAccount> scopedCashSubIds = new ConcurrentHashMap<>();
        Set<String> receivedPortfolioIds = ConcurrentHashMap.newKeySet();
        Set<Integer> receivedScopedCashSubs = ConcurrentHashMap.newKeySet();
        AtomicBoolean authExpired = new AtomicBoolean(false);
        AtomicInteger subIdCounter = new AtomicInteger(0);
        AtomicInteger expectedTickers = new AtomicInteger(-1);
        // Distinct ticker subscriptions that have answered (by wsId), not a raw message
        // count: a successful TR ticker sub streams an initial state plus continuous delta
        // updates under the same wsId, so counting messages let a fast-ticking position
        // complete the stream before slower positions had answered, dropping their prices
        // to the averageBuyIn fallback. See GH issue #23 / PR #25 review.
        Set<Integer> answeredTickerSubs = ConcurrentHashMap.newKeySet();
        AtomicInteger receivedPortfolios = new AtomicInteger(0);
        int totalPortfolioSubs = secAccounts.size();
        int totalScopedCashSubs = (int) secAccounts.stream()
            .filter(account -> account.type() == AccountType.PEA)
            .filter(account -> account.cashAccountNumber() != null && !account.cashAccountNumber().isBlank())
            .count();

        HttpHeaders headers = new HttpHeaders();
        headers.set("Origin", "https://app.traderepublic.com");
        String connectMsg = buildConnectMessage();

        new ReactorNettyWebSocketClient()
            .execute(URI.create(WS_URL), headers, session ->
                session.send(Mono.just(session.textMessage(connectMsg)))
                    .thenMany(
                        session.receive()
                            .map(msg -> msg.getPayloadAsText())
                            .concatMap(text -> {
                                log.info("TR WS <-- {}", text.length() > 500
                                        ? text.substring(0, 500) + "…" : text);

                                if ("connected".equals(text.trim())) {
                                    int id1 = subIdCounter.incrementAndGet();
                                    List<String> msgs = new ArrayList<>();
                                    msgs.add(sub(id1, "availableCash", sessionToken));
                                    log.info("TR WS --> sub {} availableCash", id1);

                                    for (SecAccount account : secAccounts) {
                                        if (account.type() == AccountType.PEA
                                                && account.cashAccountNumber() != null
                                                && !account.cashAccountNumber().isBlank()) {
                                            int id = subIdCounter.incrementAndGet();
                                            scopedCashSubIds.put(id, account);
                                            msgs.add(subAvailableCash(id, account.cashAccountNumber(), sessionToken));
                                            log.info("TR WS --> sub {} availableCash account={}", id, account.name());
                                        }
                                    }

                                    if (secAccounts.isEmpty()) {
                                        log.info("TR WS: no securities account in JWT, skipping portfolio subscription");
                                        expectedTickers.set(0);
                                    } else {
                                        for (SecAccount account : secAccounts) {
                                            int id = subIdCounter.incrementAndGet();
                                            portfolioSubIds.put(id, account);
                                            msgs.add(subCompactPortfolio(id, account.accountNumber(), sessionToken));
                                            log.info("TR WS --> sub {} compactPortfolioByType account={}",
                                                id, account.name());
                                        }
                                    }

                                    return session.send(
                                        Flux.fromIterable(msgs).map(session::textMessage)
                                    ).thenReturn(text);
                                }

                                int wsId = extractWsId(text);
                                String payload = extractWsPayload(text);

                                if (isAuthError(payload)) {
                                    log.warn("TR WS: session expired (AUTHENTICATION_ERROR)");
                                    authExpired.set(true);
                                    return Mono.just(text);
                                }

                                if (wsId == 1) {
                                    cashJson.set(payload);

                                } else if (scopedCashSubIds.containsKey(wsId)) {
                                    SecAccount account = scopedCashSubIds.get(wsId);
                                    receivedScopedCashSubs.add(wsId);
                                    scopedCashJsonByAccount.put(account.externalId(), payload);

                                } else if (portfolioSubIds.containsKey(wsId)) {
                                    SecAccount account = portfolioSubIds.get(wsId);
                                    receivedPortfolios.incrementAndGet();
                                    receivedPortfolioIds.add(account.externalId());
                                    log.info("TR compactPortfolioByType [{}] raw: {}", account.name(),
                                             payload.length() > 2000
                                                     ? payload.substring(0, 2000) + "…" : payload);
                                    try {
                                        JsonNode root = objectMapper.readTree(payload);

                                        // compactPortfolioByType (since 2026-06-21):
                                        //   {"categories":[{"positions":[{"isin":"...","netSize":"...","averageBuyIn":"..."}]}]}
                                        // Legacy compactPortfolio:
                                        //   [{"instrumentId":"...","netSize":"...","averageBuyIn":"..."}]
                                        List<JsonNode> positions = new ArrayList<>();
                                        JsonNode categories = root.path("categories");
                                        if (!categories.isMissingNode() && categories.isArray()) {
                                            for (JsonNode cat : categories) {
                                                cat.path("positions").forEach(positions::add);
                                            }
                                        } else {
                                            JsonNode posArray = root.isArray() ? root : root.path("positions");
                                            if (posArray.isArray()) posArray.forEach(positions::add);
                                        }

                                        if (!positions.isEmpty()) {
                                            List<String> tickerMsgs = new ArrayList<>();
                                            for (JsonNode pos : positions) {
                                                // new API: "isin"; legacy: "instrumentId"
                                                String isin = pos.path("isin").asText(
                                                        pos.path("instrumentId").asText(""));
                                                if (!isin.isEmpty()) {
                                                    positionsByAccount
                                                        .computeIfAbsent(account.externalId(), key -> new ConcurrentHashMap<>())
                                                        .put(isin, pos);

                                                    // Private equity funds (instrumentType "privateFund") are
                                                    // not publicly traded — TR never sends a price tick for them.
                                                    // Private equity funds don't have live tickers and will cause the websocket
                                                    // to hang waiting for an initial price that never comes.
                                                    String instrumentType = pos.path("instrumentType").asText("");
                                                    if ("privateFund".equals(instrumentType)) {
                                                        log.info("TR compactPortfolioByType [{}]: skipping ticker subscription for privateFund {}", account.name(), isin);
                                                    } else {
                                                        int tid = subIdCounter.incrementAndGet();
                                                        tickerSubToIsin.put(tid, isin);
                                                        // compactPortfolioByType positions carry no exchangeId
                                                        // (unlike the legacy compactPortfolio payload), so this
                                                        // almost always falls through to the default. LSX (Lang &
                                                        // Schwarz Exchange) is TR's home exchange for equities/ETFs
                                                        // — see GH issue #23 (all ticker subs were FORBIDDEN with TRX).
                                                        // TR-native crypto (see OpenFigiIsinConverter.isTrCryptoIsin,
                                                        // e.g. XF000BTC0017) is priced on TRD0 instead — LSX doesn't
                                                        // list it, so using LSX here would still leave every crypto
                                                        // position FORBIDDEN and silently falling back to averageBuyIn.
                                                        String exchangeId = pos.path("exchangeId").asText("");
                                                        String defaultExchange =
                                                                OpenFigiIsinConverter.isTrCryptoIsin(isin) ? "TRD0" : "LSX";
                                                        String tickerId = isin + "." + (exchangeId.isEmpty() ? defaultExchange : exchangeId);
                                                        tickerMsgs.add(subWithId(tid, "ticker",
                                                                tickerId, sessionToken));
                                                    }
                                                }
                                            }
                                            int prev = expectedTickers.get();
                                            expectedTickers.set((prev < 0 ? 0 : prev) + tickerMsgs.size());
                                            log.info("TR compactPortfolioByType [{}]: {} positions, subscribing to {} tickers",
                                                     account.name(), positions.size(), tickerMsgs.size());

                                            if (!tickerMsgs.isEmpty()) {
                                                return session.send(
                                                    Flux.fromIterable(tickerMsgs)
                                                        .map(session::textMessage)
                                                ).thenReturn(text);
                                            }
                                        } else {
                                            expectedTickers.compareAndSet(-1, 0);
                                            log.info("TR compactPortfolioByType [{}]: no positions found", account.name());
                                        }
                                    } catch (Exception ex) {
                                        log.error("Failed to parse compactPortfolioByType [{}]: {}",
                                            account.name(), payload, ex);
                                        expectedTickers.compareAndSet(-1, 0);
                                    }

                                } else if (tickerSubToIsin.containsKey(wsId)) {
                                    String isin = tickerSubToIsin.get(wsId);
                                    // Only the first message per subscription counts and is read;
                                    // later delta updates for the same wsId are ignored (see
                                    // answeredTickerSubs declaration). The initial message carries
                                    // the full ticker state, which is all a sync snapshot needs.
                                    if (answeredTickerSubs.add(wsId)) {
                                        try {
                                            JsonNode tickerRoot = objectMapper.readTree(payload);
                                            String priceStr = tickerRoot.path("last").path("price").asText(null);
                                            if (priceStr != null) {
                                                tickerPrices.put(isin, new BigDecimal(priceStr));
                                            } else {
                                                log.warn("TR ticker for {} — no last.price in: {}", isin,
                                                         payload.length() > 300 ? payload.substring(0, 300) : payload);
                                            }
                                        } catch (Exception ex) {
                                            log.warn("Failed to parse ticker for {}: {}", isin, payload);
                                        }
                                    }
                                }

                                return Mono.just(text);
                            })
                            .takeUntil(text -> {
                                if (authExpired.get()) return true;
                                boolean cashDone = cashJson.get() != null
                                        && receivedScopedCashSubs.size() >= totalScopedCashSubs;
                                boolean allPortfoliosIn = receivedPortfolios.get() >= totalPortfolioSubs;
                                int exp = expectedTickers.get();
                                boolean tickersDone = allPortfoliosIn
                                        && exp >= 0
                                        && answeredTickerSubs.size() >= exp;
                                return cashDone && tickersDone;
                            })
                            .timeout(Duration.ofSeconds(30))
                            .onErrorReturn("timeout")
                    )
                    .then()
            )
            .timeout(Duration.ofSeconds(45))
            .block();

        if (authExpired.get()) {
            throw new SyncException("SESSION_EXPIRED");
        }

        // ─── Build accounts from collected data ──────────────────────────────

        List<TrAccountData> accounts = new ArrayList<>();

        for (SecAccount secAccount : secAccounts) {
            Map<String, JsonNode> positionsByIsin = positionsByAccount.getOrDefault(
                secAccount.externalId(), new ConcurrentHashMap<>());

            // Null when the pocket is unknown (no cash account to subscribe to, or its frame
            // never arrived), not zero: parseCashValue(null) reads as an empty pocket, and the
            // sync keeps the last known pocket for an unknown one, as it keeps the holdings.
            String peaCashJson = secAccount.type() == AccountType.PEA
                ? scopedCashJsonByAccount.get(secAccount.externalId())
                : null;
            BigDecimal peaCash = peaCashJson != null ? parseCashValue(peaCashJson) : null;
            BigDecimal totalPortfolioValue = peaCash != null ? peaCash : BigDecimal.ZERO;
            int priced = 0;
            for (var entry : positionsByIsin.entrySet()) {
                String isin = entry.getKey();
                JsonNode pos = entry.getValue();
                BigDecimal size = new BigDecimal(pos.path("netSize").asText("0"));
                BigDecimal price = tickerPrices.get(isin);

                if (size.compareTo(BigDecimal.ZERO) <= 0) continue;

                if (price != null && price.compareTo(BigDecimal.ZERO) > 0) {
                    totalPortfolioValue = totalPortfolioValue.add(
                            price.multiply(size).setScale(2, RoundingMode.HALF_UP));
                    priced++;
                } else {
                    BigDecimal avgBuyIn = new BigDecimal(pos.path("averageBuyIn").asText("0"));
                    if (avgBuyIn.compareTo(BigDecimal.ZERO) > 0) {
                        totalPortfolioValue = totalPortfolioValue.add(
                                avgBuyIn.multiply(size).setScale(2, RoundingMode.HALF_UP));
                        log.warn("TR ticker price missing for {}, using averageBuyIn as fallback", isin);
                    }
                }
            }

            log.info("TR portfolio [{}]: {} positions, {} with live prices, total value: {}",
                     secAccount.name(), positionsByIsin.size(), priced, totalPortfolioValue);

            List<TradeRepublicPort.TrPosition> positions = new ArrayList<>();
            for (var entry : positionsByIsin.entrySet()) {
                String isin = entry.getKey();
                JsonNode pos = entry.getValue();
                BigDecimal size = new BigDecimal(pos.path("netSize").asText("0"));

                if (size.compareTo(BigDecimal.ZERO) <= 0) continue;

                BigDecimal averageBuyIn = new BigDecimal(pos.path("averageBuyIn").asText("0"));
                BigDecimal currentPrice = tickerPrices.getOrDefault(isin, averageBuyIn);

                positions.add(new TradeRepublicPort.TrPosition(isin, size, averageBuyIn, currentPrice));
            }

            if (totalPortfolioValue.compareTo(BigDecimal.ZERO) > 0
                    || !positions.isEmpty()
                    || receivedPortfolioIds.contains(secAccount.externalId())) {
                accounts.add(new TrAccountData(
                    secAccount.externalId(),
                    secAccount.name(),
                    secAccount.type(),
                    totalPortfolioValue,
                    positions,
                    peaCash));
            }
        }

        if (cashJson.get() != null
                && accounts.stream().noneMatch(a -> "tr_cash".equals(a.externalId()))) {
            accounts.addAll(parseCashJson(cashJson.get()));
        }

        if (accounts.isEmpty()) {
            throw new SyncException(
                "No portfolio data received from Trade Republic. Please try again later.");
        }

        log.info("TR portfolio fetched: {} account(s)", accounts.size());
        return accounts;
    }

    // ─── Private helpers ──────────────────────────────────────────────────────

    private SyncException mapAuthError(String responseBody, String fallback) {
        if (responseBody != null) {
            if (responseBody.contains("VALIDATION_CODE_INVALID")) {
                return new SyncException("VALIDATION_CODE_INVALID");
            }
            if (responseBody.contains("NUMBER_INVALID")) {
                return new SyncException("NUMBER_INVALID");
            }
            if (responseBody.contains("PIN_INVALID")) {
                return new SyncException("PIN_INVALID");
            }
            if (responseBody.contains("AUTHENTICATION_ERROR")) {
                return new SyncException("AUTHENTICATION_ERROR");
            }
        }
        return new SyncException(fallback);
    }

    private boolean isAuthError(String payload) {
        return payload != null && payload.contains("AUTHENTICATION_ERROR");
    }

    private int extractWsId(String text) {
        int space = text.indexOf(' ');
        if (space <= 0) return -1;
        try {
            return Integer.parseInt(text.substring(0, space));
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private String extractWsPayload(String text) {
        int first = text.indexOf(' ');
        if (first < 0) return text;
        int second = text.indexOf(' ', first + 1);
        if (second < 0) return text.substring(first + 1);
        return text.substring(second + 1);
    }

    private String buildConnectMessage() {
        try {
            Map<String, Object> payload = Map.of(
                "locale",          "fr",
                "platformId",      "webtrading",
                "platformVersion", "chrome - 125.0.0",
                "clientId",        "app.traderepublic.com",
                "clientVersion",   "3.151.3"
            );
            return "connect " + WS_VERSION + " " + objectMapper.writeValueAsString(payload);
        } catch (Exception ex) {
            throw new SyncException("Failed to build TR connect message: " + ex.getMessage());
        }
    }

    private String buildSub(int id, Map<String, Object> payload) {
        try {
            return "sub " + id + " " + objectMapper.writeValueAsString(payload);
        } catch (Exception ex) {
            throw new SyncException("Failed to build subscription message: " + ex.getMessage());
        }
    }

    private String sub(int id, String type, String token) {
        return buildSub(id, Map.of("type", type, "token", token));
    }

    private String subWithId(int id, String type, String idParam, String token) {
        return buildSub(id, Map.of("type", type, "id", idParam, "token", token));
    }

    private String subCompactPortfolio(int id, String secAccNo, String token) {
        Map<String, Object> payload = new java.util.LinkedHashMap<>();
        payload.put("type", "compactPortfolioByType");
        payload.put("secAccNo", secAccNo);
        payload.put("token", token);
        return buildSub(id, payload);
    }

    private String subAvailableCash(int id, String cashAccountNumber, String token) {
        Map<String, Object> payload = new java.util.LinkedHashMap<>();
        payload.put("type", "availableCash");
        payload.put("accountNumber", cashAccountNumber);
        payload.put("token", token);
        return buildSub(id, payload);
    }

    private List<SecAccount> extractSecAccounts(String sessionToken) {
        try {
            String[] parts = sessionToken.split("\\.");
            if (parts.length < 2) return List.of();
            String payload = new String(java.util.Base64.getUrlDecoder().decode(parts[1]));
            JsonNode root = objectMapper.readTree(payload);
            JsonNode ownerAccounts = root.path("act").path("acc").path("owner");
            if (!ownerAccounts.isObject()) return List.of();

            List<SecAccount> result = new ArrayList<>();
            Set<String> usedExternalIds = new HashSet<>();
            addSecAccountsFromWrapper(result, usedExternalIds, "default", ownerAccounts.path("default"));

            ownerAccounts.fields().forEachRemaining(entry -> {
                String wrapper = entry.getKey();
                if (!"default".equals(wrapper)) {
                    addSecAccountsFromWrapper(result, usedExternalIds, wrapper, entry.getValue());
                }
            });
            return result;
        } catch (Exception ex) {
            log.warn("Failed to extract sec account numbers from JWT: {}", ex.getMessage());
        }
        return List.of();
    }

    private void addSecAccountsFromWrapper(
        List<SecAccount> result,
        Set<String> usedExternalIds,
        String wrapper,
        JsonNode wrapperNode
    ) {
        JsonNode secAccounts = wrapperNode.path("sec");
        if (!secAccounts.isArray()) return;

        AccountType type = accountTypeForWrapper(wrapper);
        String baseExternalId = externalIdForWrapper(wrapper);
        String baseName = nameForWrapper(wrapper);
        JsonNode cashAccounts = wrapperNode.path("cash");

        int position = 0;
        for (JsonNode acc : secAccounts) {
            String accountNumber = acc.asText(null);
            if (accountNumber == null || accountNumber.isBlank()) continue;
            String cashAccountNumber = cashAccounts.isArray() && cashAccounts.size() > position
                ? cashAccounts.get(position).asText(null)
                : null;

            // The dedup suffix counter is deliberately separate from `position`:
            // bumping it on an external-id collision must not shift cash-account pairing.
            int suffix = position;
            String externalId = suffix == 0 ? baseExternalId : baseExternalId + "_" + (suffix + 1);
            while (!usedExternalIds.add(externalId)) {
                suffix++;
                externalId = baseExternalId + "_" + (suffix + 1);
            }
            String name = suffix == 0 ? baseName : baseName + " " + (suffix + 1);
            result.add(new SecAccount(wrapper, accountNumber, cashAccountNumber, externalId, name, type));
            position++;
        }
    }

    private AccountType accountTypeForWrapper(String wrapper) {
        return "tax_wrapper_fr".equals(wrapper) ? AccountType.PEA : AccountType.COMPTE_TITRES;
    }

    private String externalIdForWrapper(String wrapper) {
        return switch (wrapper) {
            case "default" -> "tr_securities";
            case "tax_wrapper_fr" -> "tr_pea";
            default -> "tr_" + wrapper.replaceAll("[^a-zA-Z0-9]+", "_").toLowerCase();
        };
    }

    private String nameForWrapper(String wrapper) {
        return switch (wrapper) {
            case "default" -> "TR Titres";
            case "tax_wrapper_fr" -> "TR PEA";
            default -> "TR Titres";
        };
    }

    private List<TrAccountData> parseCashJson(String json) {
        log.info("TR availableCash raw: {}", json);
        List<TrAccountData> accounts = new ArrayList<>();
        BigDecimal value = parseCashValue(json);
        if (value.compareTo(BigDecimal.ZERO) >= 0) {
            accounts.add(new TrAccountData("tr_cash", "TR Cash", AccountType.CHECKING, value, List.of()));
        }
        return accounts;
    }

    private BigDecimal parseCashValue(String json) {
        if (json == null || json.isBlank()) return BigDecimal.ZERO;
        try {
            JsonNode root = objectMapper.readTree(json);
            JsonNode array = root.isArray() ? root : root.path("availableCash");
            if (array.isMissingNode()) array = root;

            if (array.isArray()) {
                for (JsonNode item : array) {
                    BigDecimal value = extractValue(item);
                    if (value.compareTo(BigDecimal.ZERO) >= 0) {
                        return value;
                    }
                }
            } else if (array.isObject()) {
                BigDecimal value = extractValue(array);
                if (value.compareTo(BigDecimal.ZERO) >= 0) {
                    return value;
                }
            }
        } catch (Exception ex) {
            log.error("Failed to parse TR availableCash: {}", json, ex);
        }
        return BigDecimal.ZERO;
    }

    private BigDecimal extractValue(JsonNode node) {
        if (node == null || node.isMissingNode()) return BigDecimal.ZERO;
        if (node.has("value"))   return new BigDecimal(node.get("value").asText("0"));
        if (node.has("amount"))  return new BigDecimal(node.get("amount").asText("0"));
        if (node.isNumber())     return node.decimalValue();
        return BigDecimal.ZERO;
    }
}

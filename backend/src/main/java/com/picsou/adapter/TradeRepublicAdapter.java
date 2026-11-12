package com.picsou.adapter;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.picsou.adapter.sidecar.SidecarBank;
import com.picsou.adapter.sidecar.SidecarClient;
import com.picsou.adapter.sidecar.SidecarSessionState;
import com.picsou.adapter.sidecar.SidecarWebClientFactory;
import com.picsou.exception.SyncException;
import com.picsou.model.AccountType;
import com.picsou.port.SidecarErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.socket.client.ReactorNettyWebSocketClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URI;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;

/**
 * Adapter for Trade Republic's unofficial API.
 *
 * Auth (HTTP) is delegated to the tr-auth Python sidecar, which handles
 * the AWS WAF challenge that cannot be solved from plain Java HTTP.
 *
 * Data fetching uses the TR WebSocket API directly (no WAF needed).
 * Protocol version: 31. The session token authenticates the handshake as a {@code tr_session} cookie
 * and is still passed in each subscription payload.
 *
 * WebSocket subscriptions used:
 *   - availableCash      → cash balance
 *   - compactPortfolioByType → list of positions (isin, netSize, averageBuyIn), grouped by category
 *   - instrument         → the venues (exchangeIds) an ISIN is priced on
 *   - ticker             → current price per instrument, as ISIN.venue (subscribed dynamically)
 *
 * Portfolio value = sum(ticker.last.price × position.netSize) for each position.
 */
@Component
public class TradeRepublicAdapter {

    private static final Logger log = LoggerFactory.getLogger(TradeRepublicAdapter.class);

    private static final String WS_URL     = "wss://api.traderepublic.com/";
    private static final int    WS_VERSION = 31;
    // Lang & Schwarz Exchange, TR's home venue for equities and ETFs (GH #23).
    private static final String DEFAULT_EXCHANGE = "LSX";
    private static final Duration DEFAULT_AUTH_TIMEOUT = Duration.ofSeconds(45);
    /**
     * An in-app confirmation waits on a human unlocking their phone. The sidecar caps that wait at
     * 120 s, so this has to sit above it or the adapter would time out on a login about to succeed.
     */
    private static final Duration DEFAULT_COMPLETE_TIMEOUT = Duration.ofSeconds(150);
    private static final Duration DEFAULT_SESSION_TIMEOUT = Duration.ofSeconds(45);
    /** TR renders the export on demand, which takes a while over a long window. */
    private static final Duration DEFAULT_TRANSACTIONS_TIMEOUT = Duration.ofSeconds(120);
    /**
     * The CSV export is read whole, and Spring's 256 KiB default drops it from about 1,200 rows
     * (a real 721-row, 3-year export was 155 KB). 16 MiB, as for Caisse d'Epargne, holds some
     * 70,000 rows, past what the 5-year window the sidecar accepts can carry.
     */
    private static final int TRANSACTIONS_MAX_BYTES = 16 * 1024 * 1024;
    // Total time the WebSocket read may take before the sync goes on with what it has.
    // Kept under WS_HARD_TIMEOUT around the whole exchange, which fails the sync.
    static final Duration WS_WAIT_CAP = Duration.ofSeconds(30);
    static final Duration WS_HARD_TIMEOUT = Duration.ofSeconds(45);

    private record SecAccount(
        String wrapper,
        String accountNumber,
        String cashAccountNumber,
        String externalId,
        String name,
        AccountType type
    ) {}

    private final SidecarClient sidecar;
    private final ObjectMapper  objectMapper;
    private final Duration      authTimeout;
    private final Duration      completeTimeout;
    private final Duration      sessionTimeout;
    private final Duration      transactionsTimeout;
    private final URI           wsUri;

    @Autowired
    public TradeRepublicAdapter(
        SidecarWebClientFactory clients,
        ObjectMapper objectMapper
    ) {
        this(
            objectMapper,
            clients.create(SidecarBank.TRADE_REPUBLIC.label(), SidecarBank.TRADE_REPUBLIC.serviceName(),
                builder -> builder.codecs(c -> c.defaultCodecs().maxInMemorySize(TRANSACTIONS_MAX_BYTES))),
            DEFAULT_AUTH_TIMEOUT,
            DEFAULT_COMPLETE_TIMEOUT,
            DEFAULT_SESSION_TIMEOUT,
            URI.create(WS_URL)
        );
    }

    TradeRepublicAdapter(ObjectMapper objectMapper, WebClient client, Duration timeout, URI wsUri) {
        this(objectMapper, client, timeout, timeout, timeout, timeout, wsUri);
    }

    TradeRepublicAdapter(
        ObjectMapper objectMapper,
        WebClient client,
        Duration authTimeout,
        Duration completeTimeout,
        Duration sessionTimeout,
        URI wsUri
    ) {
        this(objectMapper, client, authTimeout, completeTimeout, sessionTimeout,
            DEFAULT_TRANSACTIONS_TIMEOUT, wsUri);
    }

    TradeRepublicAdapter(
        ObjectMapper objectMapper,
        WebClient client,
        Duration authTimeout,
        Duration completeTimeout,
        Duration sessionTimeout,
        Duration transactionsTimeout,
        URI wsUri
    ) {
        this.transactionsTimeout = transactionsTimeout;
        this.objectMapper    = objectMapper;
        this.sidecar         = new SidecarClient(client, objectMapper, SidecarBank.TRADE_REPUBLIC,
            SidecarErrorCode.AUTH_ATTEMPT_EXPIRED);
        this.authTimeout     = authTimeout;
        this.completeTimeout = completeTimeout;
        this.sessionTimeout  = sessionTimeout;
        this.wsUri           = wsUri;
    }

    // ─── Auth (delegated to the tr-auth sidecar) ──────────────────────────────

    /**
     * Step 1: sends phone number + PIN to Trade Republic through the sidecar, which answers once
     * TR has asked for the second factor. Credentials are NOT stored: they are used only for this call.
     *
     * @param phoneNumber international format (+33…) or local (06…, auto-converted)
     * @param pin         4-digit PIN or password (1..128 characters)
     */
    public TrInitiate initiateAuth(String phoneNumber, String pin) {
        log.info("Delegating TR auth initiation to tr-auth sidecar");
        TrInitiate response = sidecar.post(
            "/initiate",
            Map.of("phoneNumber", phoneNumber, "pin", pin),
            TrInitiate.class,
            authTimeout,
            "Could not initiate Trade Republic authentication",
            SidecarErrorCode.INVALID_CREDENTIALS
        );
        if (response.processId() == null || response.processId().isBlank()) {
            throw sidecar.coded(SidecarErrorCode.UPSTREAM_FORMAT_CHANGED,
                "Trade Republic did not return an authentication attempt", null);
        }
        return response;
    }

    /**
     * Step 2: waits for the in-app confirmation (or checks the 4-digit code) and returns the
     * serialized session state, opaque to the domain.
     *
     * @param tan the 4-digit {@code otp} or 6-digit {@code authenticator} code, null for {@code confirmInApp}
     */
    public String completeAuth(String processId, String tan) {
        log.info("Delegating TR login completion to tr-auth sidecar");
        // The sidecar declares `tan`, so the key carries an explicit null rather than being omitted.
        Map<String, Object> body = new HashMap<>();
        body.put("processId", processId);
        body.put("tan", tan);
        SidecarSessionState response = sidecar.post(
            "/complete",
            body,
            SidecarSessionState.class,
            completeTimeout,
            "Could not complete Trade Republic authentication",
            SidecarErrorCode.INVALID_OTP
        );
        if (response.sessionState() == null || response.sessionState().isBlank()) {
            throw sidecar.coded(SidecarErrorCode.UPSTREAM_FORMAT_CHANGED,
                "Trade Republic did not return a session", null);
        }
        return response.sessionState();
    }

    /**
     * Turns the stored state into a live {@code tr_session} cookie value and the decoded claims
     * (the securities and cash accounts live in {@code act.acc.owner}). The refresh cookie is not
     * rotated, so the stored state never changes.
     */
    public TrSession openSession(String sessionState) {
        log.info("Opening TR session via tr-auth sidecar");
        TrSession session = sidecar.post(
            "/session",
            Map.of("sessionState", sessionState),
            TrSession.class,
            sessionTimeout,
            "Could not open the Trade Republic session",
            SidecarErrorCode.SESSION_EXPIRED
        );
        if (session.sessionToken() == null || session.sessionToken().isBlank()) {
            throw sidecar.coded(SidecarErrorCode.UPSTREAM_FORMAT_CHANGED,
                "Trade Republic returned no session token", null);
        }
        return session;
    }

    /**
     * Trade Republic's own CSV export of the account activity between two dates (inclusive),
     * rendered by the sidecar from the stored session. The body is returned untouched: the
     * caller's parser owns its format, and it carries personal data, so it is never logged here.
     */
    public String fetchTransactionsCsv(String sessionState, LocalDate from, LocalDate to) {
        log.info("Fetching TR transactions export via tr-auth sidecar");
        return sidecar.post(
            "/transactions",
            Map.of("sessionState", sessionState, "from", from.toString(), "to", to.toString()),
            String.class,
            transactionsTimeout,
            "Could not fetch the Trade Republic transactions",
            SidecarErrorCode.SESSION_EXPIRED
        );
    }

    // ─── Data (WebSocket, no WAF needed) ──────────────────────────────────────

    /**
     * Connects via WebSocket and fetches current balances for all sub-portfolios.
     *
     * NOTE: The TR WebSocket API is reverse-engineered and undocumented.
     * Raw responses are logged at INFO level to help diagnose format changes.
     */
    public List<TrAccountData> fetchAccounts(String sessionToken) {
        return fetchAccounts(sessionToken, null);
    }

    /** As above; {@code claims} is the decoded {@code tr_claims} the sidecar returned with the session. */
    public List<TrAccountData> fetchAccounts(String sessionToken, JsonNode claims) {
        log.info("Fetching TR portfolio via WebSocket (protocol v{})", WS_VERSION);

        List<SecAccount> secAccounts = extractSecAccounts(sessionToken, claims);
        log.info("TR JWT sec accounts: {}",
            secAccounts.stream()
                .map(acc -> acc.wrapper() + ":" + acc.name())
                .toList());

        AtomicReference<String> cashJson = new AtomicReference<>();
        ConcurrentHashMap<String, String> scopedCashJsonByAccount = new ConcurrentHashMap<>();
        ConcurrentHashMap<String, ConcurrentHashMap<String, JsonNode>> positionsByAccount = new ConcurrentHashMap<>();
        ConcurrentHashMap<String, BigDecimal> tickerPrices = new ConcurrentHashMap<>();
        Set<String> bondIsins = ConcurrentHashMap.newKeySet();
        Set<String> unpricedBondIsins = ConcurrentHashMap.newKeySet();
        ConcurrentHashMap<Integer, String> tickerSubToIsin = new ConcurrentHashMap<>();
        ConcurrentHashMap<Integer, String> instrumentSubToIsin = new ConcurrentHashMap<>();
        Set<String> resolvingIsins = ConcurrentHashMap.newKeySet();
        ConcurrentHashMap<Integer, SecAccount> portfolioSubIds = new ConcurrentHashMap<>();
        ConcurrentHashMap<Integer, SecAccount> scopedCashSubIds = new ConcurrentHashMap<>();
        Set<String> receivedPortfolioIds = ConcurrentHashMap.newKeySet();
        // Portfolio subscriptions that answered with an error frame or an unparsable payload.
        // They count as answered (the wait must not hang on them) but say nothing about the
        // portfolio, so the account is left untouched this sync instead of being persisted as
        // an empty one, which deleted every holding and snapshotted the securities at zero.
        Set<String> erroredPortfolioIds = ConcurrentHashMap.newKeySet();
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
        headers.set(HttpHeaders.COOKIE, "tr_session=" + sessionToken);
        String connectMsg = buildConnectMessage();

        new ReactorNettyWebSocketClient()
            .execute(wsUri, headers, session ->
                session.send(Mono.just(session.textMessage(connectMsg)))
                    .thenMany(
                        session.receive()
                            .map(msg -> msg.getPayloadAsText())
                            .concatMap(text -> {
                                log.info("TR WS <-- {}", describeFrame(text));
                                // Opt-in only: the payload is account data (numbers, balances, positions).
                                log.trace("TR WS <-- payload: {}", text);

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
                                    // Counted once per subscription: a second frame for an account
                                    // already answered (an error after an answer, a delta) must not
                                    // complete the wait while another account is still silent.
                                    if (receivedPortfolioIds.add(account.externalId())) {
                                        receivedPortfolios.incrementAndGet();
                                    }
                                    log.info("TR compactPortfolioByType [{}] answered ({} chars)",
                                             account.name(), payload.length());
                                    if ("E".equals(extractWsType(text))) {
                                        erroredPortfolioIds.add(account.externalId());
                                        expectedTickers.compareAndSet(-1, 0);
                                        log.warn("TR compactPortfolioByType [{}] answered with an error frame: {} "
                                            + "-- keeping the previous sync's holdings", account.name(),
                                            payload.length() > 300 ? payload.substring(0, 300) : payload);
                                        return Mono.just(text);
                                    }
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
                                                boolean bondCategory = isBond(cat.path("categoryType").asText(""));
                                                for (JsonNode position : cat.path("positions")) {
                                                    if (bondCategory) {
                                                        String isin = position.path("isin").asText(
                                                            position.path("instrumentId").asText(""));
                                                        if (!isin.isEmpty()) {
                                                            bondIsins.add(isin);
                                                        }
                                                    }
                                                    positions.add(position);
                                                }
                                            }
                                        } else {
                                            JsonNode posArray = root.isArray() ? root : root.path("positions");
                                            if (posArray.isArray()) posArray.forEach(positions::add);
                                        }

                                        if (!positions.isEmpty()) {
                                            List<String> instrumentMsgs = new ArrayList<>();
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
                                                    if (isBond(instrumentType)) {
                                                        bondIsins.add(isin);
                                                    }
                                                    if ("privateFund".equals(instrumentType)) {
                                                        log.info("TR compactPortfolioByType [{}]: skipping ticker subscription for privateFund {}", account.name(), isin);
                                                    } else if (resolvingIsins.add(isin)) {
                                                        // A ticker id is ISIN.<venue>, and compactPortfolioByType
                                                        // names no venue. Guessing one (LSX, TRD0 for crypto) drew
                                                        // FORBIDDEN whenever TR priced the instrument elsewhere
                                                        // (GH #23, #268), so the instrument answer names it; its
                                                        // ticker is subscribed once that answer arrives. Once per
                                                        // ISIN: tickerPrices is keyed by ISIN for every account.
                                                        int iid = subIdCounter.incrementAndGet();
                                                        instrumentSubToIsin.put(iid, isin);
                                                        instrumentMsgs.add(subWithId(iid, "instrument", isin, sessionToken));
                                                    }
                                                }
                                            }
                                            // Each instrument subscription leads to exactly one ticker subscription.
                                            int prev = expectedTickers.get();
                                            expectedTickers.set((prev < 0 ? 0 : prev) + instrumentMsgs.size());
                                            log.info("TR compactPortfolioByType [{}]: {} positions, resolving {} instruments",
                                                     account.name(), positions.size(), instrumentMsgs.size());

                                            if (!instrumentMsgs.isEmpty()) {
                                                return session.send(
                                                    Flux.fromIterable(instrumentMsgs)
                                                        .map(session::textMessage)
                                                ).thenReturn(text);
                                            }
                                        } else {
                                            expectedTickers.compareAndSet(-1, 0);
                                            log.info("TR compactPortfolioByType [{}]: no positions found", account.name());
                                        }
                                    } catch (Exception ex) {
                                        log.error("Failed to parse compactPortfolioByType [{}] ({})",
                                            account.name(), ex.getClass().getSimpleName());
                                        erroredPortfolioIds.add(account.externalId());
                                        expectedTickers.compareAndSet(-1, 0);
                                    }

                                } else if (instrumentSubToIsin.containsKey(wsId)) {
                                    // Removed on the first frame so a later one cannot subscribe a second ticker.
                                    String isin = instrumentSubToIsin.remove(wsId);
                                    if (bondIsins.contains(isin) && !isEurNominal(payload)) {
                                        // A percentage of a non-EUR (or unknown) nominal is not a EUR
                                        // price: leave it unpriced, the position stays valued at cost.
                                        unpricedBondIsins.add(isin);
                                    }
                                    String tickerId = isin + "." + tickerExchange(payload);
                                    int tid = subIdCounter.incrementAndGet();
                                    tickerSubToIsin.put(tid, isin);
                                    log.info("TR WS --> sub {} ticker {}", tid, tickerId);
                                    return session.send(Mono.just(session.textMessage(
                                        subWithId(tid, "ticker", tickerId, sessionToken)))).thenReturn(text);

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
                                            if (priceStr != null && unpricedBondIsins.contains(isin)) {
                                                log.warn("TR bond {}: nominal currency is not EUR or unknown -- "
                                                    + "current price left empty", isin);
                                            } else if (priceStr != null) {
                                                tickerPrices.put(isin, tickerPriceInEur(
                                                    new BigDecimal(priceStr), bondIsins.contains(isin)));
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
                            .transform(frames -> untilAnsweredOrCapped(frames, text -> {
                                if (authExpired.get()) return true;
                                boolean cashDone = cashJson.get() != null
                                        && receivedScopedCashSubs.size() >= totalScopedCashSubs;
                                boolean allPortfoliosIn = receivedPortfolios.get() >= totalPortfolioSubs;
                                int exp = expectedTickers.get();
                                boolean tickersDone = allPortfoliosIn
                                        && exp >= 0
                                        && answeredTickerSubs.size() >= exp;
                                return cashDone && tickersDone;
                            }, WS_WAIT_CAP))
                    )
                    .then()
            )
            .timeout(WS_HARD_TIMEOUT)
            .block();

        if (authExpired.get()) {
            // A token the sidecar just minted was refused: not proof the stored session is dead
            // (a dead one fails at /session), so the session is kept and the sync retried.
            throw sidecar.coded(SidecarErrorCode.UPSTREAM_UNAVAILABLE,
                "Trade Republic refused the session token. Please try again.", null);
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
                        log.warn("TR price unavailable for {}: counted at averageBuyIn in the account total, "
                            + "its current price left empty", isin);
                    }
                }
            }

            log.info("TR portfolio [{}]: {} positions, {} with live prices",
                     secAccount.name(), positionsByIsin.size(), priced);

            List<TrPosition> positions = new ArrayList<>();
            for (var entry : positionsByIsin.entrySet()) {
                String isin = entry.getKey();
                JsonNode pos = entry.getValue();
                BigDecimal size = new BigDecimal(pos.path("netSize").asText("0"));

                if (size.compareTo(BigDecimal.ZERO) <= 0) continue;

                BigDecimal averageBuyIn = new BigDecimal(pos.path("averageBuyIn").asText("0"));
                // Null when TR sent no price: the buy-in stored as the current price read as a
                // market value with zero gain.
                BigDecimal currentPrice = tickerPrices.get(isin);

                positions.add(new TrPosition(isin, size, averageBuyIn, currentPrice));
            }

            boolean answered = receivedPortfolioIds.contains(secAccount.externalId());
            boolean errored = erroredPortfolioIds.contains(secAccount.externalId());
            if (!shouldPersist(totalPortfolioValue, positions.isEmpty(), answered, errored)) {
                if (errored) {
                    log.warn("TR portfolio [{}]: no usable portfolio answer this sync -- "
                        + "the account keeps its previous holdings and balance", secAccount.name());
                }
                continue;
            }
            accounts.add(new TrAccountData(
                secAccount.externalId(),
                secAccount.name(),
                secAccount.type(),
                totalPortfolioValue,
                positions,
                peaCash));
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

    /**
     * A frame for the log: its subscription id, its type and its size. Never its payload: an
     * answer carries the member's account numbers, balances and positions.
     */
    static String describeFrame(String text) {
        String frame = text.trim();
        int first = frame.indexOf(' ');
        if (first < 0) {
            return frame.length() <= 16 ? frame : "(" + text.length() + " chars)";
        }
        int second = frame.indexOf(' ', first + 1);
        String id = frame.substring(0, first);
        String type = second < 0 ? frame.substring(first + 1) : frame.substring(first + 1, second);
        if (id.isEmpty() || !id.chars().allMatch(Character::isDigit) || type.length() != 1) {
            return "(" + text.length() + " chars)";
        }
        return id + " " + type + " (" + text.length() + " chars)";
    }

    /** The frame type between the id and the payload: A (answer), D (delta), E (error), C (complete). */
    private String extractWsType(String text) {
        int first = text.indexOf(' ');
        if (first < 0) return "";
        int second = text.indexOf(' ', first + 1);
        return second < 0 ? text.substring(first + 1) : text.substring(first + 1, second);
    }

    /**
     * Exact match on TR's type value (an {@code instrumentType} or a {@code categoryType}),
     * never a substring: a bond ETF or bond fund is quoted per unit in EUR and must not be scaled.
     * NOTE: "bond" is the value assumed from TR's naming, not yet confirmed on a real payload.
     */
    static boolean isBond(String type) {
        return "bond".equalsIgnoreCase(type);
    }

    /**
     * True only when TR's {@code instrument} answer names EUR as the instrument currency, so an
     * absent or different currency fails closed (the price is left empty rather than a USD
     * percentage stored as EUR). Looks at {@code currencyId}, {@code currency} and
     * {@code currencies[]}.
     */
    boolean isEurNominal(String instrumentPayload) {
        try {
            JsonNode root = objectMapper.readTree(instrumentPayload);
            for (String field : List.of("currencyId", "currency")) {
                if (root.hasNonNull(field)) return "EUR".equalsIgnoreCase(root.path(field).asText());
            }
            JsonNode currencies = root.path("currencies");
            if (currencies.isArray() && !currencies.isEmpty()) {
                JsonNode first = currencies.get(0);
                return "EUR".equalsIgnoreCase(first.isTextual() ? first.asText() : first.path("id").asText(""));
            }
        } catch (JsonProcessingException ex) {
            log.warn("Unparsable TR instrument answer, nominal currency unknown");
        }
        return false;
    }

    /**
     * TR quotes a bond's ticker as a percentage of its nominal (98.25 = 98.25 %), while
     * {@code averageBuyIn} and {@code netSize} are already per euro of nominal (0.8941, 559.19).
     * Left as is, the 98.25 multiplied by the nominal inflated the position value a hundredfold.
     */
    static BigDecimal tickerPriceInEur(BigDecimal quoted, boolean bond) {
        return bond ? quoted.movePointLeft(2) : quoted;
    }

    /**
     * The venue to price an instrument on, read from TR's {@code instrument} answer.
     *
     * <p>LSX wins whenever TR lists it: equities and ETFs are priced there today and their
     * {@code exchangeIds} order is not documented. Otherwise the first listed venue (TR-native
     * crypto is not on LSX), and LSX again when the answer names none or is an error frame.
     */
    private String tickerExchange(String instrumentPayload) {
        String first = null;
        try {
            for (JsonNode id : objectMapper.readTree(instrumentPayload).path("exchangeIds")) {
                String exchange = id.asText("");
                if (DEFAULT_EXCHANGE.equals(exchange)) return exchange;
                if (first == null && !exchange.isBlank()) first = exchange;
            }
        } catch (Exception ex) {
            log.warn("Unparsable TR instrument answer, pricing on {}", DEFAULT_EXCHANGE);
        }
        return first != null ? first : DEFAULT_EXCHANGE;
    }

    /**
     * Reads frames until everything expected has answered, or until {@code cap} has elapsed.
     *
     * <p>The cap is on total elapsed time, not on the gap between two frames. A successful
     * ticker subscription keeps streaming price deltas, so a gap timeout never fires while
     * a single other position is ticking: one subscription TR never answers (a delisted
     * share, a contingent value right) kept the read open until the hard timeout around the
     * whole exchange failed the sync, and no account was stored at all. Completing here
     * instead lets the positions that did answer be persisted; the silent one falls back to
     * {@code averageBuyIn} like any other missing ticker price.
     *
     * <p>An error on the stream (the socket dropping, a frame that fails to process) ends the
     * read the same way, keeping the frames received so far: failing here would throw away
     * every account that had already answered.
     */
    static <T> Flux<T> untilAnsweredOrCapped(Flux<T> frames, Predicate<T> allAnswered, Duration cap) {
        return frames.takeUntil(allAnswered).take(cap)
            .onErrorResume(error -> {
                log.warn("TR WebSocket read ended early ({}), continuing with the frames received so far",
                    error.getClass().getSimpleName());
                return Flux.empty();
            });
    }

    /**
     * Whether what came back for a securities account this sync is a portfolio to persist.
     *
     * <p>An error frame or an unparsable payload answers the subscription, so the wait does not
     * hang on it, but it says nothing about the positions. Persisting it as an empty portfolio
     * made {@code TradeRepublicSyncService} delete every holding of the account and snapshot the
     * securities at zero; with a PEA it even kept the cash pocket, so the account looked like
     * cash only. Such an account is skipped and keeps the previous sync's data. A genuinely
     * emptied portfolio still persists: its answer parsed, it just had no positions.
     */
    static boolean shouldPersist(BigDecimal totalValue, boolean noPositions, boolean answered, boolean errored) {
        if (errored && noPositions) return false;
        return totalValue.signum() > 0 || !noPositions || answered;
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

    private List<SecAccount> extractSecAccounts(String sessionToken, JsonNode claims) {
        try {
            JsonNode root = claims != null && claims.path("act").isObject() ? claims : jwtPayload(sessionToken);
            if (root == null) return List.of();
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
            log.warn("Failed to extract sec account numbers from the session claims: {}", ex.getMessage());
        }
        return List.of();
    }

    /** The payload of a JWT-shaped token, or null when the token is not one. */
    private JsonNode jwtPayload(String token) throws JsonProcessingException {
        String[] parts = token.split("\\.");
        if (parts.length < 2) return null;
        return objectMapper.readTree(new String(java.util.Base64.getUrlDecoder().decode(parts[1])));
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
            log.error("Failed to parse TR availableCash ({})", ex.getClass().getSimpleName());
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

    /** Answer to {@code /initiate}: {@code flow} is {@code confirmInApp} or {@code otp}. */
    public record TrInitiate(String processId, String flow, Integer countdownInSeconds) {}

    /** A live {@code tr_session} cookie value and the claims TR issued with it. */
    public record TrSession(String sessionToken, JsonNode claims) {}

    public record TrPosition(
        String isin,
        BigDecimal quantity,
        BigDecimal averageBuyIn,
        BigDecimal currentPrice
    ) {}

    public record TrAccountData(
        String externalId,   // e.g. "tr_pea", "tr_cto", "tr_cash"
        String name,
        AccountType type,
        BigDecimal balanceEur,
        List<TrPosition> positions,
        /**
         * Cash held inside the envelope and already counted in {@code balanceEur}: the PEA's
         * scoped cash. Null for accounts without a pocket (the CTO's cash is the separate
         * "tr_cash" account). Stored on {@code Account.cashBalance} so the valuation can put
         * it on both sides, like the Bourse Direct, BoursoBank and DEGIRO connectors do.
         */
        BigDecimal cashEur
    ) {
        public TrAccountData(String externalId, String name, AccountType type,
                             BigDecimal balanceEur, List<TrPosition> positions) {
            this(externalId, name, type, balanceEur, positions, null);
        }
    }
}

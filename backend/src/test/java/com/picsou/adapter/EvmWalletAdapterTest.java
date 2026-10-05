package com.picsou.adapter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.picsou.adapter.EvmWalletAdapter.Erc20Token;
import com.picsou.adapter.EvmWalletAdapter.EvmNetwork;
import com.picsou.adapter.EvmWalletAdapter.EvmRpc;
import com.picsou.exception.WalletRpcException;
import com.picsou.port.WalletPort.WalletBalance;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EvmWalletAdapterTest {

    private static final String ADDRESS = "0x1111111111111111111111111111111111111111";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    // ── Canned JSON-RPC envelopes ──────────────────────────────────────────
    // 10^18 wei = 1 coin; 2 * 10^18 = 2 coins.
    private static final String ONE_COIN = result("0xDE0B6B3A7640000");
    private static final String TWO_COINS = result("0x1BC16D674EC80000");
    private static final String ZERO = result("0x0");
    // 500 * 10^6 (6-decimal token) = 500000000 = 0x1DCD6500.
    private static final String FIVE_HUNDRED_6DEC = result("0x1DCD6500");
    private static final String EMPTY_CALL = result("0x");           // call to a non-contract
    private static final String MALFORMED_HEX = result("0xZZZ");     // non-hex digits
    private static final String RPC_ERROR = """
        {"jsonrpc":"2.0","id":1,"error":{"code":-32000,"message":"boom"}}""";
    private static final String MISSING_RESULT = """
        {"jsonrpc":"2.0","id":1}""";

    // Sentinels the stub interprets specially (vs a JSON envelope string).
    private static final String TRANSPORT_ERROR = "__TRANSPORT_ERROR__"; // connection reset / 5xx / timeout
    private static final String NO_RESPONSE = "__NO_RESPONSE__";         // empty body

    private static String result(String hex) {
        return "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":\"" + hex + "\"}";
    }

    // ── Content-routed RPC stub (order-independent: networks run concurrently) ──
    private EvmWalletAdapter adapter(List<EvmNetwork> networks, Map<String, String> responses) {
        EvmRpc rpc = (url, request) -> {
            String key = routeKey(url, request);
            // Unspecified calls default to a benign zero result so tests only
            // declare the responses they care about.
            String canned = responses.getOrDefault(key,
                "eth_call".equals(request.get("method")) ? EMPTY_CALL : ZERO);
            if (TRANSPORT_ERROR.equals(canned)) {
                return Mono.error(new RuntimeException("simulated transport failure"));
            }
            if (NO_RESPONSE.equals(canned)) {
                return Mono.empty();
            }
            return Mono.just(parse(canned));
        };
        return new EvmWalletAdapter(rpc, networks);
    }

    private EvmWalletAdapter adapter(List<EvmNetwork> networks) {
        return adapter(networks, Map.of());
    }

    /**
     * Adapter whose transport fails the first {@code failuresBeforeSuccess} calls to a given
     * route and then succeeds, counting attempts. Lets the retry policy be observed by
     * outcome, which the canned-response stub above cannot do: its sentinels either always
     * fail or never do, so retrying changes nothing either way.
     */
    private EvmWalletAdapter retryingAdapter(
        List<EvmNetwork> networks, String route, int failuresBeforeSuccess, AtomicInteger attempts) {

        EvmRpc rpc = (url, request) -> {
            String key = routeKey(url, request);
            if (!key.equals(route)) {
                return Mono.just(parse("eth_call".equals(request.get("method")) ? EMPTY_CALL : ZERO));
            }
            // Count on subscribe, not on assembly: a retry re-subscribes to the same Mono.
            return Mono.defer(() -> attempts.incrementAndGet() <= failuresBeforeSuccess
                ? Mono.error(new RuntimeException("simulated transport failure"))
                : Mono.just(parse(ONE_COIN)));
        };
        return new EvmWalletAdapter(rpc, networks);
    }

    private static String routeKey(String url, Map<String, Object> request) {
        if ("eth_call".equals(request.get("method"))) {
            List<?> params = (List<?>) request.get("params");
            Map<?, ?> callObj = (Map<?, ?>) params.get(0);
            return url + "|" + callObj.get("to");
        }
        return url + "|native";
    }

    private static JsonNode parse(String json) {
        try {
            return MAPPER.readTree(json);
        } catch (Exception ex) {
            throw new RuntimeException(ex);
        }
    }

    private static EvmNetwork net(String name, String symbol, List<Erc20Token> tokens) {
        return new EvmNetwork(name, symbol, "https://" + name + ".example", tokens);
    }

    private static String nativeKey(String netName) {
        return "https://" + netName + ".example|native";
    }

    private static String tokenKey(String netName, String contract) {
        return "https://" + netName + ".example|" + contract;
    }

    // ── Tests ──────────────────────────────────────────────────────────────

    @Test
    void fansOutAcrossNetworks_andAggregatesSameSymbol() {
        // Ethereum (1 ETH) + Arbitrum (2 ETH), both native ETH -> one aggregated 3 ETH entry.
        var adapter = adapter(
            List.of(net("eth", "ETH", List.of()), net("arb", "ETH", List.of())),
            Map.of(nativeKey("eth"), ONE_COIN, nativeKey("arb"), TWO_COINS));

        List<WalletBalance> balances = adapter.fetchBalances(ADDRESS);

        assertThat(balances).singleElement().satisfies(b -> {
            assertThat(b.symbol()).isEqualTo("ETH");
            assertThat(b.amount()).isEqualByComparingTo("3");
        });
    }

    @Test
    void keepsDistinctNativeSymbols_andParsesTokenAtItsDecimals() {
        // BNB Chain: 1 BNB native + 500 USDC (6-decimal test token).
        var adapter = adapter(
            List.of(net("bsc", "BNB", List.of(new Erc20Token("0xUSDC", "USDC", 6)))),
            Map.of(nativeKey("bsc"), ONE_COIN, tokenKey("bsc", "0xUSDC"), FIVE_HUNDRED_6DEC));

        List<WalletBalance> balances = adapter.fetchBalances(ADDRESS);

        assertThat(balances).hasSize(2);
        assertThat(balances.get(0).symbol()).isEqualTo("BNB");   // native leads
        assertThat(balances.get(0).amount()).isEqualByComparingTo("1");
        assertThat(balances).anySatisfy(b -> {
            assertThat(b.symbol()).isEqualTo("USDC");
            assertThat(b.amount()).isEqualByComparingTo("500");
        });
    }

    @Test
    void aggregatesTokenHeldOnMultipleChains() {
        // Same stablecoin on two chains sums into one holding. Natives default to
        // zero, so only the seeded ETH:0 leader and the aggregated USDC remain.
        var adapter = adapter(
            List.of(
                net("a", "ETH", List.of(new Erc20Token("0xUSDC", "USDC", 6))),
                net("b", "ETH", List.of(new Erc20Token("0xUSDC", "USDC", 6)))),
            Map.of(tokenKey("a", "0xUSDC"), FIVE_HUNDRED_6DEC, tokenKey("b", "0xUSDC"), FIVE_HUNDRED_6DEC));

        List<WalletBalance> balances = adapter.fetchBalances(ADDRESS);

        assertThat(balances).hasSize(2);
        assertThat(balances.get(0).symbol()).isEqualTo("ETH");   // seeded native leads
        assertThat(balances.get(0).amount()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(balances).anySatisfy(b -> {
            assertThat(b.symbol()).isEqualTo("USDC");
            assertThat(b.amount()).isEqualByComparingTo("1000");
        });
    }

    @Test
    void jsonRpcErrorOnNativeProbe_failsWholeSync() {
        // A chain's native RPC returning an error payload must fail the whole sync
        // (→ 422, wallet keeps its last balance) rather than silently dropping that
        // chain from net worth while still marking the wallet synced.
        var adapter = adapter(
            List.of(net("down", "ETH", List.of()), net("up", "BNB", List.of())),
            Map.of(nativeKey("down"), RPC_ERROR, nativeKey("up"), ONE_COIN));

        assertThatThrownBy(() -> adapter.fetchBalances(ADDRESS))
            .isInstanceOf(WalletRpcException.class);
    }

    @Test
    void transportErrorOnNativeProbe_isWrappedAndFatal() {
        // A transport-level failure (connection reset / 5xx / timeout) is wrapped as
        // WalletRpcException so it's classified as an expected sync failure (WARN +
        // 422), not an unexpected bug -- and it still aborts the sync.
        var adapter = adapter(
            List.of(net("eth", "ETH", List.of())),
            Map.of(nativeKey("eth"), TRANSPORT_ERROR));

        assertThatThrownBy(() -> adapter.fetchBalances(ADDRESS))
            .isInstanceOf(WalletRpcException.class);
    }

    @Test
    void nullResponseOnNativeProbe_failsWholeSync() {
        // An empty body (dropped connection) must surface as a failure, not be read
        // as a 0 balance -- guards the reactive path's switchIfEmpty.
        var adapter = adapter(
            List.of(net("eth", "ETH", List.of())),
            Map.of(nativeKey("eth"), NO_RESPONSE));

        assertThatThrownBy(() -> adapter.fetchBalances(ADDRESS))
            .isInstanceOf(WalletRpcException.class)
            .hasMessageContaining("no response");
    }

    @Test
    void malformedHexResult_failsSync() {
        // Non-hex digits in a balance result are a broken RPC payload -> throw
        // rather than let a NumberFormatException surface as an opaque 500.
        var adapter = adapter(
            List.of(net("eth", "ETH", List.of())),
            Map.of(nativeKey("eth"), MALFORMED_HEX));

        assertThatThrownBy(() -> adapter.fetchBalances(ADDRESS))
            .isInstanceOf(WalletRpcException.class)
            .hasMessageContaining("malformed hex");
    }

    @Test
    void missingResultField_failsSync() {
        // A well-formed envelope with neither 'result' nor 'error' must not read as 0.
        var adapter = adapter(
            List.of(net("eth", "ETH", List.of())),
            Map.of(nativeKey("eth"), MISSING_RESULT));

        assertThatThrownBy(() -> adapter.fetchBalances(ADDRESS))
            .isInstanceOf(WalletRpcException.class)
            .hasMessageContaining("result");
    }

    @Test
    void jsonRpcErrorOnOneToken_doesNotDropNativeOrOtherTokens() {
        // Native ok, first token errors (JSON-RPC payload), second token ok ->
        // native + good token survive.
        var adapter = adapter(
            List.of(net("eth", "ETH", List.of(
                new Erc20Token("0xBAD", "DAI", 18),
                new Erc20Token("0xUSDC", "USDC", 6)))),
            Map.of(
                nativeKey("eth"), ONE_COIN,
                tokenKey("eth", "0xBAD"), RPC_ERROR,
                tokenKey("eth", "0xUSDC"), FIVE_HUNDRED_6DEC));

        List<WalletBalance> balances = adapter.fetchBalances(ADDRESS);

        assertThat(balances).hasSize(2);
        assertThat(balances).anySatisfy(b -> assertThat(b.symbol()).isEqualTo("ETH"));
        assertThat(balances).anySatisfy(b -> assertThat(b.symbol()).isEqualTo("USDC"));
        assertThat(balances).noneSatisfy(b -> assertThat(b.symbol()).isEqualTo("DAI"));
    }

    @Test
    void transportErrorOnOneToken_isSkipped_notFatal() {
        // A transport error on a single balanceOf call must skip just that token,
        // not abort the whole multi-chain sync (regression guard: the catch must
        // cover wrapped transport errors, not only JSON-RPC error payloads).
        var adapter = adapter(
            List.of(net("eth", "ETH", List.of(
                new Erc20Token("0xBAD", "DAI", 18),
                new Erc20Token("0xUSDC", "USDC", 6)))),
            Map.of(
                nativeKey("eth"), ONE_COIN,
                tokenKey("eth", "0xBAD"), TRANSPORT_ERROR,
                tokenKey("eth", "0xUSDC"), FIVE_HUNDRED_6DEC));

        List<WalletBalance> balances = adapter.fetchBalances(ADDRESS);

        assertThat(balances).hasSize(2);
        assertThat(balances).anySatisfy(b -> assertThat(b.symbol()).isEqualTo("ETH"));
        assertThat(balances).anySatisfy(b -> assertThat(b.symbol()).isEqualTo("USDC"));
        assertThat(balances).noneSatisfy(b -> assertThat(b.symbol()).isEqualTo("DAI"));
    }

    @Test
    void nativeFailureOnOneChain_abortsEvenWhenAnotherChainOnlyLosesAToken() {
        // The two failure modes occurring together across concurrently-queried networks:
        // BNB's native probe fails (fatal) while Ethereum merely loses one token
        // (recoverable). The whole sync must abort -- otherwise the wallet would be marked
        // synced with BNB silently missing, understating net worth. Asserting the outcome
        // rather than any ordering, since the fan-out gives no completion-order guarantee.
        var adapter = adapter(
            List.of(
                net("eth", "ETH", List.of(new Erc20Token("0xBAD", "DAI", 18))),
                net("bnb", "BNB", List.of())),
            Map.of(
                nativeKey("eth"), ONE_COIN,
                tokenKey("eth", "0xBAD"), RPC_ERROR,   // recoverable on its own
                nativeKey("bnb"), RPC_ERROR));         // fatal

        assertThatThrownBy(() -> adapter.fetchBalances(ADDRESS))
            .isInstanceOf(WalletRpcException.class);
    }

    @Test
    void nativeTransportFailureOnOneChain_abortsWhileAnotherChainSucceeds() {
        // Same asymmetry, but the fatal side is a transport error rather than a JSON-RPC
        // payload, and the other chain fully succeeds. A partially-successful fan-out must
        // still abort: never report a subset as if it were the whole wallet.
        var adapter = adapter(
            List.of(
                net("eth", "ETH", List.of()),
                net("bnb", "BNB", List.of())),
            Map.of(
                nativeKey("eth"), ONE_COIN,
                nativeKey("bnb"), TRANSPORT_ERROR));

        assertThatThrownBy(() -> adapter.fetchBalances(ADDRESS))
            .isInstanceOf(WalletRpcException.class);
    }

    @Test
    void transientTransportFailure_isRetried_andTheSyncSucceeds() {
        // The point of the retry: a single blip on one chain must not fail a whole wallet
        // sync. Two failures then success is within the 2-retry budget (3 attempts total).
        AtomicInteger attempts = new AtomicInteger();
        var adapter = retryingAdapter(
            List.of(net("eth", "ETH", List.of())), nativeKey("eth"), 2, attempts);

        List<WalletBalance> balances = adapter.fetchBalances(ADDRESS);

        assertThat(balances).singleElement()
            .satisfies(b -> assertThat(b.amount()).isEqualByComparingTo("1"));
        assertThat(attempts).hasValue(3); // initial + 2 retries
    }

    @Test
    void transportFailureBeyondTheRetryBudget_failsTheSync() {
        // Three failures exceeds the budget, so the native probe stays fatal rather than
        // retrying forever. onRetryExhaustedThrow must surface the ORIGINAL cause, wrapped
        // as WalletRpcException -- not reactor's RetryExhaustedException, which would miss
        // WalletSyncService's expected-failure catch and read as a bug.
        AtomicInteger attempts = new AtomicInteger();
        var adapter = retryingAdapter(
            List.of(net("eth", "ETH", List.of())), nativeKey("eth"), 3, attempts);

        assertThatThrownBy(() -> adapter.fetchBalances(ADDRESS))
            .isInstanceOf(WalletRpcException.class);
        assertThat(attempts).hasValue(3);
    }

    @Test
    void jsonRpcErrorIsNotRetried() {
        // The retry filter excludes WalletRpcException: a node answering "method not found"
        // or "rate limited" will answer the same way three times, so retrying only delays
        // the failure and triples the load on an endpoint that may already be rate-limiting.
        AtomicInteger calls = new AtomicInteger();
        EvmRpc rpc = (url, request) -> {
            calls.incrementAndGet();
            return Mono.just(parse(RPC_ERROR));
        };
        var adapter = new EvmWalletAdapter(rpc, List.of(net("eth", "ETH", List.of())));

        assertThatThrownBy(() -> adapter.fetchBalances(ADDRESS))
            .isInstanceOf(WalletRpcException.class);
        assertThat(calls).hasValue(1);
    }

    @Test
    void transientFailureOnAToken_isRetried_beforeBeingSkipped() {
        // Same budget applies to token calls; only after it is exhausted does the token get
        // skipped, so a blip does not silently drop a holding.
        AtomicInteger attempts = new AtomicInteger();
        var adapter = retryingAdapter(
            List.of(net("eth", "ETH", List.of(new Erc20Token("0xUSDC", "USDC", 18)))),
            tokenKey("eth", "0xUSDC"), 1, attempts);

        List<WalletBalance> balances = adapter.fetchBalances(ADDRESS);

        // Retry succeeded, so the token survives (ONE_COIN at 18 decimals = 1).
        assertThat(balances).anySatisfy(b -> assertThat(b.symbol()).isEqualTo("USDC"));
        assertThat(attempts).hasValue(2);
    }

    @Test
    void summarisesTokenFailuresPerNetwork() {
        // Individually each skipped token logs its own line, which is easy to lose among
        // seven concurrently-queried chains. The shape that matters is the ratio: the sync
        // still succeeds on the native balance, so without this a user could conclude their
        // tokens are gone when the calls merely failed.
        var logger = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(EvmWalletAdapter.class);
        var logs = new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();
        logs.start();
        logger.addAppender(logs);
        try {
            var adapter = adapter(
                List.of(net("poly", "POL", List.of(
                    new Erc20Token("0xA", "USDT", 6),
                    new Erc20Token("0xB", "USDC", 6)))),
                Map.of(
                    nativeKey("poly"), ONE_COIN,
                    tokenKey("poly", "0xA"), RPC_ERROR,
                    tokenKey("poly", "0xB"), RPC_ERROR));

            List<WalletBalance> balances = adapter.fetchBalances(ADDRESS);

            // Native still reported -- the sync does not fail over tokens.
            assertThat(balances).singleElement()
                .satisfies(b -> assertThat(b.symbol()).isEqualTo("POL"));

            assertThat(logs.list)
                .anySatisfy(e -> assertThat(e.getFormattedMessage())
                    .contains("2 of 2 tokens failed"));
        } finally {
            logger.detachAppender(logs);
        }
    }

    @Test
    void emptyCallResult_treatedAsZeroToken() {
        // A "0x" result (call to a non-contract) is not an error -- just no balance.
        var adapter = adapter(
            List.of(net("eth", "ETH", List.of(new Erc20Token("0xUSDC", "USDC", 6)))),
            Map.of(nativeKey("eth"), ONE_COIN, tokenKey("eth", "0xUSDC"), EMPTY_CALL));

        List<WalletBalance> balances = adapter.fetchBalances(ADDRESS);

        assertThat(balances).singleElement().satisfies(b -> assertThat(b.symbol()).isEqualTo("ETH"));
    }

    @Test
    void returnsZeroNative_forValidButEmptyWallet() {
        // Every balance is zero, but the network responded -> report ETH:0, not empty
        // (WalletSyncService would otherwise treat empty as an adapter failure).
        var adapter = adapter(List.of(net("eth", "ETH", List.of())), Map.of(nativeKey("eth"), ZERO));

        List<WalletBalance> balances = adapter.fetchBalances(ADDRESS);

        assertThat(balances).singleElement().satisfies(b -> {
            assertThat(b.symbol()).isEqualTo("ETH");
            assertThat(b.amount()).isEqualByComparingTo(BigDecimal.ZERO);
        });
    }

    @Test
    void malformedAddress_throwsBeforeAnyRpcCall() {
        // Over-long / non-hex addresses are rejected up front (guards padAddress,
        // whose '0'.repeat(64 - length) would otherwise throw for a long string).
        var adapter = adapter(List.of(net("eth", "ETH", List.of())));

        assertThatThrownBy(() -> adapter.fetchBalances("0x" + "1".repeat(41)))
            .isInstanceOf(WalletRpcException.class)
            .hasMessageContaining("Malformed EVM address");
        assertThatThrownBy(() -> adapter.fetchBalances("not-an-address"))
            .isInstanceOf(WalletRpcException.class);
        assertThatThrownBy(() -> adapter.fetchBalances(null))
            .isInstanceOf(WalletRpcException.class);
    }

    @Test
    void validateAddress_rejectsMalformed_asABadRequest() {
        // Pre-persist gate: a typed-in typo must read as HTTP 400
        // (IllegalArgumentException), not as the 422 a failed sync produces.
        var adapter = adapter(List.of(net("eth", "ETH", List.of())));

        assertThatThrownBy(() -> adapter.validateAddress("0x123"))
            .isInstanceOf(IllegalArgumentException.class)
            .isNotInstanceOf(WalletRpcException.class)
            .hasMessageContaining("Invalid EVM address");
        assertThatThrownBy(() -> adapter.validateAddress("not-an-address"))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> adapter.validateAddress(null))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void validateAddress_acceptsWellFormedAddress_inEitherCase() {
        var adapter = adapter(List.of(net("eth", "ETH", List.of())));

        assertThatCode(() -> adapter.validateAddress(ADDRESS)).doesNotThrowAnyException();
        // EIP-55 checksummed addresses mix cases -- they must pass unchanged.
        assertThatCode(() -> adapter.validateAddress("0xc579D4Eb8179aF7f322F028D12BDDB845cA10a3b"))
            .doesNotThrowAnyException();
    }
}

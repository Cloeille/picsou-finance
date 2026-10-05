package com.picsou.adapter;

import com.fasterxml.jackson.databind.JsonNode;
import com.picsou.adapter.util.JsonRpcResponse;
import com.picsou.exception.WalletRpcException;
import com.picsou.model.Chain;
import com.picsou.port.WalletPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;

/**
 * Tracks a single EVM address across every enabled EVM network. A Ledger (or any
 * secp256k1 key) exposes the <em>same</em> {@code 0x} address on Ethereum, BNB
 * Chain, Polygon, Arbitrum, Optimism, Base and Avalanche C-Chain — so one wallet
 * entry fans out over all of them. {@code eth_getBalance} / {@code eth_call} are
 * byte-identical across EVM chains; only the RPC URL, native symbol and curated
 * token list change per network.
 *
 * <p>The networks are queried <b>concurrently</b> (one wallet = ~7 chains × native
 * + curated tokens), each call bounded by a timeout and a small retry to ride out
 * transient blips, so total latency is roughly the slowest chain rather than the
 * sum of every call.
 *
 * <p>Balances are aggregated by symbol before returning: ETH held on Ethereum +
 * Arbitrum + Optimism + Base rolls into one {@code ETH} entry, and a stablecoin
 * held on two chains sums into one — which also keeps the caller's per-ticker
 * holding upsert (unique on {@code account_id, ticker}) collision-free.
 *
 * <p>Failure isolation is deliberately asymmetric (mirroring the July 2026
 * Ethereum-outage post-mortem — never read a failed RPC as a 0 balance):
 * <ul>
 *   <li>A <b>native</b> probe failure on any chain (transport error, timeout, or a
 *       JSON-RPC error payload — all surfaced as {@link WalletRpcException}) aborts
 *       the whole sync. Silently dropping a chain would understate net worth while
 *       still marking the wallet synced.</li>
 *   <li>A <b>token</b> call failure skips only that token (a reverting/non-standard
 *       contract, or a transient blip after the chain's native probe already
 *       succeeded), keeping the native and every other token — like the Solana
 *       adapter's per-token handling.</li>
 * </ul>
 */
@Component
public class EvmWalletAdapter implements WalletPort {

    private static final Logger log = LoggerFactory.getLogger(EvmWalletAdapter.class);

    /** All these chains use an 18-decimal native coin (ETH, BNB, POL, AVAX). */
    private static final BigDecimal WEI_PER_COIN = BigDecimal.TEN.pow(18);
    /** ERC-20 {@code balanceOf(address)} function selector. */
    private static final String BALANCE_OF_SELECTOR = "0x70a08231";
    /** A well-formed EVM address: {@code 0x} followed by exactly 40 hex chars. */
    private static final Pattern EVM_ADDRESS = Pattern.compile("0x[0-9a-fA-F]{40}");

    /** A valid address is 42 chars; enough to show the user what was rejected. */
    private static final int MESSAGE_ADDRESS_LIMIT = 64;

    private static final Duration RPC_TIMEOUT = Duration.ofSeconds(10);
    private static final int MAX_RETRIES = 2;
    private static final Duration RETRY_BACKOFF = Duration.ofMillis(200);

    /** A curated ERC-20/BEP-20 token we know how to price. */
    record Erc20Token(String contract, String symbol, int decimals) {}

    /** One EVM network: its native coin plus the curated tokens we track on it. */
    record EvmNetwork(String displayName, String nativeSymbol, String rpcUrl, List<Erc20Token> tokens) {}

    /**
     * Thin transport seam: given an RPC URL and a JSON-RPC request body, return a
     * {@code Mono} of the raw response envelope. Production wraps a {@link WebClient};
     * tests inject a stub that routes by URL + request content (order-independent,
     * which matters because the networks are queried concurrently). Timeout, retry,
     * error-classification and envelope validation are applied by the adapter around
     * this, so the seam stays trivial.
     */
    @FunctionalInterface
    interface EvmRpc {
        Mono<JsonNode> call(String rpcUrl, Map<String, Object> request);
    }

    /**
     * Enabled networks, all keyless PublicNode RPCs (no API key, matching the
     * project convention). Token lists are curated — only tokens CoinGecko can
     * price and whose contract we've verified. Extend by adding a row / entry.
     * Note BSC stablecoins use 18 decimals, unlike the 6-decimal versions on most
     * other chains.
     */
    private static final List<EvmNetwork> NETWORKS = List.of(
        new EvmNetwork("Ethereum", "ETH", "https://ethereum-rpc.publicnode.com", List.of(
            new Erc20Token("0xdAC17F958D2ee523a2206206994597C13D831ec7", "USDT", 6),
            new Erc20Token("0xA0b86991c6218b36c1d19D4a2e9Eb0cE3606eB48", "USDC", 6),
            new Erc20Token("0x6B175474E89094C44Da98b954EedeAC495271d0F", "DAI", 18)
        )),
        new EvmNetwork("BNB Chain", "BNB", "https://bsc-rpc.publicnode.com", List.of(
            new Erc20Token("0x55d398326f99059fF775485246999027B3197955", "USDT", 18),
            new Erc20Token("0x8AC76a51cc950d9822D68b83fE1Ad97B32Cd580d", "USDC", 18)
        )),
        new EvmNetwork("Polygon", "POL", "https://polygon-bor-rpc.publicnode.com", List.of(
            new Erc20Token("0xc2132D05D31c914a87C6611C10748AEb04B58e8F", "USDT", 6),
            new Erc20Token("0x3c499c542cEF5E3811e1192ce70d8cC03d5c3359", "USDC", 6)
        )),
        new EvmNetwork("Arbitrum", "ETH", "https://arbitrum-one-rpc.publicnode.com", List.of(
            new Erc20Token("0xFd086bC7CD5C481DCC9C85ebE478A1C0b69FCbb9", "USDT", 6),
            new Erc20Token("0xaf88d065e77c8cC2239327C5EDb3A432268e5831", "USDC", 6)
        )),
        new EvmNetwork("Optimism", "ETH", "https://optimism-rpc.publicnode.com", List.of(
            new Erc20Token("0x94b008aA00579c1307B0EF2c499aD98a8ce58e58", "USDT", 6),
            new Erc20Token("0x0b2C639c533813f4Aa9D7837CAf62653d097Ff85", "USDC", 6)
        )),
        new EvmNetwork("Base", "ETH", "https://base-rpc.publicnode.com", List.of(
            new Erc20Token("0x833589fCD6eDb6E08f4c7C32D4f71b54bdA02913", "USDC", 6)
        )),
        new EvmNetwork("Avalanche C-Chain", "AVAX", "https://avalanche-c-chain-rpc.publicnode.com", List.of(
            new Erc20Token("0x9702230A8Ea53601f5cD2dc00fDBc13d4dF4A8c7", "USDT", 6),
            new Erc20Token("0xB97EF9Ef8734C71904D8002F8b6Bc66Dd9c48a6E", "USDC", 6)
        ))
    );

    private final EvmRpc rpcClient;
    private final List<EvmNetwork> networks;

    public EvmWalletAdapter() {
        this(defaultRpc(WebClient.builder()
            .defaultHeader("Content-Type", "application/json")
            .build()), NETWORKS);
    }

    // Package-private seam for tests: inject an EvmRpc stub and a small network
    // registry pointing at dummy RPC URLs.
    EvmWalletAdapter(EvmRpc rpcClient, List<EvmNetwork> networks) {
        this.rpcClient = rpcClient;
        this.networks = networks;
    }

    private static EvmRpc defaultRpc(WebClient webClient) {
        return (rpcUrl, request) -> webClient.post()
            .uri(rpcUrl)
            .bodyValue(request)
            .retrieve()
            .bodyToMono(JsonNode.class);
    }

    @Override
    public Chain chain() {
        return Chain.EVM;
    }

    @Override
    public List<WalletBalance> fetchBalances(String address) {
        requireEvmAddress(address);

        // Fan out across every network concurrently; the per-call timeout/retry
        // bound each call, and a native failure on any chain aborts the whole sync.
        List<WalletBalance> collected = Flux.fromIterable(networks)
            .flatMap(net -> fetchNetwork(net, address))
            .collectList()
            .block();

        // Aggregate by symbol, seeding the primary native (Ethereum's ETH) so it
        // always leads the list regardless of the concurrent completion order:
        // WalletSyncService reads balances.get(0) as the account's display ticker,
        // and a token-only or empty wallet must still read as the native. The zero
        // seed is dropped by the caller's positive-amount guard, never a holding.
        Map<String, BigDecimal> bySymbol = new LinkedHashMap<>();
        if (!networks.isEmpty()) {
            bySymbol.put(networks.get(0).nativeSymbol(), BigDecimal.ZERO);
        }
        // collectList().block() returns a (possibly empty) list or throws -- never null,
        // so no null guard here: a broken contract must fail loudly rather than read as
        // "this wallet holds nothing".
        for (WalletBalance b : collected) {
            if (b.amount().signum() > 0) {
                merge(bySymbol, b.symbol(), b.amount());
            }
        }

        List<WalletBalance> balances = new ArrayList<>();
        bySymbol.forEach((symbol, amount) -> balances.add(new WalletBalance(symbol, amount)));
        return balances;
    }

    private Flux<WalletBalance> fetchNetwork(EvmNetwork net, String address) {
        // Counts this network's token failures so they can be summarised. Individually the
        // warnings below are easy to lose among the other chains' output, and the shape that
        // matters — "most of this network's tokens failed" — is only visible in aggregate:
        // the sync still succeeds on the native balance, so a user could otherwise conclude
        // their tokens are gone when the calls merely failed.
        AtomicInteger tokenFailures = new AtomicInteger();

        Flux<WalletBalance> tokens = Flux.fromIterable(net.tokens())
            .flatMap(token -> fetchToken(net, token, address)
                .onErrorResume(WalletRpcException.class, ex -> {
                    // One bad token (a reverting/non-standard contract, or a
                    // transient token-call failure) must not drop the network's
                    // native or its other tokens — log and skip.
                    tokenFailures.incrementAndGet();
                    log.warn("EVM token {} on {} failed for {}: {} -- skipping token",
                        token.symbol(), net.displayName(), address, ex.getMessage());
                    return Mono.empty();
                }))
            .doOnComplete(() -> {
                int failed = tokenFailures.get();
                if (failed > 0) {
                    log.warn("EVM {}: {} of {} tokens failed for {} -- balance reported without them",
                        net.displayName(), failed, net.tokens().size(), address);
                }
            });
        // Native first: if it fails, concat never reaches the tokens and the error
        // propagates, aborting the sync rather than under-reporting this chain.
        return Flux.concat(fetchNative(net, address), tokens);
    }

    private Mono<WalletBalance> fetchNative(EvmNetwork net, String address) {
        String context = net.displayName() + " eth_getBalance";
        Map<String, Object> request = Map.of(
            "jsonrpc", "2.0",
            "id", 1,
            "method", "eth_getBalance",
            "params", List.of(address, "latest"));
        return rpc(net, request, context).map(result -> {
            BigInteger wei = parseHex(result.asText(), context);
            BigDecimal coin = new BigDecimal(wei).divide(WEI_PER_COIN, 18, RoundingMode.HALF_UP);
            log.info("EVM balance on {} for {}: {} {}", net.displayName(), address, coin, net.nativeSymbol());
            return new WalletBalance(net.nativeSymbol(), coin);
        });
    }

    private Mono<WalletBalance> fetchToken(EvmNetwork net, Erc20Token token, String address) {
        String context = net.displayName() + " balanceOf " + token.symbol();
        String data = BALANCE_OF_SELECTOR + padAddress(address);
        Map<String, Object> request = Map.of(
            "jsonrpc", "2.0",
            "id", 1,
            "method", "eth_call",
            "params", List.of(Map.of("to", token.contract(), "data", data), "latest"));
        return rpc(net, request, context).map(result -> {
            String hex = result.asText();
            // A call to a non-contract address returns "0x" (empty) -- no balance.
            if (hex == null || hex.length() <= 2) {
                return new WalletBalance(token.symbol(), BigDecimal.ZERO);
            }
            BigInteger raw = parseHex(hex, context);
            BigDecimal amount = new BigDecimal(raw)
                .divide(BigDecimal.TEN.pow(token.decimals()), token.decimals(), RoundingMode.HALF_UP);
            if (amount.signum() > 0) {
                log.info("EVM token balance on {} for {}: {} {}", net.displayName(), address, amount, token.symbol());
            }
            return new WalletBalance(token.symbol(), amount);
        });
    }

    /**
     * Applies the shared call policy around the raw transport: a per-call timeout,
     * a small retry to absorb transient blips, transport-error classification
     * (anything not already a {@link WalletRpcException} — connection reset, HTTP
     * 5xx, timeout, retries exhausted — is wrapped as one so callers treat it as an
     * expected sync failure, not a bug), and JSON-RPC envelope validation.
     */
    private Mono<JsonNode> rpc(EvmNetwork net, Map<String, Object> request, String context) {
        return rpcClient.call(net.rpcUrl(), request)
            .timeout(RPC_TIMEOUT)
            .retryWhen(Retry.backoff(MAX_RETRIES, RETRY_BACKOFF)
                .filter(ex -> !(ex instanceof WalletRpcException))
                .onRetryExhaustedThrow((spec, signal) -> signal.failure()))
            // Name the exception type: a read timeout and a connection reset otherwise
            // produce near-identical messages, and this string is what reaches the log.
            .onErrorMap(ex -> ex instanceof WalletRpcException ? ex
                : new WalletRpcException(
                    context + ": RPC failed (" + ex.getClass().getSimpleName() + ") - " + ex.getMessage(), ex))
            // An empty body (dropped connection) completes without onNext, which
            // would skip validation entirely -- surface it as a failure instead.
            .switchIfEmpty(Mono.error(new WalletRpcException(context + ": RPC returned no response")))
            .map(result -> JsonRpcResponse.requireResult(result, context));
    }

    private static void merge(Map<String, BigDecimal> bySymbol, String symbol, BigDecimal amount) {
        bySymbol.merge(symbol, amount, BigDecimal::add);
    }

    /** A well-formed EVM address: {@code 0x} followed by exactly 40 hex characters. */
    private static boolean isEvmAddress(String address) {
        return address != null && EVM_ADDRESS.matcher(address).matches();
    }

    /**
     * Pre-persist gate called by {@code WalletSyncService.addWallet} before the wallet
     * row is written. A user-supplied typo is a <em>bad request</em> (400), not a sync
     * failure — hence {@link IllegalArgumentException} rather than the
     * {@link WalletRpcException} its fetch-time twin throws.
     */
    @Override
    public void validateAddress(String address) {
        if (!isEvmAddress(address)) {
            throw new IllegalArgumentException(
                "Invalid EVM address '" + forMessage(address) + "' (expected 0x followed by 40 hex characters)");
        }
    }

    /**
     * Bounds and flattens a rejected address before it is echoed. The message reaches the
     * caller verbatim as the 400 body and is also logged, so an unbounded value lets a
     * client amplify a multi-megabyte payload back out of the server, and embedded
     * newlines let it forge extra log lines. A valid address is 42 chars; 64 is plenty to
     * show the user what was rejected.
     */
    private static String forMessage(String address) {
        if (address == null) return "null";
        // Truncate BEFORE flattening: running a regex over the whole input first would
        // allocate a second copy of a multi-megabyte payload, which is the amplification
        // this method exists to prevent.
        String bounded = address.length() <= MESSAGE_ADDRESS_LIMIT
            ? address
            : address.substring(0, MESSAGE_ADDRESS_LIMIT) + "...";
        return bounded.replaceAll("\\s", " ");
    }

    /**
     * The same rule re-checked before any RPC call, for an address that is <em>already
     * stored</em> — one written before {@link #validateAddress} existed, or edited
     * directly in the database. That is a sync problem (422), not a bad request, so it
     * stays a {@link WalletRpcException}. It also guarantees {@link #padAddress}
     * receives exactly 40 hex chars — an over-long string would otherwise make
     * {@code "0".repeat(64 - length)} throw an uncaught {@link IllegalArgumentException}
     * mid-sync.
     */
    private static void requireEvmAddress(String address) {
        if (!isEvmAddress(address)) {
            // Bounded like the validateAddress path: this one handles a *stored* address,
            // so the value is just as untrusted, and its message is logged twice.
            throw new WalletRpcException(
                "Malformed EVM address '" + forMessage(address) + "' (expected 0x + 40 hex characters)");
        }
    }

    /** Left-pads a {@code 0x} address to the 32-byte (64 hex char) ABI word expected by {@code balanceOf}. */
    private static String padAddress(String address) {
        String hex = address.startsWith("0x") ? address.substring(2) : address;
        return "0".repeat(64 - hex.length()) + hex.toLowerCase();
    }

    /**
     * Parses a {@code 0x}-prefixed hex integer. A malformed value (missing prefix,
     * non-hex digits) is a broken RPC response — throw rather than let a raw
     * {@link NumberFormatException} surface as an opaque 500.
     */
    private static BigInteger parseHex(String hex, String context) {
        if (hex == null || !hex.startsWith("0x")) {
            throw new WalletRpcException(context + ": malformed hex '" + hex + "'");
        }
        try {
            return new BigInteger(hex.substring(2), 16);
        } catch (NumberFormatException ex) {
            throw new WalletRpcException(context + ": malformed hex '" + hex + "'");
        }
    }
}

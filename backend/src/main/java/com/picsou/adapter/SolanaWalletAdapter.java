package com.picsou.adapter;

import com.fasterxml.jackson.databind.JsonNode;
import com.picsou.adapter.util.JsonRpcResponse;
import com.picsou.model.Chain;
import com.picsou.port.WalletPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Component
public class SolanaWalletAdapter implements WalletPort {

    private static final Logger log = LoggerFactory.getLogger(SolanaWalletAdapter.class);
    private static final String RPC_URL = "https://api.mainnet-beta.solana.com";
    private static final BigDecimal LAMPORTS_PER_SOL = new BigDecimal("1000000000");
    private static final String SPL_TOKEN_PROGRAM_ID = "TokenkegQfeZyiNwAJbNbGKPFXCWuBvf9Ss623VQ5DA";

    /**
     * Hand-curated mint → symbol map. We only resolve to a known ticker if
     * PriceService can plausibly price it; unknown mints are skipped (we
     * can't EUR-value them anyway). Extend as new stablecoins matter.
     */
    private static final Map<String, String> KNOWN_MINTS = Map.of(
        "EPjFWdd5AufqSSqeM2qN1xzybapC8G4wEGGkZwyTDt1v", "USDC",
        "HzwqbKZw8HxMN6bF2yFZNrht3c2iXXzpKcFu7uBEDKtr", "EURC",
        "Es9vMFrzaCERmJfrF4H2FYD4KCoNkY11McCe8BenwNYB", "USDT"
    );

    private final WebClient webClient;

    public SolanaWalletAdapter() {
        this(WebClient.builder()
            .baseUrl(RPC_URL)
            .defaultHeader("Content-Type", "application/json")
            .build());
    }

    // Package-private seam for tests: inject a WebClient backed by an ExchangeFunction.
    SolanaWalletAdapter(WebClient webClient) {
        this.webClient = webClient;
    }

    @Override
    public Chain chain() {
        return Chain.SOLANA;
    }

    @Override
    public List<WalletBalance> fetchBalances(String address) {
        List<WalletBalance> balances = new ArrayList<>();
        balances.add(fetchSol(address));
        balances.addAll(fetchSplTokens(address));
        return balances;
    }

    private WalletBalance fetchSol(String address) {
        Map<String, Object> rpcRequest = Map.of(
            "jsonrpc", "2.0",
            "id", 1,
            "method", "getBalance",
            "params", new Object[]{address}
        );

        JsonNode response = webClient.post()
            .bodyValue(rpcRequest)
            .retrieve()
            .bodyToMono(JsonNode.class)
            .timeout(Duration.ofSeconds(10))
            .block();

        JsonNode result = JsonRpcResponse.requireResult(response, "Solana getBalance");
        long lamports = result.path("value").asLong(0);
        BigDecimal sol = new BigDecimal(lamports).divide(LAMPORTS_PER_SOL, 9, RoundingMode.HALF_UP);

        log.info("Solana balance for {}: {} SOL", address, sol);
        return new WalletBalance("SOL", sol);
    }

    /**
     * Fetches all SPL token accounts owned by the address and returns one
     * {@link WalletBalance} per non-zero, recognised token. Unknown mints
     * are dropped — we have no way to price them in EUR.
     */
    private List<WalletBalance> fetchSplTokens(String address) {
        Map<String, Object> rpcRequest = Map.of(
            "jsonrpc", "2.0",
            "id", 2,
            "method", "getTokenAccountsByOwner",
            "params", List.of(
                address,
                Map.of("programId", SPL_TOKEN_PROGRAM_ID),
                Map.of("encoding", "jsonParsed")
            )
        );

        JsonNode response = webClient.post()
            .bodyValue(rpcRequest)
            .retrieve()
            .bodyToMono(JsonNode.class)
            .timeout(Duration.ofSeconds(10))
            .block();

        JsonNode accounts = JsonRpcResponse
            .requireResult(response, "Solana getTokenAccountsByOwner")
            .path("value");
        if (!accounts.isArray()) {
            log.warn("Solana getTokenAccountsByOwner returned non-array 'value': {}", accounts);
            return List.of();
        }

        List<WalletBalance> tokens = new ArrayList<>();
        for (JsonNode acct : accounts) {
            JsonNode info = acct.path("account").path("data").path("parsed").path("info");
            String mint = info.path("mint").asText("");
            String symbol = KNOWN_MINTS.get(mint);
            if (symbol == null) continue;

            String uiAmount = info.path("tokenAmount").path("uiAmountString").asText("0");
            BigDecimal amount;
            try {
                amount = new BigDecimal(uiAmount);
            } catch (NumberFormatException ex) {
                // Corrupt balance from the RPC: skip this one token but log
                // loudly rather than dropping it silently. Failing the whole
                // sync over one bad field would also hide the SOL balance and
                // every other token.
                log.error("Failed to parse SPL token balance for mint {}: '{}'", mint, uiAmount, ex);
                continue;
            }
            if (amount.signum() <= 0) continue;

            log.info("Solana SPL balance for {}: {} {} (mint {})", address, amount, symbol, mint);
            tokens.add(new WalletBalance(symbol, amount));
        }
        return tokens;
    }
}

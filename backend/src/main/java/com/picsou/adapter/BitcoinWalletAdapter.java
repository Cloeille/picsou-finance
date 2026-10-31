package com.picsou.adapter;

import com.fasterxml.jackson.databind.JsonNode;
import com.picsou.adapter.util.BitcoinKeyUtils;
import com.picsou.model.Chain;
import com.picsou.port.WalletPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.util.List;

/**
 * Bitcoin wallet adapter using the Blockstream Esplora public API.
 *
 * Supports three input formats for the "address" field:
 *   - Plain address  (bc1q..., 1..., 3...) — single address lookup
 *   - xpub / zpub   — HD wallet extended public key; all derived addresses are scanned
 *   - Output descriptor  wpkh([fingerprint/path]xpub.../chain/*)#checksum — Proton Wallet format
 *
 * For extended keys the adapter derives P2WPKH addresses (BIP84) along both the external
 * (m/0/*) and internal/change (m/1/*) chains and stops after {@link BitcoinKeyUtils#GAP_LIMIT}
 * consecutive unused addresses per chain (BIP44 standard).
 */
@Component
public class BitcoinWalletAdapter implements WalletPort {

    private static final Logger log = LoggerFactory.getLogger(BitcoinWalletAdapter.class);
    private static final String BASE_URL = "https://blockstream.info";
    private static final BigDecimal SATS_PER_BTC = new BigDecimal("100000000");
    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    private final WebClient webClient;

    public BitcoinWalletAdapter() {
        this.webClient = WebClient.builder()
            .baseUrl(BASE_URL)
            .defaultHeader("Accept", "application/json")
            .build();
    }

    @Override
    public Chain chain() {
        return Chain.BITCOIN;
    }

    @Override
    public List<WalletBalance> fetchBalances(String address) {
        WalletBalance btc = BitcoinKeyUtils.isExtendedKey(address)
            ? fetchExtendedKeyBalance(address)
            : fetchSingleAddressBalance(address);
        return List.of(btc);
    }

    // ─── Single address ───────────────────────────────────────────────────────

    private WalletBalance fetchSingleAddressBalance(String address) {
        AddressStats stats = fetchAddressStats(address);
        BigDecimal btc = satsToBtc(stats.balanceSats());
        log.info("Bitcoin balance for {}: {} BTC", address, btc);
        return new WalletBalance("BTC", btc);
    }

    // ─── Extended key (xpub / zpub / descriptor) ──────────────────────────────

    private WalletBalance fetchExtendedKeyBalance(String input) {
        // No catch-all here. An Esplora 429 or 5xx, a timeout or an invalid key used to come
        // back as BTC = 0, which WalletSyncService then wrote into the account and its daily
        // snapshot as a real balance. Let it propagate: the service turns it into a failed
        // sync that leaves the previous figures standing and shows the user an error.
        {
            String xpub = BitcoinKeyUtils.normalizeToXpub(input);
            BitcoinKeyUtils.Xpub root = BitcoinKeyUtils.parseXpub(xpub);

            long totalSats = 0;
            // Scan external chain (m/0/*) and internal/change chain (m/1/*)
            for (int chain = 0; chain <= 1; chain++) {
                BitcoinKeyUtils.Xpub chainKey = BitcoinKeyUtils.deriveChild(root, chain);
                totalSats += scanChain(chainKey, chain == 0 ? "external" : "change");
            }

            BigDecimal btc = satsToBtc(totalSats);
            log.info("Bitcoin HD wallet balance for [xpub]: {} BTC ({} sats total)", btc, totalSats);
            return new WalletBalance("BTC", btc);

        }
    }

    /**
     * Scans a single BIP32 chain (external or change) until GAP_LIMIT consecutive unused
     * addresses are found. Returns the total balance in satoshis.
     */
    private long scanChain(BitcoinKeyUtils.Xpub chainKey, String chainName) {
        long totalSats = 0;
        int consecutiveUnused = 0;
        int index = 0;

        while (consecutiveUnused < BitcoinKeyUtils.GAP_LIMIT) {
            BitcoinKeyUtils.Xpub childKey = BitcoinKeyUtils.deriveChild(chainKey, index);
            String address = BitcoinKeyUtils.toP2WPKHAddress(childKey.pubKey());

            AddressStats stats = fetchAddressStats(address);

            if (stats.txCount() > 0) {
                consecutiveUnused = 0;
                if (stats.balanceSats() > 0) {
                    totalSats += stats.balanceSats();
                    log.debug("Bitcoin {} chain index {}: {} sats ({})", chainName, index, stats.balanceSats(), address);
                }
            } else {
                consecutiveUnused++;
            }

            index++;
        }

        log.info("Bitcoin {} chain: scanned {} addresses, {} with history", chainName, index, index - consecutiveUnused);
        return totalSats;
    }

    // ─── Blockstream API ──────────────────────────────────────────────────────

    private record AddressStats(long balanceSats, long txCount) {}

    private AddressStats fetchAddressStats(String address) {
        JsonNode response = webClient.get()
            .uri("/api/address/{address}", address)
            .retrieve()
            .bodyToMono(JsonNode.class)
            .timeout(TIMEOUT)
            .block();

        if (response == null) return new AddressStats(0, 0);

        long funded  = response.path("chain_stats").path("funded_txo_sum").asLong(0);
        long spent   = response.path("chain_stats").path("spent_txo_sum").asLong(0);
        long txCount = response.path("chain_stats").path("tx_count").asLong(0);

        return new AddressStats(funded - spent, txCount);
    }

    private BigDecimal satsToBtc(long sats) {
        return new BigDecimal(sats).divide(SATS_PER_BTC, 8, RoundingMode.HALF_UP);
    }
}

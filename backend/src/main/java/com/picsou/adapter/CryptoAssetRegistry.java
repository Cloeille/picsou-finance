package com.picsou.adapter;

import com.picsou.model.CryptoAsset;
import com.picsou.repository.CryptoAssetRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Which tickers are crypto, and which CoinGecko coin prices each of them.
 *
 * <p>This is also the <em>router</em>: PriceService sends anything {@link CoinGeckoPriceProvider}
 * supports to CoinGecko and everything else to Yahoo Finance, with no notion of which account a
 * ticker came from. A row is therefore not free -- a symbol that is also a listed equity (STX,
 * SNX) would get that stock priced as a token. Curated rows are seeded by the migration; learned
 * rows only ever come from {@code CompositePriceProvider.learnCryptoTickers}, which refuses any
 * symbol Yahoo quotes.
 *
 * <p>Reads are served from memory: {@code supports()} runs per ticker on every price path, and
 * the table is a few hundred rows at most. It is loaded once, on first use rather than at
 * construction, so building the bean never touches the database.
 */
@Component
public class CryptoAssetRegistry {

    /** The CoinGecko coin id and the display name stored for a ticker. */
    public record Coin(String id, String name) {}

    private final CryptoAssetRepository repository;
    private final Map<String, Coin> coins = new ConcurrentHashMap<>();
    private volatile boolean loaded;

    @Autowired
    public CryptoAssetRegistry(CryptoAssetRepository repository) {
        this.repository = repository;
    }

    private CryptoAssetRegistry(Map<String, Coin> seed) {
        this.repository = null;
        this.coins.putAll(seed);
        this.loaded = true;
    }

    /** An in-memory registry with no repository behind it; {@link #learn} only updates the map. */
    public static CryptoAssetRegistry of(Map<String, Coin> coins) {
        return new CryptoAssetRegistry(coins);
    }

    public Optional<Coin> find(String ticker) {
        if (ticker == null) return Optional.empty();
        ensureLoaded();
        return Optional.ofNullable(coins.get(ticker.toUpperCase(Locale.ROOT)));
    }

    /**
     * Records a learned coin. An existing row wins: a curated entry must never be overwritten by
     * a runtime guess, and two threads learning the same ticker must not fail on the second.
     *
     * <p>{@code REQUIRES_NEW}: the insert commits on its own, whatever transaction the caller
     * holds. {@code CryptoExchangeSyncService.sync} runs inside a transaction that also writes
     * the account's balance, so a failed learn must not mark it rollback-only, and a learned row
     * must not be lost if that sync later rolls back (the in-memory map is updated at this
     * commit, so the row must not depend on the caller's outcome).
     *
     * <p>The coin reaches the in-memory map only once that transaction commits: the map is the
     * router, and a row that failed to commit must stay unknown so the caller's retry runs.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void learn(String ticker, String id, String name) {
        String key = ticker.toUpperCase(Locale.ROOT);
        ensureLoaded();
        if (coins.containsKey(key)) return;
        if (repository != null && repository.insertAutoIfAbsent(key, id, name) == 0) {
            // Another instance or thread inserted the same ticker first; its row stands.
            repository.findById(key).ifPresent(row ->
                publishAfterCommit(key, new Coin(row.getCoingeckoId(), row.getName())));
            return;
        }
        publishAfterCommit(key, new Coin(id, name));
    }

    private void publishAfterCommit(String key, Coin coin) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            coins.putIfAbsent(key, coin);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                coins.putIfAbsent(key, coin);
            }
        });
    }

    private void ensureLoaded() {
        if (loaded) return;
        synchronized (this) {
            if (loaded) return;
            for (CryptoAsset row : repository.findAll()) {
                coins.put(row.getTicker(), new Coin(row.getCoingeckoId(), row.getName()));
            }
            loaded = true;
        }
    }
}

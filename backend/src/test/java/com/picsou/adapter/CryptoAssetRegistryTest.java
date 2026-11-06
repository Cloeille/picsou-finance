package com.picsou.adapter;

import com.picsou.model.CryptoAsset;
import com.picsou.repository.CryptoAssetRepository;
import com.picsou.repository.PriceSnapshotRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CryptoAssetRegistryTest {

    @Mock CryptoAssetRepository repository;
    @Mock PriceSnapshotRepository priceSnapshotRepository;

    private static CryptoAsset row(String ticker, String id, String name, CryptoAsset.Source source) {
        return CryptoAsset.builder().ticker(ticker).coingeckoId(id).name(name).source(source).build();
    }

    @Test
    void construction_doesNotTouchTheRepository() {
        new CryptoAssetRegistry(repository, priceSnapshotRepository);

        verifyNoInteractions(repository, priceSnapshotRepository);
    }

    @Test
    void find_loadsTheTableOnce_onFirstUse() {
        when(repository.findAll()).thenReturn(List.of(
            row("BTC", "bitcoin", "Bitcoin", CryptoAsset.Source.CURATED),
            row("NEXO", "nexo", "NEXO", CryptoAsset.Source.CURATED)));
        var registry = new CryptoAssetRegistry(repository, priceSnapshotRepository);

        assertThat(registry.find("btc")).contains(new CryptoAssetRegistry.Coin("bitcoin", "Bitcoin"));
        assertThat(registry.find("NEXO")).contains(new CryptoAssetRegistry.Coin("nexo", "NEXO"));
        assertThat(registry.find("STX")).isEmpty();

        verify(repository, times(1)).findAll();
    }

    @Test
    void find_isNullSafe() {
        var registry = CryptoAssetRegistry.of(Map.of());

        assertThat(registry.find(null)).isEmpty();
    }

    @Test
    void learn_insertsAnAutoRow_andServesItFromMemory() {
        when(repository.findAll()).thenReturn(List.of());
        when(repository.insertAutoIfAbsent("NEXO", "nexo", "NEXO")).thenReturn(1);
        var registry = new CryptoAssetRegistry(repository, priceSnapshotRepository);

        registry.learn("nexo", "nexo", "NEXO");

        verify(repository).insertAutoIfAbsent("NEXO", "nexo", "NEXO");
        verify(repository, never()).findById(any());
        assertThat(registry.find("NEXO")).contains(new CryptoAssetRegistry.Coin("nexo", "NEXO"));
    }

    @Test
    void learn_doesNotWriteWhenTheTickerIsAlreadyKnown() {
        when(repository.findAll()).thenReturn(List.of(row("BTC", "bitcoin", "Bitcoin", CryptoAsset.Source.CURATED)));
        var registry = new CryptoAssetRegistry(repository, priceSnapshotRepository);

        registry.learn("BTC", "bitcoin-wrapped", "Wrapped");

        verify(repository, never()).insertAutoIfAbsent(any(), any(), any());
        assertThat(registry.find("BTC")).contains(new CryptoAssetRegistry.Coin("bitcoin", "Bitcoin"));
    }

    @Test
    void learn_keepsTheRowAnotherInstanceInsertedFirst() {
        when(repository.findAll()).thenReturn(List.of());
        when(repository.insertAutoIfAbsent("CRO", "other-cro", "Other")).thenReturn(0);
        when(repository.findById("CRO")).thenReturn(
            Optional.of(row("CRO", "crypto-com-chain", "Cronos", CryptoAsset.Source.AUTO)));
        var registry = new CryptoAssetRegistry(repository, priceSnapshotRepository);

        registry.learn("CRO", "other-cro", "Other");

        assertThat(registry.find("CRO")).contains(new CryptoAssetRegistry.Coin("crypto-com-chain", "Cronos"));
    }

    @Test
    void learn_publishesTheCoinOnlyOnceItsTransactionCommits() {
        when(repository.findAll()).thenReturn(List.of());
        when(repository.insertAutoIfAbsent("NEXO", "nexo", "NEXO")).thenReturn(1);
        var registry = new CryptoAssetRegistry(repository, priceSnapshotRepository);
        TransactionSynchronizationManager.initSynchronization();
        try {
            registry.learn("NEXO", "nexo", "NEXO");
            // Not committed yet: a rollback here must leave NEXO unknown so the caller retries.
            assertThat(registry.find("NEXO")).isEmpty();

            TransactionSynchronizationManager.getSynchronizations().forEach(TransactionSynchronization::afterCommit);
            assertThat(registry.find("NEXO")).contains(new CryptoAssetRegistry.Coin("nexo", "NEXO"));
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void learn_aRolledBackWrite_leavesTheTickerUnknown_soTheRetryWritesIt() {
        when(repository.findAll()).thenReturn(List.of());
        when(repository.insertAutoIfAbsent("NEXO", "nexo", "NEXO")).thenReturn(1);
        var registry = new CryptoAssetRegistry(repository, priceSnapshotRepository);
        TransactionSynchronizationManager.initSynchronization();
        try {
            registry.learn("NEXO", "nexo", "NEXO");
            TransactionSynchronizationManager.getSynchronizations()
                .forEach(s -> s.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK));
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
        assertThat(registry.find("NEXO")).isEmpty();

        registry.learn("NEXO", "nexo", "NEXO");

        verify(repository, times(2)).insertAutoIfAbsent("NEXO", "nexo", "NEXO");
        assertThat(registry.find("NEXO")).isPresent();
    }

    @Test
    void learn_dropsTheTickersStockSnapshots_afterInsertingTheRow() {
        // price_snapshot is keyed by ticker alone: before this insert the ticker was routed to
        // Yahoo, so any rows it has are a stock's closes and must not pass for coverage of the
        // CoinGecko history. Insert and purge share the REQUIRES_NEW transaction, in this order.
        when(repository.findAll()).thenReturn(List.of());
        when(repository.insertAutoIfAbsent("NEXO", "nexo", "NEXO")).thenReturn(1);
        when(priceSnapshotRepository.deleteByTicker("NEXO")).thenReturn(3);
        var registry = new CryptoAssetRegistry(repository, priceSnapshotRepository);

        registry.learn("nexo", "nexo", "NEXO");

        InOrder order = inOrder(repository, priceSnapshotRepository);
        order.verify(repository).insertAutoIfAbsent("NEXO", "nexo", "NEXO");
        order.verify(priceSnapshotRepository).deleteByTicker("NEXO");
        assertThat(registry.find("NEXO")).isPresent();
    }

    @Test
    void learn_keepsTheSnapshots_whenAnotherInstanceInsertedTheRowFirst() {
        // Not this call's insert: the row, and the CoinGecko snapshots written since, are theirs.
        when(repository.findAll()).thenReturn(List.of());
        when(repository.insertAutoIfAbsent("CRO", "other-cro", "Other")).thenReturn(0);
        when(repository.findById("CRO")).thenReturn(
            Optional.of(row("CRO", "crypto-com-chain", "Cronos", CryptoAsset.Source.AUTO)));
        var registry = new CryptoAssetRegistry(repository, priceSnapshotRepository);

        registry.learn("CRO", "other-cro", "Other");

        verifyNoInteractions(priceSnapshotRepository);
    }

    @Test
    void learn_keepsTheSnapshots_whenTheTickerIsAlreadyKnown() {
        // A curated or earlier-learned coin: its snapshots are genuine CoinGecko history.
        when(repository.findAll()).thenReturn(List.of(row("BTC", "bitcoin", "Bitcoin", CryptoAsset.Source.CURATED)));
        var registry = new CryptoAssetRegistry(repository, priceSnapshotRepository);

        registry.learn("BTC", "bitcoin-wrapped", "Wrapped");

        verifyNoInteractions(priceSnapshotRepository);
    }

    @Test
    void of_learnOnlyUpdatesTheMap() {
        var registry = CryptoAssetRegistry.of(new java.util.HashMap<>());

        registry.learn("NEXO", "nexo", "NEXO");

        assertThat(registry.find("NEXO")).contains(new CryptoAssetRegistry.Coin("nexo", "NEXO"));
    }
}

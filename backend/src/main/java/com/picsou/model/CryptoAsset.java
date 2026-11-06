package com.picsou.model;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

/**
 * One row of the crypto registry: which ticker is a coin, and which CoinGecko coin prices it.
 * Global reference data, not member-scoped (same as {@link PriceSnapshot}).
 */
@Entity
@Table(name = "crypto_asset")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CryptoAsset {

    public enum Source { CURATED, AUTO }

    @Id
    @Column(length = 30)
    private String ticker;

    @Column(name = "coingecko_id", nullable = false, length = 100)
    private String coingeckoId;

    @Column(nullable = false)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private Source source;

    @Column(name = "resolved_at", nullable = false)
    @Builder.Default
    private Instant resolvedAt = Instant.now();
}

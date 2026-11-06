package com.picsou.model;

import java.util.Arrays;
import java.util.Locale;
import java.util.Optional;

/**
 * The products a {@link AccountType#PRECIOUS_METALS} account can hold, and the tickers that
 * identify them.
 *
 * <p>The {@code :} in every ticker is deliberate: it fails Yahoo's symbol pattern, so the logo,
 * security-profile and dividend jobs, which gate on {@code supports}, never send a gold ticker
 * to Yahoo. Pricing is routed to {@code PreciousMetalPriceProvider} instead.
 *
 * <p>Quantity is grams for {@link Unit#GRAM} and a piece count for {@link Unit#COIN}. A coin is
 * priced at its own market quote, premium included, not at its gold weight.
 */
public enum PreciousMetal {

    GOLD_GRAM("GOLD:GRAM", "Gold (fine gram)", Unit.GRAM, null),
    NAPOLEON_20F("GOLD:NAPOLEON-20F", "Napoléon 20 F", Unit.COIN, "20-francs-napoleon"),
    NAPOLEON_10F("GOLD:NAPOLEON-10F", "Napoléon 10 F", Unit.COIN, "10-francs-napoleon"),
    SWISS_20F("GOLD:SWISS-20F", "20 Francs Suisse", Unit.COIN, "20-francs-suisse"),
    LATIN_UNION_20F("GOLD:LATIN-UNION-20F", "Union latine 20 F", Unit.COIN, "union-latine"),
    TUNISIA_20F("GOLD:TUNISIA-20F", "20 Francs Tunisie", Unit.COIN, "20-francs-tunisie"),
    SOVEREIGN("GOLD:SOVEREIGN", "Souverain", Unit.COIN, "souverain"),
    SOVEREIGN_ELIZABETH_II("GOLD:SOVEREIGN-EII", "Souverain Elizabeth II", Unit.COIN,
        "souverain-elisabeth-ii"),
    HALF_SOVEREIGN("GOLD:HALF-SOVEREIGN", "Demi-souverain", Unit.COIN, "demi-souverain"),
    KRUGERRAND("GOLD:KRUGERRAND", "Krugerrand", Unit.COIN, "krugerrand-sud-afrique"),
    USD_20("GOLD:USD-20", "20 Dollars US", Unit.COIN, "20-dollars"),
    USD_10("GOLD:USD-10", "10 Dollars US", Unit.COIN, "10-dollars"),
    USD_5("GOLD:USD-5", "5 Dollars US", Unit.COIN, "5-dollars-us"),
    PESO_50("GOLD:PESO-50", "50 Pesos", Unit.COIN, "50-pesos"),
    FLORIN_10("GOLD:FLORIN-10", "10 Florins", Unit.COIN, "10-florins-hollandais"),
    REICHSMARK_20("GOLD:REICHSMARK-20", "20 Reichsmark", Unit.COIN, "20-reich-mark");

    /** What a holding's quantity counts. */
    public enum Unit { GRAM, COIN }

    private final String ticker;
    private final String displayName;
    private final Unit unit;
    private final String loomisSlug;

    PreciousMetal(String ticker, String displayName, Unit unit, String loomisSlug) {
        this.ticker = ticker;
        this.displayName = displayName;
        this.unit = unit;
        this.loomisSlug = loomisSlug;
    }

    public String ticker() {
        return ticker;
    }

    public String displayName() {
        return displayName;
    }

    public Unit unit() {
        return unit;
    }

    /** The product's path segment on the Loomis FXGS quote page; null for the spot gram. */
    public String loomisSlug() {
        return loomisSlug;
    }

    /** The product behind {@code ticker}, case-insensitively; empty for anything else. */
    public static Optional<PreciousMetal> fromTicker(String ticker) {
        if (ticker == null) return Optional.empty();
        String upper = ticker.trim().toUpperCase(Locale.ROOT);
        return Arrays.stream(values()).filter(m -> m.ticker.equals(upper)).findFirst();
    }
}

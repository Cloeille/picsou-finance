package com.picsou.finary;

import com.picsou.adapter.OpenFigiIsinConverter;
import com.picsou.finary.dto.FinaryAccountDto;
import com.picsou.finary.dto.FinaryAssetRefDto;
import com.picsou.finary.dto.FinaryPositionDto;
import com.picsou.finary.dto.FinarySecurityRefDto;
import com.picsou.model.Account;
import com.picsou.model.AccountHolding;
import com.picsou.repository.AccountHoldingRepository;
import com.picsou.service.HoldingDedup;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Maps Finary account positions (securities, cryptos, fonds euro, …) onto
 * {@link AccountHolding} rows and records leftover cash on the account.
 *
 * <p>Holdings and cash are replaced wholesale, so only a complete snapshot may replace them: one
 * that carries every list this importer reads ({@code securities}, {@code cryptos},
 * {@code fonds_euro}, {@code fiats}, {@code generic_assets}, {@code scpis},
 * {@code precious_metals}), even empty, and a EUR value on every cash line. A payload missing any
 * of them cannot tell an emptied group from an omitted one, or state the cash, so the existing
 * holdings and cash are kept.
 * Values are stored as EUR only from {@code display_*} fields, and only once the
 * caller has proven the display currency is EUR; otherwise nothing is changed. Cash-like
 * lines (fiats, "Solde Espèces") are not persisted as holdings — they go into
 * {@code account.cashBalance}.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class FinaryHoldingsImporter {

    static final int TICKER_MAX = 100;
    static final int NAME_MAX = 255;

    private static final Pattern CASH_NAME = Pattern.compile(
        "(?i)solde\\s+esp[eè]ces|\\bcash\\b|^euro$");
    private static final String BLANK_ISIN = "000000000000";

    private final AccountHoldingRepository holdingRepository;
    private final OpenFigiIsinConverter isinConverter;

    public int importHoldings(Account account, FinaryAccountDto finaryAcc, boolean displayIsEur) {
        if (!isCompleteSnapshot(finaryAcc)) {
            log.warn("Keeping the holdings of account {}: Finary's payload omits a position list",
                account.getId());
            return 0;
        }
        if (!displayIsEur) {
            log.warn("Keeping the holdings of account {}: Finary's display currency is not proven EUR",
                account.getId());
            return 0;
        }
        BigDecimal cash = sumCash(finaryAcc);
        if (cash == null) {
            log.warn("Keeping the holdings of account {}: a cash line has no EUR value",
                account.getId());
            return 0;
        }
        List<FinaryPositionDto> positions = collectPositions(finaryAcc);

        account.setCashBalance(cash.signum() == 0 ? null : cash);

        if (positions.isEmpty()) {
            holdingRepository.deleteByAccountId(account.getId());
            return 0;
        }

        Instant now = Instant.now();
        Map<String, HoldingDedup.HoldingAgg> deduped = new HashMap<>();
        Map<String, ProviderVal> providerByTicker = new HashMap<>();

        for (FinaryPositionDto position : positions) {
            if (isCash(position)) {
                continue;
            }
            BigDecimal qty = decimal(position.quantity());
            if (qty == null || qty.signum() == 0) {
                continue;
            }
            ResolvedInstrument instrument = resolveInstrument(position);
            if (instrument.ticker() == null || instrument.ticker().isBlank()) {
                log.warn("Skipping Finary position without a ticker on account {}: {}",
                    account.getId(), instrument.name());
                continue;
            }

            BigDecimal valueEur = eurValue(position);
            BigDecimal pnlEur = eurPnl(position);
            BigDecimal priceEur = eurPrice(position, qty, valueEur);
            BigDecimal avgBuyIn = averageBuyIn(position, qty);

            deduped.merge(
                instrument.ticker(),
                new HoldingDedup.HoldingAgg(qty, avgBuyIn, priceEur, instrument.name()),
                FinaryHoldingsImporter::mergeLines);
            providerByTicker.merge(
                instrument.ticker(),
                new ProviderVal(valueEur, pnlEur),
                ProviderVal::add);
        }

        holdingRepository.deleteByAccountId(account.getId());
        holdingRepository.flush();

        int saved = 0;
        for (Map.Entry<String, HoldingDedup.HoldingAgg> entry : deduped.entrySet()) {
            HoldingDedup.HoldingAgg agg = entry.getValue();
            if (agg.quantity().signum() == 0) {
                continue;
            }
            ProviderVal provider = providerByTicker.getOrDefault(entry.getKey(), ProviderVal.EMPTY);
            holdingRepository.save(AccountHolding.builder()
                .account(account)
                .ticker(entry.getKey())
                .name(truncate(agg.name(), NAME_MAX))
                .quantity(agg.quantity())
                .averageBuyIn(agg.averageBuyIn())
                .currentPrice(agg.currentPrice())
                .quoteCurrency("EUR")
                .providerValueEur(provider.valueEur())
                .providerPnlEur(provider.pnlEur())
                .lastSyncedAt(now)
                .build());
            saved++;
        }
        return saved;
    }

    static boolean isCompleteSnapshot(FinaryAccountDto acc) {
        return acc.securities() != null && acc.cryptos() != null && acc.fondsEuro() != null
            && acc.fiats() != null && acc.genericAssets() != null && acc.scpis() != null
            && acc.preciousMetals() != null;
    }

    // HoldingDedup reads an unknown average as zero, which would turn one line of unknown cost
    // into free units and halve the merged cost basis. One unknown line leaves it unknown.
    static HoldingDedup.HoldingAgg mergeLines(HoldingDedup.HoldingAgg a, HoldingDedup.HoldingAgg b) {
        HoldingDedup.HoldingAgg merged = HoldingDedup.vwapMerge(a, b);
        if (a.averageBuyIn() != null && b.averageBuyIn() != null) {
            return merged;
        }
        return new HoldingDedup.HoldingAgg(merged.quantity(), null, merged.currentPrice(), merged.name());
    }

    static List<FinaryPositionDto> collectPositions(FinaryAccountDto acc) {
        List<FinaryPositionDto> out = new ArrayList<>();
        addAll(out, acc.securities());
        addAll(out, acc.cryptos());
        addAll(out, acc.fondsEuro());
        addAll(out, acc.genericAssets());
        addAll(out, acc.scpis());
        addAll(out, acc.preciousMetals());
        return out;
    }

    private static void addAll(List<FinaryPositionDto> out, List<FinaryPositionDto> src) {
        if (src != null) {
            src.stream().filter(Objects::nonNull).forEach(out::add);
        }
    }

    /** Null when a cash line has no EUR value: counting it as zero would understate the cash. */
    BigDecimal sumCash(FinaryAccountDto acc) {
        List<FinaryPositionDto> lines = new ArrayList<>();
        addAll(lines, acc.fiats());
        collectPositions(acc).stream().filter(FinaryHoldingsImporter::isCash).forEach(lines::add);
        BigDecimal cash = BigDecimal.ZERO;
        for (FinaryPositionDto line : lines) {
            BigDecimal value = eurValue(line);
            if (value == null) {
                return null;
            }
            cash = cash.add(value);
        }
        return cash;
    }

    static boolean isCash(FinaryPositionDto position) {
        if (position == null) {
            return false;
        }
        if (position.fiat() != null) {
            return true;
        }
        String type = position.type();
        if (type != null && type.toLowerCase(Locale.ROOT).contains("fiat")) {
            return true;
        }
        FinarySecurityRefDto security = position.security();
        if (security != null) {
            if (BLANK_ISIN.equals(security.symbol()) || BLANK_ISIN.equals(security.isin())) {
                return true;
            }
            if (security.isin() == null && CASH_NAME.matcher(nvl(security.name())).find()) {
                return true;
            }
        }
        return position.security() == null
            && position.crypto() == null
            && CASH_NAME.matcher(nvl(position.name())).find();
    }

    ResolvedInstrument resolveInstrument(FinaryPositionDto position) {
        FinaryAssetRefDto crypto = position.crypto();
        if (crypto != null) {
            String code = firstNonBlank(crypto.code(), crypto.symbol());
            return new ResolvedInstrument(
                sanitizeTicker(firstNonBlank(code, crypto.name(), position.id())),
                firstNonBlank(crypto.name(), code));
        }

        FinarySecurityRefDto security = position.security();
        if (security != null) {
            String isin = security.isin();
            if (OpenFigiIsinConverter.isIsin(isin)) {
                OpenFigiIsinConverter.TickerResult resolved = isinConverter.resolve(isin);
                String ticker = firstNonBlank(resolved.ticker(), isin);
                String name = firstNonBlank(resolved.name(), security.name(), ticker);
                return new ResolvedInstrument(sanitizeTicker(ticker), name);
            }
            String fallback = firstNonBlank(isin, security.symbol(), security.slug(), security.name(), position.id());
            return new ResolvedInstrument(
                sanitizeTicker(fallback),
                firstNonBlank(security.name(), fallback));
        }

        String name = firstNonBlank(position.name(), position.id());
        return new ResolvedInstrument(sanitizeTicker("FINARY_" + name), name);
    }

    static String sanitizeTicker(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String cleaned = raw.trim().toUpperCase(Locale.ROOT)
            .replaceAll("[^A-Z0-9._-]", "_")
            .replaceAll("_+", "_");
        if (cleaned.startsWith("_")) {
            cleaned = cleaned.substring(1);
        }
        if (cleaned.endsWith("_")) {
            cleaned = cleaned.substring(0, cleaned.length() - 1);
        }
        if (cleaned.isBlank()) {
            return null;
        }
        return truncate(cleaned, TICKER_MAX);
    }

    // Native amounts are in the line's own currency, which the payload does not name: an absent
    // display value stays unknown rather than borrowing the native one as EUR.
    static BigDecimal eurValue(FinaryPositionDto position) {
        return decimal(position.displayCurrentValue());
    }

    static BigDecimal eurPnl(FinaryPositionDto position) {
        return firstDecimal(position.displayUnrealizedPnl(), position.displayCurrentUpnl());
    }

    static BigDecimal eurPrice(FinaryPositionDto position, BigDecimal qty, BigDecimal valueEur) {
        BigDecimal displayPrice = decimal(position.displayCurrentPrice());
        if (displayPrice != null) {
            return displayPrice;
        }
        if (valueEur != null && qty != null && qty.signum() != 0) {
            return valueEur.divide(qty, 8, RoundingMode.HALF_UP);
        }
        return null;
    }

    static BigDecimal averageBuyIn(FinaryPositionDto position, BigDecimal qty) {
        BigDecimal buyValue = decimal(position.displayBuyingValue());
        if (buyValue != null && qty != null && qty.signum() != 0) {
            return buyValue.divide(qty, 8, RoundingMode.HALF_UP);
        }
        return null;
    }

    static BigDecimal firstDecimal(Double preferred, Double fallback) {
        BigDecimal first = decimal(preferred);
        return first != null ? first : decimal(fallback);
    }

    static BigDecimal decimal(Double value) {
        return value == null ? null : BigDecimal.valueOf(value);
    }

    private static String nvl(String value) {
        return value != null ? value : "";
    }

    static String firstNonBlank(String... values) {
        if (values == null) {
            return null;
        }
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    static String truncate(String value, int max) {
        if (value == null || value.length() <= max) {
            return value;
        }
        return value.substring(0, max);
    }

    record ResolvedInstrument(String ticker, String name) {}

    record ProviderVal(BigDecimal valueEur, BigDecimal pnlEur) {
        static final ProviderVal EMPTY = new ProviderVal(null, null);

        ProviderVal add(ProviderVal other) {
            return new ProviderVal(sum(valueEur, other.valueEur), sum(pnlEur, other.pnlEur));
        }

        // One unknown line makes the merged total unknown: summing only the known lines would
        // present a partial value as the whole position's, and stop the live-price fallback.
        private static BigDecimal sum(BigDecimal a, BigDecimal b) {
            return a == null || b == null ? null : a.add(b);
        }
    }
}

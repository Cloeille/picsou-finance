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
import java.util.regex.Pattern;

/**
 * Maps Finary account positions (securities, cryptos, fonds euro, …) onto
 * {@link AccountHolding} rows and records leftover cash on the account.
 *
 * <p>XLSX import has no position payload, so this is a no-op when every list is
 * null/empty. Cash-like lines (fiats, "Solde Espèces") are not persisted as
 * holdings — they go into {@code account.cashBalance}.
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

    public int importHoldings(Account account, FinaryAccountDto finaryAcc) {
        List<FinaryPositionDto> positions = collectPositions(finaryAcc);
        BigDecimal cash = sumCash(finaryAcc);

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
                HoldingDedup::vwapMerge);
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
            out.addAll(src);
        }
    }

    BigDecimal sumCash(FinaryAccountDto acc) {
        BigDecimal cash = BigDecimal.ZERO;
        if (acc.fiats() != null) {
            for (FinaryPositionDto fiat : acc.fiats()) {
                cash = cash.add(nvl(eurValue(fiat)));
            }
        }
        for (FinaryPositionDto position : collectPositions(acc)) {
            if (isCash(position)) {
                cash = cash.add(nvl(eurValue(position)));
            }
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

    static BigDecimal eurValue(FinaryPositionDto position) {
        return firstDecimal(position.displayCurrentValue(), position.currentValue());
    }

    static BigDecimal eurPnl(FinaryPositionDto position) {
        return firstDecimal(position.displayUnrealizedPnl(), position.unrealizedPnl());
    }

    static BigDecimal eurPrice(FinaryPositionDto position, BigDecimal qty, BigDecimal valueEur) {
        BigDecimal displayPrice = decimal(position.displayCurrentPrice());
        if (displayPrice != null) {
            return displayPrice;
        }
        if (valueEur != null && qty != null && qty.signum() != 0) {
            return valueEur.divide(qty, 8, RoundingMode.HALF_UP);
        }
        return decimal(position.currentPrice());
    }

    static BigDecimal averageBuyIn(FinaryPositionDto position, BigDecimal qty) {
        BigDecimal buyValue = firstDecimal(position.displayBuyingValue(), position.buyingValue());
        if (buyValue != null && qty != null && qty.signum() != 0) {
            return buyValue.divide(qty, 8, RoundingMode.HALF_UP);
        }
        return firstDecimal(position.buyingPrice(), null);
    }

    static BigDecimal firstDecimal(Double preferred, Double fallback) {
        BigDecimal first = decimal(preferred);
        return first != null ? first : decimal(fallback);
    }

    static BigDecimal decimal(Double value) {
        return value == null ? null : BigDecimal.valueOf(value);
    }

    private static BigDecimal nvl(BigDecimal value) {
        return value != null ? value : BigDecimal.ZERO;
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

        private static BigDecimal sum(BigDecimal a, BigDecimal b) {
            if (a == null) {
                return b;
            }
            if (b == null) {
                return a;
            }
            return a.add(b);
        }
    }
}

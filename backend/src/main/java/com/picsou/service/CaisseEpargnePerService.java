package com.picsou.service;

import com.picsou.adapter.OpenFigiIsinConverter;
import com.picsou.exception.SyncException;
import com.picsou.model.Account;
import com.picsou.model.AccountHolding;
import com.picsou.port.CaisseEpargneErrorCode;
import com.picsou.port.CaisseEpargnePort;
import com.picsou.port.SymbolCatalogPort;
import com.picsou.repository.AccountHoldingRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** Prepares the bank's complete PER valuation before the parent sync writes it. */
@Service
public class CaisseEpargnePerService {

    private static final Logger log = LoggerFactory.getLogger(CaisseEpargnePerService.class);
    private static final BigDecimal RECONCILIATION_TOLERANCE = new BigDecimal("0.02");
    private static final Pattern ISIN_PATTERN = Pattern.compile("[A-Z]{2}[A-Z0-9]{10}");
    private static final Pattern TICKER_PATTERN = Pattern.compile("[A-Z0-9][A-Z0-9.-]{0,29}");

    private final AccountHoldingRepository holdingRepository;
    private final OpenFigiIsinConverter isinConverter;
    private final SymbolCatalogPort symbolCatalog;
    private final SecurityIdentityService identityService;
    private final PriceService priceService;

    public CaisseEpargnePerService(
        AccountHoldingRepository holdingRepository,
        OpenFigiIsinConverter isinConverter,
        SymbolCatalogPort symbolCatalog,
        SecurityIdentityService identityService,
        PriceService priceService
    ) {
        this.holdingRepository = holdingRepository;
        this.isinConverter = isinConverter;
        this.symbolCatalog = symbolCatalog;
        this.identityService = identityService;
        this.priceService = priceService;
    }

    /**
     * Validates and resolves a complete bank valuation without writing anything.
     * A null detail is the legacy balance-only shape and must not be treated as an empty portfolio.
     */
    @Transactional(propagation = Propagation.NEVER)
    public Prepared prepare(CaisseEpargnePort.PerDetails detail, String currency) {
        if (detail == null) return null;
        if (!"EUR".equals(currency)
            || detail.balance() == null || detail.euroFundBalance() == null
            || detail.valuationDate() == null || detail.positions() == null
            || isNegative(detail.balance()) || isNegative(detail.euroFundBalance())) {
            throw invalidPerDetails();
        }

        Map<String, AggregatedPosition> grouped = new LinkedHashMap<>();
        Set<CompartmentKey> seenCompartments = new HashSet<>();
        BigDecimal ucTotal = BigDecimal.ZERO;
        for (CaisseEpargnePort.PerPosition position : detail.positions()) {
            validatePosition(position, detail.valuationDate(), seenCompartments);
            ucTotal = ucTotal.add(position.value());
            grouped.computeIfAbsent(position.isin(), AggregatedPosition::new).add(position);
        }
        if (outsideTolerance(ucTotal.add(detail.euroFundBalance()), detail.balance())) {
            throw invalidPerDetails();
        }

        List<ResolvedPosition> resolved = new ArrayList<>(grouped.size());
        for (AggregatedPosition position : grouped.values()) {
            resolved.add(resolve(position));
        }
        Set<String> collisions = tickerCollisions(resolved);

        List<PreparedPosition> positions = new ArrayList<>(resolved.size());
        Map<String, String> isinByTicker = new LinkedHashMap<>();
        Set<String> verifiedTickers = new LinkedHashSet<>();
        for (ResolvedPosition position : resolved) {
            String ticker = collisions.contains(position.ticker()) ? position.isin() : position.ticker();
            positions.add(new PreparedPosition(
                ticker,
                position.name(),
                position.quantity(),
                position.unitPrice(),
                position.value(),
                position.priceDate(),
                position.isin()
            ));
            if (position.verified() && !collisions.contains(position.ticker())) {
                isinByTicker.put(ticker, position.isin());
                verifiedTickers.add(ticker);
            }
        }
        return new Prepared(
            detail.balance(), detail.euroFundBalance(), detail.valuationDate(),
            positions, isinByTicker, verifiedTickers
        );
    }

    /**
     * Replaces a complete PER position set inside the parent sync transaction.
     * The parent must save the owner-scoped account first and call this with that managed account.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void apply(Account account, Prepared prepared, Instant syncedAt) {
        if (prepared == null) return;
        if (account == null || account.getId() == null || syncedAt == null) {
            throw new IllegalArgumentException("A persisted account and sync time are required");
        }

        account.setCurrentBalance(prepared.balance());
        account.setCashBalance(prepared.euroFundBalance());

        holdingRepository.deleteByAccountId(account.getId());
        holdingRepository.flush();
        List<AccountHolding> holdings = prepared.positions().stream()
            .map(position -> AccountHolding.builder()
                .account(account)
                .ticker(position.ticker())
                .name(position.name())
                .quantity(position.quantity())
                .currentPrice(position.unitPrice())
                .quoteCurrency("EUR")
                .providerValueEur(position.value())
                .providerPriceDate(position.priceDate())
                .lastSyncedAt(syncedAt)
                .build())
            .toList();
        holdingRepository.saveAll(holdings);
        holdingRepository.flush();
    }

    /**
     * Backfills security identity and market history only after the parent sync transaction commits.
     * Bank NAVs themselves are never written as price snapshots.
     */
    @Transactional(propagation = Propagation.NEVER)
    public void backfill(Prepared prepared) {
        if (prepared == null) return;
        if (!prepared.isinByTicker().isEmpty()) {
            try {
                identityService.record(prepared.isinByTicker());
            } catch (RuntimeException ignored) {
                log.warn("Caisse d'Epargne PER security identity backfill failed");
            }
        }
        if (prepared.verifiedTickers().isEmpty()) return;
        try {
            priceService.backfillHistoricalPrices(prepared.verifiedTickers(), LocalDate.now().minusMonths(12));
        } catch (RuntimeException ignored) {
            log.warn("Caisse d'Epargne PER historical price backfill failed");
        }
    }

    private void validatePosition(
        CaisseEpargnePort.PerPosition position,
        LocalDate valuationDate,
        Set<CompartmentKey> seenCompartments
    ) {
        if (position == null || position.isin() == null || !ISIN_PATTERN.matcher(position.isin()).matches()
            || position.quantity() == null || isNegative(position.quantity())
            || position.value() == null || isNegative(position.value())
            || position.priceDate() == null || position.priceDate().isAfter(valuationDate)
            || position.compartmentId() == null || position.compartmentId() < 0
            || (position.name() != null && position.name().length() > 100)
            || (position.unitPrice() != null && isNegative(position.unitPrice()))) {
            throw invalidPerDetails();
        }
        if (!seenCompartments.add(new CompartmentKey(position.isin(), position.compartmentId()))) {
            throw invalidPerDetails();
        }
        if (position.quantity().signum() > 0
            && (position.unitPrice() == null || position.unitPrice().signum() <= 0)) {
            throw invalidPerDetails();
        }
        BigDecimal lineValue = position.unitPrice() == null
            ? BigDecimal.ZERO
            : position.quantity().multiply(position.unitPrice());
        if (outsideTolerance(lineValue, position.value())) {
            throw invalidPerDetails();
        }
    }

    private ResolvedPosition resolve(AggregatedPosition position) {
        BigDecimal unitPrice = position.quantity.signum() == 0
            ? BigDecimal.ZERO
            : position.value.divide(position.quantity, 8, RoundingMode.HALF_UP);
        String name = position.name == null || position.name.isBlank() ? position.isin : position.name;
        String ticker = position.isin;
        boolean verified = false;
        try {
            OpenFigiIsinConverter.TickerResult result = isinConverter.resolve(position.isin);
            if (result != null && result.ticker() != null) {
                String candidate = result.ticker().toUpperCase(Locale.ROOT);
                if (!candidate.equals(position.isin) && TICKER_PATTERN.matcher(candidate).matches()
                    && symbolCatalog.hasQuote(candidate)) {
                    ticker = candidate;
                    verified = true;
                    if ((position.name == null || position.name.isBlank())
                        && result.name() != null && !result.name().isBlank() && result.name().length() <= 100) {
                        name = result.name();
                    }
                }
            }
        } catch (RuntimeException ignored) {
            // A resolved ticker is an optimization; the raw ISIN and bank valuation are authoritative.
        }
        return new ResolvedPosition(
            position.isin, ticker, name, position.quantity, unitPrice, position.value, position.priceDate, verified
        );
    }

    private static Set<String> tickerCollisions(List<ResolvedPosition> positions) {
        Map<String, Set<String>> isinsByTicker = new LinkedHashMap<>();
        for (ResolvedPosition position : positions) {
            isinsByTicker.computeIfAbsent(position.ticker(), ignored -> new LinkedHashSet<>()).add(position.isin());
        }
        Set<String> collisions = new HashSet<>();
        isinsByTicker.forEach((ticker, isins) -> {
            if (isins.size() > 1) collisions.add(ticker);
        });
        return collisions;
    }

    private static boolean isNegative(BigDecimal value) {
        return value.signum() < 0;
    }

    private static boolean outsideTolerance(BigDecimal left, BigDecimal right) {
        return left.subtract(right).abs().compareTo(RECONCILIATION_TOLERANCE) > 0;
    }

    private static SyncException invalidPerDetails() {
        return new SyncException(
            "Caisse d'Epargne returned an invalid PER valuation",
            null,
            CaisseEpargneErrorCode.UPSTREAM_FORMAT_CHANGED.name()
        );
    }

    private record CompartmentKey(String isin, int compartmentId) {}

    private record ResolvedPosition(
        String isin,
        String ticker,
        String name,
        BigDecimal quantity,
        BigDecimal unitPrice,
        BigDecimal value,
        LocalDate priceDate,
        boolean verified
    ) {}

    private static final class AggregatedPosition {
        private final String isin;
        private BigDecimal quantity = BigDecimal.ZERO;
        private BigDecimal value = BigDecimal.ZERO;
        private String name;
        private LocalDate priceDate;

        private AggregatedPosition(String isin) {
            this.isin = isin;
        }

        private void add(CaisseEpargnePort.PerPosition position) {
            quantity = quantity.add(position.quantity());
            value = value.add(position.value());
            if (name == null || name.isBlank()) name = position.name();
            if (priceDate == null || position.priceDate().isBefore(priceDate)) priceDate = position.priceDate();
        }
    }

    public record Prepared(
        BigDecimal balance,
        BigDecimal euroFundBalance,
        LocalDate valuationDate,
        List<PreparedPosition> positions,
        Map<String, String> isinByTicker,
        Set<String> verifiedTickers
    ) {
        public Prepared {
            positions = List.copyOf(positions);
            isinByTicker = Map.copyOf(isinByTicker);
            verifiedTickers = Set.copyOf(verifiedTickers);
        }
    }

    public record PreparedPosition(
        String ticker,
        String name,
        BigDecimal quantity,
        BigDecimal unitPrice,
        BigDecimal value,
        LocalDate priceDate,
        String isin
    ) {}
}

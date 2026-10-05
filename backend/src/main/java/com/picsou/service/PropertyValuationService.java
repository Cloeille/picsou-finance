package com.picsou.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.picsou.dto.PropertyValuationResponse;
import com.picsou.dto.PropertyValuationResponse.AdjustmentDto;
import com.picsou.exception.ValuationProviderException;
import com.picsou.model.*;
import com.picsou.port.GeocodingPort;
import com.picsou.port.HousingPriceIndexPort;
import com.picsou.port.PropertyValuationPort;
import com.picsou.port.PropertyValuationPort.ValuationInput;
import com.picsou.port.PropertyValuationPort.ValuationResult;
import com.picsou.repository.AccountRepository;
import com.picsou.repository.PropertyValuationRepository;
import com.picsou.repository.RealEstateMetadataRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Estimates what a property is worth and, unless the user has locked it, writes that onto
 * the account balance.
 *
 * <p><b>Why this is persisted rather than computed on read.</b> Loan amortisation is
 * recomputed on every request (see the loan-amortisation ADR) because its formula is local
 * and costs microseconds. A valuation depends on two external HTTP services, so recomputing
 * it per request would put a third party in the path of every dashboard load. Instead the
 * monthly job writes {@code account.currentBalance}, and {@code AccountService.liveBalanceEur}
 * is left completely untouched — a valued property behaves exactly like any manual account.
 * The daily snapshot job then captures the gain curve for free.
 */
@Service
public class PropertyValuationService {

    private static final Logger log = LoggerFactory.getLogger(PropertyValuationService.class);

    /** Below this geocoding score the match is too shaky to value against. */
    private static final BigDecimal MIN_GEOCODE_SCORE = new BigDecimal("0.4");

    private final List<PropertyValuationPort> providers;
    private final GeocodingPort geocoder;
    private final HousingPriceIndexPort priceIndex;
    private final PropertyAdjustments adjustments;
    private final RealEstateMetadataRepository metadataRepository;
    private final PropertyValuationRepository valuationRepository;
    private final AccountRepository accountRepository;
    private final AccountAccessResolver accessResolver;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate txTemplate;
    private final boolean enabled;

    public PropertyValuationService(
        List<PropertyValuationPort> providers,
        GeocodingPort geocoder,
        HousingPriceIndexPort priceIndex,
        PropertyAdjustments adjustments,
        RealEstateMetadataRepository metadataRepository,
        PropertyValuationRepository valuationRepository,
        AccountRepository accountRepository,
        AccountAccessResolver accessResolver,
        ObjectMapper objectMapper,
        TransactionTemplate txTemplate,
        @Value("${app.valuation.enabled:true}") boolean enabled
    ) {
        this.providers = providers;
        this.geocoder = geocoder;
        this.priceIndex = priceIndex;
        this.adjustments = adjustments;
        this.metadataRepository = metadataRepository;
        this.valuationRepository = valuationRepository;
        this.accountRepository = accountRepository;
        this.accessResolver = accessResolver;
        this.objectMapper = objectMapper;
        this.txTemplate = txTemplate;
        this.enabled = enabled;
    }

    // ─── Read ────────────────────────────────────────────────────────────────

    /** Stored estimates, newest first. Readable by co-owners. */
    @Transactional(readOnly = true)
    public List<PropertyValuation> history(Long accountId, Long memberId) {
        accessResolver.requireReadable(accountId, memberId);
        return valuationRepository.findByAccountIdOrderByValuedAtDesc(accountId);
    }

    // ─── Estimate ────────────────────────────────────────────────────────────

    /**
     * Values one property.
     *
     * <p>Owner-only: an estimate can move the account balance, and a co-owner must not be
     * able to rewrite the owner's net worth.
     */
    @Transactional
    public PropertyValuationResponse estimate(Long accountId, Long memberId) {
        Account account = accessResolver.requireOwner(accountId, memberId);
        if (account.getType() != AccountType.REAL_ESTATE) {
            throw new IllegalArgumentException("Account is not a real estate account");
        }
        RealEstateMetadata metadata = metadataRepository.findByAccountId(accountId)
            .orElseThrow(() -> new IllegalStateException("Property has no metadata yet"));

        return estimateFor(account, metadata);
    }

    /**
     * Values every property of a member, for the scheduled refresh.
     *
     * <p>Guarded per property: one unreachable commune must not abort the rest.
     *
     * <p><b>Each property commits on its own.</b> This method is deliberately not
     * {@code @Transactional}: catching the exception is not enough to isolate a database
     * failure, because JPA marks the transaction rollback-only when the provider raises, and
     * the commit at the end then throws {@code UnexpectedRollbackException} — discarding every
     * property that had already succeeded. A per-property {@link TransactionTemplate} is what
     * makes the guard mean what it says. The same reason {@code AmundiSyncService} and
     * {@code BourseDirectSyncService} use one.
     */
    public int refreshAllForMember(Long memberId) {
        if (!enabled) {
            return 0;
        }
        List<Account> accounts = txTemplate.execute(status ->
            accountRepository.findAllByMemberIdOrderByCreatedAtAsc(memberId));
        int refreshed = 0;
        for (Account account : accounts) {
            if (account.getType() != AccountType.REAL_ESTATE) {
                continue;
            }
            // The metadata lookup belongs inside the guard: it hits the database, so it can
            // fail too, and a single bad property must not abort the whole member's refresh.
            try {
                Boolean ok = txTemplate.execute(status -> {
                    Optional<RealEstateMetadata> metadata =
                        metadataRepository.findByAccountId(account.getId());
                    if (metadata.isEmpty()) {
                        return false;
                    }
                    return estimateFor(account, metadata.get()).status() == ValuationStatus.OK;
                });
                if (Boolean.TRUE.equals(ok)) {
                    refreshed++;
                }
            } catch (Exception ex) {
                log.error("Valuation failed for property {} (member {}) — skipping it",
                    account.getId(), memberId, ex);
            }
        }
        return refreshed;
    }

    private PropertyValuationResponse estimateFor(Account account, RealEstateMetadata metadata) {
        ValuationMode mode = metadata.getValuationMode();

        if (!enabled) {
            return withCostBasisFloor(account, metadata,
                PropertyValuationResponse.failed(ValuationStatus.PROVIDER_UNAVAILABLE, mode));
        }
        PropertyKind kind = metadata.kind();
        if (kind == null || !kind.isEstimable()) {
            return withCostBasisFloor(account, metadata,
                PropertyValuationResponse.failed(ValuationStatus.NOT_ESTIMABLE, mode));
        }
        if (metadata.getSurfaceArea() == null || metadata.getSurfaceArea().signum() <= 0) {
            return withCostBasisFloor(account, metadata,
                PropertyValuationResponse.failed(ValuationStatus.INCOMPLETE_DATA, mode));
        }

        ValuationStatus geocodeStatus = geocodeIfNeeded(metadata);
        if (geocodeStatus != ValuationStatus.OK) {
            return withCostBasisFloor(account, metadata,
                PropertyValuationResponse.failed(geocodeStatus, mode));
        }

        ValuationInput input = toInput(metadata, kind);

        PropertyValuationPort provider = providers.stream()
            .filter(p -> p.supports(input))
            .findFirst()
            .orElse(null);
        if (provider == null) {
            // No provider covers this area at all — Alsace-Moselle and Mayotte are the
            // structural cases, and the user needs to be told that rather than shown a guess.
            return withCostBasisFloor(account, metadata,
                PropertyValuationResponse.failed(ValuationStatus.UNSUPPORTED_AREA, mode));
        }

        Optional<ValuationResult> raw;
        try {
            raw = provider.estimate(input);
        } catch (ValuationProviderException ex) {
            // The source was unreachable. Saying so beats telling the user their commune has
            // no sales, which is what a swallowed transport error used to look like.
            log.warn("Valuation provider unavailable for property {}", account.getId(), ex);
            return withCostBasisFloor(account, metadata,
                PropertyValuationResponse.failed(ValuationStatus.PROVIDER_UNAVAILABLE, mode));
        }
        if (raw.isEmpty()) {
            return withCostBasisFloor(account, metadata,
                PropertyValuationResponse.failed(ValuationStatus.NO_COMPARABLE_DATA, mode));
        }
        ValuationResult result = raw.get();

        // 1. Correct the commune median for this specific property.
        PropertyAdjustments.Result adjusted =
            adjustments.compute(metadata, result.estimatedValue(), result.pricePerSqm());

        // 2. Correct the bounds the same way. The band brackets *this property's* figure, so it
        // has to go through the identical transform: adjusting only the headline value left a
        // q25/q75 pair the estimate could sit outside of, since the multiplier reaches ±25% and
        // a garage adds its area-equivalent on top.
        BigDecimal low = adjusted.applyTo(result.lowValue());
        BigDecimal high = adjusted.applyTo(result.highValue());

        // 3. Carry it all forward from the data's vintage to today.
        BigDecimal reindexRatio = reindexRatio(result.sourceYear(), kind);
        BigDecimal finalValue = adjusted.value();
        if (reindexRatio != null) {
            finalValue = finalValue.multiply(reindexRatio);
            low = low != null ? low.multiply(reindexRatio) : null;
            high = high != null ? high.multiply(reindexRatio) : null;
        }
        finalValue = finalValue.setScale(2, RoundingMode.HALF_UP);

        PropertyValuation saved = persist(account, metadata, provider.providerName(), result,
            finalValue, low, high, adjusted, reindexRatio);

        boolean applied = mode == ValuationMode.ESTIMATED;
        if (applied) {
            account.setCurrentBalance(finalValue);
            accountRepository.save(account);
        }

        return PropertyValuationResponse.from(
            saved, mode, applied, reindexRatio, toDtos(adjusted.applied()), result.scale());
    }

    /**
     * Keeps a property that could not be valued from sitting at zero.
     *
     * <p>A property with no estimate and no balance reported 0 € and a 100% loss against its
     * purchase price — technically "we have no figure", but read by anyone as "your flat is
     * worthless". What was paid for it is a fact the user supplied, so it is a far better
     * floor than nothing, and it is replaced the moment a real estimate succeeds.
     *
     * <p>Only ever raises a zero balance: an existing valuation, manual or estimated, is left
     * exactly as it was.
     *
     * <p>Applies in {@code MANUAL} mode as well, which is the single place that lock does not
     * hold — deliberately, because it protects a figure the user gave and a zero is the absence
     * of one. See {@link com.picsou.model.ValuationMode}.
     */
    private PropertyValuationResponse withCostBasisFloor(Account account, RealEstateMetadata metadata,
                                                         PropertyValuationResponse response) {
        BigDecimal current = account.getCurrentBalance();
        if (current != null && current.signum() != 0) {
            return response;
        }
        BigDecimal costBasis = metadata.costBasis();
        if (costBasis == null || costBasis.signum() <= 0) {
            return response;
        }
        log.info("Property {} has no valuation yet; seeding its value from the cost basis {}",
            account.getId(), costBasis);
        account.setCurrentBalance(costBasis);
        accountRepository.save(account);
        return response;
    }

    private ValuationInput toInput(RealEstateMetadata m, PropertyKind kind) {
        String insee = m.getInseeCode();
        String department = null;
        if (insee != null && insee.length() >= 2) {
            department = insee.startsWith("97") && insee.length() >= 3
                ? insee.substring(0, 3)
                : insee.substring(0, 2);
        }
        return new ValuationInput(
            insee,
            department,
            m.getCountry(),
            kind,
            m.getSurfaceArea(),
            m.getLandArea(),
            m.getRooms(),
            m.getConstructionYear(),
            m.getLatitude(),
            m.getLongitude()
        );
    }

    /**
     * Resolves the address once and caches the result on the property.
     *
     * <p>Re-geocoding happens only when {@code inseeCode} is missing — the metadata update
     * path clears it whenever the address changes, which is what makes this cheap enough to
     * call on every estimate.
     */
    private ValuationStatus geocodeIfNeeded(RealEstateMetadata m) {
        if (m.getInseeCode() != null && !m.getInseeCode().isBlank()) {
            return ValuationStatus.OK;
        }
        String query = buildAddressQuery(m);
        if (query.isBlank()) {
            return ValuationStatus.INCOMPLETE_DATA;
        }
        Optional<GeocodingPort.GeocodeResult> hit = geocoder.geocode(query);
        if (hit.isEmpty()) {
            return ValuationStatus.GEOCODING_FAILED;
        }
        GeocodingPort.GeocodeResult g = hit.get();
        if (g.inseeCode() == null || g.inseeCode().isBlank()) {
            return ValuationStatus.GEOCODING_FAILED;
        }
        if (g.score() != null && g.score().compareTo(MIN_GEOCODE_SCORE) < 0) {
            // A weak match usually means a typo or an incomplete address. Valuing the wrong
            // commune is worse than asking the user to fix it.
            log.info("Geocoding score {} too low for '{}' — refusing to value on it", g.score(), query);
            return ValuationStatus.GEOCODING_FAILED;
        }

        m.setInseeCode(g.inseeCode());
        m.setLatitude(g.latitude());
        m.setLongitude(g.longitude());
        m.setBanId(g.banId());
        m.setGeocodeScore(g.score());
        m.setGeocodedAt(java.time.Instant.now());
        if (m.getPostalCode() == null || m.getPostalCode().isBlank()) {
            m.setPostalCode(g.postcode());
        }
        if (m.getCity() == null || m.getCity().isBlank()) {
            m.setCity(g.city());
        }
        metadataRepository.save(m);
        return ValuationStatus.OK;
    }

    private String buildAddressQuery(RealEstateMetadata m) {
        StringBuilder sb = new StringBuilder();
        if (m.getAddress() != null) sb.append(m.getAddress().trim());
        if (m.getPostalCode() != null && !m.getPostalCode().isBlank()) sb.append(' ').append(m.getPostalCode().trim());
        if (m.getCity() != null && !m.getCity().isBlank()) sb.append(' ').append(m.getCity().trim());
        return sb.toString().trim();
    }

    /**
     * How much prices moved between the source vintage and now.
     *
     * <p>The vintage is a calendar year, so it is treated as its midpoint — using January
     * would systematically over-correct by half a year.
     */
    private BigDecimal reindexRatio(Short sourceYear, PropertyKind kind) {
        if (sourceYear == null) {
            return null;
        }
        YearMonth from = YearMonth.of(sourceYear, 7);
        YearMonth to = YearMonth.now();
        if (!from.isBefore(to)) {
            return null;
        }
        return priceIndex.reindexRatio(from, to, kind).orElse(null);
    }

    private PropertyValuation persist(Account account, RealEstateMetadata metadata,
                                      String providerName, ValuationResult result,
                                      BigDecimal finalValue, BigDecimal low, BigDecimal high,
                                      PropertyAdjustments.Result adjusted,
                                      BigDecimal reindexRatio) {
        LocalDate today = LocalDate.now();
        // Upsert on (account, day): a user hitting refresh repeatedly should correct today's
        // estimate, not litter the history with near-identical rows.
        PropertyValuation valuation = valuationRepository
            .findByAccountIdAndValuedAt(account.getId(), today)
            .orElseGet(() -> PropertyValuation.builder()
                .account(account)
                .member(metadata.getMember())
                .valuedAt(today)
                .build());

        valuation.setEstimatedValue(finalValue);
        valuation.setLowValue(scaleOrNull(low));
        valuation.setHighValue(scaleOrNull(high));
        valuation.setPricePerSqm(result.pricePerSqm());
        valuation.setProvider(providerName);
        valuation.setConfidence(result.confidence());
        valuation.setSampleSize(result.sampleSize());
        valuation.setSourceYear(result.sourceYear());
        valuation.setMethodDetail(methodDetailJson(result, adjusted, reindexRatio));
        return valuationRepository.save(valuation);
    }

    private static BigDecimal scaleOrNull(BigDecimal value) {
        return value == null ? null : value.setScale(2, RoundingMode.HALF_UP);
    }

    /**
     * Freezes how this estimate was built.
     *
     * <p>Written as JSON rather than columns because it is explanatory, not queryable, and
     * the coefficient set will change over time — an old row must still describe the rules
     * that actually produced it.
     */
    private String methodDetailJson(ValuationResult result, PropertyAdjustments.Result adjusted,
                                    BigDecimal reindexRatio) {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("basePricePerSqm", result.pricePerSqm());
        detail.put("baseValue", result.estimatedValue());
        detail.put("scale", result.scale());
        detail.put("sourceYear", result.sourceYear());
        detail.put("sampleSize", result.sampleSize());
        detail.put("multiplier", adjusted.multiplier());
        detail.put("reindexRatio", reindexRatio);
        detail.put("adjustments", toDtos(adjusted.applied()));
        try {
            return objectMapper.writeValueAsString(detail);
        } catch (Exception ex) {
            log.warn("Could not serialise valuation method detail", ex);
            return null;
        }
    }

    private static List<AdjustmentDto> toDtos(List<PropertyAdjustments.Adjustment> applied) {
        List<AdjustmentDto> dtos = new ArrayList<>(applied.size());
        for (PropertyAdjustments.Adjustment a : applied) {
            dtos.add(new AdjustmentDto(a.code(), a.factor(), a.sqm(), a.amount()));
        }
        return dtos;
    }
}

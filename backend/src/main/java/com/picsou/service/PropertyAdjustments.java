package com.picsou.service;

import com.picsou.model.PropertyKind;
import com.picsou.model.RealEstateMetadata;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

/**
 * Corrects a commune-wide median for what makes one property differ from another.
 *
 * <p><b>These are declared heuristics, not a calibrated model.</b> DVF records no
 * construction year, floor, lift, balcony, garden, garage or condition, so none of the
 * coefficients below can be fitted against the open data — they encode ordinary market
 * intuition. That is precisely why every applied coefficient is returned to the caller and
 * shown in the UI: a user who disagrees can see exactly what moved the number and switch the
 * property to a manual valuation.
 *
 * <p>Two design choices worth keeping:
 *
 * <ul>
 *   <li><b>Garage and parking scale with the local price per m², not a flat euro amount.</b>
 *       A parking space is worth roughly the price of a handful of square metres wherever it
 *       is; a fixed €12,000 would be absurd in central Paris and equally absurd in a village.
 *   <li><b>Everything is bounded.</b> The multiplier is clamped and the area-equivalents are
 *       capped, so no pile-up of individually-plausible bonuses can produce a silly total.
 * </ul>
 */
@Component
public class PropertyAdjustments {

    // ─── Multiplicative factors ──────────────────────────────────────────────

    /** Walk-up penalty: from the 3rd floor with no lift, and worse the higher it goes. */
    private static final BigDecimal NO_ELEVATOR_BASE = new BigDecimal("-0.05");
    private static final BigDecimal NO_ELEVATOR_PER_FLOOR = new BigDecimal("-0.01");
    private static final BigDecimal NO_ELEVATOR_FLOOR_CAP = new BigDecimal("-0.10");
    private static final int NO_ELEVATOR_FROM_FLOOR = 3;

    /** Top floor is a premium — but only when a lift makes it comfortable. */
    private static final BigDecimal TOP_FLOOR_WITH_ELEVATOR = new BigDecimal("0.03");
    /** Street-level flats trade at a discount (noise, privacy, security). */
    private static final BigDecimal GROUND_FLOOR = new BigDecimal("-0.03");

    /**
     * A second bathroom is a genuine differentiator for a family-sized home; beyond that the
     * effect flattens. Small and capped because DVF records no bathroom count at all, so
     * unlike the price per m² this is intuition, not measurement.
     */
    private static final BigDecimal EXTRA_BATHROOM = new BigDecimal("0.02");
    private static final BigDecimal EXTRA_BATHROOM_CAP = new BigDecimal("0.04");

    private static final BigDecimal GARDEN_APARTMENT = new BigDecimal("0.05");
    private static final BigDecimal GARDEN_HOUSE = new BigDecimal("0.02");
    private static final BigDecimal TERRACE = new BigDecimal("0.03");
    private static final BigDecimal BALCONY = new BigDecimal("0.015");

    /**
     * Energy performance. The "passoire thermique" discount on F/G is the best documented
     * effect here — since 2025 the worst-rated homes cannot be let, which shows up in price.
     */
    private static final BigDecimal ENERGY_A_B = new BigDecimal("0.04");
    private static final BigDecimal ENERGY_C = new BigDecimal("0.02");
    private static final BigDecimal ENERGY_E = new BigDecimal("-0.03");
    private static final BigDecimal ENERGY_F = new BigDecimal("-0.06");
    private static final BigDecimal ENERGY_G = new BigDecimal("-0.10");

    /** Construction era, used only when no energy rating is known — see {@link #compute}. */
    private static final BigDecimal ERA_PRE_1949 = new BigDecimal("-0.02");
    private static final BigDecimal ERA_1949_1974 = new BigDecimal("-0.04");
    private static final BigDecimal ERA_2001_2012 = new BigDecimal("0.02");
    private static final BigDecimal ERA_POST_2012 = new BigDecimal("0.05");

    /** Hard bounds on the combined multiplier. */
    private static final BigDecimal MIN_MULTIPLIER = new BigDecimal("0.75");
    private static final BigDecimal MAX_MULTIPLIER = new BigDecimal("1.25");

    // ─── Additive area-equivalents (multiplied by the local €/m²) ────────────

    private static final BigDecimal GARAGE_SQM_EQUIVALENT = new BigDecimal("12");
    private static final BigDecimal PARKING_SQM_EQUIVALENT = new BigDecimal("7");

    /**
     * Land is already partly reflected in a house's price per m², so only the plot beyond a
     * typical one counts, and at a heavily discounted rate: 100 m² of extra garden is worth
     * about 2 m² of housing.
     */
    private static final BigDecimal LAND_REFERENCE_SQM = new BigDecimal("500");
    private static final BigDecimal LAND_SQM_PER_EXTRA_SQM = new BigDecimal("0.02");
    private static final BigDecimal LAND_CAP_SQM_EQUIVALENT = new BigDecimal("30");

    /** Ceiling on all area-equivalents combined. */
    private static final BigDecimal ADDITIVE_CAP_SQM_EQUIVALENT = new BigDecimal("60");

    /**
     * One applied correction, surfaced verbatim to the user.
     *
     * @param code   stable key the frontend translates
     * @param factor multiplicative delta (e.g. -0.05), or null for an additive one
     * @param sqm    area-equivalent added, or null for a multiplicative one
     * @param amount euro impact of this correction
     */
    public record Adjustment(String code, BigDecimal factor, BigDecimal sqm, BigDecimal amount) {}

    /**
     * The correction, kept as the affine transform it is rather than only its output.
     *
     * <p>{@link #value} is {@code applyTo(baseValue)}. Exposing the transform lets the caller
     * put the provider's q25/q75 bounds through the identical correction, which is what keeps
     * the band comparable with the figure it brackets — see {@link #applyTo}.
     *
     * @param value       the corrected headline figure
     * @param multiplier  clamped combined multiplicative factor
     * @param addedAmount euro value of the area-equivalents, already priced at the local €/m²
     */
    public record Result(BigDecimal value, List<Adjustment> applied,
                         BigDecimal multiplier, BigDecimal addedAmount) {

        /**
         * The same correction applied to any figure derived from the same commune median.
         *
         * <p>Used for the q25/q75 bounds. The transform is {@code x -> x * multiplier + added}
         * with a strictly positive multiplier, so it preserves ordering: a band that contained
         * the raw median still contains the corrected estimate. Re-indexing the bounds alone —
         * as this once did — left a band the estimate could sit outside of, which reads as the
         * app contradicting itself.
         *
         * @return null for a null bound, since a provider may report one and not the other
         */
        public BigDecimal applyTo(BigDecimal bound) {
            if (bound == null) {
                return null;
            }
            return bound.multiply(multiplier).add(addedAmount).setScale(2, RoundingMode.HALF_UP);
        }
    }

    /**
     * @param baseValue    median €/m² × living area, before corrections
     * @param pricePerSqm  the local median, used to price area-equivalents
     */
    public Result compute(RealEstateMetadata m, BigDecimal baseValue, BigDecimal pricePerSqm) {
        List<Adjustment> applied = new ArrayList<>();
        PropertyKind kind = m.kind();

        BigDecimal factorSum = BigDecimal.ZERO;
        factorSum = factorSum.add(floorFactors(m, kind, applied, baseValue));
        factorSum = factorSum.add(outdoorFactors(m, kind, applied, baseValue));
        factorSum = factorSum.add(bathroomFactor(m, applied, baseValue));
        factorSum = factorSum.add(energyOrEraFactor(m, applied, baseValue));

        BigDecimal multiplier = clamp(BigDecimal.ONE.add(factorSum), MIN_MULTIPLIER, MAX_MULTIPLIER);
        BigDecimal adjusted = baseValue.multiply(multiplier);

        BigDecimal extraSqm = additiveSqm(m, kind, applied, pricePerSqm);
        BigDecimal added = pricePerSqm != null && extraSqm.signum() > 0
            ? extraSqm.multiply(pricePerSqm)
            : BigDecimal.ZERO;
        adjusted = adjusted.add(added);

        return new Result(adjusted.setScale(2, RoundingMode.HALF_UP), applied, multiplier, added);
    }

    private BigDecimal floorFactors(RealEstateMetadata m, PropertyKind kind,
                                    List<Adjustment> applied, BigDecimal base) {
        // Only meaningful for flats: a house's "floor" is not a thing a buyer prices.
        if (kind != PropertyKind.APARTMENT || m.getFloorNumber() == null) {
            return BigDecimal.ZERO;
        }
        short floor = m.getFloorNumber();
        boolean hasElevator = Boolean.TRUE.equals(m.getHasElevator());
        BigDecimal total = BigDecimal.ZERO;

        if (floor == 0) {
            total = total.add(record(applied, "GROUND_FLOOR", GROUND_FLOOR, base));
        } else if (floor >= NO_ELEVATOR_FROM_FLOOR && !hasElevator) {
            BigDecimal extra = NO_ELEVATOR_PER_FLOOR
                .multiply(BigDecimal.valueOf(floor - NO_ELEVATOR_FROM_FLOOR));
            BigDecimal penalty = NO_ELEVATOR_BASE.add(extra).max(NO_ELEVATOR_FLOOR_CAP);
            total = total.add(record(applied, "NO_ELEVATOR", penalty, base));
        }

        Short top = m.getFloorsTotal();
        if (hasElevator && top != null && top >= 3 && floor == top) {
            total = total.add(record(applied, "TOP_FLOOR_ELEVATOR", TOP_FLOOR_WITH_ELEVATOR, base));
        }
        return total;
    }

    /**
     * Bathrooms beyond the first.
     *
     * <p>Counted relative to one because every dwelling has at least one; a flat with two is
     * what commands the premium. Ignored when the count is unknown, so leaving the field empty
     * costs nothing.
     */
    private BigDecimal bathroomFactor(RealEstateMetadata m, List<Adjustment> applied, BigDecimal base) {
        Short bathrooms = m.getBathrooms();
        if (bathrooms == null || bathrooms <= 1) {
            return BigDecimal.ZERO;
        }
        BigDecimal factor = EXTRA_BATHROOM
            .multiply(BigDecimal.valueOf(bathrooms - 1))
            .min(EXTRA_BATHROOM_CAP);
        return record(applied, "EXTRA_BATHROOMS", factor, base);
    }

    private BigDecimal outdoorFactors(RealEstateMetadata m, PropertyKind kind,
                                      List<Adjustment> applied, BigDecimal base) {
        BigDecimal total = BigDecimal.ZERO;
        if (Boolean.TRUE.equals(m.getHasGarden())) {
            // A private garden is a differentiator for a flat; for a house it is expected,
            // and most of its value already sits in the land area handled separately.
            BigDecimal factor = kind == PropertyKind.APARTMENT ? GARDEN_APARTMENT : GARDEN_HOUSE;
            total = total.add(record(applied, "GARDEN", factor, base));
        }
        if (Boolean.TRUE.equals(m.getHasTerrace())) {
            total = total.add(record(applied, "TERRACE", TERRACE, base));
        }
        if (Boolean.TRUE.equals(m.getHasBalcony())) {
            total = total.add(record(applied, "BALCONY", BALCONY, base));
        }
        return total;
    }

    /**
     * Energy rating when known, construction era otherwise.
     *
     * <p>Never both: the era coefficients are a proxy for thermal performance, so applying
     * them alongside a real DPE rating would count the same effect twice.
     */
    private BigDecimal energyOrEraFactor(RealEstateMetadata m, List<Adjustment> applied, BigDecimal base) {
        String energy = m.getEnergyClass();
        if (energy != null && !energy.isBlank()) {
            BigDecimal factor = switch (energy.trim().toUpperCase(java.util.Locale.ROOT)) {
                case "A", "B" -> ENERGY_A_B;
                case "C" -> ENERGY_C;
                case "E" -> ENERGY_E;
                case "F" -> ENERGY_F;
                case "G" -> ENERGY_G;
                default -> BigDecimal.ZERO; // D is the reference
            };
            if (factor.signum() != 0) {
                return record(applied, "ENERGY_" + energy.trim().toUpperCase(java.util.Locale.ROOT), factor, base);
            }
            return BigDecimal.ZERO;
        }

        Short year = m.getConstructionYear();
        if (year == null) {
            return BigDecimal.ZERO;
        }
        BigDecimal factor;
        String code;
        if (year < 1949) {
            factor = ERA_PRE_1949;
            code = "ERA_PRE_1949";
        } else if (year <= 1974) {
            // Post-war reconstruction: built fast, before any thermal regulation.
            factor = ERA_1949_1974;
            code = "ERA_1949_1974";
        } else if (year <= 2000) {
            return BigDecimal.ZERO;
        } else if (year <= 2012) {
            factor = ERA_2001_2012;
            code = "ERA_2001_2012";
        } else {
            factor = ERA_POST_2012;
            code = "ERA_POST_2012";
        }
        return record(applied, code, factor, base);
    }

    private BigDecimal additiveSqm(RealEstateMetadata m, PropertyKind kind,
                                   List<Adjustment> applied, BigDecimal pricePerSqm) {
        BigDecimal total = BigDecimal.ZERO;
        // Multiplicative corrections are already in `applied`; only what this method appends
        // may be rescaled by the cap below.
        int from = applied.size();

        if (m.getGarageCount() != null && m.getGarageCount() > 0) {
            BigDecimal sqm = GARAGE_SQM_EQUIVALENT.multiply(BigDecimal.valueOf(m.getGarageCount()));
            total = total.add(sqm);
            applied.add(additive("GARAGE", sqm, pricePerSqm));
        }
        if (m.getParkingCount() != null && m.getParkingCount() > 0) {
            BigDecimal sqm = PARKING_SQM_EQUIVALENT.multiply(BigDecimal.valueOf(m.getParkingCount()));
            total = total.add(sqm);
            applied.add(additive("PARKING", sqm, pricePerSqm));
        }
        if (kind == PropertyKind.HOUSE && m.getLandArea() != null) {
            BigDecimal extra = m.getLandArea().subtract(LAND_REFERENCE_SQM);
            if (extra.signum() > 0) {
                BigDecimal sqm = extra.multiply(LAND_SQM_PER_EXTRA_SQM).min(LAND_CAP_SQM_EQUIVALENT);
                total = total.add(sqm);
                applied.add(additive("LAND_SURPLUS", sqm, pricePerSqm));
            }
        }

        // The cap has to reach the disclosed lines too, not just the total. The breakdown is
        // load-bearing — the ADR leans on it to make a heuristic figure auditable — and lines
        // recorded at full size while the total was capped added up to more than what actually
        // went into the estimate, so the panel contradicted the number it was explaining.
        // Scaling them keeps every line's share of the truth rather than dropping the last one,
        // which would misattribute the cut to whichever feature happened to be entered last.
        if (total.compareTo(ADDITIVE_CAP_SQM_EQUIVALENT) > 0) {
            rescale(applied, from, ADDITIVE_CAP_SQM_EQUIVALENT.divide(total, 10, RoundingMode.HALF_UP),
                pricePerSqm);
            return ADDITIVE_CAP_SQM_EQUIVALENT;
        }
        return total;
    }

    /**
     * Shrinks the additive lines recorded from index {@code from} onward by {@code ratio}, so
     * their areas and euro amounts still sum to what the cap allowed.
     */
    private void rescale(List<Adjustment> applied, int from, BigDecimal ratio, BigDecimal pricePerSqm) {
        for (int i = from; i < applied.size(); i++) {
            Adjustment a = applied.get(i);
            BigDecimal sqm = a.sqm().multiply(ratio).setScale(2, RoundingMode.HALF_UP);
            applied.set(i, additive(a.code(), sqm, pricePerSqm));
        }
    }

    private BigDecimal record(List<Adjustment> applied, String code, BigDecimal factor, BigDecimal base) {
        applied.add(new Adjustment(code, factor, null,
            base.multiply(factor).setScale(2, RoundingMode.HALF_UP)));
        return factor;
    }

    private Adjustment additive(String code, BigDecimal sqm, BigDecimal pricePerSqm) {
        BigDecimal amount = pricePerSqm != null
            ? sqm.multiply(pricePerSqm).setScale(2, RoundingMode.HALF_UP)
            : null;
        return new Adjustment(code, null, sqm, amount);
    }

    private static BigDecimal clamp(BigDecimal value, BigDecimal min, BigDecimal max) {
        return value.max(min).min(max);
    }
}

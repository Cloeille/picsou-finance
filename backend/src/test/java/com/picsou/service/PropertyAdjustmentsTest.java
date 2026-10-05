package com.picsou.service;

import com.picsou.model.RealEstateMetadata;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * These coefficients are declared heuristics, not a fitted model, so the tests pin the
 * properties that must hold rather than exact euro amounts: corrections apply in the right
 * direction, never double-count, and cannot compound into an absurd total.
 */
class PropertyAdjustmentsTest {

    private static final BigDecimal BASE = new BigDecimal("400000");
    private static final BigDecimal PRICE_PER_SQM = new BigDecimal("4000");

    private final PropertyAdjustments adjustments = new PropertyAdjustments();

    private static RealEstateMetadata.RealEstateMetadataBuilder apartment() {
        return RealEstateMetadata.builder()
            .propertyType("APARTMENT")
            .surfaceArea(new BigDecimal("100"))
            .garageCount((short) 0).parkingCount((short) 0)
            .hasGarden(false).hasTerrace(false).hasBalcony(false);
    }

    private static RealEstateMetadata.RealEstateMetadataBuilder house() {
        return RealEstateMetadata.builder()
            .propertyType("HOUSE")
            .surfaceArea(new BigDecimal("100"))
            .garageCount((short) 0).parkingCount((short) 0)
            .hasGarden(false).hasTerrace(false).hasBalcony(false);
    }

    private PropertyAdjustments.Result compute(RealEstateMetadata m) {
        return adjustments.compute(m, BASE, PRICE_PER_SQM);
    }

    @Test
    void noDistinguishingFeatures_leavesTheValueAlone() {
        PropertyAdjustments.Result result = compute(apartment().build());

        assertThat(result.value()).isEqualByComparingTo("400000.00");
        assertThat(result.applied()).isEmpty();
    }

    @Test
    void walkUpApartment_isDiscounted() {
        PropertyAdjustments.Result result = compute(
            apartment().floorNumber((short) 4).hasElevator(false).build());

        assertThat(result.value()).isLessThan(BASE);
        assertThat(result.applied()).extracting(PropertyAdjustments.Adjustment::code)
            .contains("NO_ELEVATOR");
    }

    @Test
    void walkUpPenalty_growsWithHeightButIsCapped() {
        BigDecimal third = compute(apartment().floorNumber((short) 3).hasElevator(false).build()).value();
        BigDecimal sixth = compute(apartment().floorNumber((short) 6).hasElevator(false).build()).value();
        BigDecimal twelfth = compute(apartment().floorNumber((short) 12).hasElevator(false).build()).value();

        assertThat(sixth).isLessThan(third);
        // Capped: beyond a point another flight of stairs stops mattering, and an uncapped
        // per-floor penalty would send a tower block towards zero.
        assertThat(twelfth).isEqualByComparingTo(compute(
            apartment().floorNumber((short) 8).hasElevator(false).build()).value());
    }

    @Test
    void lift_removesTheWalkUpPenalty() {
        BigDecimal withLift = compute(apartment().floorNumber((short) 4).hasElevator(true).build()).value();
        assertThat(withLift).isEqualByComparingTo("400000.00");
    }

    @Test
    void groundFloorApartment_isDiscounted() {
        PropertyAdjustments.Result result = compute(apartment().floorNumber((short) 0).build());

        assertThat(result.value()).isLessThan(BASE);
        assertThat(result.applied()).extracting(PropertyAdjustments.Adjustment::code)
            .contains("GROUND_FLOOR");
    }

    @Test
    void topFloorWithLift_isAPremium() {
        PropertyAdjustments.Result result = compute(
            apartment().floorNumber((short) 5).floorsTotal((short) 5).hasElevator(true).build());

        assertThat(result.value()).isGreaterThan(BASE);
        assertThat(result.applied()).extracting(PropertyAdjustments.Adjustment::code)
            .contains("TOP_FLOOR_ELEVATOR");
    }

    @Test
    void houseFloorNumber_isIgnored() {
        // "Which floor" is not something a buyer prices on a house.
        assertThat(compute(house().floorNumber((short) 0).build()).value())
            .isEqualByComparingTo("400000.00");
    }

    @Test
    void outdoorSpace_addsValue() {
        assertThat(compute(apartment().hasBalcony(true).build()).value()).isGreaterThan(BASE);
        assertThat(compute(apartment().hasTerrace(true).build()).value()).isGreaterThan(BASE);
        assertThat(compute(apartment().hasGarden(true).build()).value()).isGreaterThan(BASE);
    }

    @Test
    void gardenIsWorthMoreOnAFlatThanOnAHouse() {
        BigDecimal flat = compute(apartment().hasGarden(true).build()).value();
        BigDecimal detached = compute(house().hasGarden(true).build()).value();

        // On a house a garden is expected and most of its value already sits in the land
        // area; on a flat it is a genuine differentiator.
        assertThat(flat).isGreaterThan(detached);
    }

    @Test
    void parkingAndGarage_scaleWithTheLocalPricePerSqm() {
        RealEstateMetadata withGarage = apartment().garageCount((short) 1).build();

        BigDecimal cheapArea = adjustments.compute(withGarage, BASE, new BigDecimal("1000")).value();
        BigDecimal pricyArea = adjustments.compute(withGarage, BASE, new BigDecimal("10000")).value();

        // A flat euro amount would be absurd in central Paris and equally absurd in a village;
        // a garage is worth roughly a fixed number of square metres wherever it is.
        assertThat(pricyArea).isGreaterThan(cheapArea);
    }

    @Test
    void garageIsWorthMoreThanAnOpenParkingSpace() {
        BigDecimal garage = compute(apartment().garageCount((short) 1).build()).value();
        BigDecimal parking = compute(apartment().parkingCount((short) 1).build()).value();
        assertThat(garage).isGreaterThan(parking);
    }

    @Test
    void houseLandSurplus_countsOnlyBeyondATypicalPlot() {
        BigDecimal typical = compute(house().landArea(new BigDecimal("400")).build()).value();
        BigDecimal large = compute(house().landArea(new BigDecimal("2000")).build()).value();

        // A normal plot is already reflected in the price per m² of built surface, so only
        // the surplus adds anything -- otherwise land would be counted twice.
        assertThat(typical).isEqualByComparingTo("400000.00");
        assertThat(large).isGreaterThan(typical);
    }

    @Test
    void apartmentLandArea_isIgnored() {
        assertThat(compute(apartment().landArea(new BigDecimal("5000")).build()).value())
            .isEqualByComparingTo("400000.00");
    }

    @Test
    void poorEnergyRating_isDiscounted() {
        assertThat(compute(apartment().energyClass("G").build()).value()).isLessThan(BASE);
        assertThat(compute(apartment().energyClass("F").build()).value()).isLessThan(BASE);
        assertThat(compute(apartment().energyClass("A").build()).value()).isGreaterThan(BASE);
        // D is the reference point, so it moves nothing.
        assertThat(compute(apartment().energyClass("D").build()).value()).isEqualByComparingTo("400000.00");
    }

    @Test
    void energyRating_supersedesConstructionEra() {
        // The era coefficients are a proxy for thermal performance. Applying both would count
        // the same effect twice, so a real DPE rating wins outright.
        RealEstateMetadata both = apartment().energyClass("A").constructionYear((short) 1960).build();
        RealEstateMetadata ratingOnly = apartment().energyClass("A").build();

        assertThat(compute(both).value()).isEqualByComparingTo(compute(ratingOnly).value());
        assertThat(compute(both).applied()).extracting(PropertyAdjustments.Adjustment::code)
            .noneMatch(code -> code.startsWith("ERA_"));
    }

    @Test
    void constructionEra_usedWhenNoRatingIsKnown() {
        assertThat(compute(apartment().constructionYear((short) 1960).build()).value()).isLessThan(BASE);
        assertThat(compute(apartment().constructionYear((short) 2020).build()).value()).isGreaterThan(BASE);
        // 1975-2000 is the reference band.
        assertThat(compute(apartment().constructionYear((short) 1990).build()).value())
            .isEqualByComparingTo("400000.00");
    }

    @Test
    void multiplier_cannotCompoundIntoSomethingAbsurd() {
        RealEstateMetadata everything = apartment()
            .floorNumber((short) 5).floorsTotal((short) 5).hasElevator(true)
            .hasGarden(true).hasTerrace(true).hasBalcony(true)
            .energyClass("A")
            .build();

        PropertyAdjustments.Result result = compute(everything);

        // Individually plausible bonuses must not stack into a silly total.
        assertThat(result.multiplier()).isBetween(new BigDecimal("0.75"), new BigDecimal("1.25"));
    }

    @Test
    void multiplier_flooredForTheWorstCase() {
        RealEstateMetadata grim = apartment()
            .floorNumber((short) 9).hasElevator(false)
            .energyClass("G")
            .build();

        assertThat(compute(grim).multiplier()).isGreaterThanOrEqualTo(new BigDecimal("0.75"));
    }

    @Test
    void additiveExtras_areCapped() {
        RealEstateMetadata hoarder = house()
            .garageCount((short) 20).parkingCount((short) 20)
            .landArea(new BigDecimal("100000"))
            .build();

        // 60 m²-equivalent ceiling: without it, a data-entry slip on the plot size would
        // dominate the whole estimate.
        BigDecimal maximumExtra = new BigDecimal("60").multiply(PRICE_PER_SQM);
        assertThat(compute(hoarder).value()).isLessThanOrEqualTo(BASE.add(maximumExtra));
    }

    @Test
    void cappedExtras_stillAddUpToWhatWasApplied() {
        // The breakdown is what makes a heuristic figure auditable, so it has to reconcile with
        // the number it explains. Recording each line at full size while capping only the total
        // made the panel claim more than went in.
        RealEstateMetadata hoarder = house()
            .garageCount((short) 20).parkingCount((short) 20)
            .landArea(new BigDecimal("100000"))
            .build();
        PropertyAdjustments.Result result = compute(hoarder);

        BigDecimal disclosed = result.applied().stream()
            .filter(a -> a.sqm() != null)
            .map(PropertyAdjustments.Adjustment::amount)
            .reduce(BigDecimal.ZERO, BigDecimal::add);

        assertThat(disclosed).isEqualByComparingTo(result.addedAmount());
        // And the cap still bites -- otherwise this would pass by never capping at all.
        assertThat(result.addedAmount())
            .isEqualByComparingTo(new BigDecimal("60").multiply(PRICE_PER_SQM));
    }

    @Test
    void uncappedExtras_areReportedAtFullSize() {
        RealEstateMetadata modest = house().garageCount((short) 1).build();
        PropertyAdjustments.Result result = compute(modest);

        assertThat(result.applied()).singleElement().satisfies(a -> {
            assertThat(a.code()).isEqualTo("GARAGE");
            assertThat(a.sqm()).isEqualByComparingTo("12");
        });
        assertThat(result.addedAmount())
            .isEqualByComparingTo(new BigDecimal("12").multiply(PRICE_PER_SQM));
    }

    @Test
    void everyAppliedCorrectionIsReported() {
        RealEstateMetadata m = apartment()
            .floorNumber((short) 4).hasElevator(false)
            .hasBalcony(true)
            .garageCount((short) 1)
            .build();

        // The panel explaining the estimate is built from this list, so anything that moved
        // the number has to appear in it.
        assertThat(compute(m).applied()).extracting(PropertyAdjustments.Adjustment::code)
            .contains("NO_ELEVATOR", "BALCONY", "GARAGE");
    }

    @Test
    void unknownPropertyType_stillProducesAValue() {
        // property_type is free text predating the enum, so an unrecognised label must
        // degrade to "no type-specific corrections" rather than blow up.
        RealEstateMetadata odd = RealEstateMetadata.builder()
            .propertyType("chalet en bois")
            .surfaceArea(new BigDecimal("100"))
            .garageCount((short) 0).parkingCount((short) 0)
            .hasGarden(false).hasTerrace(false).hasBalcony(false)
            .build();

        assertThat(compute(odd).value()).isEqualByComparingTo("400000.00");
    }

    @Test
    void missingPricePerSqm_skipsTheAreaEquivalents() {
        // The department fallback can leave the median absent; extras priced off it are then
        // simply not applied rather than counted as zero-value or crashing.
        PropertyAdjustments.Result result =
            adjustments.compute(apartment().garageCount((short) 2).build(), BASE, null);

        assertThat(result.value()).isEqualByComparingTo("400000.00");
    }

    @Test
    void applyTo_putsABoundThroughTheSameTransformAsTheValue() {
        // The headline figure is applyTo(baseValue) by construction — that identity is what
        // lets the caller correct the q25/q75 bounds without re-deriving the maths.
        PropertyAdjustments.Result result = compute(
            apartment().garageCount((short) 1).floorNumber((short) 5).hasElevator(true).build());

        assertThat(result.applyTo(BASE)).isEqualByComparingTo(result.value());
    }

    @Test
    void applyTo_keepsTheBandAroundTheEstimate() {
        // A generously-featured flat: the multiplier pushes up and the garage adds on top, which
        // is exactly the shape that used to leave the raw q25/q75 band below the estimate.
        RealEstateMetadata flat = apartment()
            .garageCount((short) 2).parkingCount((short) 1)
            .floorNumber((short) 6).floorsTotal((short) 6).hasElevator(true)
            .hasTerrace(true).energyClass("A")
            .build();
        PropertyAdjustments.Result result = compute(flat);

        BigDecimal q25 = new BigDecimal("340000");
        BigDecimal q75 = new BigDecimal("470000");

        assertThat(result.value())
            .isBetween(result.applyTo(q25), result.applyTo(q75));
    }

    @Test
    void applyTo_survivesTheDownwardClampToo() {
        RealEstateMetadata grim = apartment()
            .floorNumber((short) 6).hasElevator(false).energyClass("G")
            .build();
        PropertyAdjustments.Result result = compute(grim);

        assertThat(result.value())
            .isBetween(result.applyTo(new BigDecimal("340000")),
                       result.applyTo(new BigDecimal("470000")));
    }

    @Test
    void applyTo_passesANullBoundThrough() {
        // A provider may report one quartile and not the other; a missing bound stays missing
        // rather than becoming the additive amount on its own.
        assertThat(compute(apartment().garageCount((short) 1).build()).applyTo(null)).isNull();
    }
}

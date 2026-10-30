package com.picsou.dto;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Drives the validator directly rather than through a controller.
 *
 * <p>Its real job is to pin that {@code @AssertTrue} on a <em>record</em> is picked up at all —
 * Hibernate Validator has to treat the derived {@code isSummingToOneHundred()} as a JavaBeans
 * property, which is not obvious for a type whose accessors are components. If that ever stops
 * holding, allocation targets would silently accept any total.
 */
class AllocationTargetsRequestTest {

    private static Validator validator;

    @BeforeAll
    static void setUp() {
        ValidatorFactory factory = Validation.buildDefaultValidatorFactory();
        validator = factory.getValidator();
    }

    private static AllocationTargetsRequest with(String realEstate, String equity,
                                                 String crypto, String alternative) {
        return new AllocationTargetsRequest(
            new BigDecimal("1850"), (short) 6,
            new BigDecimal(realEstate), new BigDecimal(equity),
            new BigDecimal(crypto), new BigDecimal(alternative));
    }

    @Test
    void acceptsTargetsSummingToOneHundred() {
        assertThat(validator.validate(with("30", "50", "10", "10"))).isEmpty();
    }

    @Test
    void rejectsTargetsThatDoNotSumToOneHundred() {
        Set<ConstraintViolation<AllocationTargetsRequest>> violations =
            validator.validate(with("30", "50", "10", "5"));

        assertThat(violations).hasSize(1);
        // The 422 body keys this under the derived property name, not under a component name.
        // The client maps its message off `summingToOneHundred`; renaming the method is a
        // breaking change for the form.
        assertThat(violations.iterator().next().getPropertyPath())
            .hasToString("summingToOneHundred");
    }

    @Test
    void toleratesDecimalTargets() {
        assertThat(validator.validate(with("32.5", "47.5", "10", "10"))).isEmpty();
    }

    @Test
    void monthlyExpensesMayBeNullSoTheSafetyNetStaysUnrated() {
        AllocationTargetsRequest request = new AllocationTargetsRequest(
            null, (short) 6, new BigDecimal("30"), new BigDecimal("50"),
            new BigDecimal("10"), new BigDecimal("10"));

        assertThat(validator.validate(request)).isEmpty();
    }

    @Test
    void rejectsAnImpossibleCushionLength() {
        AllocationTargetsRequest request = new AllocationTargetsRequest(
            new BigDecimal("1850"), (short) 36, new BigDecimal("30"), new BigDecimal("50"),
            new BigDecimal("10"), new BigDecimal("10"));

        assertThat(validator.validate(request)).hasSize(1);
    }

    @Test
    void doesNotPileTheSumMessageOnTopOfAMissingField() {
        // A half-filled form should say what is missing, not also complain about a total the
        // user has not had a chance to reach yet.
        AllocationTargetsRequest request = new AllocationTargetsRequest(
            new BigDecimal("1850"), (short) 6, null, new BigDecimal("50"),
            new BigDecimal("10"), new BigDecimal("10"));

        Set<ConstraintViolation<AllocationTargetsRequest>> violations = validator.validate(request);
        assertThat(violations).hasSize(1);
        assertThat(violations.iterator().next().getPropertyPath()).hasToString("realEstatePct");
    }
}

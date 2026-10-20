package com.picsou.dto;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

class ScpiPositionRequestTest {

    private static Validator validator;

    @BeforeAll
    static void setUp() {
        ValidatorFactory factory = Validation.buildDefaultValidatorFactory();
        validator = factory.getValidator();
    }

    private static Set<String> violations(BigDecimal annualDistribution) {
        ScpiPositionRequest request = new ScpiPositionRequest(
            null, null, null, null, new BigDecimal("10"), null, null, null, null,
            annualDistribution, null);
        return validator.validate(request).stream()
            .map(ConstraintViolation::getPropertyPath)
            .map(Object::toString)
            .collect(Collectors.toSet());
    }

    @Test
    void anAnnualDistributionThatFitsNumeric20_8IsAccepted() {
        assertThat(violations(new BigDecimal("12.5"))).isEmpty();
        assertThat(violations(new BigDecimal("999999999999.12345678"))).isEmpty();
        assertThat(violations(null)).isEmpty();
    }

    @Test
    void anAnnualDistributionBeyondTheColumnIsA400NotA500() {
        // 13 integer digits or 9 decimals would overflow NUMERIC(20, 8) at flush time.
        assertThat(violations(new BigDecimal("1000000000000"))).containsExactly("annualDistributionPerShareEur");
        assertThat(violations(new BigDecimal("1.123456789"))).containsExactly("annualDistributionPerShareEur");
    }
}

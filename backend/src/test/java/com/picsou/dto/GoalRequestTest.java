package com.picsou.dto;

import com.picsou.model.GoalType;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class GoalRequestTest {

    private static Validator validator;

    @BeforeAll
    static void setUp() {
        ValidatorFactory factory = Validation.buildDefaultValidatorFactory();
        validator = factory.getValidator();
    }

    private static Set<String> paths(GoalRequest request) {
        return validator.validate(request).stream()
            .map(ConstraintViolation::getPropertyPath)
            .map(Object::toString)
            .collect(java.util.stream.Collectors.toSet());
    }

    @Test
    void anOmittedTypeMeansASavingsTarget() {
        // The compatibility guarantee: every payload written before this field existed — the
        // frontend's and four MCP tools' — omits it and must keep meaning what it meant.
        GoalRequest req = new GoalRequest("Trip", null, new BigDecimal("5000"),
            LocalDate.now().plusYears(1), null, null, null, null, null, List.of(1L));

        assertThat(req.type()).isEqualTo(GoalType.SAVINGS_TARGET);
        // Same guarantee for the split, added later still: an omitted list reads as an empty
        // one, so nothing downstream has to null-check it.
        assertThat(req.allocations()).isEmpty();
        assertThat(validator.validate(req)).isEmpty();
    }

    @Test
    void aSavingsTargetNeedsBothATargetAndADeadline() {
        GoalRequest noTarget = GoalRequest.savingsTarget("Trip", null, LocalDate.now().plusYears(1), List.of(1L));
        GoalRequest noDeadline = GoalRequest.savingsTarget("Trip", new BigDecimal("5000"), null, List.of(1L));

        // Cross-field rules have no field to attach to, so the 422 keys them under the derived
        // property name. The form maps its message off that; renaming the method breaks it.
        assertThat(paths(noTarget)).contains("savingsTargetComplete");
        assertThat(paths(noDeadline)).contains("savingsTargetComplete");
    }

    @Test
    void aRecurringPlanNeedsAMonthlyAmount() {
        GoalRequest req = new GoalRequest("PEA", GoalType.RECURRING_INVESTMENT,
            null, null, null, null, null, null, null, List.of(1L));

        assertThat(paths(req)).contains("recurringComplete");
    }

    @Test
    void aRecurringPlanNeedsNeitherTargetNorDeadline() {
        GoalRequest req = GoalRequest.recurringInvestment(
            "PEA", new BigDecimal("300"), new BigDecimal("7.5"), null, null, 1L);

        assertThat(validator.validate(req)).isEmpty();
    }

    @Test
    void aRecurringPlanFundsExactlyOneAccount() {
        GoalRequest req = new GoalRequest("PEA", GoalType.RECURRING_INVESTMENT,
            null, null, new BigDecimal("300"), null, null, null, null, List.of(1L, 2L));

        assertThat(paths(req)).contains("recurringSingleAccount");
    }

    @Test
    void aSavingsTargetMaySpanSeveralAccounts() {
        GoalRequest req = GoalRequest.savingsTarget(
            "Trip", new BigDecimal("5000"), LocalDate.now().plusYears(1), List.of(1L, 2L, 3L));

        assertThat(validator.validate(req)).isEmpty();
    }

    @Test
    void theEndDateMustFollowTheStartDate() {
        GoalRequest req = GoalRequest.recurringInvestment("PEA", new BigDecimal("300"), null,
            LocalDate.now().plusYears(2), LocalDate.now().plusYears(1), 1L);

        assertThat(paths(req)).contains("dateRangeOrdered");
    }

    @Test
    void aPastDeadlineIsStillRefusedAtCreation() {
        // Dropping chk_goal_deadline removed the rule from the database, where it wrongly
        // blocked every later edit. @Future keeps it where it means what the user meant.
        GoalRequest req = GoalRequest.savingsTarget(
            "Trip", new BigDecimal("5000"), LocalDate.now().minusDays(1), List.of(1L));

        assertThat(paths(req)).contains("deadline");
    }

    @Test
    void aNameIsStillRequired() {
        GoalRequest req = GoalRequest.savingsTarget(
            "", new BigDecimal("5000"), LocalDate.now().plusYears(1), List.of(1L));

        assertThat(paths(req)).contains("name");
    }

    // ─── The monthly split ────────────────────────────────────────────────────

    private static GoalRequest plan(BigDecimal monthlyAmount, GoalAllocationRequest... lines) {
        return new GoalRequest("PEA", GoalType.RECURRING_INVESTMENT, null, null,
            monthlyAmount, null, null, null, List.of(lines), List.of(1L));
    }

    private static GoalAllocationRequest line(String ticker, String amount) {
        return new GoalAllocationRequest(ticker, new BigDecimal(amount));
    }

    @Test
    void aSplitMayCoverOnlyPartOfTheMonthlyAmount() {
        // The remainder is not an error: it reads as unallocated. Detailing half a plan has to
        // be as valid as detailing all of it, or nobody details anything.
        GoalRequest req = plan(new BigDecimal("400"), line("CW8", "150"));

        assertThat(validator.validate(req)).isEmpty();
    }

    @Test
    void aSplitCannotExceedTheMonthlyAmount() {
        GoalRequest req = plan(new BigDecimal("400"), line("CW8", "300"), line("ESE", "200"));

        assertThat(paths(req)).contains("allocationWithinMonthlyAmount");
    }

    @Test
    void aSplitMayMatchTheMonthlyAmountExactly() {
        GoalRequest req = plan(new BigDecimal("400"), line("CW8", "200"), line("ESE", "200"));

        assertThat(validator.validate(req)).isEmpty();
    }

    @Test
    void aPositionCanOnlyAppearOnceInTheSplit() {
        // Otherwise the duplicate reaches uk_goal_allocation_goal_ticker and is a 500.
        GoalRequest req = plan(new BigDecimal("400"), line("CW8", "100"), line("CW8", "100"));

        assertThat(paths(req)).contains("allocationTickersUnique");
    }

    @Test
    void aSavingsTargetCannotCarryASplit() {
        GoalRequest req = new GoalRequest("Trip", GoalType.SAVINGS_TARGET, new BigDecimal("5000"),
            LocalDate.now().plusYears(1), null, null, null, null,
            List.of(line("CW8", "100")), List.of(1L));

        assertThat(paths(req)).contains("allocationOnlyOnRecurring");
    }

    @Test
    void aMissingMonthlyAmountReportsItselfRatherThanTheSplit() {
        // On a half-filled form the useful message is "this plan needs a monthly amount", not a
        // complaint about a total the user has not typed yet.
        GoalRequest req = plan(null, line("CW8", "100"));

        assertThat(paths(req))
            .contains("recurringComplete")
            .doesNotContain("allocationWithinMonthlyAmount");
    }

    @Test
    void aSplitLineNeedsATickerAndAPositiveAmount() {
        GoalRequest req = plan(new BigDecimal("400"), line("", "100"),
            new GoalAllocationRequest("ESE", new BigDecimal("0")));

        assertThat(paths(req))
            .contains("allocations[0].ticker", "allocations[1].monthlyAmount")
            // A blank ticker is its own problem; it must not also read as a duplicate.
            .doesNotContain("allocationTickersUnique");
    }
}

package com.picsou.controller;

import com.picsou.dto.AccountResponse;
import com.picsou.dto.SavingsConfigDto;
import com.picsou.dto.SavingsInterestProjection;
import com.picsou.dto.SavingsSuggestionResponse;
import com.picsou.exception.ResourceNotFoundException;
import com.picsou.model.AccountType;
import com.picsou.model.RateBasis;
import com.picsou.model.SavingsProduct;
import com.picsou.service.SavingsService;
import com.picsou.service.UserContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link SavingsController}.
 *
 * <p>Uses Mockito only (no Spring context).  Business logic lives in
 * {@link SavingsService} — the controller is a thin delegation layer, so these
 * tests verify delegation, member-scoping propagation, and exception pass-through.</p>
 */
@ExtendWith(MockitoExtension.class)
class SavingsControllerTest {

    @Mock SavingsService savingsService;
    @Mock UserContext userContext;

    SavingsController controller;

    private static final Long MEMBER_ID = 1L;
    private static final Long ACCOUNT_ID = 42L;
    private static final Long OTHER_ACCOUNT_ID = 99L;

    @BeforeEach
    void setUp() {
        controller = new SavingsController(savingsService, userContext);
        when(userContext.currentMemberId()).thenReturn(MEMBER_ID);
    }

    // ─── GET /api/savings/suggestions ────────────────────────────────────────

    @Test
    void suggestions_returnsSuggestionsFromService() {
        SavingsSuggestionResponse suggestion = new SavingsSuggestionResponse(
            ACCOUNT_ID, "Livret A BNP", SavingsProduct.LIVRET_A, new BigDecimal("2.40"), false
        );
        when(savingsService.getSuggestions(MEMBER_ID)).thenReturn(List.of(suggestion));

        List<SavingsSuggestionResponse> result = controller.suggestions();

        assertThat(result).hasSize(1);
        assertThat(result.get(0).accountId()).isEqualTo(ACCOUNT_ID);
        assertThat(result.get(0).suggestedProduct()).isEqualTo(SavingsProduct.LIVRET_A);
        assertThat(result.get(0).defaultAnnualRate()).isEqualByComparingTo("2.40");
        assertThat(result.get(0).uncertain()).isFalse();
    }

    @Test
    void suggestions_returnsEmptyList_whenNoEligibleAccounts() {
        when(savingsService.getSuggestions(MEMBER_ID)).thenReturn(List.of());

        List<SavingsSuggestionResponse> result = controller.suggestions();

        assertThat(result).isEmpty();
    }

    @Test
    void suggestions_onlyIncludesSyncedAndUnconfiguredAccounts() {
        // The service filters — the controller just delegates. Verify member id is passed.
        when(savingsService.getSuggestions(MEMBER_ID)).thenReturn(List.of());

        controller.suggestions();

        verify(savingsService).getSuggestions(MEMBER_ID);
        verifyNoMoreInteractions(savingsService);
    }

    // ─── PUT /api/accounts/{id}/savings-config ────────────────────────────────

    @Test
    void upsertSavingsConfig_delegatesToServiceAndReturnsAccountResponse() {
        SavingsConfigDto req = new SavingsConfigDto(
            SavingsProduct.LIVRET_A, new BigDecimal("2.40"), RateBasis.NET, null, new BigDecimal("22950")
        );
        AccountResponse expected = accountResponseWithSavings(ACCOUNT_ID, req);
        when(savingsService.upsertConfig(ACCOUNT_ID, MEMBER_ID, req)).thenReturn(expected);

        AccountResponse result = controller.upsertSavingsConfig(ACCOUNT_ID, req);

        assertThat(result).isSameAs(expected);
        assertThat(result.savingsConfig()).isNotNull();
        assertThat(result.savingsConfig().product()).isEqualTo(SavingsProduct.LIVRET_A);
        verify(savingsService).upsertConfig(ACCOUNT_ID, MEMBER_ID, req);
    }

    @Test
    void upsertSavingsConfig_upsert_reusesSameRowOnSecondCall() {
        // First PUT — creates config
        SavingsConfigDto firstReq = new SavingsConfigDto(
            SavingsProduct.LIVRET_A, new BigDecimal("2.40"), RateBasis.NET, null, null
        );
        AccountResponse afterCreate = accountResponseWithSavings(ACCOUNT_ID, firstReq);
        when(savingsService.upsertConfig(ACCOUNT_ID, MEMBER_ID, firstReq)).thenReturn(afterCreate);

        AccountResponse r1 = controller.upsertSavingsConfig(ACCOUNT_ID, firstReq);
        assertThat(r1.savingsConfig().annualRate()).isEqualByComparingTo("2.40");

        // Second PUT — updates config (different rate)
        SavingsConfigDto secondReq = new SavingsConfigDto(
            SavingsProduct.LIVRET_A, new BigDecimal("3.00"), RateBasis.NET, null, null
        );
        AccountResponse afterUpdate = accountResponseWithSavings(ACCOUNT_ID, secondReq);
        when(savingsService.upsertConfig(ACCOUNT_ID, MEMBER_ID, secondReq)).thenReturn(afterUpdate);

        AccountResponse r2 = controller.upsertSavingsConfig(ACCOUNT_ID, secondReq);
        assertThat(r2.savingsConfig().annualRate()).isEqualByComparingTo("3.00");

        // Service was called twice — row reuse is the service's responsibility
        verify(savingsService).upsertConfig(ACCOUNT_ID, MEMBER_ID, firstReq);
        verify(savingsService).upsertConfig(ACCOUNT_ID, MEMBER_ID, secondReq);
    }

    @Test
    void upsertSavingsConfig_regulatedWithGross_propagates400() {
        SavingsConfigDto invalidReq = new SavingsConfigDto(
            SavingsProduct.LIVRET_A, new BigDecimal("2.40"), RateBasis.GROSS, null, null
        );
        when(savingsService.upsertConfig(ACCOUNT_ID, MEMBER_ID, invalidReq))
            .thenThrow(new IllegalArgumentException(
                "Regulated savings products (LIVRET_A) must use a NET rate basis."));

        // Controller does not swallow — GlobalExceptionHandler maps this to HTTP 400
        assertThatThrownBy(() -> controller.upsertSavingsConfig(ACCOUNT_ID, invalidReq))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("NET rate basis");
    }

    @Test
    void upsertSavingsConfig_memberScoping_propagates404ForForeignAccount() {
        SavingsConfigDto req = new SavingsConfigDto(
            SavingsProduct.COMMERCIAL, new BigDecimal("4.00"), RateBasis.NET, null, null
        );
        when(savingsService.upsertConfig(OTHER_ACCOUNT_ID, MEMBER_ID, req))
            .thenThrow(ResourceNotFoundException.account(OTHER_ACCOUNT_ID));

        assertThatThrownBy(() -> controller.upsertSavingsConfig(OTHER_ACCOUNT_ID, req))
            .isInstanceOf(ResourceNotFoundException.class);
    }

    // ─── DELETE /api/accounts/{id}/savings-config ─────────────────────────────

    @Test
    void deleteSavingsConfig_delegatesToService() {
        controller.deleteSavingsConfig(ACCOUNT_ID);

        verify(savingsService).deleteConfig(ACCOUNT_ID, MEMBER_ID);
        verifyNoMoreInteractions(savingsService);
    }

    @Test
    void deleteSavingsConfig_memberScoping_propagates404ForForeignAccount() {
        doThrow(ResourceNotFoundException.account(OTHER_ACCOUNT_ID))
            .when(savingsService).deleteConfig(OTHER_ACCOUNT_ID, MEMBER_ID);

        assertThatThrownBy(() -> controller.deleteSavingsConfig(OTHER_ACCOUNT_ID))
            .isInstanceOf(ResourceNotFoundException.class);
    }

    // ─── GET /api/accounts/{id}/savings-interest ──────────────────────────────

    @Test
    void getSavingsInterest_returnsProjection() {
        SavingsInterestProjection projection = new SavingsInterestProjection(
            new BigDecimal("120.00"),
            new BigDecimal("240.00"),
            LocalDate.of(2026, 12, 31),
            new BigDecimal("2.40"),
            RateBasis.NET,
            true
        );
        when(savingsService.getProjection(ACCOUNT_ID, MEMBER_ID)).thenReturn(projection);

        SavingsInterestProjection result = controller.getSavingsInterest(ACCOUNT_ID);

        assertThat(result).isSameAs(projection);
        assertThat(result.estimatedInterestYtd()).isEqualByComparingTo("120.00");
        assertThat(result.projectedInterestFullYear()).isEqualByComparingTo("240.00");
        assertThat(result.basis()).isEqualTo(RateBasis.NET);
        assertThat(result.netOfTax()).isTrue();
    }

    @Test
    void getSavingsInterest_noConfig_propagates404() {
        when(savingsService.getProjection(ACCOUNT_ID, MEMBER_ID))
            .thenThrow(new ResourceNotFoundException("No savings config for account " + ACCOUNT_ID));

        assertThatThrownBy(() -> controller.getSavingsInterest(ACCOUNT_ID))
            .isInstanceOf(ResourceNotFoundException.class)
            .hasMessageContaining("savings config");
    }

    @Test
    void getSavingsInterest_memberScoping_propagates404ForForeignAccount() {
        when(savingsService.getProjection(OTHER_ACCOUNT_ID, MEMBER_ID))
            .thenThrow(ResourceNotFoundException.account(OTHER_ACCOUNT_ID));

        assertThatThrownBy(() -> controller.getSavingsInterest(OTHER_ACCOUNT_ID))
            .isInstanceOf(ResourceNotFoundException.class);
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────

    private static AccountResponse accountResponseWithSavings(Long accountId, SavingsConfigDto savings) {
        return new AccountResponse(
            accountId,
            "Test Account",
            AccountType.SAVINGS,
            "BNP",
            "EUR",
            new BigDecimal("10000"),
            new BigDecimal("10000"),
            null,
            null,
            false,
            "#6366f1",
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            savings,
            null,
            false,
            null,
            null
        );
    }
}

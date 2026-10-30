package com.picsou.controller;

import com.picsou.dto.AccountResponse;
import com.picsou.dto.SavingsConfigDto;
import com.picsou.dto.SavingsInterestProjection;
import com.picsou.dto.SavingsSuggestionResponse;
import com.picsou.service.SavingsService;
import com.picsou.service.UserContext;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * REST endpoints for the savings-livret feature.
 *
 * <p>Endpoints:</p>
 * <ul>
 *   <li>{@code GET  /api/savings/suggestions} — suggest livret configs for unconfigured synced accounts</li>
 *   <li>{@code PUT  /api/accounts/{id}/savings-config} — create or update savings config</li>
 *   <li>{@code DELETE /api/accounts/{id}/savings-config} — remove savings config</li>
 *   <li>{@code GET  /api/accounts/{id}/savings-interest} — compute interest projection</li>
 * </ul>
 *
 * <p>All endpoints are member-scoped: the current member is resolved via {@link UserContext}.
 * Cross-member access results in a 404 (same behaviour as the rest of the API surface).</p>
 */
@RestController
public class SavingsController {

    private final SavingsService savingsService;
    private final UserContext userContext;

    public SavingsController(SavingsService savingsService, UserContext userContext) {
        this.savingsService = savingsService;
        this.userContext = userContext;
    }

    /**
     * Returns savings-book suggestions for bank-synced accounts that have no config yet.
     * <p>
     * {@code GET /api/savings/suggestions}
     */
    @GetMapping("/api/savings/suggestions")
    public List<SavingsSuggestionResponse> suggestions() {
        return savingsService.getSuggestions(userContext.currentMemberId());
    }

    /**
     * Creates or updates the savings-interest config for an account.
     * <p>
     * Validation errors (e.g. regulated product with GROSS rate) surface as HTTP 400.
     * <p>
     * {@code PUT /api/accounts/{id}/savings-config}
     */
    @PutMapping("/api/accounts/{id}/savings-config")
    public AccountResponse upsertSavingsConfig(
        @PathVariable Long id,
        @RequestBody SavingsConfigDto req
    ) {
        return savingsService.upsertConfig(id, userContext.currentMemberId(), req);
    }

    /**
     * Removes the savings-interest config for an account (idempotent).
     * <p>
     * {@code DELETE /api/accounts/{id}/savings-config}
     */
    @DeleteMapping("/api/accounts/{id}/savings-config")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteSavingsConfig(@PathVariable Long id) {
        savingsService.deleteConfig(id, userContext.currentMemberId());
    }

    /**
     * Returns the year-to-date and full-year interest projection for an account.
     * <p>
     * Returns 404 if the account has no savings config.
     * <p>
     * {@code GET /api/accounts/{id}/savings-interest}
     */
    @GetMapping("/api/accounts/{id}/savings-interest")
    public SavingsInterestProjection getSavingsInterest(@PathVariable Long id) {
        return savingsService.getProjection(id, userContext.currentMemberId());
    }
}

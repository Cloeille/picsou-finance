package com.picsou.controller;

import com.picsou.dto.UpcomingDividendResponse;
import com.picsou.service.DividendForecastService;
import com.picsou.service.UserContext;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;

/**
 * Projected dividends and SCPI distributions. Read-only: detection of the cadence happened
 * earlier, in the weekly job, and this endpoint only reads what that job stored.
 */
@RestController
@RequestMapping("/api/dividends")
public class DividendController {

    private final DividendForecastService forecastService;
    private final UserContext userContext;

    public DividendController(DividendForecastService forecastService, UserContext userContext) {
        this.forecastService = forecastService;
        this.userContext = userContext;
    }

    /**
     * Payments from today through the next {@code horizonDays} days (default 60, clamped to 1..366).
     * {@code accountId}, when set, keeps that account only. An id the member cannot read is the
     * usual 404 from {@code AccountAccessResolver.requireReadable}, not an empty list.
     */
    @GetMapping("/upcoming")
    public List<UpcomingDividendResponse> upcoming(
        @RequestParam(defaultValue = "60") int horizonDays,
        @RequestParam(required = false) Long accountId
    ) {
        return forecastService.upcoming(
            userContext.currentMemberId(),
            LocalDate.now(),
            DividendForecastService.clampHorizon(horizonDays),
            accountId);
    }
}

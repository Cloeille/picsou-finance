package com.picsou.controller;

import com.picsou.dto.CashflowFlowResponse;
import com.picsou.dto.CashflowPeriod;
import com.picsou.dto.CashflowResponse;
import com.picsou.service.UserContext;
import com.picsou.service.budget.CashflowFlowService;
import com.picsou.service.budget.CashflowService;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

/** Cashflow view: income / expense / net for the current cycle or year-to-date. */
@RestController
@RequestMapping("/api/cashflow")
public class CashflowController {

    private final CashflowService cashflowService;
    private final CashflowFlowService cashflowFlowService;
    private final UserContext userContext;

    public CashflowController(
        CashflowService cashflowService,
        CashflowFlowService cashflowFlowService,
        UserContext userContext
    ) {
        this.cashflowService = cashflowService;
        this.cashflowFlowService = cashflowFlowService;
        this.userContext = userContext;
    }

    @GetMapping
    public CashflowResponse cashflow(
        @RequestParam(defaultValue = "CYCLE") CashflowPeriod period,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate anchor
    ) {
        return cashflowService.compute(userContext.currentMemberId(), period,
            anchor != null ? anchor : LocalDate.now());
    }

    /** Income → budget → expenses money-flow graph for the Sankey diagram. */
    @GetMapping("/flow")
    public CashflowFlowResponse flow(
        @RequestParam(defaultValue = "CYCLE") CashflowPeriod period,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate anchor
    ) {
        return cashflowFlowService.flow(userContext.currentMemberId(), period,
            anchor != null ? anchor : LocalDate.now());
    }
}

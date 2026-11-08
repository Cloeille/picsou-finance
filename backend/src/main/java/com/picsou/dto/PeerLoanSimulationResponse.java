package com.picsou.dto;

import com.picsou.service.PeerLoanCalculator;

import java.math.BigDecimal;

public record PeerLoanSimulationResponse(
    BigDecimal monthlyPayment,
    int months,
    BigDecimal totalInterest,
    BigDecimal totalCost
) {
    public static PeerLoanSimulationResponse from(PeerLoanCalculator.Simulation simulation) {
        return new PeerLoanSimulationResponse(
            simulation.monthlyPayment(),
            simulation.months(),
            simulation.totalInterest(),
            simulation.totalCost()
        );
    }
}

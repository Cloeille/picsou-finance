package com.picsou.dto;

import com.picsou.model.TransactionType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.time.LocalDate;

public record TransactionRequest(
    @NotNull LocalDate date,
    @NotBlank String description,
    @NotNull BigDecimal amount,
    TransactionType txType,
    String ticker,
    String name,
    BigDecimal quantity,
    BigDecimal pricePerUnit,
    String currency,
    Long categoryId,
    BigDecimal fees
) {
    /** Backwards-compatible constructor for callers that specify a category but no per-trade fees. */
    public TransactionRequest(
        LocalDate date, String description, BigDecimal amount, TransactionType txType,
        String ticker, String name, BigDecimal quantity, BigDecimal pricePerUnit, String currency,
        Long categoryId) {
        this(date, description, amount, txType, ticker, name, quantity, pricePerUnit, currency, categoryId, null);
    }

    /** Backwards-compatible constructor for callers that specify neither category nor fees. */
    public TransactionRequest(
        LocalDate date, String description, BigDecimal amount, TransactionType txType,
        String ticker, String name, BigDecimal quantity, BigDecimal pricePerUnit, String currency) {
        this(date, description, amount, txType, ticker, name, quantity, pricePerUnit, currency, null, null);
    }
}

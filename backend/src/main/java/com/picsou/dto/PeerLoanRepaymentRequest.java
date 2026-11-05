package com.picsou.dto;

import com.picsou.model.PeerLoanRepaymentKind;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.LocalDate;

public record PeerLoanRepaymentRequest(
    @NotNull LocalDate paidOn,
    @NotNull @DecimalMin(value = "0.01", message = "Amount must be greater than zero")
    BigDecimal amount,
    @NotNull PeerLoanRepaymentKind kind,
    @Size(max = 255) String note
) {}

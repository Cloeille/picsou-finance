package com.picsou.port;

import java.math.BigDecimal;
import java.time.LocalDate;

/** A bank-sidecar transaction in the common shape used by cash-account importers. */
public record SidecarTransaction(
    String externalId,
    LocalDate date,
    String description,
    BigDecimal amount,
    String counterparty,
    String kind
) {}

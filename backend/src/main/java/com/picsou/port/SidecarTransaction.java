package com.picsou.port;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * A bank-sidecar transaction in the common shape used by cash-account importers.
 *
 * <p>{@code currency} is the leg's own currency when the sidecar reports one; null means the
 * account's. A Revolut exchange leg can be in another currency than the pocket it is listed on.
 */
public record SidecarTransaction(
    String externalId,
    LocalDate date,
    String description,
    BigDecimal amount,
    String counterparty,
    String kind,
    String currency
) {
    public SidecarTransaction(String externalId, LocalDate date, String description, BigDecimal amount,
                              String counterparty, String kind) {
        this(externalId, date, description, amount, counterparty, kind, null);
    }
}

package com.picsou.dto;

import java.math.BigDecimal;

/**
 * Preview of a Revolut wallet/pocket/vault harvested during discovery, presented to the member
 * for selection before {@code POST /api/revolut/sync/confirm} persists the chosen subset.
 *
 * @param externalId       stable id from the sidecar (see {@code RevolutPort.RevolutAccountData})
 * @param name             display name (e.g. "Revolut EUR", "Pocket ••abc123")
 * @param type             {@code "CHECKING"} or {@code "SAVINGS"}
 * @param currency         ISO currency code
 * @param balance          harvested balance in native currency
 * @param parentExternalId non-null for pocket sub-accounts: the external id of their parent wallet
 * @param alreadyImported  true if this account already has an active (non soft-deleted) row for
 *                         this member -- seeds the selection checkbox as checked
 * @param transactionCount number of transactions harvested for this account
 */
public record DiscoveredRevolutAccount(
    String externalId,
    String name,
    String type,
    String currency,
    BigDecimal balance,
    String parentExternalId,
    boolean alreadyImported,
    int transactionCount
) {}

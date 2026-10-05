package com.picsou.port;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * Port for bank account synchronization providers.
 * Implement this interface to add a new bank connector (e.g. Plaid, Powens).
 */
public interface BankConnectorPort {

    /** Fallback country when none is specified — Picsou's primary market. */
    String DEFAULT_COUNTRY = "FR";

    /**
     * Create an authorization link to connect a bank account.
     *
     * @param state OAuth {@code state} nonce the provider must echo back on the
     *              redirect; the caller persists it to correlate the callback
     *              with its requisition.
     */
    InitiateResult initiateConnection(String institutionId, String state);

    /** Exchange the OAuth code from the callback for a session ID. */
    String exchangeCode(String oauthCode);

    /** Fetch balances for all accounts linked to this session. */
    List<AccountData> fetchBalances(String sessionId);

    /**
     * Fetch the transactions booked on one account since {@code dateFrom} (inclusive).
     *
     * <p>Default: an empty list. Balance-only providers keep working untouched —
     * {@code SyncService} treats "no transactions" and "cannot serve transactions"
     * the same way, so a provider only overrides this once it can actually answer.
     *
     * @param sessionId         the provider session the account was linked through
     * @param externalAccountId the provider's own account id, as carried on
     *                          {@link AccountData#externalId()}
     */
    default List<TransactionData> fetchTransactions(String sessionId, String externalAccountId, LocalDate dateFrom) {
        return List.of();
    }

    /** Search institutions by name/country. */
    List<InstitutionData> searchInstitutions(String query, String country);

    /** Distinct country codes this provider has institutions for, for a "which country" selector. */
    List<String> listCountries();

    /**
     * Splits an institution id built by an adapter's {@code searchInstitutions} into its
     * name and country parts. Two shapes exist (see {@code docs/features/bank-sync.md}):
     * the current {@code "BankName::CC::psuType"} and the legacy {@code "BankName::CC"}
     * written before PSU types were modelled. The country is therefore the SECOND segment
     * in both — not the last one, which would resolve to {@code "business"}/{@code
     * "personal"} for every current id — so this splits at the FIRST "::" for the name and
     * takes what follows up to the next "::" as the country.
     *
     * <p>{@code country()} is an empty string when absent/blank; callers decide their own
     * fallback (e.g. {@link #DEFAULT_COUNTRY} where a concrete country is required, or
     * treating blank as "unknown" for a broad, unfiltered institution search). A {@code null}
     * input returns an empty name and country rather than throwing — request-body validation
     * (see {@code SyncController.InitiateRequest}) is the primary guard against a missing id,
     * but this parser stays safe on its own regardless of caller diligence.
     */
    static ParsedInstitutionId parseInstitutionId(String institutionId) {
        if (institutionId == null) return new ParsedInstitutionId("", "");
        int sep = institutionId.indexOf("::");
        if (sep < 0) return new ParsedInstitutionId(institutionId, "");
        String name = institutionId.substring(0, sep);
        String rest = institutionId.substring(sep + 2);
        int next = rest.indexOf("::");
        String country = next >= 0 ? rest.substring(0, next) : rest;
        return new ParsedInstitutionId(name, country);
    }

    record ParsedInstitutionId(String name, String country) {}

    record InitiateResult(String requisitionId, String authLink) {}

    /**
     * One booked account entry, normalized across providers.
     *
     * @param externalId  the provider's stable id for this entry, used to avoid re-importing
     *                    it on the next sync. {@code null} when the provider exposes none —
     *                    the caller then falls back to a content fingerprint.
     * @param amount      signed from the account holder's point of view: negative = money out.
     * @param description free-text label; never blank (adapters substitute a fallback).
     * @param category    provider-side categorization, if any. {@code null} otherwise.
     */
    record TransactionData(
        String externalId,
        LocalDate date,
        String description,
        BigDecimal amount,
        String currency,
        String category
    ) {}

    record AccountData(
        String externalId,
        String name,
        String iban,
        String currency,
        BigDecimal balance
    ) {}

    /**
     * @param id       opaque round-trip token; the Enable Banking adapter encodes
     *                 {@code name::country::psuType} in it (see the adapter's
     *                 {@code parseInstitutionId}). Clients must pass it back verbatim.
     * @param psuType  {@code "personal"} or {@code "business"} — which login the
     *                 provider will present. Banks serving only professionals
     *                 (Swan, Qonto…) are published under {@code business} only.
     */
    record InstitutionData(
        String id,
        String name,
        String bic,
        String logoUrl,
        String country,
        String psuType
    ) {}
}

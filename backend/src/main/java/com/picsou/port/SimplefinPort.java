package com.picsou.port;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * SimpleFIN protocol: claim a one-time setup token, then read accounts.
 *
 * <p>The access URL returned by {@link #claim} embeds HTTP Basic credentials.
 * Callers store it encrypted and pass the plaintext back to {@link #fetchAccounts}.
 * Pending transactions are omitted; their ids change once they post.
 */
public interface SimplefinPort {

    /** Longest setup token accepted, checked by the request DTO and again by the adapter. */
    int MAX_SETUP_TOKEN_CHARS = 4096;

    /** Exchange a setup token for an access URL. The token cannot be claimed twice. */
    String claim(String setupToken);

    /**
     * Accounts and posted transactions on or after {@code startDate} (inclusive).
     * {@code errors} may be non-empty alongside accounts when the server reported
     * a partial failure.
     */
    SimplefinAccountSet fetchAccounts(String accessUrl, LocalDate startDate);

    record SimplefinAccountSet(List<String> errors, List<SimplefinAccount> accounts) {}

    record SimplefinAccount(
        String externalId,
        String connectionName,
        String name,
        String currency,
        BigDecimal balance,
        List<SimplefinTransaction> transactions
    ) {}

    record SimplefinTransaction(
        String externalId,
        LocalDate date,
        BigDecimal amount,
        String description
    ) {}
}

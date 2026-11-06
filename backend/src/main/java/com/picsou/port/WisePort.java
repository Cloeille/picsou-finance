package com.picsou.port;

import java.math.BigDecimal;
import java.util.List;

/**
 * Wise jars read with a member's personal API token.
 *
 * <p>Only {@code SAVINGS} balances (jars) are read. Wise current balances and their
 * transactions already arrive through Enable Banking, and Wise refuses balance statements
 * to personal tokens in the EEA and the UK, so jars come in as balances only.
 */
public interface WisePort {

    /** Longest token accepted, checked by the request DTO and again by the adapter. */
    int MAX_TOKEN_CHARS = 512;

    /** Every jar on every profile the token can read. */
    List<WiseJar> fetchJars(String apiToken);

    /**
     * @param balanceId  Wise balance id, unique across profiles
     * @param business   whether the jar sits on a business profile
     * @param totalWorth cash plus any invested part, in {@code currency}
     */
    record WiseJar(long balanceId, boolean business, String name, String currency, BigDecimal totalWorth) {}
}

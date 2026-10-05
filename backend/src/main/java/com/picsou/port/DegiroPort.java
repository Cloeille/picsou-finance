package com.picsou.port;

import java.math.BigDecimal;
import java.util.List;

/**
 * DEGIRO is a compte-titres-only broker (no PEA envelope in France) accessed
 * through an unofficial, reverse-engineered API. Unlike the other broker ports,
 * there is no long-lived session: DEGIRO's cookie times out after ~30 minutes of
 * inactivity and there is no refresh token, so {@link #fetchPortfolio} can throw
 * a {@link com.picsou.exception.DegiroSessionExpiredException} at any time —
 * callers must surface this as a re-authentication prompt, never retry silently.
 * See docs/decisions/2026-08-05-degiro-session-only-no-stored-totp.md.
 */
public interface DegiroPort {

    /**
     * Step 1: Authenticates with DEGIRO (username + password).
     *
     * @return result indicating whether TOTP is required; if not, sessionBlob is populated
     */
    InitiateResult initiateAuth(String username, String password);

    /**
     * Step 2 (TOTP only): Completes login with the 6-digit authenticator code.
     *
     * @param processId returned by {@link #initiateAuth}
     * @param code      current TOTP code from the user's authenticator app
     * @return opaque serialized {sessionId, intAccount} blob to store in DB
     */
    String completeAuth(String processId, String code);

    /**
     * Fetches the current portfolio valuation and open positions.
     *
     * @param sessionBlob opaque blob returned by the auth flow
     * @throws com.picsou.exception.DegiroSessionExpiredException when DEGIRO rejects
     *         the session (expired) — never interpreted as an empty portfolio.
     */
    DegiroPortfolioData fetchPortfolio(String sessionBlob);

    record InitiateResult(
        String processId,
        boolean totpRequired,
        String sessionBlob   // populated only when totpRequired == false
    ) {}

    record DegiroPosition(
        String isin,
        String symbol,
        String name,
        BigDecimal quantity,
        BigDecimal buyingPrice,
        BigDecimal currentPrice
    ) {}

    record DegiroPortfolioData(
        BigDecimal cashEur,
        List<DegiroPosition> positions
    ) {}
}

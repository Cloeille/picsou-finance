package com.picsou.exception;

/**
 * A valuation source could not be reached or did not answer usably.
 *
 * <p>Distinct from a provider returning no data. "This commune has no comparable sales" is a
 * fact about the market; "the request failed" is a fact about the infrastructure, and
 * collapsing the two is actively misleading — a 265 KB response exceeding the client's buffer
 * limit once surfaced to users as "no comparable transactions in this municipality", which
 * sent them looking at their address instead of at the logs.
 *
 * <p>Callers translate this into {@code PROVIDER_UNAVAILABLE} and keep the previous valuation.
 */
public class ValuationProviderException extends RuntimeException {

    public ValuationProviderException(String message, Throwable cause) {
        super(message, cause);
    }
}

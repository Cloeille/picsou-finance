package com.picsou.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2ErrorCodes;
import org.springframework.security.oauth2.core.OAuth2RefreshToken;
import org.springframework.security.oauth2.server.authorization.OAuth2Authorization;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationService;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2RefreshTokenAuthenticationToken;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Optional;

/**
 * Refresh-token reuse detection for the native app (OAuth 2.1, RFC 9700 §4.14.2). Rotation alone
 * leaves whoever redeems a stolen token first holding the live chain; here, presenting a
 * {@code picsou-ios} refresh token that was already rotated away deletes its whole authorization,
 * so the current refresh token and access token die with it, whichever side holds them.
 *
 * <p>Runs ahead of the framework's {@code OAuth2RefreshTokenAuthenticationProvider} and defers
 * ({@code null}) for anything that is not a rotated-away native-app token, including the current
 * token, so the normal refresh path is unchanged. A detected reuse answers {@code invalid_grant}.
 */
final class RefreshTokenReuseDetector implements AuthenticationProvider {

    private static final Logger log = LoggerFactory.getLogger(RefreshTokenReuseDetector.class);

    private final OAuth2AuthorizationService authorizationService;
    private final NativeAppRefreshTokens nativeAppRefreshTokens;

    RefreshTokenReuseDetector(OAuth2AuthorizationService authorizationService, NativeAppRefreshTokens nativeAppRefreshTokens) {
        this.authorizationService = authorizationService;
        this.nativeAppRefreshTokens = nativeAppRefreshTokens;
    }

    @Override
    public Authentication authenticate(Authentication authentication) {
        String presented = ((OAuth2RefreshTokenAuthenticationToken) authentication).getRefreshToken();
        Optional<String> authorizationId = nativeAppRefreshTokens.issuedFor(presented);
        if (authorizationId.isEmpty()) {
            return null;
        }
        OAuth2Authorization authorization = authorizationService.findById(authorizationId.get());
        if (authorization == null || isCurrent(authorization, presented)) {
            return null;
        }
        authorizationService.remove(authorization);
        log.warn("Rotated-away refresh token presented for authorization {}: authorization revoked", authorization.getId());
        throw new OAuth2AuthenticationException(OAuth2ErrorCodes.INVALID_GRANT);
    }

    @Override
    public boolean supports(Class<?> authentication) {
        return OAuth2RefreshTokenAuthenticationToken.class.isAssignableFrom(authentication);
    }

    private static boolean isCurrent(OAuth2Authorization authorization, String presented) {
        OAuth2Authorization.Token<OAuth2RefreshToken> current = authorization.getRefreshToken();
        return current != null && MessageDigest.isEqual(
            current.getToken().getTokenValue().getBytes(StandardCharsets.US_ASCII),
            presented.getBytes(StandardCharsets.US_ASCII));
    }
}

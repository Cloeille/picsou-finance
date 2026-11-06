package com.picsou.service;

import com.picsou.config.AuthCookieWriter;
import com.picsou.config.JwtUtil;
import com.picsou.dto.ReAuthDto;
import com.picsou.model.AppUser;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

@Service
public class ReAuthService {

    public static class ReAuthFailedException extends RuntimeException {
        public ReAuthFailedException(String message) {
            super(message);
        }
    }

    private final MfaService mfaService;
    private final PasswordEncoder passwordEncoder;
    private final JwtUtil jwtUtil;

    public ReAuthService(MfaService mfaService, PasswordEncoder passwordEncoder, JwtUtil jwtUtil) {
        this.mfaService = mfaService;
        this.passwordEncoder = passwordEncoder;
        this.jwtUtil = jwtUtil;
    }

    /**
     * Whether the request carries a still-valid {@code reauth_proof} cookie minted for
     * this exact account: signature and expiry valid, {@code type=reauth}, same {@code uid}
     * and same {@code tv} (so a password change or logout-everywhere invalidates it).
     */
    public boolean hasValidProof(AppUser user, HttpServletRequest request) {
        String token = extractCookie(request, AuthCookieWriter.REAUTH_PROOF_COOKIE);
        if (token == null || token.isBlank()) {
            return false;
        }
        Claims claims;
        try {
            claims = jwtUtil.validateAndParse(token);
        } catch (JwtException ex) {
            return false;
        }
        Long uid = jwtUtil.getUserId(claims);
        Long tv = jwtUtil.getTokenVersion(claims);
        return jwtUtil.isReauthProof(claims)
            && uid != null && uid.equals(user.getId())
            && tv != null && tv == user.getTokenVersion();
    }

    public void requireProof(AppUser user, HttpServletRequest request) {
        if (!hasValidProof(user, request)) {
            throw new ReAuthFailedException("re-auth proof missing or invalid");
        }
    }

    public void verify(AppUser user, ReAuthDto reAuth, HttpServletRequest request) {
        if (hasValidProof(user, request)) {
            return;
        }
        if (reAuth == null) {
            throw new ReAuthFailedException("re-auth payload missing");
        }
        if (mfaService.isEnabled(user)) {
            if (reAuth.totpCode() == null || reAuth.totpCode().isBlank()) {
                throw new ReAuthFailedException("totp required");
            }
            if (!mfaService.verifyTotp(user, reAuth.totpCode())) {
                throw new ReAuthFailedException("invalid totp");
            }
        } else {
            if (reAuth.password() == null || reAuth.password().isBlank()) {
                throw new ReAuthFailedException("password required");
            }
            if (!passwordEncoder.matches(reAuth.password(), user.getPasswordHash())) {
                throw new ReAuthFailedException("invalid password");
            }
        }
    }

    /**
     * Mints the credential for {@code POST /api/auth/reauth}: the password must be present
     * and match a non-blank hash, and when 2FA is enabled a valid TOTP is required in
     * addition — never just one of the two factors.
     */
    public void verifyPasswordAndTotp(AppUser user, ReAuthDto reAuth) {
        String password = reAuth == null ? null : reAuth.password();
        String hash = user.getPasswordHash();
        if (password == null || password.isBlank()) {
            throw new ReAuthFailedException("password required");
        }
        if (hash == null || hash.isBlank() || !passwordEncoder.matches(password, hash)) {
            throw new ReAuthFailedException("invalid password");
        }
        if (mfaService.isEnabled(user)) {
            if (reAuth.totpCode() == null || reAuth.totpCode().isBlank()) {
                throw new ReAuthFailedException("totp required");
            }
            if (!mfaService.verifyTotp(user, reAuth.totpCode())) {
                throw new ReAuthFailedException("invalid totp");
            }
        }
    }

    private static String extractCookie(HttpServletRequest request, String name) {
        if (request.getCookies() == null) return null;
        for (Cookie cookie : request.getCookies()) {
            if (name.equals(cookie.getName())) return cookie.getValue();
        }
        return null;
    }
}

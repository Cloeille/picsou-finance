package com.picsou.service;

import com.picsou.config.AuthCookieWriter;
import com.picsou.config.JwtUtil;
import com.picsou.dto.ReAuthDto;
import com.picsou.model.AppUser;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ReAuthServiceTest {

    private static final String SECRET = "0123456789abcdef0123456789abcdef-test";

    @Mock private MfaService mfaService;
    @Mock private PasswordEncoder passwordEncoder;
    private JwtUtil jwtUtil;
    private ReAuthService service;
    private AppUser user;

    @BeforeEach
    void setUp() {
        jwtUtil = new JwtUtil(SECRET, 15, 7, 5);
        service = new ReAuthService(mfaService, passwordEncoder, jwtUtil);
        user = new AppUser();
        user.setId(42L);
        user.setUsername("alice");
        user.setPasswordHash("$2a$hash");
    }

    private static MockHttpServletRequest requestWithProof(String token) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setCookies(new Cookie(AuthCookieWriter.REAUTH_PROOF_COOKIE, token));
        return request;
    }

    // ─── proof acceptance (hasValidProof / requireProof) ─────────────────

    @Test
    void hasValidProof_acceptsAProofMintedForTheUser() {
        assertThat(service.hasValidProof(user, requestWithProof(jwtUtil.generateReauthProof(user)))).isTrue();
    }

    @Test
    void hasValidProof_rejectsAnotherUsersProof() {
        AppUser other = new AppUser();
        other.setId(43L);
        other.setUsername("mallory");
        other.setTokenVersion(user.getTokenVersion());

        assertThat(service.hasValidProof(user, requestWithProof(jwtUtil.generateReauthProof(other)))).isFalse();
    }

    @Test
    void hasValidProof_rejectsAProofAfterTheTokenVersionChanged() {
        String proof = jwtUtil.generateReauthProof(user);
        user.setTokenVersion(user.getTokenVersion() + 1);

        assertThat(service.hasValidProof(user, requestWithProof(proof))).isFalse();
    }

    @Test
    void hasValidProof_rejectsAnExpiredProof() {
        String expired = Jwts.builder()
            .subject(user.getUsername())
            .claim("uid", user.getId())
            .claim("type", "reauth")
            .claim("tv", user.getTokenVersion())
            .issuedAt(Date.from(Instant.now().minus(10, ChronoUnit.MINUTES)))
            .expiration(Date.from(Instant.now().minus(5, ChronoUnit.MINUTES)))
            .signWith(Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8)))
            .compact();

        assertThat(service.hasValidProof(user, requestWithProof(expired))).isFalse();
    }

    @Test
    void hasValidProof_rejectsAProofOfTheWrongType() {
        assertThat(service.hasValidProof(user, requestWithProof(jwtUtil.generateAccessToken(user)))).isFalse();
    }

    @Test
    void hasValidProof_rejectsAMissingCookie() {
        assertThat(service.hasValidProof(user, new MockHttpServletRequest())).isFalse();
    }

    @Test
    void requireProof_passesWithAValidProof() {
        service.requireProof(user, requestWithProof(jwtUtil.generateReauthProof(user)));
    }

    @Test
    void requireProof_throwsWithoutAProof() {
        assertThatThrownBy(() -> service.requireProof(user, new MockHttpServletRequest()))
            .isInstanceOf(ReAuthService.ReAuthFailedException.class);
    }

    // ─── verify: proof or the inline check ────────────────────────────────

    @Test
    void verify_acceptsAValidProofWithoutAnInlinePayload() {
        service.verify(user, null, requestWithProof(jwtUtil.generateReauthProof(user)));
    }

    @Test
    void verify_withoutProofOrPayload_isRefused() {
        assertThatThrownBy(() -> service.verify(user, null, new MockHttpServletRequest()))
            .isInstanceOf(ReAuthService.ReAuthFailedException.class);
    }

    @Test
    void verify_withMfaEnabled_acceptsValidTotp() {
        when(mfaService.isEnabled(user)).thenReturn(true);
        when(mfaService.verifyTotp(user, "123456")).thenReturn(true);
        service.verify(user, new ReAuthDto(null, "123456"), new MockHttpServletRequest());
    }

    @Test
    void verify_withMfaEnabled_rejectsWrongTotp() {
        when(mfaService.isEnabled(user)).thenReturn(true);
        when(mfaService.verifyTotp(user, "000000")).thenReturn(false);
        assertThatThrownBy(() -> service.verify(user, new ReAuthDto(null, "000000"), new MockHttpServletRequest()))
            .isInstanceOf(ReAuthService.ReAuthFailedException.class);
    }

    @Test
    void verify_withMfaEnabled_rejectsMissingTotp() {
        when(mfaService.isEnabled(user)).thenReturn(true);
        assertThatThrownBy(() -> service.verify(user, new ReAuthDto("password", null), new MockHttpServletRequest()))
            .isInstanceOf(ReAuthService.ReAuthFailedException.class);
    }

    @Test
    void verify_withoutMfa_acceptsValidPassword() {
        when(mfaService.isEnabled(user)).thenReturn(false);
        when(passwordEncoder.matches("hunter2", "$2a$hash")).thenReturn(true);
        service.verify(user, new ReAuthDto("hunter2", null), new MockHttpServletRequest());
    }

    @Test
    void verify_withoutMfa_rejectsWrongPassword() {
        when(mfaService.isEnabled(user)).thenReturn(false);
        when(passwordEncoder.matches("wrong", "$2a$hash")).thenReturn(false);
        assertThatThrownBy(() -> service.verify(user, new ReAuthDto("wrong", null), new MockHttpServletRequest()))
            .isInstanceOf(ReAuthService.ReAuthFailedException.class);
    }

    @Test
    void verify_withoutMfa_rejectsMissingPassword() {
        when(mfaService.isEnabled(user)).thenReturn(false);
        assertThatThrownBy(() -> service.verify(user, new ReAuthDto(null, "123456"), new MockHttpServletRequest()))
            .isInstanceOf(ReAuthService.ReAuthFailedException.class);
    }

    @Test
    void verify_rejectsNullPayload() {
        assertThatThrownBy(() -> service.verify(user, null, new MockHttpServletRequest()))
            .isInstanceOf(ReAuthService.ReAuthFailedException.class);
    }

    // ─── verifyPasswordAndTotp: both factors when 2FA is on ──────────────

    @Test
    void verifyPasswordAndTotp_withMfaOn_requiresBothFactors() {
        when(mfaService.isEnabled(user)).thenReturn(true);
        when(passwordEncoder.matches("hunter2", "$2a$hash")).thenReturn(true);
        when(mfaService.verifyTotp(user, "123456")).thenReturn(true);

        service.verifyPasswordAndTotp(user, new ReAuthDto("hunter2", "123456"));
    }

    @Test
    void verifyPasswordAndTotp_withMfaOn_refusesPasswordOnly() {
        when(mfaService.isEnabled(user)).thenReturn(true);
        when(passwordEncoder.matches("hunter2", "$2a$hash")).thenReturn(true);

        assertThatThrownBy(() -> service.verifyPasswordAndTotp(user, new ReAuthDto("hunter2", null)))
            .isInstanceOf(ReAuthService.ReAuthFailedException.class);
    }

    @Test
    void verifyPasswordAndTotp_refusesTotpOnly() {
        assertThatThrownBy(() -> service.verifyPasswordAndTotp(user, new ReAuthDto(null, "123456")))
            .isInstanceOf(ReAuthService.ReAuthFailedException.class);
    }

    @Test
    void verifyPasswordAndTotp_withoutMfa_acceptsPasswordOnly() {
        when(passwordEncoder.matches("hunter2", "$2a$hash")).thenReturn(true);

        service.verifyPasswordAndTotp(user, new ReAuthDto("hunter2", null));
    }

    @Test
    void verifyPasswordAndTotp_refusesAWrongPassword() {
        when(passwordEncoder.matches("wrong", "$2a$hash")).thenReturn(false);

        assertThatThrownBy(() -> service.verifyPasswordAndTotp(user, new ReAuthDto("wrong", "123456")))
            .isInstanceOf(ReAuthService.ReAuthFailedException.class);
    }

    @Test
    void verifyPasswordAndTotp_refusesAUserWithoutAPasswordHash() {
        user.setPasswordHash(" ");

        assertThatThrownBy(() -> service.verifyPasswordAndTotp(user, new ReAuthDto("hunter2", "123456")))
            .isInstanceOf(ReAuthService.ReAuthFailedException.class);
    }
}

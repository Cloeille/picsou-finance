package com.picsou.controller;

import com.picsou.config.AuthCookieWriter;
import com.picsou.config.JwtUtil;
import com.picsou.config.RateLimitConfig;
import com.picsou.dto.ReAuthDto;
import com.picsou.dto.SetPasswordRequest;
import com.picsou.dto.SignInMethodsResponse;
import com.picsou.dto.SsoInfoResponse;
import com.picsou.model.AppUser;
import com.picsou.service.ReAuthService;
import com.picsou.service.SignInMethodsService;
import io.github.bucket4j.Bucket;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/auth")
public class SignInMethodsController {

    private final SignInMethodsService signInMethodsService;
    private final ReAuthService reAuthService;
    private final JwtUtil jwtUtil;
    private final AuthCookieWriter cookieWriter;
    private final Map<String, Bucket> reauthBuckets;

    public SignInMethodsController(
        SignInMethodsService signInMethodsService,
        ReAuthService reAuthService,
        JwtUtil jwtUtil,
        AuthCookieWriter cookieWriter,
        @Qualifier("reauthBuckets") Map<String, Bucket> reauthBuckets
    ) {
        this.signInMethodsService = signInMethodsService;
        this.reAuthService = reAuthService;
        this.jwtUtil = jwtUtil;
        this.cookieWriter = cookieWriter;
        this.reauthBuckets = reauthBuckets;
    }

    @GetMapping("/sso")
    public SsoInfoResponse sso() {
        return signInMethodsService.ssoInfo();
    }

    @GetMapping("/sign-in-methods")
    public SignInMethodsResponse signInMethods(@AuthenticationPrincipal AppUser user) {
        return signInMethodsService.signInMethods(user);
    }

    @PostMapping("/reauth")
    public ResponseEntity<?> reauth(
        @AuthenticationPrincipal AppUser user,
        @Valid @RequestBody ReAuthDto req,
        HttpServletResponse response
    ) {
        String key = String.valueOf(user.getId());
        Bucket bucket = reauthBuckets.computeIfAbsent(key, k -> RateLimitConfig.createReauthBucket());
        if (!bucket.tryConsume(1)) {
            ProblemDetail detail = ProblemDetail.forStatus(HttpStatus.TOO_MANY_REQUESTS);
            detail.setDetail("Too many re-authentication attempts. Try again in 15 minutes.");
            detail.setProperty("code", "RATE_LIMITED");
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).body(detail);
        }
        reAuthService.verifyPasswordAndTotp(user, req);
        cookieWriter.setReauthProof(response, jwtUtil.generateReauthProof(user));
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/password")
    public ResponseEntity<Void> removePassword(
        @AuthenticationPrincipal AppUser user,
        HttpServletRequest request,
        HttpServletResponse response
    ) {
        signInMethodsService.removePassword(user, request, response);
        return ResponseEntity.noContent().build();
    }

    @PutMapping("/password")
    public ResponseEntity<Void> setPassword(
        @AuthenticationPrincipal AppUser user,
        @Valid @RequestBody SetPasswordRequest req,
        HttpServletRequest request,
        HttpServletResponse response
    ) {
        signInMethodsService.setPassword(user, req.newPassword(), request, response);
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/identities/{id}")
    public ResponseEntity<Void> unlinkIdentity(
        @AuthenticationPrincipal AppUser user,
        @PathVariable Long id,
        HttpServletRequest request
    ) {
        signInMethodsService.unlinkIdentity(user, id, request);
        return ResponseEntity.noContent().build();
    }
}

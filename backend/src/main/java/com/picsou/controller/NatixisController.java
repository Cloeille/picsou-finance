package com.picsou.controller;

import com.picsou.config.RateLimitConfig;
import com.picsou.service.NatixisSyncService;
import com.picsou.service.UserContext;
import io.github.bucket4j.Bucket;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Valid;
import jakarta.validation.Validator;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.*;
import org.springframework.validation.BindingResult;
import org.springframework.validation.beanvalidation.SpringValidatorAdapter;
import org.springframework.web.bind.WebDataBinder;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

import static com.picsou.config.PerIpRateLimit.tooManyRequests;
import static com.picsou.config.PerIpRateLimit.tryConsume;

@RestController
@RequestMapping("/api/natixis")
public class NatixisController {
    private static final String TOO_MANY_AUTH =
        "Too many Natixis Interépargne authentication attempts. Please wait before retrying.";

    private final NatixisSyncService service;
    private final UserContext userContext;
    private final Map<String, Bucket> authBuckets;
    private final Map<String, Bucket> completeBuckets;
    private final Map<String, Bucket> syncBuckets;
    private final SpringValidatorAdapter tokenValidator;

    public NatixisController(
        NatixisSyncService service,
        UserContext userContext,
        @Qualifier("natixisAuthBuckets") Map<String, Bucket> authBuckets,
        @Qualifier("natixisCompleteBuckets") Map<String, Bucket> completeBuckets,
        @Qualifier("syncBuckets") Map<String, Bucket> syncBuckets,
        Validator validator
    ) {
        this.service = service;
        this.userContext = userContext;
        this.authBuckets = authBuckets;
        this.completeBuckets = completeBuckets;
        this.syncBuckets = syncBuckets;
        // The pasted token is a credential: a failed validation must not carry it, as the rejected
        // value, into the exception message that a log or an error report would then record.
        this.tokenValidator = new SpringValidatorAdapter(validator) {
            @Override
            protected Object getRejectedValue(String field, ConstraintViolation<Object> violation, BindingResult binding) {
                return null;
            }
        };
    }

    @InitBinder("browserSessionRequest")
    void redactRejectedToken(WebDataBinder binder) {
        binder.replaceValidators(tokenValidator);
    }

    @PostMapping("/auth/initiate")
    public ResponseEntity<?> initiate(@Valid @RequestBody InitiateRequest req, HttpServletRequest request) {
        if (!tryConsume(authBuckets, request, RateLimitConfig::createNatixisAuthBucket)) return tooManyRequests(TOO_MANY_AUTH);
        return ResponseEntity.ok(service.initiateAuth(req.email(), req.password(), userContext.currentMemberId()));
    }

    @PostMapping("/auth/complete")
    public ResponseEntity<?> complete(@Valid @RequestBody CompleteRequest req, HttpServletRequest request) {
        if (!tryConsume(completeBuckets, request, RateLimitConfig::createNatixisCompleteBucket)) return tooManyRequests(TOO_MANY_AUTH);
        return ResponseEntity.ok(service.completeAuth(req.processId(), req.code(), userContext.currentMemberId()));
    }

    /**
     * Hands over the refresh token of a session the saver opened in their own browser, for accounts
     * where Natixis requires a passkey the sidecar cannot register. Shares the code-check budget.
     */
    @PostMapping("/auth/browser-session")
    public ResponseEntity<?> browserSession(@Valid @RequestBody BrowserSessionRequest req, HttpServletRequest request) {
        if (!tryConsume(completeBuckets, request, RateLimitConfig::createNatixisCompleteBucket)) return tooManyRequests(TOO_MANY_AUTH);
        return ResponseEntity.ok(service.importBrowserSession(req.refreshToken(), userContext.currentMemberId()));
    }

    @PostMapping("/sync")
    public ResponseEntity<?> sync(HttpServletRequest request) {
        if (!tryConsume(syncBuckets, request, RateLimitConfig::createSyncBucket)) {
            return tooManyRequests("Too many Natixis Interépargne synchronization requests. Please wait before retrying.");
        }
        return ResponseEntity.accepted().body(service.queueSync(userContext.currentMemberId()));
    }

    @GetMapping("/status")
    public NatixisSyncService.SessionStatusResponse status() { return service.getStatus(userContext.currentMemberId()); }

    @DeleteMapping("/session")
    public ResponseEntity<Void> clear() {
        service.clearSession(userContext.currentMemberId());
        return ResponseEntity.noContent().build();
    }

    /** Matches the sidecar's own bounds, so a value it would refuse never leaves the backend. */
    record InitiateRequest(
        @NotBlank @Size(max = 254) String email,
        @NotBlank @Size(max = 100) String password
    ) {}

    /** Printable ASCII without a space: the sidecar's own bound, so a paste accident never leaves the backend. */
    record BrowserSessionRequest(
        @NotNull @Pattern(regexp = "[\\x21-\\x7e]{8,4096}") String refreshToken
    ) {
        /** The token is a credential: it must not reach a log through a record's default toString. */
        @Override
        public String toString() {
            return "BrowserSessionRequest[refreshToken=<redacted>]";
        }
    }

    /** {@code code} is the SMS, voice or e-mail code; Natixis has no mobile confirmation. */
    record CompleteRequest(
        @NotBlank @Size(max = 100) String processId,
        @NotNull @Pattern(regexp = "\\d{4,12}") String code
    ) {}
}

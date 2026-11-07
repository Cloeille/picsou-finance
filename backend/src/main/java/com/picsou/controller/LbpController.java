package com.picsou.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.picsou.config.ClientIp;
import com.picsou.config.RateLimitConfig;
import com.picsou.service.LbpSyncService;
import com.picsou.service.UserContext;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.ConsumptionProbe;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * La Banque Postale endpoints. Login is three calls ({@code /auth/initiate},
 * {@code /auth/keypad}, {@code /auth/complete}); no password ever reaches Picsou:
 * {@code /auth/initiate} answers with the bank's keypad, the user clicks his digits in his
 * browser, then approves the sign-in in the bank's mobile app. The sync is on demand only.
 */
@RestController
@RequestMapping("/api/lbp")
public class LbpController {
    static final String DELETE_MESSAGE =
        "The stored session was deleted from Picsou. The La Banque Postale session itself is not "
            + "revoked and may remain active until it expires.";
    /** What a body this endpoint cannot accept answers, whatever was in it. */
    static final String INVALID_BODY = "The La Banque Postale request is not valid";
    /**
     * The bank's customer identifier is 10 digits for a retail account. A small range keeps a
     * typo from spending a sign-in attempt without assuming every account uses the same width.
     */
    static final Pattern CUSTOMER_ID = Pattern.compile("\\d{6,12}");
    /**
     * The bank's password is always 6 digits, clicked on a 10-key pad. The sidecar
     * enforces the same width: anything else is a malformed request, never a login attempt.
     */
    static final int POSITIONS_COUNT = 6;
    static final int PAD_SIZE = 10;
    static final int PROCESS_ID_MAX = 100;

    private final LbpSyncService service;
    private final UserContext userContext;
    private final Map<String, Bucket> authBuckets;
    private final Map<String, Bucket> stepBuckets;
    private final Map<String, Bucket> syncBuckets;

    public LbpController(
        LbpSyncService service,
        UserContext userContext,
        @Qualifier("lbpAuthBuckets") Map<String, Bucket> authBuckets,
        @Qualifier("lbpKeypadBuckets") Map<String, Bucket> stepBuckets,
        @Qualifier("syncBuckets") Map<String, Bucket> syncBuckets
    ) {
        this.service = service;
        this.userContext = userContext;
        this.authBuckets = authBuckets;
        this.stepBuckets = stepBuckets;
        this.syncBuckets = syncBuckets;
    }

    /**
     * Starts the login and answers with the bank's keypad for the user to click. Throttled at
     * 3 per 15 minutes per IP: a wrong pad costs a bank attempt. The body is validated first, so
     * a malformed request spends neither an attempt nor a sidecar slot. Never retried here.
     */
    @PostMapping("/auth/initiate")
    public ResponseEntity<?> initiate(@RequestBody JsonNode body, HttpServletRequest request) {
        // Read strictly: an unexpected field (a password, typically) is refused, never ignored.
        requireOnlyFields(body, "customerId", "rememberIdentifier");
        String customerId = text(body, "customerId");
        if (!CUSTOMER_ID.matcher(customerId).matches()) {
            throw invalidBody();
        }
        boolean rememberIdentifier = bool(body, "rememberIdentifier");
        ConsumptionProbe probe = consumeToken(authBuckets, RateLimitConfig::createLbpAuthBucket, request);
        if (!probe.isConsumed()) {
            return authRateLimited(probe);
        }
        return ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL, "no-store")
            .body(service.initiateAuth(customerId, rememberIdentifier, userContext.currentMemberId()));
    }

    /**
     * Clicks the digits the user chose, then leaves the attempt waiting for the mobile approval.
     * The body is validated first: a malformed request spends nothing. Its own bucket, so it can
     * never eat one of the 3 login attempts.
     */
    @PostMapping("/auth/keypad")
    public ResponseEntity<?> keypad(@RequestBody JsonNode body, HttpServletRequest request) {
        requireOnlyFields(body, "processId", "positions");
        String processId = text(body, "processId");
        if (processId.isBlank() || processId.length() > PROCESS_ID_MAX) {
            throw invalidBody();
        }
        List<Integer> positions = positions(body.get("positions"));
        ConsumptionProbe probe = consumeToken(stepBuckets, RateLimitConfig::createLbpKeypadBucket, request);
        if (!probe.isConsumed()) {
            return keypadRateLimited(probe);
        }
        service.submitKeypad(processId, positions, userContext.currentMemberId());
        // The positions are never echoed back, here or in an error.
        return ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL, "no-store")
            .body(new KeypadResponse(processId));
    }

    /**
     * Blocks while the customer approves the sign-in in the bank's mobile app (up to 150 s on
     * the sidecar), then stores the session. Takes no password and does not draw from the login
     * bucket: it cannot spend a bank attempt. Deliberately no rate-limit bucket (unlike CIC's
     * {@code /auth/complete}): {@code LbpSyncService.takePending} removes the pending login
     * before the sidecar is called, so a processId is single-use and a replay fails with the
     * existing expiration error.
     */
    @PostMapping("/auth/complete")
    public ResponseEntity<?> complete(@RequestBody JsonNode body) {
        requireOnlyFields(body, "processId");
        String processId = text(body, "processId");
        if (processId.isBlank() || processId.length() > PROCESS_ID_MAX) {
            throw invalidBody();
        }
        return ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL, "no-store")
            .body(service.completeAuth(processId, userContext.currentMemberId()));
    }

    /**
     * Throttled like every other sync entry point: queueing takes a row lock and decrypts the
     * stored session. {@code queueSync} already refuses to stack jobs.
     */
    @PostMapping("/sync")
    public ResponseEntity<?> sync(HttpServletRequest request) {
        if (!consumeSyncToken(request)) {
            ProblemDetail detail = ProblemDetail.forStatus(HttpStatus.TOO_MANY_REQUESTS);
            detail.setDetail("Too many La Banque Postale synchronization requests. Please wait before retrying.");
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).body(detail);
        }
        return ResponseEntity.accepted().body(service.queueSync(userContext.currentMemberId()));
    }

    @GetMapping("/status")
    public LbpSyncService.SessionStatusResponse status() {
        return service.getStatus(userContext.currentMemberId());
    }

    /** Forgets the stored session in Picsou only; the answer never claims the bank session ended. */
    @DeleteMapping("/session")
    public ResponseEntity<DeleteResponse> clear() {
        boolean removed = service.clearSession(userContext.currentMemberId());
        return ResponseEntity.ok(new DeleteResponse(removed, false, DELETE_MESSAGE));
    }

    // --- request reading --------------------------------------------------------

    /**
     * The body must carry exactly these fields. This is what keeps a caller that still sends a
     * password from being silently accepted, and it keeps anything unexpected out of the DTOs.
     */
    static void requireOnlyFields(JsonNode body, String... allowed) {
        if (body == null || !body.isObject()) {
            throw invalidBody();
        }
        Set<String> names = new HashSet<>();
        body.fieldNames().forEachRemaining(names::add);
        if (!Set.of(allowed).containsAll(names)) {
            throw invalidBody();
        }
    }

    static String text(JsonNode body, String field) {
        JsonNode value = body.get(field);
        if (value == null || !value.isTextual()) {
            throw invalidBody();
        }
        return value.textValue();
    }

    /** An optional boolean flag; present but not a boolean is a malformed body. */
    static boolean bool(JsonNode body, String field) {
        JsonNode value = body.get(field);
        if (value == null) {
            return false;
        }
        if (!value.isBoolean()) {
            throw invalidBody();
        }
        return value.booleanValue();
    }

    /** Exactly 6 positions, each an index on a ten-key pad, in the user's click order. */
    static List<Integer> positions(JsonNode node) {
        if (node == null || !node.isArray() || node.size() != POSITIONS_COUNT) {
            throw invalidBody();
        }
        List<Integer> positions = new ArrayList<>(node.size());
        for (JsonNode position : node) {
            if (!position.isIntegralNumber() || !position.canConvertToInt()) {
                throw invalidBody();
            }
            int value = position.intValue();
            if (value < 0 || value >= PAD_SIZE) {
                throw invalidBody();
            }
            positions.add(value);
        }
        return List.copyOf(positions);
    }

    /** A body-level refusal. The message is fixed: nothing of the input is ever echoed. */
    static IllegalArgumentException invalidBody() {
        return new IllegalArgumentException(INVALID_BODY);
    }

    // --- rate limiting ----------------------------------------------------------

    private ConsumptionProbe consumeToken(
        Map<String, Bucket> buckets,
        java.util.function.Supplier<Bucket> factory,
        HttpServletRequest request
    ) {
        return buckets.computeIfAbsent(ClientIp.resolve(request), key -> factory.get())
            .tryConsumeAndReturnRemaining(1);
    }

    private ResponseEntity<ProblemDetail> authRateLimited(ConsumptionProbe probe) {
        return tooMany(probe, "Too many La Banque Postale sign-in attempts. Please wait before retrying.");
    }

    private ResponseEntity<ProblemDetail> keypadRateLimited(ConsumptionProbe probe) {
        return tooMany(probe, "Too many La Banque Postale keypad requests. Please wait before retrying.");
    }

    private ResponseEntity<ProblemDetail> tooMany(ConsumptionProbe probe, String message) {
        long seconds = Math.max(1, Duration.ofNanos(probe.getNanosToWaitForRefill()).plusMillis(999).toSeconds());
        ProblemDetail detail = ProblemDetail.forStatus(HttpStatus.TOO_MANY_REQUESTS);
        detail.setDetail(message);
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
            .header(HttpHeaders.RETRY_AFTER, Long.toString(seconds))
            .body(detail);
    }

    private boolean consumeSyncToken(HttpServletRequest request) {
        return syncBuckets.computeIfAbsent(ClientIp.resolve(request), key -> RateLimitConfig.createSyncBucket())
            .tryConsume(1);
    }

    // --- contract ---------------------------------------------------------------

    /**
     * The identifier and the optional "remember it" flag. Its {@code toString} redacts the
     * identifier so a logged or exception-wrapped DTO cannot leak it, and there is no password
     * field: the pad is clicked in the user's browser and only the positions come back.
     */
    record InitiateRequest(String customerId, Boolean rememberIdentifier) {
        @Override
        public String toString() {
            return "InitiateRequest[customerId=***, rememberIdentifier=" + rememberIdentifier + "]";
        }
    }

    record KeypadRequest(String processId, List<Integer> positions) {
        @Override
        public String toString() {
            return "KeypadRequest[processId=" + processId + ", positions=***]";
        }
    }

    record KeypadResponse(String processId, String status) {
        KeypadResponse(String processId) {
            this(processId, "APPROVAL_PENDING");
        }
    }

    record CompleteRequest(String processId) {}

    record DeleteResponse(boolean removed, boolean bankSessionRevoked, String message) {}
}

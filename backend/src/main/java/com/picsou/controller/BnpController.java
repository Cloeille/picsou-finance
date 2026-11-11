package com.picsou.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.picsou.config.ClientIp;
import com.picsou.config.RateLimitConfig;
import com.picsou.service.BnpSyncService;
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
 * BNP Paribas and Hello bank! endpoints. Login is three calls ({@code /auth/initiate},
 * {@code /auth/keypad}, {@code /auth/complete}); no password ever reaches Picsou: the bank's
 * keypad is clicked in the member's browser and only the tile positions come back, then the
 * member confirms the strong authentication (the bank's app, or the code it sent by SMS).
 * The sync is on demand and daily.
 */
@RestController
@RequestMapping("/api/bnp")
public class BnpController {
    static final String DELETE_MESSAGE =
        "The stored session was deleted from Picsou. The BNP Paribas session itself is not "
            + "revoked and may remain active until it expires.";
    /** What a body this endpoint cannot accept answers, whatever was in it. */
    static final String INVALID_BODY = "The BNP Paribas request is not valid";
    /** The login page takes 7 to 10 digits; refusing anything else here keeps a typo from spending an attempt. */
    static final Pattern CUSTOMER_ID = Pattern.compile("\\d{7,10}");
    static final Pattern CODE = Pattern.compile("\\d{4,10}");
    static final Set<String> BRANDS = Set.of("bnp", "hellobank");
    /** The password is always 6 digits, clicked on a 10-tile keypad. */
    static final int POSITIONS_COUNT = 6;
    static final int PAD_SIZE = 10;
    static final int PROCESS_ID_MAX = 100;

    private final BnpSyncService service;
    private final UserContext userContext;
    private final Map<String, Bucket> authBuckets;
    private final Map<String, Bucket> stepBuckets;
    private final Map<String, Bucket> completeBuckets;
    private final Map<String, Bucket> syncBuckets;

    public BnpController(
        BnpSyncService service,
        UserContext userContext,
        @Qualifier("bnpAuthBuckets") Map<String, Bucket> authBuckets,
        @Qualifier("bnpKeypadBuckets") Map<String, Bucket> stepBuckets,
        @Qualifier("bnpCompleteBuckets") Map<String, Bucket> completeBuckets,
        @Qualifier("syncBuckets") Map<String, Bucket> syncBuckets
    ) {
        this.service = service;
        this.userContext = userContext;
        this.authBuckets = authBuckets;
        this.stepBuckets = stepBuckets;
        this.completeBuckets = completeBuckets;
        this.syncBuckets = syncBuckets;
    }

    /**
     * Starts the login and answers with the bank's keypad. Throttled at 3 per 15 minutes per IP:
     * a wrong keypad costs a bank attempt. The body is validated first, so a malformed request
     * spends neither an attempt nor a sidecar slot. Never retried here.
     */
    @PostMapping("/auth/initiate")
    public ResponseEntity<?> initiate(@RequestBody JsonNode body, HttpServletRequest request) {
        // Read strictly: an unexpected field (a password, typically) is refused, never ignored.
        requireOnlyFields(body, "customerId", "brand");
        String customerId = text(body, "customerId");
        if (!CUSTOMER_ID.matcher(customerId).matches()) {
            throw invalidBody();
        }
        String brand = body.has("brand") ? text(body, "brand") : "bnp";
        if (!BRANDS.contains(brand)) {
            throw invalidBody();
        }
        ConsumptionProbe probe = consumeToken(authBuckets, RateLimitConfig::createBnpAuthBucket, request);
        if (!probe.isConsumed()) {
            return tooMany(probe, "Too many BNP Paribas sign-in attempts. Please wait before retrying.");
        }
        return ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL, "no-store")
            .body(service.initiateAuth(customerId, brand, userContext.currentMemberId()));
    }

    /** Sends the clicked tiles. Its own bucket, so it never eats one of the 3 login attempts or a code attempt. */
    @PostMapping("/auth/keypad")
    public ResponseEntity<?> keypad(@RequestBody JsonNode body, HttpServletRequest request) {
        requireOnlyFields(body, "processId", "positions");
        String processId = processId(body);
        List<Integer> positions = positions(body.get("positions"));
        ConsumptionProbe probe = consumeToken(stepBuckets, RateLimitConfig::createBnpKeypadBucket, request);
        if (!probe.isConsumed()) {
            return tooMany(probe, "Too many BNP Paribas keypad requests. Please wait before retrying.");
        }
        // The positions are never echoed back, here or in an error.
        return ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL, "no-store")
            .body(service.submitKeypad(processId, positions, userContext.currentMemberId()));
    }

    /**
     * Finishes the strong authentication, then stores the session. Without a {@code code} it waits
     * for the approval in the bank's app; with one it submits the SMS code. A wrong code can be
     * typed again; the code has its own bucket (10 per 15 minutes), apart from the keypad one,
     * so a guessing loop is throttled and a login whose keypad budget is spent can still be
     * completed once the bank sent the code.
     */
    @PostMapping("/auth/complete")
    public ResponseEntity<?> complete(@RequestBody JsonNode body, HttpServletRequest request) {
        requireOnlyFields(body, "processId", "code");
        String processId = processId(body);
        String code = null;
        if (body.has("code") && !body.get("code").isNull()) {
            code = text(body, "code");
            if (!CODE.matcher(code).matches()) {
                throw invalidBody();
            }
            ConsumptionProbe probe = consumeToken(completeBuckets, RateLimitConfig::createBnpCompleteBucket, request);
            if (!probe.isConsumed()) {
                return tooMany(probe, "Too many BNP Paribas code attempts. Please wait before retrying.");
            }
        }
        return ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL, "no-store")
            .body(service.completeAuth(processId, code, userContext.currentMemberId()));
    }

    @PostMapping("/sync")
    public ResponseEntity<?> sync(HttpServletRequest request) {
        boolean allowed = syncBuckets.computeIfAbsent(ClientIp.resolve(request), key -> RateLimitConfig.createSyncBucket())
            .tryConsume(1);
        if (!allowed) {
            ProblemDetail detail = ProblemDetail.forStatus(HttpStatus.TOO_MANY_REQUESTS);
            detail.setDetail("Too many BNP Paribas synchronization requests. Please wait before retrying.");
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).body(detail);
        }
        return ResponseEntity.accepted().body(service.queueSync(userContext.currentMemberId()));
    }

    @GetMapping("/status")
    public BnpSyncService.SessionStatusResponse status() {
        return service.getStatus(userContext.currentMemberId());
    }

    /** Forgets the stored session in Picsou only; the answer never claims the bank session ended. */
    @DeleteMapping("/session")
    public ResponseEntity<DeleteResponse> clear() {
        boolean removed = service.clearSession(userContext.currentMemberId());
        return ResponseEntity.ok(new DeleteResponse(removed, false, DELETE_MESSAGE));
    }

    // --- request reading --------------------------------------------------------

    /** The body must carry only these fields: anything unexpected is refused, never ignored. */
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

    private static String processId(JsonNode body) {
        String processId = text(body, "processId");
        if (processId.isBlank() || processId.length() > PROCESS_ID_MAX) {
            throw invalidBody();
        }
        return processId;
    }

    /** Exactly 6 positions, each an index on a ten-tile keypad, in the member's click order. */
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

    private ResponseEntity<ProblemDetail> tooMany(ConsumptionProbe probe, String message) {
        long seconds = Math.max(1, Duration.ofNanos(probe.getNanosToWaitForRefill()).plusMillis(999).toSeconds());
        ProblemDetail detail = ProblemDetail.forStatus(HttpStatus.TOO_MANY_REQUESTS);
        detail.setDetail(message);
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
            .header(HttpHeaders.RETRY_AFTER, Long.toString(seconds))
            .body(detail);
    }

    record DeleteResponse(boolean removed, boolean bankSessionRevoked, String message) {}
}

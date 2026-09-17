package com.picsou.controller;

import com.picsou.config.ClientIp;
import com.picsou.config.RateLimitConfig;
import com.picsou.dto.BoursoBankHealthResponse;
import com.picsou.dto.CryptoKeyGenerateResponse;
import com.picsou.dto.EnableBankingConfigRequest;
import com.picsou.dto.EnableBankingImportRequest;
import com.picsou.dto.EnableBankingKeypairResponse;
import com.picsou.dto.EnableBankingTestResponse;
import com.picsou.dto.SetupAdminRequest;
import com.picsou.dto.SetupAdminResponse;
import com.picsou.dto.SetupSecurityRequest;
import com.picsou.dto.SetupStatusResponse;
import com.picsou.model.AppUser;
import com.picsou.service.CryptoKeyGeneratorService;
import com.picsou.service.EnableBankingKeyPairService;
import com.picsou.service.IntegrationsHealthService;
import com.picsou.service.IntegrationsService;
import com.picsou.service.SetupAuditService;
import com.picsou.service.SetupService;
import io.github.bucket4j.Bucket;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;

/**
 * Public (unauthenticated) REST surface for the first-launch setup wizard.
 *
 * <h3>Endpoint lifecycle</h3>
 * <ul>
 *   <li>{@code GET /status} — always available. Used by the frontend to
 *       decide whether to render the wizard or the login page.</li>
 *   <li>All mutating endpoints — guarded twice: by {@code SetupFilter}
 *       which short-circuits once state is {@code COMPLETE}, and by the
 *       {@code requireNotComplete} helper here which returns HTTP 410 Gone
 *       if somehow a mutating request arrives post-completion. Defence in
 *       depth matters on unauth endpoints.</li>
 * </ul>
 *
 * <h3>Rate limiting</h3>
 * 10 mutating requests per minute per client IP (see {@code setupBuckets} in
 * {@link RateLimitConfig}). {@code GET /status} is polled by the frontend
 * and is exempt. The key is {@link com.picsou.config.ClientIp#resolve}, never
 * {@code request.getRemoteAddr()} directly and never {@code X-Forwarded-For}:
 * under {@code server.forward-headers-strategy: framework} (the deployment
 * contract is nginx on the docker bridge), {@code getRemoteAddr()} itself is
 * rewritten from the client-suppliable leftmost X-Forwarded-For entry, so it
 * is no safer than trusting the header outright. {@code ClientIp} reads
 * nginx's {@code X-Real-IP} instead, which a client cannot inject.
 */
@RestController
@RequestMapping("/api/setup")
public class SetupController {

    private final SetupService setupService;
    private final IntegrationsService integrationsService;
    private final IntegrationsHealthService healthService;
    private final EnableBankingKeyPairService keyPairService;
    private final CryptoKeyGeneratorService cryptoKeyService;
    private final SetupAuditService auditService;
    private final Map<String, Bucket> setupBuckets;

    public SetupController(SetupService setupService,
                           IntegrationsService integrationsService,
                           IntegrationsHealthService healthService,
                           EnableBankingKeyPairService keyPairService,
                           CryptoKeyGeneratorService cryptoKeyService,
                           SetupAuditService auditService,
                           @Qualifier("setupBuckets") Map<String, Bucket> setupBuckets) {
        this.setupService = setupService;
        this.integrationsService = integrationsService;
        this.healthService = healthService;
        this.keyPairService = keyPairService;
        this.cryptoKeyService = cryptoKeyService;
        this.auditService = auditService;
        this.setupBuckets = setupBuckets;
    }

    @GetMapping("/status")
    public SetupStatusResponse status() {
        return setupService.getStatus();
    }

    @PostMapping("/admin")
    public ResponseEntity<?> seedAdmin(@Valid @RequestBody SetupAdminRequest request,
                                       HttpServletRequest httpRequest) {
        if (!consumeRateLimitToken(httpRequest)) return rateLimited();
        requireNotComplete();

        String bcryptHash = setupService.hashPassword(request.password());
        AppUser user = setupService.seedAdmin(
            request.username(),
            bcryptHash,
            request.displayName(),
            request.avatarColor()
        );
        auditService.record("setup.admin.created", user.getUsername(), httpRequest, null);
        return ResponseEntity.ok(new SetupAdminResponse(
            user.getUsername(),
            user.getMember() != null ? user.getMember().getDisplayName() : user.getUsername()
        ));
    }

    @PostMapping("/security")
    public ResponseEntity<?> writeSecurity(@Valid @RequestBody SetupSecurityRequest request,
                                           HttpServletRequest httpRequest) {
        if (!consumeRateLimitToken(httpRequest)) return rateLimited();
        requireNotComplete();

        setupService.writeSecurity(request.allowedOrigins(), request.secureCookies());
        auditService.record(
            "setup.security.updated",
            null,
            httpRequest,
            "origins=" + request.allowedOrigins().size() + " secure=" + request.secureCookies()
        );
        return ResponseEntity.noContent().build();
    }

    // ─── Enable Banking substeps ─────────────────────────────────────────────

    @PostMapping("/integrations/enablebanking/config")
    public ResponseEntity<?> writeEnableBankingConfig(@Valid @RequestBody EnableBankingConfigRequest request,
                                                      HttpServletRequest httpRequest) {
        if (!consumeRateLimitToken(httpRequest)) return rateLimited();
        requireNotComplete();

        setupService.writeEnableBankingConfig(
            request.applicationId(),
            request.redirectUri()
        );
        return ResponseEntity.noContent().build();
    }

    /**
     * Returns the current public key PEM, generating a new pair on disk only
     * if none exists yet. Idempotent — re-calling never invalidates the
     * public key a user may have already uploaded to their Enable Banking
     * dashboard.
     */
    @PostMapping("/integrations/enablebanking/keypair")
    public ResponseEntity<?> generateEnableBankingKeyPair(HttpServletRequest httpRequest) {
        if (!consumeRateLimitToken(httpRequest)) return rateLimited();
        requireNotComplete();

        boolean existedBefore = keyPairService.exists();
        String publicPem = keyPairService.getOrGeneratePublicPem();
        return ResponseEntity.ok(new EnableBankingKeypairResponse(publicPem, !existedBefore));
    }

    @PostMapping("/integrations/enablebanking/keypair/import")
    public ResponseEntity<?> importEnableBankingPrivateKey(
            @Valid @RequestBody EnableBankingImportRequest request,
            HttpServletRequest httpRequest) {
        if (!consumeRateLimitToken(httpRequest)) return rateLimited();
        requireNotComplete();

        try {
            String publicPem = keyPairService.importPrivateKey(request.privatePem());
            return ResponseEntity.ok(new EnableBankingKeypairResponse(publicPem, false));
        } catch (IllegalArgumentException ex) {
            ProblemDetail pd = ProblemDetail.forStatus(HttpStatus.UNPROCESSABLE_ENTITY);
            pd.setDetail(ex.getMessage());
            return ResponseEntity.unprocessableEntity().body(pd);
        }
    }

    @PostMapping("/integrations/enablebanking/test")
    public ResponseEntity<EnableBankingTestResponse> testEnableBanking(HttpServletRequest httpRequest) {
        if (!consumeRateLimitToken(httpRequest)) {
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).build();
        }
        requireNotComplete();

        EnableBankingTestResponse response = healthService.testEnableBanking();
        if (response.ok()) {
            integrationsService.enable("enablebanking");
            auditService.record("setup.integration.enabled", null, httpRequest, "key=enablebanking");
        }
        return ResponseEntity.ok(response);
    }

    // ─── BoursoBank ──────────────────────────────────────────────────────────

    @GetMapping("/integrations/boursobank/health")
    public ResponseEntity<BoursoBankHealthResponse> boursoBankHealth(HttpServletRequest httpRequest) {
        if (!consumeRateLimitToken(httpRequest)) {
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).build();
        }
        requireNotComplete();

        BoursoBankHealthResponse response = healthService.checkBoursoBankSidecar();
        if (response.ok()) {
            integrationsService.enable("boursobank");
            auditService.record("setup.integration.enabled", null, httpRequest, "key=boursobank");
        }
        return ResponseEntity.ok(response);
    }

    // ─── Crypto encryption key ──────────────────────────────────────────────

    /**
     * Idempotent. In the normal Docker flow the entrypoint has already created
     * the key file before Spring booted (otherwise {@code CryptoEncryption}
     * would have failed its constructor check), so this hits the
     * {@code existed=true} branch. Bare-metal installs usually provide
     * {@code CRYPTO_ENCRYPTION_KEY} directly through {@code .env.local}; that
     * is also treated as an existing encryption key and does not touch
     * Docker's {@code /data} path.
     */
    @PostMapping("/integrations/crypto/generate-key")
    public ResponseEntity<CryptoKeyGenerateResponse> generateCryptoKey(HttpServletRequest httpRequest) {
        if (!consumeRateLimitToken(httpRequest)) {
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).build();
        }
        requireNotComplete();

        boolean existedBefore = cryptoKeyService.exists();
        cryptoKeyService.ensureKey();
        integrationsService.enable("crypto");
        auditService.record(
            "setup.integration.enabled",
            null,
            httpRequest,
            "key=crypto existed=" + existedBefore
        );
        return ResponseEntity.ok(new CryptoKeyGenerateResponse(
            existedBefore,
            cryptoKeyService.keyLocation()
        ));
    }

    // ─── Integration acknowledgements (TR, Finary) ──────────────────────────

    @PostMapping("/integrations/{key}/acknowledge")
    public ResponseEntity<?> acknowledge(@PathVariable String key, HttpServletRequest httpRequest) {
        if (!consumeRateLimitToken(httpRequest)) return rateLimited();
        requireNotComplete();

        if (!"traderepublic".equals(key) && !"finary".equals(key) && !"boursedirect".equals(key)
            && !"fortuneo".equals(key)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "This step only applies to integrations configured after signing in.");
        }
        integrationsService.enable(key);
        auditService.record("setup.integration.enabled", null, httpRequest, "key=" + key);
        return ResponseEntity.noContent().build();
    }

    // ─── Finalise setup ─────────────────────────────────────────────────────

    /**
     * Flips {@code setup.state} to {@code COMPLETE}. After this, the
     * {@code SetupFilter} lets every other API route through and every
     * endpoint on this controller starts returning HTTP 410 (via
     * {@link #requireNotComplete()}). The state transition is one-way —
     * re-entering the wizard requires operator intervention at the DB level.
     */
    @PostMapping("/complete")
    public ResponseEntity<?> complete(HttpServletRequest httpRequest) {
        if (!consumeRateLimitToken(httpRequest)) return rateLimited();
        requireNotComplete();

        setupService.markComplete();
        auditService.record("setup.completed", null, httpRequest, null);
        return ResponseEntity.noContent().build();
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────

    private boolean consumeRateLimitToken(HttpServletRequest request) {
        String ip = ClientIp.resolve(request);
        Bucket bucket = setupBuckets.computeIfAbsent(ip, k -> RateLimitConfig.createSetupBucket());
        return bucket.tryConsume(1);
    }

    private ResponseEntity<ProblemDetail> rateLimited() {
        ProblemDetail detail = ProblemDetail.forStatus(HttpStatus.TOO_MANY_REQUESTS);
        detail.setDetail("Too many setup requests. Try again in a minute.");
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).body(detail);
    }

    private void requireNotComplete() {
        if (setupService.isComplete()) {
            throw new ResponseStatusException(HttpStatus.GONE,
                "Setup is already complete; use the admin UI to manage users and security.");
        }
    }
}

package com.picsou.config;

import com.picsou.model.AppUser;
import com.picsou.model.UserRole;
import com.picsou.repository.AppUserRepository;
import com.picsou.service.SetupAuditService;
import com.picsou.util.LogSanitizer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.List;

/**
 * Break-glass admin password recovery, gated by an env flag that an operator
 * sets ONE TIME after losing the admin password. On boot, regenerates an
 * activation token for every ADMIN user, prints the URL to logs, and bumps
 * tokenVersion to invalidate any pre-existing sessions.
 *
 * Intentionally limited to boot-time: there is no API surface, so a runtime
 * compromise cannot trigger this. The operator must redeploy / restart with
 * the flag set, then unset it after recovering.
 *
 * See docs/features/admin-recovery.md.
 */
@Component
public class AdminRecoveryRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(AdminRecoveryRunner.class);
    private static final SecureRandom RNG = new SecureRandom();
    private static final long TOKEN_TTL_HOURS = 1;

    private final boolean enabled;
    private final String publicBaseUrl;
    private final AppUserRepository userRepository;
    private final SetupAuditService auditService;

    public AdminRecoveryRunner(
        @Value("${app.admin-recovery.enabled:false}") boolean enabled,
        @Value("${app.public-base-url:http://localhost:5173}") String publicBaseUrl,
        AppUserRepository userRepository,
        SetupAuditService auditService
    ) {
        this.enabled = enabled;
        this.publicBaseUrl = publicBaseUrl.replaceAll("/+$", "");
        this.userRepository = userRepository;
        this.auditService = auditService;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (!enabled) {
            return;
        }

        List<AppUser> admins = userRepository.findAll().stream()
            .filter(u -> u.getRole() == UserRole.ADMIN)
            .toList();

        if (admins.isEmpty()) {
            log.warn("ADMIN_RECOVERY_ENABLED=true but no ADMIN user exists yet — skipping.");
            return;
        }

        for (AppUser admin : admins) {
            String token = generateToken();
            admin.setActivationToken(token);
            admin.setActivationTokenExpires(Instant.now().plus(TOKEN_TTL_HOURS, ChronoUnit.HOURS));
            admin.setActivated(false);
            admin.setTokenVersion(admin.getTokenVersion() + 1);
            userRepository.save(admin);

            String url = publicBaseUrl + "/activate/" + token;
            printBanner(admin.getUsername(), url);
            auditService.record(
                "admin.recovery.token-generated",
                admin.getUsername(),
                null,
                "ttl=" + TOKEN_TTL_HOURS + "h"
            );
        }
    }

    private static String generateToken() {
        byte[] bytes = new byte[32];
        RNG.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private void printBanner(String username, String url) {
        String separator = "============================================================";
        log.warn("");
        log.warn(separator);
        log.warn("ADMIN RECOVERY ACTIVATED");
        log.warn("Username : {}", LogSanitizer.safe(username));
        log.warn("Valid    : {} hour", TOKEN_TTL_HOURS);
        log.warn("URL      : {}", url);
        log.warn("");
        log.warn("After resetting the password, set ADMIN_RECOVERY_ENABLED=false");
        log.warn("and restart Picsou. All previous sessions have been invalidated.");
        log.warn(separator);
        log.warn("");
    }
}

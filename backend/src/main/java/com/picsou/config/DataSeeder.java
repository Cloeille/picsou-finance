package com.picsou.config;

import com.picsou.service.SetupService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * Creates the bootstrap admin from {@code APP_USERNAME} / {@code APP_PASSWORD_HASH}
 * env vars when both are set — the historical self-hosted path.
 *
 * When either is blank the seeder is a no-op: the app boots in PENDING_ADMIN
 * state and the web setup wizard at /setup takes over.
 */
@Component
public class DataSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DataSeeder.class);

    private final SetupService setupService;
    private final SsoProperties ssoProperties;

    @Value("${app.user.username:}")
    private String username;

    @Value("${app.user.password-hash:}")
    private String passwordHash;

    public DataSeeder(SetupService setupService, SsoProperties ssoProperties) {
        this.setupService = setupService;
        this.ssoProperties = ssoProperties;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (username.isBlank() || passwordHash.isBlank()) {
            log.info("APP_USERNAME or APP_PASSWORD_HASH not set — skipping auto-seed, setup wizard will take over");
            return;
        }
        if (ssoProperties.adminGroupConfigured()) {
            log.warn("APP_USERNAME and APP_PASSWORD_HASH are set together with SSO_ADMIN_GROUP; the environment admin wins");
        }
        setupService.seedAdmin(username, passwordHash, username, null);
        setupService.markComplete();
    }
}

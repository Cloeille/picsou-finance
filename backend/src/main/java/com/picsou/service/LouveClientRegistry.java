package com.picsou.service;

import com.picsou.adapter.LouveOAuthClient;
import com.picsou.model.LouveOAuthClientRegistration;
import com.picsou.repository.LouveOAuthClientRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;

/**
 * One Louve Invest client registration per redirect URI, made on first use. A new public origin
 * means a new redirect URI, hence a new registration; the old one stays for the sessions that
 * still refresh with its client id. {@link #forget} drops one Louve stopped recognising.
 */
@Service
public class LouveClientRegistry {

    private static final Logger log = LoggerFactory.getLogger(LouveClientRegistry.class);

    private final LouveOAuthClientRepository repository;
    private final LouveOAuthClient oauth;
    private final TransactionTemplate tx;
    private final Clock clock;

    public LouveClientRegistry(
        LouveOAuthClientRepository repository, LouveOAuthClient oauth, TransactionTemplate tx, Clock clock
    ) {
        this.repository = repository;
        this.oauth = oauth;
        this.tx = tx;
        this.clock = clock;
    }

    /**
     * The stored client id for {@code redirectUri}, registering one when there is none. The
     * registration call runs outside any transaction; two registrations racing for the same URI
     * keep the first one stored.
     */
    public synchronized String clientIdFor(String redirectUri) {
        return repository.findByRedirectUri(redirectUri)
            .map(LouveOAuthClientRegistration::getClientId)
            .orElseGet(() -> register(redirectUri));
    }

    /**
     * Drops the registration of {@code redirectUri} if it still holds the refused client id: a
     * newer registration (another member reconnected first) is kept, and a second instance
     * forgetting the same client finds 0 rows instead of failing.
     */
    public synchronized void forget(String redirectUri, String refusedClientId) {
        if (redirectUri == null || refusedClientId == null || refusedClientId.isBlank()) {
            return;
        }
        int[] removed = {0};
        tx.executeWithoutResult(status -> removed[0] = repository.deleteRegistration(redirectUri, refusedClientId));
        if (removed[0] > 0) {
            log.info("Dropped the Louve Invest client registration Louve no longer recognises");
        }
    }

    private String register(String redirectUri) {
        String clientId = oauth.register(redirectUri);
        try {
            tx.executeWithoutResult(status -> repository.saveAndFlush(LouveOAuthClientRegistration.builder()
                .redirectUri(redirectUri).clientId(clientId).registeredAt(clock.instant()).build()));
            log.info("Registered Picsou as a Louve Invest client");
            return clientId;
        } catch (DataIntegrityViolationException ex) {
            return repository.findByRedirectUri(redirectUri)
                .map(LouveOAuthClientRegistration::getClientId)
                .orElseThrow(() -> ex);
        }
    }
}

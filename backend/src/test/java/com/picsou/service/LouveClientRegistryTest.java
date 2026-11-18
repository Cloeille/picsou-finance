package com.picsou.service;

import com.picsou.adapter.LouveOAuthClient;
import com.picsou.model.LouveOAuthClientRegistration;
import com.picsou.repository.LouveOAuthClientRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@SuppressWarnings("unchecked")
class LouveClientRegistryTest {

    private static final String PROD = "https://picsou.example/sync/louve/callback";
    private static final String OTHER = "https://other.example/sync/louve/callback";

    private final List<LouveOAuthClientRegistration> rows = new ArrayList<>();
    private LouveOAuthClient oauth;
    private LouveClientRegistry registry;

    @BeforeEach
    void setUp() {
        oauth = mock(LouveOAuthClient.class);
        LouveOAuthClientRepository repository = mock(LouveOAuthClientRepository.class);
        when(repository.findByRedirectUri(anyString())).thenAnswer(inv -> rows.stream()
            .filter(r -> r.getRedirectUri().equals(inv.getArgument(0))).findFirst());
        when(repository.saveAndFlush(any())).thenAnswer(inv -> {
            rows.add(inv.getArgument(0));
            return inv.getArgument(0);
        });
        when(repository.deleteRegistration(anyString(), anyString()))
            .thenAnswer(inv -> rows.removeIf(r -> r.getRedirectUri().equals(inv.getArgument(0))
                && r.getClientId().equals(inv.getArgument(1))) ? 1 : 0);
        TransactionTemplate tx = mock(TransactionTemplate.class);
        doAnswer(inv -> {
            ((Consumer<TransactionStatus>) inv.getArgument(0)).accept(mock(TransactionStatus.class));
            return null;
        }).when(tx).executeWithoutResult(any());
        when(oauth.register(PROD)).thenReturn("client-prod", "client-prod-2");
        when(oauth.register(OTHER)).thenReturn("client-other");
        registry = new LouveClientRegistry(repository, oauth, tx,
            Clock.fixed(Instant.parse("2026-11-17T08:00:00Z"), ZoneOffset.UTC));
    }

    @Test
    void registersOncePerRedirectUri() {
        assertThat(registry.clientIdFor(PROD)).isEqualTo("client-prod");
        assertThat(registry.clientIdFor(PROD)).isEqualTo("client-prod");
        assertThat(registry.clientIdFor(OTHER)).isEqualTo("client-other");

        verify(oauth, times(1)).register(PROD);
        assertThat(rows).extracting(LouveOAuthClientRegistration::getRedirectUri).containsExactly(PROD, OTHER);
        assertThat(Optional.of(rows.get(0).getRegisteredAt())).contains(Instant.parse("2026-11-17T08:00:00Z"));
    }

    @Test
    void aForgottenRegistrationIsMadeAgain() {
        registry.clientIdFor(PROD);

        registry.forget(PROD, "client-prod");

        assertThat(registry.clientIdFor(PROD)).isEqualTo("client-prod-2");
        verify(oauth, times(2)).register(PROD);
    }

    @Test
    void forgettingAStaleClientIdKeepsTheNewerRegistration() {
        registry.clientIdFor(PROD);
        registry.forget(PROD, "client-prod");
        registry.clientIdFor(PROD); // client-prod-2, made by another member's reconnect

        registry.forget(PROD, "client-prod"); // a late refusal of the old id

        assertThat(registry.clientIdFor(PROD)).isEqualTo("client-prod-2");
        verify(oauth, times(2)).register(PROD);
    }
}

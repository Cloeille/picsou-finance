package com.picsou.service;

import com.picsou.adapter.LouveOAuthClient;
import com.picsou.model.AppSetting;
import com.picsou.repository.AppSettingRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** The registry's rules on an in-memory app_setting; the SQL itself is covered by the Postgres test. */
@SuppressWarnings("unchecked")
class LouveClientRegistryTest {

    private static final String PROD = "https://picsou.example/sync/louve/callback";
    private static final String OTHER = "https://other.example/sync/louve/callback";

    private final Map<String, String> rows = new LinkedHashMap<>();
    private LouveOAuthClient oauth;
    private LouveClientRegistry registry;

    @BeforeEach
    void setUp() {
        oauth = mock(LouveOAuthClient.class);
        AppSettingRepository settings = mock(AppSettingRepository.class);
        when(settings.findByKey(anyString())).thenAnswer(inv -> Optional.ofNullable(rows.get(inv.<String>getArgument(0)))
            .map(value -> AppSetting.builder().key(inv.getArgument(0)).value(value).build()));
        when(settings.insertIfAbsent(anyString(), anyString()))
            .thenAnswer(inv -> rows.putIfAbsent(inv.getArgument(0), inv.getArgument(1)) == null ? 1 : 0);
        when(settings.deleteIfValue(anyString(), anyString()))
            .thenAnswer(inv -> rows.remove(inv.<String>getArgument(0), inv.<String>getArgument(1)) ? 1 : 0);
        TransactionTemplate tx = mock(TransactionTemplate.class);
        when(tx.execute(any())).thenAnswer(inv -> ((TransactionCallback<Object>) inv.getArgument(0)).doInTransaction(null));
        when(oauth.register(PROD)).thenReturn("client-prod", "client-prod-2");
        when(oauth.register(OTHER)).thenReturn("client-other");
        registry = new LouveClientRegistry(settings, oauth, tx);
    }

    @Test
    void registersOncePerRedirectUri() {
        assertThat(registry.clientIdFor(PROD)).isEqualTo("client-prod");
        assertThat(registry.clientIdFor(PROD)).isEqualTo("client-prod");
        assertThat(registry.clientIdFor(OTHER)).isEqualTo("client-other");

        verify(oauth, times(1)).register(PROD);
        assertThat(rows).containsExactly(
            Map.entry(LouveClientRegistry.key(PROD), "client-prod"),
            Map.entry(LouveClientRegistry.key(OTHER), "client-other"));
    }

    @Test
    void theKeyFitsTheSettingKeyColumnWhateverTheRedirectUri() {
        String longUri = "https://" + "a".repeat(240) + ".example/sync/louve/callback";

        assertThat(LouveClientRegistry.key(longUri)).startsWith(LouveClientRegistry.KEY_PREFIX).hasSize(80)
            .isNotEqualTo(LouveClientRegistry.key(PROD));
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

    @Test
    void aRegistrationStoredMeanwhileWins() {
        when(oauth.register(OTHER)).thenAnswer(inv -> {
            rows.put(LouveClientRegistry.key(OTHER), "client-first"); // another instance stored its own
            return "client-late";
        });

        assertThat(registry.clientIdFor(OTHER)).isEqualTo("client-first");
        assertThat(rows).containsEntry(LouveClientRegistry.key(OTHER), "client-first");
    }
}

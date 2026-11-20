package com.picsou.service;

import com.picsou.adapter.LouveOAuthClient;
import com.picsou.model.AppSetting;
import com.picsou.repository.AppSettingRepository;
import com.picsou.support.PostgresContainers;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * The Louve client registry on the real app_setting table: the hashed key fits
 * {@code setting_key VARCHAR(100)}, the insert keeps the first registration stored, and the
 * forget step is a compare-and-delete that never removes a newer registration.
 */
@DataJpaTest(properties = {"spring.jpa.hibernate.ddl-auto=validate", "spring.jpa.show-sql=false"})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers(disabledWithoutDocker = true)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Import({LouveClientRegistry.class, LouveClientRegistryPostgresTest.Beans.class})
class LouveClientRegistryPostgresTest {

    private static final String LONG_REDIRECT =
        "https://" + "a".repeat(200) + ".example/sync/louve/callback";
    private static final String REDIRECT = "https://picsou.example/sync/louve/callback";

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = PostgresContainers.newContainer();

    @TestConfiguration
    static class Beans {
        @Bean TransactionTemplate transactionTemplate(PlatformTransactionManager manager) {
            return new TransactionTemplate(manager);
        }
    }

    @Autowired LouveClientRegistry registry;
    @Autowired AppSettingRepository settings;
    @Autowired TransactionTemplate tx;
    @MockitoBean LouveOAuthClient oauth;

    private String stored(String redirectUri) {
        return settings.findByKey(LouveClientRegistry.key(redirectUri)).map(AppSetting::getValue).orElse(null);
    }

    @Test
    void aRedirectUriLongerThanTheKeyColumnIsStoredOnce() {
        when(oauth.register(LONG_REDIRECT)).thenReturn("client-long", "client-unexpected");

        assertThat(registry.clientIdFor(LONG_REDIRECT)).isEqualTo("client-long");
        assertThat(registry.clientIdFor(LONG_REDIRECT)).isEqualTo("client-long");
        assertThat(stored(LONG_REDIRECT)).isEqualTo("client-long");
    }

    @Test
    void aRegistrationStoredByAnotherInstanceMeanwhileWins() {
        String redirect = "https://race.example/sync/louve/callback";
        when(oauth.register(redirect)).thenAnswer(inv -> {
            tx.execute(status -> settings.insertIfAbsent(LouveClientRegistry.key(redirect), "client-first"));
            return "client-late";
        });

        assertThat(registry.clientIdFor(redirect)).isEqualTo("client-first");
        assertThat(stored(redirect)).isEqualTo("client-first");
    }

    @Test
    void forgettingRemovesOnlyTheRefusedClientAndNeverFailsTwice() {
        when(oauth.register(REDIRECT)).thenReturn("client-old", "client-new");
        registry.clientIdFor(REDIRECT);

        registry.forget(REDIRECT, "client-other");
        assertThat(stored(REDIRECT)).isEqualTo("client-old");

        registry.forget(REDIRECT, "client-old");
        registry.forget(REDIRECT, "client-old"); // a second instance forgetting the same client
        assertThat(stored(REDIRECT)).isNull();

        registry.clientIdFor(REDIRECT);
        registry.forget(REDIRECT, "client-old"); // a late refusal of the old id
        assertThat(stored(REDIRECT)).isEqualTo("client-new");
    }
}

package com.picsou.config;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.settings.OAuth2TokenFormat;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class IosClientSeedRunnerTest {

    private final AuthorizationServerConfig config = new AuthorizationServerConfig();

    @Test
    void firstBoot_insertsTheClientFromConfiguration() throws Exception {
        RegisteredClient seeded = seed(new OAuthClientProperties());

        assertThat(seeded.getClientId()).isEqualTo("picsou-ios");
        assertThat(seeded.getRedirectUris()).containsExactly("picsou://callback");
        assertThat(seeded.getTokenSettings().getAccessTokenTimeToLive()).isEqualTo(Duration.ofMinutes(15));
        assertThat(seeded.getTokenSettings().getRefreshTokenTimeToLive()).isEqualTo(Duration.ofDays(30));
    }

    @Test
    void restartWithUnchangedConfiguration_writesNothing() throws Exception {
        RegisteredClient existing = seed(new OAuthClientProperties());
        RegisteredClientRepository repo = repoHolding(existing);

        runner(repo, new OAuthClientProperties()).run(null);

        verify(repo, never()).save(any());
    }

    @Test
    void restartWithChangedConfiguration_updatesTheExistingRowInPlace() throws Exception {
        RegisteredClient existing = seed(new OAuthClientProperties());
        RegisteredClientRepository repo = repoHolding(existing);
        OAuthClientProperties props = new OAuthClientProperties();
        props.setRedirectUri("myfork://callback");
        props.setAccessTokenTtlMinutes(5);
        props.setRefreshTokenTtlDays(7);

        runner(repo, props).run(null);

        ArgumentCaptor<RegisteredClient> saved = ArgumentCaptor.forClass(RegisteredClient.class);
        verify(repo).save(saved.capture());
        RegisteredClient updated = saved.getValue();
        assertThat(updated.getId()).isEqualTo(existing.getId());
        assertThat(updated.getClientId()).isEqualTo("picsou-ios");
        assertThat(updated.getRedirectUris()).containsExactly("myfork://callback");
        assertThat(updated.getTokenSettings().getAccessTokenTimeToLive()).isEqualTo(Duration.ofMinutes(5));
        assertThat(updated.getTokenSettings().getRefreshTokenTimeToLive()).isEqualTo(Duration.ofDays(7));
        assertThat(updated.getTokenSettings().isReuseRefreshTokens()).isFalse();
        assertThat(updated.getTokenSettings().getAccessTokenFormat()).isEqualTo(OAuth2TokenFormat.SELF_CONTAINED);
        assertThat(updated.getScopes()).containsExactlyInAnyOrder("read", "write");
        assertThat(updated.getClientSettings().isRequireProofKey()).isTrue();
    }

    private RegisteredClient seed(OAuthClientProperties props) throws Exception {
        RegisteredClientRepository repo = mock(RegisteredClientRepository.class);
        runner(repo, props).run(null);
        ArgumentCaptor<RegisteredClient> saved = ArgumentCaptor.forClass(RegisteredClient.class);
        verify(repo).save(saved.capture());
        return saved.getValue();
    }

    private static RegisteredClientRepository repoHolding(RegisteredClient client) {
        RegisteredClientRepository repo = mock(RegisteredClientRepository.class);
        when(repo.findByClientId(client.getClientId())).thenReturn(client);
        return repo;
    }

    private ApplicationRunner runner(RegisteredClientRepository repo, OAuthClientProperties props) {
        return config.seedIosClientRunner(repo, props);
    }
}

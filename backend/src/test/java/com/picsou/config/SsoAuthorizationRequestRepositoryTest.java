package com.picsou.config;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;

import static org.assertj.core.api.Assertions.assertThat;

class SsoAuthorizationRequestRepositoryTest {
    private final SsoAuthorizationRequestRepository repository = new SsoAuthorizationRequestRepository();
    private final MockHttpServletRequest request = new MockHttpServletRequest();
    private final MockHttpServletResponse response = new MockHttpServletResponse();
    private final OAuth2AuthorizationRequest authorization = OAuth2AuthorizationRequest.authorizationCode()
        .authorizationUri("https://idp.test/authorize").clientId("picsou").state("expected").build();

    @Test
    void matchedRemovalExposesRequestForHandlersAndPreventsReplay() {
        repository.saveAuthorizationRequest(authorization, request, response);
        request.addParameter("state", "expected");
        assertThat(repository.loadAuthorizationRequest(request)).isSameAs(authorization);
        assertThat(repository.removeAuthorizationRequest(request, response)).isSameAs(authorization);
        assertThat(SsoAuthorizationRequestRepository.removedRequest(request)).contains(authorization);
        var replay = new MockHttpServletRequest();
        replay.setSession(request.getSession(false));
        replay.addParameter("state", "expected");
        assertThat(repository.removeAuthorizationRequest(replay, response)).isNull();
        assertThat(SsoAuthorizationRequestRepository.removedRequest(replay)).isEmpty();
    }

    @Test
    void wrongStateCannotExposeOrConsumeTheStoredRequest() {
        repository.saveAuthorizationRequest(authorization, request, response);
        request.addParameter("state", "wrong");
        assertThat(repository.removeAuthorizationRequest(request, response)).isNull();
        assertThat(SsoAuthorizationRequestRepository.removedRequest(request)).isEmpty();
        request.setParameter("state", "expected");
        assertThat(repository.loadAuthorizationRequest(request)).isSameAs(authorization);
    }

    @Test
    void missingSessionDoesNotCreateOne() {
        assertThat(repository.removeAuthorizationRequest(request, response)).isNull();
        assertThat(SsoAuthorizationRequestRepository.removedRequest(request)).isEmpty();
        assertThat(request.getSession(false)).isNull();
    }
}

package com.picsou.config;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Optional;
import org.springframework.security.oauth2.client.web.AuthorizationRequestRepository;
import org.springframework.security.oauth2.client.web.HttpSessionOAuth2AuthorizationRequestRepository;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;

public class SsoAuthorizationRequestRepository implements AuthorizationRequestRepository<OAuth2AuthorizationRequest> {
    public static final String REMOVED_REQUEST_ATTRIBUTE = SsoAuthorizationRequestRepository.class.getName() + ".REMOVED";
    private final HttpSessionOAuth2AuthorizationRequestRepository delegate = new HttpSessionOAuth2AuthorizationRequestRepository();

    @Override
    public OAuth2AuthorizationRequest loadAuthorizationRequest(HttpServletRequest request) {
        return delegate.loadAuthorizationRequest(request);
    }

    @Override
    public void saveAuthorizationRequest(OAuth2AuthorizationRequest authorizationRequest,
                                         HttpServletRequest request, HttpServletResponse response) {
        delegate.saveAuthorizationRequest(authorizationRequest, request, response);
    }

    @Override
    public OAuth2AuthorizationRequest removeAuthorizationRequest(HttpServletRequest request, HttpServletResponse response) {
        OAuth2AuthorizationRequest removed = delegate.removeAuthorizationRequest(request, response);
        request.setAttribute(REMOVED_REQUEST_ATTRIBUTE, removed);
        return removed;
    }

    public static Optional<OAuth2AuthorizationRequest> removedRequest(HttpServletRequest request) {
        return Optional.ofNullable((OAuth2AuthorizationRequest) request.getAttribute(REMOVED_REQUEST_ATTRIBUTE));
    }
}

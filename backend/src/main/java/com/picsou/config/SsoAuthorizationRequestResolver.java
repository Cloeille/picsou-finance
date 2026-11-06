package com.picsou.config;

import com.picsou.service.sso.SsoIntent;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.web.DefaultOAuth2AuthorizationRequestResolver;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestResolver;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;

public class SsoAuthorizationRequestResolver implements OAuth2AuthorizationRequestResolver {
    private final DefaultOAuth2AuthorizationRequestResolver login;
    private final DefaultOAuth2AuthorizationRequestResolver reauth;

    public SsoAuthorizationRequestResolver(ClientRegistrationRepository repository) {
        login = new DefaultOAuth2AuthorizationRequestResolver(repository, "/oauth2/sso/authorization");
        reauth = new DefaultOAuth2AuthorizationRequestResolver(repository, "/oauth2/sso/authorization");
        reauth.setAuthorizationRequestCustomizer(b -> b.additionalParameters(p -> {
            p.put("prompt", "login");
            p.put("max_age", "0");
        }));
    }

    @Override
    public OAuth2AuthorizationRequest resolve(HttpServletRequest request) {
        if (!(request.getAttribute(SsoIntent.ATTRIBUTE) instanceof SsoIntent intent)) {
            return null;
        }
        return withIntent(delegate(intent).resolve(request), intent);
    }

    @Override
    public OAuth2AuthorizationRequest resolve(HttpServletRequest request, String registrationId) {
        if (!(request.getAttribute(SsoIntent.ATTRIBUTE) instanceof SsoIntent intent)) {
            return null;
        }
        return withIntent(delegate(intent).resolve(request, registrationId), intent);
    }

    private DefaultOAuth2AuthorizationRequestResolver delegate(SsoIntent intent) {
        return switch (intent.kind()) {
            case REAUTH -> reauth;
            case LOGIN, LINK, ACTIVATE, SETUP -> login;
        };
    }

    private static OAuth2AuthorizationRequest withIntent(OAuth2AuthorizationRequest result, SsoIntent intent) {
        return result == null ? null : OAuth2AuthorizationRequest.from(result)
            .attributes(a -> a.put(SsoIntent.ATTRIBUTE, intent)).build();
    }
}

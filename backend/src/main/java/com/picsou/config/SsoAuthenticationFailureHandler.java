package com.picsou.config;

import com.picsou.service.sso.SsoIntent;
import com.picsou.util.LogSanitizer;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.io.IOException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Conditional;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.web.authentication.AuthenticationFailureHandler;
import org.springframework.stereotype.Component;

@Component
@Conditional(SsoEnabledCondition.class)
public class SsoAuthenticationFailureHandler implements AuthenticationFailureHandler {
    private static final Logger log = LoggerFactory.getLogger(SsoAuthenticationFailureHandler.class);

    @Override
    public void onAuthenticationFailure(HttpServletRequest request, HttpServletResponse response,
                                         AuthenticationException exception) throws IOException {
        String code = "IDP_ERROR";
        if (exception instanceof OAuth2AuthenticationException oauth
                && "authorization_request_not_found".equals(oauth.getError().getErrorCode())) {
            code = "SESSION_EXPIRED";
        } else {
            log.warn("SSO authentication failed: {}", LogSanitizer.safe(exception.getMessage()));
        }
        SsoIntent intent = SsoAuthorizationRequestRepository.removedRequest(request)
            .map(authorization -> authorization.<SsoIntent>getAttribute(SsoIntent.ATTRIBUTE)).orElse(null);
        HttpSession session = request.getSession(false);
        if (session != null) {
            session.invalidate();
        }
        response.sendRedirect(intent == null ? SsoIntent.loginError(code) : intent.errorRedirect(code));
    }
}

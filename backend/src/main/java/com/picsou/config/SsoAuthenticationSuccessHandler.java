package com.picsou.config;

import com.picsou.service.sso.SsoIntent;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.io.IOException;
import org.springframework.context.annotation.Conditional;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.stereotype.Component;

@Component
@Conditional(SsoEnabledCondition.class)
public class SsoAuthenticationSuccessHandler implements AuthenticationSuccessHandler {
    @Override
    public void onAuthenticationSuccess(HttpServletRequest request, HttpServletResponse response,
                                         Authentication authentication) throws IOException {
        String target = SsoAuthorizationRequestRepository.removedRequest(request)
            .map(authorization -> authorization.<SsoIntent>getAttribute(SsoIntent.ATTRIBUTE))
            .map(intent -> intent.errorRedirect("IDP_ERROR"))
            .orElseGet(() -> SsoIntent.loginError("SESSION_EXPIRED"));
        HttpSession session = request.getSession(false);
        if (session != null) {
            session.invalidate();
        }
        response.sendRedirect(target);
    }
}

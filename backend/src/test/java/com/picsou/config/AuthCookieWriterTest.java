package com.picsou.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AuthCookieWriterTest {

    @Mock SecureCookieProvider secureCookieProvider;
    @Mock JwtUtil jwtUtil;

    @Test
    void setAccessAndRefresh_omitsMaxAge_whenNotPersistent() {
        AuthCookieWriter writer = new AuthCookieWriter(secureCookieProvider, jwtUtil);
        MockHttpServletResponse response = new MockHttpServletResponse();

        writer.setAccessAndRefresh(response, "acc", "ref", false);

        List<String> cookies = response.getHeaders("Set-Cookie");
        assertThat(cookies).hasSize(2);
        // A non-"Remember Me" login must not survive the browser closing: no Max-Age
        // attribute at all means the browser treats it as a session cookie.
        assertThat(cookies).allSatisfy(c -> assertThat(c).doesNotContain("Max-Age"));
        assertThat(cookies).anySatisfy(c -> assertThat(c).contains("access_token=acc"));
        assertThat(cookies).anySatisfy(c -> assertThat(c).contains("refresh_token=ref"));
    }

    @Test
    void setAccessAndRefresh_setsMaxAge_whenPersistent() {
        when(jwtUtil.getAccessExpirySeconds()).thenReturn(900L);
        when(jwtUtil.getRefreshExpirySeconds()).thenReturn(604_800L);
        AuthCookieWriter writer = new AuthCookieWriter(secureCookieProvider, jwtUtil);
        MockHttpServletResponse response = new MockHttpServletResponse();

        writer.setAccessAndRefresh(response, "acc", "ref", true);

        List<String> cookies = response.getHeaders("Set-Cookie");
        assertThat(cookies).anySatisfy(c -> assertThat(c).contains("access_token=acc").contains("Max-Age=900"));
        assertThat(cookies).anySatisfy(c -> assertThat(c).contains("refresh_token=ref").contains("Max-Age=604800"));
    }

    @Test
    void addCookie_stillSetsExplicitMaxAgeZero_whenClearing() {
        lenient().when(secureCookieProvider.isSecure()).thenReturn(false);
        AuthCookieWriter writer = new AuthCookieWriter(secureCookieProvider, jwtUtil);
        MockHttpServletResponse response = new MockHttpServletResponse();

        writer.clearAuthCookies(response);

        List<String> cookies = response.getHeaders("Set-Cookie");
        assertThat(cookies).isNotEmpty();
        assertThat(cookies).allSatisfy(c -> assertThat(c).contains("Max-Age=0"));
    }

    @Test
    void setReauthProof_writesTheCookieWithTheProofLifetimeAndStandardAttributes() {
        when(jwtUtil.getReauthProofExpirySeconds()).thenReturn(300L);
        AuthCookieWriter writer = new AuthCookieWriter(secureCookieProvider, jwtUtil);
        MockHttpServletResponse response = new MockHttpServletResponse();

        writer.setReauthProof(response, "proof");

        assertThat(response.getHeaders("Set-Cookie")).singleElement()
            .satisfies(c -> assertThat(c)
                .contains("reauth_proof=proof")
                .contains("Max-Age=300")
                .contains("Path=/")
                .contains("HttpOnly")
                .contains("SameSite=Lax"));
    }

    @Test
    void clearReauthProof_expiresTheCookie() {
        AuthCookieWriter writer = new AuthCookieWriter(secureCookieProvider, jwtUtil);
        MockHttpServletResponse response = new MockHttpServletResponse();

        writer.clearReauthProof(response);

        assertThat(response.getHeaders("Set-Cookie")).singleElement()
            .satisfies(c -> assertThat(c).contains("reauth_proof=").contains("Max-Age=0"));
    }

    @Test
    void clearAuthCookies_alsoClearsReauthProof() {
        // logout runs clearAuthCookies: the re-authentication proof must die with the session.
        AuthCookieWriter writer = new AuthCookieWriter(secureCookieProvider, jwtUtil);
        MockHttpServletResponse response = new MockHttpServletResponse();

        writer.clearAuthCookies(response);

        assertThat(response.getHeaders("Set-Cookie"))
            .anySatisfy(c -> assertThat(c).contains("reauth_proof=").contains("Max-Age=0"));
    }
}

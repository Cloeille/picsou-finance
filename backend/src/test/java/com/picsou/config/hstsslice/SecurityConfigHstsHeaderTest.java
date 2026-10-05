package com.picsou.config.hstsslice;

import com.picsou.config.AuthCookieWriter;
import com.picsou.config.JwtTokenAuthenticator;
import com.picsou.config.JwtUtil;
import com.picsou.config.SecurityConfig;
import com.picsou.config.SetupFilter;
import com.picsou.mcp.AccessKeyService;
import com.picsou.repository.AppSettingRepository;
import com.picsou.repository.AppUserRepository;
import com.picsou.service.MfaService;
import com.picsou.service.PersistentSessionService;
import io.github.bucket4j.Bucket;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Verifies that the {@code app.hsts-enabled} gate actually controls whether Spring Security
 * <em>emits</em> {@code Strict-Transport-Security}, not merely how the flag is parsed.
 *
 * <p>{@link SecurityConfigHstsParsingTest} covers the parsing and pins it against
 * {@code docker/entrypoint.sh}. This test covers the other half: that {@code hsts.disable()} is
 * really wired to the flag. An inverted condition, or a dropped {@code disable()} call, would leave
 * every HTTPS response pinning HSTS for a year — on a locally-issued certificate that is the
 * browser lockout the whole feature exists to prevent, and no parsing test would catch it.
 *
 * <p>Requests are made with {@code .secure(true)} because Spring Security's {@code HstsHeaderWriter}
 * only fires when {@code request.isSecure()} is true. In production that comes from
 * {@code X-Forwarded-Proto: https} via {@code forward-headers-strategy: framework}.
 */
class SecurityConfigHstsHeaderTest {

    /** {@code /api/setup/**} is {@code permitAll}, so this needs no authentication. */
    @RestController
    static class ProbeController {
        @GetMapping("/api/setup/hsts-probe")
        String probe() {
            return "ok";
        }
    }

    /** Collaborators of the {@code filterChain} bean — none are exercised by this test. */
    abstract static class Fixture {
        @MockitoBean JwtUtil jwtUtil;
        @MockitoBean JwtTokenAuthenticator jwtTokenAuthenticator;
        @MockitoBean AppUserRepository appUserRepository;
        @MockitoBean SetupFilter setupFilter;
        @MockitoBean PersistentSessionService persistentSessionService;
        @MockitoBean AuthCookieWriter authCookieWriter;
        @MockitoBean MfaService mfaService;
        @MockitoBean AccessKeyService accessKeyService;
        @MockitoBean AppSettingRepository appSettingRepository;
        @MockitoBean @Qualifier("mcpKeyBuckets") Map<Long, Bucket> mcpKeyBuckets;

        @Autowired MockMvc mvc;

        /**
         * A mocked Filter does nothing by default, which would swallow the request. Make it pass
         * through so the chain reaches the controller and the header writers run.
         */
        @BeforeEach
        void passThroughSetupFilter() throws Exception {
            doAnswer(inv -> {
                ServletRequest req = inv.getArgument(0);
                ServletResponse res = inv.getArgument(1);
                FilterChain chain = inv.getArgument(2);
                chain.doFilter(req, res);
                return null;
            }).when(setupFilter).doFilter(any(), any(), any());
        }
    }

    @Nested
    @WebMvcTest(controllers = ProbeController.class)
    @Import({SecurityConfig.class, ProbeController.class})
    @TestPropertySource(properties = "app.hsts-enabled=true")
    class WhenEnabled extends Fixture {

        @Test
        void sendsStrictTransportSecurityOverHttps() throws Exception {
            mvc.perform(get("/api/setup/hsts-probe").secure(true))
                .andExpect(status().isOk())
                .andExpect(header().string("Strict-Transport-Security",
                    org.hamcrest.Matchers.containsString("max-age=31536000")));
        }

        /** HSTS over plain HTTP is meaningless; Spring Security omits it and so should we. */
        @Test
        void doesNotSendItOverPlainHttp() throws Exception {
            mvc.perform(get("/api/setup/hsts-probe").secure(false))
                .andExpect(status().isOk())
                .andExpect(header().doesNotExist("Strict-Transport-Security"));
        }
    }

    @Nested
    @WebMvcTest(controllers = ProbeController.class)
    @Import({SecurityConfig.class, ProbeController.class})
    @TestPropertySource(properties = "app.hsts-enabled=false")
    class WhenDisabled extends Fixture {

        @Test
        void omitsStrictTransportSecurityEvenOverHttps() throws Exception {
            mvc.perform(get("/api/setup/hsts-probe").secure(true))
                .andExpect(status().isOk())
                .andExpect(header().doesNotExist("Strict-Transport-Security"));
        }
    }

    /** An unrecognised value must behave exactly like disabled — never crash the context. */
    @Nested
    @WebMvcTest(controllers = ProbeController.class)
    @Import({SecurityConfig.class, ProbeController.class})
    @TestPropertySource(properties = "app.hsts-enabled=enabled")
    class WhenUnrecognised extends Fixture {

        @Test
        void bootsAndOmitsTheHeader() throws Exception {
            mvc.perform(get("/api/setup/hsts-probe").secure(true))
                .andExpect(status().isOk())
                .andExpect(header().doesNotExist("Strict-Transport-Security"));
        }
    }
}

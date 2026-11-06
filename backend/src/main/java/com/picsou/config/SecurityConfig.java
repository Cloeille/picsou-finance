package com.picsou.config;

import com.picsou.mcp.AccessKeyService;
import com.picsou.repository.AppSettingRepository;
import com.picsou.repository.AppUserRepository;
import com.picsou.service.MfaService;
import com.picsou.service.PersistentSessionService;
import io.github.bucket4j.Bucket;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.access.AccessDeniedHandlerImpl;
import org.springframework.security.web.access.DelegatingAccessDeniedHandler;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.context.RequestAttributeSecurityContextRepository;
import org.springframework.security.web.csrf.CsrfException;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.csrf.CsrfTokenRepository;
import org.springframework.security.web.csrf.DefaultCsrfToken;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter;
import org.springframework.security.web.util.matcher.AntPathRequestMatcher;
import org.springframework.security.web.util.matcher.AnyRequestMatcher;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.configurers.HeadersConfigurer;
import org.springframework.security.web.header.writers.DelegatingRequestMatcherHeaderWriter;
import org.springframework.security.web.header.writers.StaticHeadersWriter;
import org.springframework.security.web.header.writers.CacheControlHeadersWriter;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.filter.CorsFilter;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Value("${app.cors.allowed-origins:}")
    private String allowedOrigins;

    static final String CONTENT_SECURITY_POLICY = "default-src 'self'; script-src 'self'; "
        + "style-src 'self' 'unsafe-inline'; img-src 'self' data: https:; font-src 'self'; "
        + "connect-src 'self'; frame-ancestors 'none'";

    /** GET and HEAD outside the backend prefixes: the SPA's files and client-side routes. */
    private static final RequestMatcher SPA_READ = request ->
        ("GET".equals(request.getMethod()) || "HEAD".equals(request.getMethod()))
            && !SpaPaths.isBackend(SpaPaths.requestPath(request));

    static final String PERMISSIONS_POLICY = "camera=(), microphone=(), geolocation=()";

    private static final Logger log = LoggerFactory.getLogger(SecurityConfig.class);

    /**
     * Whether to send {@code Strict-Transport-Security}. Off by default: Spring is the only
     * emitter now that no proxy in the image touches headers, for the SPA, {@code /api} and the
     * OAuth2 authorization-server chain alike (both chains use {@link #securityHeaders}).
     *
     * <p>Gating this matters because Spring Security's {@code HstsHeaderWriter} fires on any
     * request where {@code isSecure()} is true, and {@code forward-headers-strategy: native}
     * makes that true from {@code X-Forwarded-Proto: https} sent by a configured proxy. Without the gate, every
     * {@code /api/*} response carries HSTS, and the SPA calls the API on load, so the browser
     * pins the policy on the first page view. With a locally-issued certificate that is a
     * lockout: the browser then refuses the "proceed anyway" bypass and there is no in-app
     * recovery.
     *
     * <p>Bound as a {@code String} and parsed leniently on purpose. A primitive {@code boolean}
     * would make Spring fail field injection on any value it cannot convert, including a bare
     * {@code HSTS_ENABLED=}, which takes the whole backend down. An unrecognised value disables
     * HSTS and logs one warning at startup ({@link #warnOnUnrecognisedHsts}).
     */
    @Value("${app.hsts-enabled:false}")
    private String hstsEnabledRaw;

    /** The truthy tokens accepted for {@code HSTS_ENABLED}. Case-insensitive, trimmed. */
    static final java.util.Set<String> HSTS_TRUTHY = java.util.Set.of("true", "1", "yes", "on");

    private static final java.util.Set<String> HSTS_FALSY = java.util.Set.of("false", "0", "no", "off", "");

    /** Lenient, case-insensitive, surrounding-whitespace-tolerant. */
    static boolean parseHstsEnabled(String raw) {
        return raw != null && HSTS_TRUTHY.contains(raw.trim().toLowerCase());
    }

    /** True when the value is neither a truthy token nor an explicit "off" spelling. */
    static boolean isUnrecognisedHsts(String raw) {
        return raw != null && !parseHstsEnabled(raw) && !HSTS_FALSY.contains(raw.trim().toLowerCase());
    }

    @PostConstruct
    void warnOnUnrecognisedHsts() {
        if (isUnrecognisedHsts(hstsEnabledRaw)) {
            log.warn("HSTS_ENABLED='{}' not understood, HSTS disabled.", hstsEnabledRaw);
        }
    }

    // @Order(2): the catch-all API chain. The OAuth2 authorization-server chain
    // (AuthorizationServerConfig, @Order(1)) matches /oauth2/** ahead of this one; every other
    // request falls through to here. This chain is otherwise unchanged from the cookie-only design.
    @Bean
    @Order(2)
    public SecurityFilterChain filterChain(HttpSecurity http,
                                           JwtUtil jwtUtil,
                                           JwtTokenAuthenticator jwtTokenAuthenticator,
                                           AppUserRepository appUserRepository,
                                           SetupFilter setupFilter,
                                           PersistentSessionService persistentSessionService,
                                           AuthCookieWriter authCookieWriter,
                                           MfaService mfaService,
                                           AccessKeyService accessKeyService,
                                           @Qualifier("mcpKeyBuckets") Map<Long, Bucket> mcpKeyBuckets,
                                           CorsConfigurationSource corsConfigurationSource,
                                           SsoProperties ssoProperties, java.time.Clock clock,
                                           Customizer<HeadersConfigurer<HttpSecurity>> securityHeaders) throws Exception {
        // The chain is stateless, so no session ever carries the MCP authentication. The
        // request-attribute repository is what reloads it on the ASYNC dispatch that completes a
        // stream; AccessKeyAuthFilter saves into this same instance.
        var securityContextRepository = new RequestAttributeSecurityContextRepository();
        http
            .cors(Customizer.withDefaults())
            // CSRF without tokens: only a cross-site, cookie-authenticated, state-changing request
            // requires protection, and the token repository never holds a token, so such a request
            // always fails the check (403). Everything else skips the filter, which keeps the chain
            // stateless (no session, no XSRF cookie) and the SPA free of token plumbing.
            .csrf(csrf -> csrf
                .requireCsrfProtectionMatcher(new CrossSiteCookieRequestMatcher(corsConfigurationSource))
                .csrfTokenRepository(new NoStoredCsrfTokenRepository()))
            // STATELESS still installs SessionManagementFilter with ChangeSessionIdAuthenticationStrategy: a
            // JWT-authenticated request (RequestAttribute repo => no saved context) carrying a JSESSIONID would
            // rotate it. That id is the SSO handshake session of the /oauth2/sso chain, so polling requests made
            // while the IdP step is open invalidated the id the callback later presents (SESSION_EXPIRED).
            // This chain never creates sessions or binds authentication to one, so there is nothing to fix.
            .sessionManagement(sm -> sm
                .sessionCreationPolicy(SessionCreationPolicy.STATELESS)
                .sessionFixation(fixation -> fixation.none()))
            .securityContext(sc -> sc.securityContextRepository(securityContextRepository))
            .headers(securityHeaders)
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/api/setup/**").permitAll()
                .requestMatchers(HttpMethod.POST, "/api/auth/login").permitAll()
                .requestMatchers(HttpMethod.POST, "/api/auth/refresh").permitAll()
                .requestMatchers(HttpMethod.POST, "/api/auth/logout").permitAll()
                .requestMatchers(HttpMethod.POST, "/api/auth/mfa/verify").permitAll()
                .requestMatchers(HttpMethod.POST, "/api/auth/activate/*").permitAll()
                .requestMatchers(HttpMethod.GET, "/api/auth/sso").permitAll()
                .requestMatchers("/actuator/health", "/actuator/health/liveness",
                    "/actuator/health/readiness", "/actuator/info").permitAll()
                // RFC 9728 protected-resource metadata (Task 7) and RFC 7591 dynamic client
                // registration (Task 8): unauthenticated by design, so a remote-MCP client can
                // discover the resource + self-register before the OAuth handshake even starts.
                // Neither is matched by the AS chain's own securityMatcher (@Order(1) above) —
                // /.well-known/oauth-protected-resource isn't an AS-native endpoint, and
                // /oauth2/register is a plain controller, not a configured clientRegistrationEndpoint
                // — so both fall through to this chain and need an explicit permitAll here.
                .requestMatchers(ProtectedResourceMetadataController.PATH).permitAll()
                .requestMatchers(HttpMethod.POST, "/oauth2/register").permitAll()
                // The SPA's static files and client-side routes, readable by anyone (the app
                // shows its own login). Everything under a backend prefix keeps its rules.
                .requestMatchers(SPA_READ).permitAll()
                .requestMatchers("/api/admin/**").hasRole("ADMIN")
                .requestMatchers("/mcp/**").authenticated()
                .anyRequest().authenticated()
            )
            // All custom filters anchor to UsernamePasswordAuthenticationFilter because
            // Spring Security's FilterOrderRegistration only knows the order of its own
            // well-known filter classes — passing a custom class as anchor throws
            // "does not have a registered order". SetupFilter returns 503/410 before
            // setup is complete; on a fresh install no JWT cookie exists anyway. The
            // PersistentTokenAuthFilter must run AFTER JwtAuthenticationFilter so an
            // active access cookie short-circuits and we don't pay the DB hit per
            // request — registration order below preserves that ordering.
            .addFilterBefore(setupFilter, UsernamePasswordAuthenticationFilter.class)
            .addFilterBefore(new JwtAuthenticationFilter(jwtTokenAuthenticator), UsernamePasswordAuthenticationFilter.class)
            .addFilterBefore(
                new PersistentTokenAuthFilter(persistentSessionService, appUserRepository, jwtUtil, authCookieWriter, mfaService,
                    ssoProperties, clock),
                UsernamePasswordAuthenticationFilter.class
            )
            // Last of the same-anchor filters: only acts on /mcp/** (shouldNotFilter), validates the
            // Bearer access-key or MCP JWT, and sets an AccessKeyAuthentication carrying scope
            // authorities only.
            .addFilterBefore(
                new AccessKeyAuthFilter(accessKeyService, mcpKeyBuckets, jwtTokenAuthenticator, appUserRepository,
                    securityContextRepository),
                UsernamePasswordAuthenticationFilter.class
            )
            // Two "default" entry points rather than one plain authenticationEntryPoint(...):
            // ExceptionHandlingConfigurer ignores defaultAuthenticationEntryPointFor(...) mappings
            // entirely once a plain authenticationEntryPoint(...) is set, so /mcp/** gets its own
            // RFC 9728 challenge (McpAuthenticationEntryPoint) while every other path falls through
            // to the catch-all matcher below, which reproduces the original problem+json body
            // unchanged.
            .exceptionHandling(ex -> ex
                // CsrfFilter takes this handler too. Only its CsrfException gets a problem+json
                // body; every other 403 keeps Spring's default handler.
                .accessDeniedHandler(crossSiteAwareAccessDeniedHandler())
                .defaultAuthenticationEntryPointFor(
                    new McpAuthenticationEntryPoint(),
                    new AntPathRequestMatcher("/mcp/**")
                )
                .defaultAuthenticationEntryPointFor(
                    (req, res, authEx) -> {
                        res.setStatus(401);
                        res.setContentType("application/problem+json");
                        res.getWriter().write("""
                            {"status":401,"title":"Unauthorized","detail":"Authentication required"}
                            """);
                    },
                    AnyRequestMatcher.INSTANCE
                )
            );

        return http.build();
    }

    /**
     * Response headers shared by the API chain and the OAuth2 authorization-server chain, so no
     * path is served without them and HSTS follows one gate. {@code Cache-Control: no-store} is
     * limited to backend paths: the SPA's files carry their own caching headers.
     */
    @Bean
    Customizer<HeadersConfigurer<HttpSecurity>> securityHeadersCustomizer() {
        return this::securityHeaders;
    }

    void securityHeaders(HeadersConfigurer<HttpSecurity> headers) {
        headers
            .frameOptions(fo -> fo.deny())
            .contentTypeOptions(cto -> {})
            .httpStrictTransportSecurity(hsts -> {
                if (parseHstsEnabled(hstsEnabledRaw)) {
                    hsts.maxAgeInSeconds(31536000).includeSubDomains(true);
                } else {
                    hsts.disable();
                }
            })
            .referrerPolicy(rp -> rp
                .policy(ReferrerPolicyHeaderWriter.ReferrerPolicy.STRICT_ORIGIN_WHEN_CROSS_ORIGIN))
            .contentSecurityPolicy(csp -> csp.policyDirectives(CONTENT_SECURITY_POLICY))
            .cacheControl(cache -> cache.disable())
            .addHeaderWriter(new StaticHeadersWriter("Permissions-Policy", PERMISSIONS_POLICY))
            .addHeaderWriter(new DelegatingRequestMatcherHeaderWriter(
                request -> SpaPaths.isBackend(SpaPaths.requestPath(request)),
                new CacheControlHeadersWriter()));
    }

    static AccessDeniedHandler crossSiteAwareAccessDeniedHandler() {
        LinkedHashMap<Class<? extends AccessDeniedException>, AccessDeniedHandler> handlers = new LinkedHashMap<>();
        handlers.put(CsrfException.class, (req, res, denied) -> {
            res.setStatus(403);
            res.setContentType("application/problem+json");
            res.getWriter().write("""
                {"status":403,"title":"Forbidden","detail":"Cross-site request rejected"}
                """);
        });
        return new DelegatingAccessDeniedHandler(handlers, new AccessDeniedHandlerImpl());
    }

    /**
     * Never stores a token, so {@code CsrfFilter} generates a fresh random one for each request it
     * checks and the submitted value can never match. Saving is a no-op: no session, no cookie.
     */
    static final class NoStoredCsrfTokenRepository implements CsrfTokenRepository {
        @Override
        public CsrfToken generateToken(HttpServletRequest request) {
            return new DefaultCsrfToken("X-CSRF-TOKEN", "_csrf", UUID.randomUUID().toString());
        }

        @Override
        public void saveToken(CsrfToken token, HttpServletRequest request, HttpServletResponse response) {
        }

        @Override
        public CsrfToken loadToken(HttpServletRequest request) {
            return null;
        }
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(12);
    }

    @Bean
    public CorsFilter corsFilter(CorsConfigurationSource corsConfigurationSource) {
        CorsFilter filter = new CorsFilter(corsConfigurationSource);
        filter.setCorsProcessor(new LoggingCorsProcessor());
        return filter;
    }

    /**
     * Dynamic CORS: reads {@code cors.allowed-origins} from {@code app_setting}
     * per request, so changes made through the setup wizard's Security step
     * take effect without a container restart. Falls back to the env var for
     * fresh installs (and for env-only operators who never run the wizard).
     */
    @Bean
    public DynamicCorsConfigurationSource corsConfigurationSource(AppSettingRepository settingRepository) {
        return new DynamicCorsConfigurationSource(settingRepository, allowedOrigins);
    }
}

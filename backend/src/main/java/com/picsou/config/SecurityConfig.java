package com.picsou.config;

import com.picsou.mcp.AccessKeyService;
import com.picsou.repository.AppSettingRepository;
import com.picsou.repository.AppUserRepository;
import com.picsou.service.MfaService;
import com.picsou.service.PersistentSessionService;
import io.github.bucket4j.Bucket;
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
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter;
import org.springframework.security.web.util.matcher.AntPathRequestMatcher;
import org.springframework.security.web.util.matcher.AnyRequestMatcher;
import org.springframework.security.config.Customizer;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.filter.CorsFilter;

import java.util.List;
import java.util.Map;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Value("${app.cors.allowed-origins:}")
    private String allowedOrigins;

    /**
     * Whether to send {@code Strict-Transport-Security}. Off by default, and it must stay in
     * lock-step with the nginx-side gate (docker/entrypoint.sh writes
     * {@code /etc/nginx/snippets/picsou-hsts.conf} from the same {@code HSTS_ENABLED}).
     *
     * <p>Gating this matters because Spring Security's {@code HstsHeaderWriter} fires on any
     * request where {@code isSecure()} is true, and {@code forward-headers-strategy: framework}
     * makes that true from {@code X-Forwarded-Proto: https}. Without the gate, every
     * {@code /api/*} response carries HSTS — and the SPA calls the API on load, so the browser
     * pins the policy on the first page view. With a locally-issued certificate that is a
     * lockout: the browser then refuses the "proceed anyway" bypass and there is no in-app
     * recovery.
     *
     * <p>In the all-in-one Docker image this is defence in depth — nginx strips the backend's
     * copy on {@code /api} and {@code /actuator} ({@code proxy_hide_header}) and emits its own.
     * It is the operative control for the split stack and for bare-metal runs behind an
     * operator's own proxy, where nothing hides it.
     *
     * <p>Bound as a {@code String} and parsed leniently on purpose. A primitive {@code boolean}
     * would make Spring fail field injection on any value it cannot convert — including a bare
     * {@code HSTS_ENABLED=} — which takes the whole backend down under supervisord while nginx
     * keeps serving, turning a harmless typo into an app-wide 502. {@code docker/entrypoint.sh}
     * deliberately tolerates the same inputs and warns; the two must agree.
     */
    @Value("${app.hsts-enabled:false}")
    private String hstsEnabledRaw;

    /**
     * The truthy tokens accepted for {@code HSTS_ENABLED}. This set is duplicated in the
     * {@code case} arm of {@code docker/entrypoint.sh}, which gates the nginx-side emitter from
     * the same env var. The two must stay identical: if they diverge, one emitter sends HSTS
     * while the other does not, and on a locally-issued certificate that is the browser lockout
     * the whole gate exists to prevent. {@code SecurityConfigHstsParsingTest} pins this list
     * against the shell script so the drift fails the build rather than a deployment.
     */
    static final java.util.Set<String> HSTS_TRUTHY = java.util.Set.of("true", "1", "yes", "on");

    /** Lenient, case-insensitive, surrounding-whitespace-tolerant — mirrors entrypoint.sh. */
    static boolean parseHstsEnabled(String raw) {
        return raw != null && HSTS_TRUTHY.contains(raw.trim().toLowerCase());
    }

    private boolean hstsEnabled() {
        return parseHstsEnabled(hstsEnabledRaw);
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
                                           @Qualifier("mcpKeyBuckets") Map<Long, Bucket> mcpKeyBuckets) throws Exception {
        http
            .cors(Customizer.withDefaults())
            .csrf(csrf -> csrf.disable())   // stateless JWT + SameSite cookies cover this
            .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .headers(headers -> headers
                .frameOptions(fo -> fo.deny())
                .contentTypeOptions(cto -> {})
                .httpStrictTransportSecurity(hsts -> {
                    if (hstsEnabled()) {
                        hsts.maxAgeInSeconds(31536000).includeSubDomains(true);
                    } else {
                        hsts.disable();
                    }
                })
                .referrerPolicy(rp -> rp
                    .policy(ReferrerPolicyHeaderWriter.ReferrerPolicy.STRICT_ORIGIN_WHEN_CROSS_ORIGIN)
                )
            )
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/api/setup/**").permitAll()
                .requestMatchers(HttpMethod.POST, "/api/auth/login").permitAll()
                .requestMatchers(HttpMethod.POST, "/api/auth/refresh").permitAll()
                .requestMatchers(HttpMethod.POST, "/api/auth/logout").permitAll()
                .requestMatchers(HttpMethod.POST, "/api/auth/mfa/verify").permitAll()
                .requestMatchers(HttpMethod.POST, "/api/auth/activate/*").permitAll()
                .requestMatchers("/actuator/health", "/actuator/info").permitAll()
                // RFC 9728 protected-resource metadata (Task 7) and RFC 7591 dynamic client
                // registration (Task 8): unauthenticated by design, so a remote-MCP client can
                // discover the resource + self-register before the OAuth handshake even starts.
                // Neither is matched by the AS chain's own securityMatcher (@Order(1) above) —
                // /.well-known/oauth-protected-resource isn't an AS-native endpoint, and
                // /oauth2/register is a plain controller, not a configured clientRegistrationEndpoint
                // — so both fall through to this chain and need an explicit permitAll here.
                .requestMatchers(ProtectedResourceMetadataController.PATH).permitAll()
                .requestMatchers(HttpMethod.POST, "/oauth2/register").permitAll()
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
                new PersistentTokenAuthFilter(persistentSessionService, appUserRepository, jwtUtil, authCookieWriter, mfaService),
                UsernamePasswordAuthenticationFilter.class
            )
            // Last of the same-anchor filters: only acts on /mcp/** (shouldNotFilter), validates the
            // Bearer access-key or MCP JWT, and sets an AccessKeyAuthentication carrying scope
            // authorities only.
            .addFilterBefore(
                new AccessKeyAuthFilter(accessKeyService, mcpKeyBuckets, jwtTokenAuthenticator, appUserRepository),
                UsernamePasswordAuthenticationFilter.class
            )
            // Two "default" entry points rather than one plain authenticationEntryPoint(...):
            // ExceptionHandlingConfigurer ignores defaultAuthenticationEntryPointFor(...) mappings
            // entirely once a plain authenticationEntryPoint(...) is set, so /mcp/** gets its own
            // RFC 9728 challenge (McpAuthenticationEntryPoint) while every other path falls through
            // to the catch-all matcher below, which reproduces the original problem+json body
            // unchanged.
            .exceptionHandling(ex -> ex
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
    public CorsConfigurationSource corsConfigurationSource(AppSettingRepository settingRepository) {
        return new DynamicCorsConfigurationSource(settingRepository, allowedOrigins);
    }
}

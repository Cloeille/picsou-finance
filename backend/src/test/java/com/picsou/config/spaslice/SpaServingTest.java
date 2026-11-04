package com.picsou.config.spaslice;

import com.picsou.config.AuthCookieWriter;
import com.picsou.config.JwtTokenAuthenticator;
import com.picsou.config.JwtUtil;
import com.picsou.config.SecurityConfig;
import com.picsou.config.SetupFilter;
import com.picsou.config.SpaWebConfig;
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
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.head;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The built React app served by Spring through the real {@link SecurityConfig} and
 * {@link SpaWebConfig}, against a temporary directory standing in for {@code SPA_LOCATION}.
 */
@WebMvcTest(controllers = SpaServingTest.ProbeController.class)
@Import({SecurityConfig.class, SpaWebConfig.class, SpaServingTest.ProbeController.class})
class SpaServingTest {

    private static final String INDEX = "<html><body>picsou-spa-index</body></html>";
    private static final String SECRET = "outside-the-spa-directory";

    private static final Path ROOT = createSite();

    private static Path createSite() {
        try {
            Path parent = Files.createTempDirectory("picsou-spa-test");
            Files.writeString(parent.resolve("secret.txt"), SECRET);
            // '#' starts a fragment in a file: URL; the SPA directory must still resolve.
            Path site = Files.createDirectory(parent.resolve("public#1"));
            Files.writeString(site.resolve("index.html"), INDEX);
            Files.writeString(site.resolve("application.yml"), "inside: true");
            Files.createDirectories(site.resolve("assets"));
            Files.writeString(site.resolve("assets/app-abc.js"), "console.log('app');");
            Files.writeString(site.resolve("assets/logo.SVG"), "<svg/>");
            Files.writeString(site.resolve("favicon.svg"), "<svg/>");
            Files.writeString(site.resolve("theme-init.js"), "document.title='x';");
            Files.createDirectories(site.resolve("providers"));
            Files.writeString(site.resolve("providers/bank.svg"), "<svg/>");
            Files.createDirectories(site.resolve("etag"));
            Files.writeString(site.resolve("etag/index.html"), "<html/>");
            Files.writeString(site.resolve("etag/theme-init.js"), "a");
            Files.writeString(site.resolve("etag/x.json"), "{}");
            Files.createDirectories(site.resolve("locales/en"));
            Files.writeString(site.resolve("locales/en/x.json"), "{\"a\":1}");
            return site;
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @DynamicPropertySource
    static void spaLocation(DynamicPropertyRegistry registry) {
        registry.add("app.spa.location", ROOT::toString);
    }

    /** {@code /api/setup/**} is {@code permitAll}, so this stands in for any backend response. */
    @RestController
    static class ProbeController {
        @GetMapping("/api/setup/spa-probe")
        String probe() {
            return "ok";
        }
    }

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

    private String body(MvcResult result) throws Exception {
        return result.getResponse().getContentAsString();
    }

    @Test
    void root_servesIndexWithSecurityHeadersAndRevalidates() throws Exception {
        MvcResult result = mvc.perform(get("/"))
            .andExpect(status().isOk())
            .andExpect(header().string("Content-Type", org.hamcrest.Matchers.startsWith("text/html")))
            .andExpect(header().string("Content-Security-Policy", SecurityConfigAccess.csp()))
            .andExpect(header().string("Permissions-Policy", "camera=(), microphone=(), geolocation=()"))
            .andExpect(header().string("X-Frame-Options", "DENY"))
            .andExpect(header().string("X-Content-Type-Options", "nosniff"))
            .andExpect(header().string("Referrer-Policy", "strict-origin-when-cross-origin"))
            .andExpect(header().exists("ETag"))
            .andExpect(header().exists("Last-Modified"))
            .andExpect(header().string("Cache-Control", "no-cache"))
            .andExpect(header().doesNotExist("Expires"))
            .andReturn();
        assertThat(body(result)).isEqualTo(INDEX);
    }

    @Test
    void clientRoutes_fallBackToIndex() throws Exception {
        for (String route : new String[] {"/accounts/12", "/setup/admin", "/consent?x=1"}) {
            MvcResult result = mvc.perform(get(route))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-cache"))
                .andExpect(header().doesNotExist("Expires"))
                .andReturn();
            assertThat(body(result)).as(route).isEqualTo(INDEX);
        }
    }

    @Test
    void head_isServedToo() throws Exception {
        mvc.perform(head("/accounts/12")).andExpect(status().isOk());
    }

    @Test
    void hashedAsset_isCachedForAYear() throws Exception {
        MvcResult result = mvc.perform(get("/assets/app-abc.js"))
            .andExpect(status().isOk())
            .andExpect(header().string("Cache-Control", "max-age=31536000, public, immutable"))
            .andExpect(header().exists("Expires"))
            .andExpect(header().exists("ETag"))
            .andExpect(header().exists("Last-Modified"))
            .andExpect(header().string("Accept-Ranges", "bytes"))
            .andExpect(header().string("Content-Security-Policy", SecurityConfigAccess.csp()))
            .andReturn();
        assertThat(body(result)).isEqualTo("console.log('app');");
    }

    @Test
    void extensionMatch_isCaseInsensitive() throws Exception {
        mvc.perform(get("/assets/logo.SVG"))
            .andExpect(status().isOk())
            .andExpect(header().string("Cache-Control", "max-age=31536000, public, immutable"));
    }

    @Test
    void unhashedPublicFile_isRevalidatedNotCached() throws Exception {
        mvc.perform(get("/favicon.svg"))
            .andExpect(status().isOk())
            .andExpect(header().string("Cache-Control", "no-cache"))
            .andExpect(header().doesNotExist("Expires"))
            .andExpect(header().exists("ETag"))
            .andExpect(header().exists("Last-Modified"));
    }

    @Test
    void staticExtensionOutsideAssets_isNotImmutable() throws Exception {
        for (String path : new String[] {"/providers/bank.svg", "/theme-init.js"}) {
            MvcResult result = mvc.perform(get(path))
                .andExpect(status().isOk())
                .andExpect(header().doesNotExist("Expires"))
                .andReturn();
            assertThat(result.getResponse().getHeader("Cache-Control")).as(path)
                .isEqualTo("no-cache");
        }
    }

    @Test
    void missingAsset_is404NotIndex() throws Exception {
        for (String path : new String[] {"/assets/nope.js", "/nope.png"}) {
            MvcResult result = mvc.perform(get(path))
                .andExpect(status().isNotFound())
                .andExpect(header().doesNotExist("Cache-Control"))
                .andReturn();
            assertThat(body(result)).as(path).doesNotContain("picsou-spa-index");
        }
    }

    @Test
    void otherExistingFile_isRevalidated() throws Exception {
        MvcResult result = mvc.perform(get("/locales/en/x.json"))
            .andExpect(status().isOk())
            .andExpect(header().string("Cache-Control", "no-cache"))
            .andReturn();
        assertThat(body(result)).isEqualTo("{\"a\":1}");
    }

    /** Every no-cache file is revalidated by its ETag: a rebuild with the same length and second must show. */
    @Test
    void unhashedFileEtag_followsContentEvenWhenLengthAndMtimeMatch() throws Exception {
        for (String path : new String[] {"/etag/index.html", "/etag/theme-init.js", "/etag/x.json"}) {
            Path file = ROOT.resolve(path.substring(1));
            FileTime sameSecond = FileTime.from(Instant.parse("2026-01-01T00:00:00Z"));
            Files.writeString(file, "build-A");
            Files.setLastModifiedTime(file, sameSecond);
            String first = mvc.perform(get(path)).andExpect(status().isOk())
                .andReturn().getResponse().getHeader("ETag");

            Files.writeString(file, "build-B");
            Files.setLastModifiedTime(file, sameSecond);
            String second = mvc.perform(get(path)).andExpect(status().isOk())
                .andReturn().getResponse().getHeader("ETag");

            assertThat(first).as(path).startsWith("W/\"").endsWith("\"");
            assertThat(second).as(path).startsWith("W/\"");
            assertThat(second).as(path).isNotEqualTo(first);
        }
    }

    @Test
    void unhashedFileEtag_isAContentDigestNotATimestampAndLength() throws Exception {
        for (String path : new String[] {"/", "/accounts/12", "/favicon.svg", "/locales/en/x.json", "/providers/bank.svg"}) {
            String etag = mvc.perform(get(path)).andExpect(status().isOk())
                .andReturn().getResponse().getHeader("ETag");
            assertThat(etag).as(path).matches("W/\"[0-9a-f]{32}\"");
        }
    }

    /** A hashed asset is immutable and renamed on change: its tag stays cheap, the bundle is never read. */
    @Test
    void hashedAssetEtag_keepsTheTimestampAndLengthShape() throws Exception {
        for (String path : new String[] {"/assets/app-abc.js", "/assets/logo.SVG"}) {
            String etag = mvc.perform(get(path)).andExpect(status().isOk())
                .andReturn().getResponse().getHeader("ETag");
            assertThat(etag).as(path).matches("W/\"[0-9a-f]+-[0-9a-f]+\"");
        }
    }

    @Test
    void matchingIfNoneMatch_answers304ForTheIndexAndItsRoutes() throws Exception {
        for (String route : new String[] {"/", "/accounts/12"}) {
            String etag = mvc.perform(get(route)).andExpect(status().isOk())
                .andReturn().getResponse().getHeader("ETag");
            mvc.perform(get(route).header("If-None-Match", etag))
                .andExpect(status().isNotModified());
        }
    }

    @Test
    void backendPaths_neverFallBackToIndex() throws Exception {
        for (String path : new String[] {"/api/nope", "/mcpx", "/oauth2/nope", "/actuator/nope",
            "/.well-known/oauth-nope"}) {
            MvcResult result = mvc.perform(get(path)).andReturn();
            assertThat(body(result)).as(path).doesNotContain("picsou-spa-index");
            assertThat(result.getResponse().getStatus()).as(path).isIn(401, 404);
        }
    }

    @Test
    void wellKnownOutsideOauth_isASpaPath() throws Exception {
        MvcResult result = mvc.perform(get("/.well-known/security.txt")).andReturn();
        assertThat(body(result)).isEqualTo(INDEX);
    }

    @Test
    void nonReadMethodsOutsideBackend_areNotPermitted() throws Exception {
        mvc.perform(post("/accounts/12")).andExpect(status().is4xxClientError());
    }

    /** {@code /api/setup/**} is permitAll, so only the resolver's backend guard stops the fallback. */
    @Test
    void permittedBackendPathWithoutController_is404WithoutHtml() throws Exception {
        MvcResult result = mvc.perform(get("/api/setup/nope")).andExpect(status().isNotFound()).andReturn();
        assertThat(body(result)).doesNotContain("picsou-spa-index");
        assertThat(String.valueOf(result.getResponse().getContentType())).doesNotStartWith("text/html");
    }

    /**
     * Each of these is refused by the Spring Security firewall with a 400 and an empty body, so
     * neither a handler nor a resolver sees it. That is what this test pins. The resolver's own
     * guard is tested directly in {@code SpaResourceResolverTest}, because MockMvc cannot carry
     * such a path past the firewall.
     */
    @Test
    void traversalForms_areRejectedWithAnEmptyFourHundred() throws Exception {
        for (String uri : new String[] {"/..%2fsecret.txt", "/%2e%2e/secret.txt", "/%2e%2e%2fsecret.txt",
            "/../secret.txt", "/assets/..%2f..%2fsecret.txt", "/%252e%252e/secret.txt"}) {
            MvcResult result = mvc.perform(get(URI.create(uri))).andReturn();
            assertThat(result.getResponse().getStatus()).as(uri).isEqualTo(400);
            assertThat(result.getHandler()).as(uri).isNull();
            // The documented contract, which also covers "no part of the file outside the SPA".
            assertThat(body(result)).as(uri).isEmpty();
        }
    }

    @Test
    void encodedBackendPrefix_isStillTreatedAsBackend() throws Exception {
        MvcResult result = mvc.perform(get(URI.create("/%61pi/nope"))).andReturn();
        assertThat(body(result)).doesNotContain("picsou-spa-index");
    }

    @Test
    void backendResponse_keepsNoStore() throws Exception {
        mvc.perform(get("/api/setup/spa-probe"))
            .andExpect(status().isOk())
            .andExpect(header().string("Cache-Control", "no-cache, no-store, max-age=0, must-revalidate"))
            .andExpect(header().string("Pragma", "no-cache"))
            .andExpect(header().string("Content-Security-Policy", SecurityConfigAccess.csp()));
    }

    /** The CSP the nginx baseline sent, spelled out here so a silent edit fails the test. */
    private static final class SecurityConfigAccess {
        static String csp() {
            return "default-src 'self'; script-src 'self'; style-src 'self' 'unsafe-inline'; "
                + "img-src 'self' data: https:; font-src 'self'; connect-src 'self'; frame-ancestors 'none'";
        }
    }
}

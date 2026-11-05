package com.picsou.config;

import com.picsou.config.SpaWebConfig.SpaResourceResolver;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.Resource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.servlet.HandlerMapping;
import org.springframework.web.servlet.resource.NoResourceFoundException;
import org.springframework.web.servlet.resource.ResourceHttpRequestHandler;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Drives the real resource handler, because that is the layer the app registers. MockMvc cannot
 * carry a traversal path this far: the Spring Security firewall answers 400 before any handler
 * runs (see {@code SpaServingTest}).
 *
 * <p>Two layers refuse a path leading out of the site directory, and each is pinned on its own.
 * {@code ResourceHttpRequestHandler} rejects any path holding {@code ..} (raw or encoded) before
 * a resolver runs. Past that, the resolver itself must refuse: the traversal tests drive it
 * directly with the location the app builds ({@link SpaWebConfig#siteLocation}), whose normalized
 * path makes {@code PathResourceResolver.checkResource} see the escape.
 */
class SpaResourceResolverTest {

    private static final String INDEX = "<html><body>picsou-spa-index</body></html>";
    private static final String SECRET = "outside-the-site-directory";
    private static final String REFUSED = "<refused>";

    @TempDir
    static Path parent;

    private static Resource location;

    @BeforeAll
    static void createSite() throws IOException {
        Files.writeString(parent.resolve("secret.txt"), SECRET);
        Files.writeString(parent.resolve("secret"), SECRET);
        Files.writeString(parent.resolve("secret.js"), SECRET);
        Path site = Files.createDirectory(parent.resolve("site"));
        Files.writeString(site.resolve("index.html"), INDEX);
        Files.writeString(site.resolve("favicon.svg"), "<svg/>");
        Files.createDirectory(site.resolve("assets"));
        Files.writeString(site.resolve("assets/index-abc.js"), "console.log(1)");
        location = SpaWebConfig.siteLocation(site.toString());
    }

    private static ResourceHttpRequestHandler handler() throws Exception {
        ResourceHttpRequestHandler handler = new ResourceHttpRequestHandler();
        handler.setLocations(List.of(location));
        handler.setResourceResolvers(List.of(new SpaResourceResolver()));
        handler.afterPropertiesSet();
        return handler;
    }

    /** "status|body" for a served file, or {@link #REFUSED} when no resource is found at all. */
    private static String served(String path) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", path);
        request.setAttribute(HandlerMapping.PATH_WITHIN_HANDLER_MAPPING_ATTRIBUTE, path);
        MockHttpServletResponse response = new MockHttpServletResponse();
        try {
            handler().handleRequest(request, response);
        } catch (NoResourceFoundException refused) {
            return REFUSED;
        }
        return response.getStatus() + "|" + response.getContentAsString();
    }

    @Test
    void aFileInTheSiteDirectory_isServed() throws Exception {
        assertThat(served("favicon.svg")).isEqualTo("200|<svg/>");
        assertThat(served("assets/index-abc.js")).isEqualTo("200|console.log(1)");
    }

    @Test
    void aClientRoute_fallsBackToTheIndex() throws Exception {
        assertThat(served("accounts/12")).isEqualTo("200|" + INDEX);
        assertThat(served("index.html")).isEqualTo("200|" + INDEX);
    }

    @Test
    void aMissingFileWithAStaticExtension_isNotFoundNotTheIndex() throws Exception {
        assertThat(served("assets/missing.js")).isEqualTo(REFUSED);
    }

    @Test
    void aPathLeadingOutOfTheSiteDirectory_isNeverServed() throws Exception {
        for (String path : new String[] {"../secret.txt", "../../secret.txt", "../site/../secret.txt",
            "assets/../../secret.txt", "/../secret.txt", "....//secret.txt", "..%2fsecret.txt",
            "..\\secret.txt", "%2e%2e/secret.txt", "%2e%2e%2fsecret.txt", "assets/..%2f..%2fsecret.txt"}) {
            assertThat(served(path)).as(path).isEqualTo(REFUSED);
        }
    }

    /**
     * The resolver driven without the handler's own {@code ..} check. {@code .txt} is not a static
     * extension, so the refused escape is answered as a client route: the index, never the secret.
     */
    @Test
    void theResolverItself_neverServesAFileAboveTheSiteDirectory() throws Exception {
        SpaResourceResolver resolver = new SpaResourceResolver();
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/");
        List<Resource> locations = List.of(location);

        for (String path : new String[] {"../secret.txt", "assets/../../secret.txt", "../secret"}) {
            Resource resolved = resolver.resolveResource(request, path, locations, null);
            assertThat(resolved).as(path).isNotNull();
            assertThat(resolved.getContentAsString(StandardCharsets.UTF_8)).as(path).isEqualTo(INDEX);
        }
        assertThat(resolver.resolveResource(request, "../secret.js", locations, null)).isNull();
    }

    @Test
    void aBackendPath_isNeverAnsweredHere() throws Exception {
        assertThat(served("api/setup/state")).isEqualTo(REFUSED);
    }
}

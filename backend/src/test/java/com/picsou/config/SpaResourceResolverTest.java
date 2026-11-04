package com.picsou.config;

import com.picsou.config.SpaWebConfig.SpaResourceResolver;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.servlet.HandlerMapping;
import org.springframework.web.servlet.resource.NoResourceFoundException;
import org.springframework.web.servlet.resource.ResourceHttpRequestHandler;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Drives the real resource handler, not just the resolver, because that is the layer the app
 * registers. MockMvc cannot carry a traversal path this far: the Spring Security firewall answers
 * 400 before any handler runs (see {@code SpaServingTest}).
 *
 * <p>{@code PathResourceResolver} alone, driven directly, does serve a file above its location, so
 * a test written against it would claim a guarantee the code does not have. The refusal comes from
 * {@code ResourceHttpRequestHandler}, and this test pins that it stays.
 */
class SpaResourceResolverTest {

    private static final String INDEX = "<html><body>picsou-spa-index</body></html>";
    private static final String SECRET = "outside-the-site-directory";
    private static final String REFUSED = "<refused>";

    private static final Path PARENT = createSite();
    private static final Resource LOCATION = new FileSystemResource(PARENT.resolve("site") + "/");

    private static Path createSite() {
        try {
            Path parent = Files.createTempDirectory("spa-resolver");
            Files.writeString(parent.resolve("secret.txt"), SECRET);
            Path site = Files.createDirectory(parent.resolve("site"));
            Files.writeString(site.resolve("index.html"), INDEX);
            Files.writeString(site.resolve("favicon.svg"), "<svg/>");
            Files.createDirectory(site.resolve("assets"));
            Files.writeString(site.resolve("assets/index-abc.js"), "console.log(1)");
            return parent;
        } catch (IOException ex) {
            throw new IllegalStateException("Cannot build the test site", ex);
        }
    }

    private static ResourceHttpRequestHandler handler() throws Exception {
        ResourceHttpRequestHandler handler = new ResourceHttpRequestHandler();
        handler.setLocations(List.of(LOCATION));
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
            String answer = served(path);
            assertThat(answer).as(path).isIn(REFUSED, "200|" + INDEX);
        }
    }

    @Test
    void aBackendPath_isNeverAnsweredHere() throws Exception {
        assertThat(served("api/setup/state")).isEqualTo(REFUSED);
    }
}

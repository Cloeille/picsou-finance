package com.picsou.config;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.core.io.AbstractResource;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.util.DigestUtils;
import org.springframework.util.StringUtils;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.HandlerMapping;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.resource.HttpResource;
import org.springframework.web.servlet.resource.PathResourceResolver;
import org.springframework.web.servlet.resource.ResourceHttpRequestHandler;
import org.springframework.web.servlet.resource.ResourceResolverChain;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URL;
import java.nio.file.FileSystems;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * Serves the built React app from {@code app.spa.location} ({@code SPA_LOCATION}), replacing the
 * static half of the bundled nginx. Blank means nothing is registered: development uses Vite and
 * the backend-only tests are unaffected.
 *
 * <p>Resolution order for a request path:
 * <ol>
 *   <li>a backend path ({@link SpaPaths#isBackend}) is never answered here, so it can not fall
 *       back to {@code index.html};</li>
 *   <li>an existing readable file is served as is;</li>
 *   <li>a missing file with a static extension ({@link SpaPaths#hasStaticExtension}) is a 404 (a
 *       stale bundle must not turn into HTML);</li>
 *   <li>anything else is a client-side route and gets {@code index.html}.</li>
 * </ol>
 *
 * <p>Caching: a hashed asset ({@link SpaPaths#isHashedAsset}) is cached for a year; every other
 * file, {@code index.html} and its client-route fallbacks included, is {@code no-cache} so the
 * browser revalidates it with the ETag. The headers ride on the resolved resource, so a 404 never
 * carries them.
 *
 * <p>Highest precedence so this configurer registers {@code /**} before Spring Boot's default
 * static mapping, which skips itself once the pattern is taken.
 */
@Configuration
@Order(Ordered.HIGHEST_PRECEDENCE)
public class SpaWebConfig implements WebMvcConfigurer {

    private static final String INDEX = "index.html";
    private static final Duration ASSET_MAX_AGE = Duration.ofDays(365);

    private final String location;

    public SpaWebConfig(@Value("${app.spa.location:}") String location) {
        this.location = location;
    }

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        if (!StringUtils.hasText(location)) {
            return;
        }
        registry.addResourceHandler("/**")
            .addResourceLocations(siteLocation(location))
            .setEtagGenerator(SpaWebConfig::etag)
            .resourceChain(false)
            .addResolver(new SpaResourceResolver());
    }

    /**
     * A Resource, not a "file:" URL string: a '#' in the path would start a URL fragment. Built
     * with the file system overload because it cleans and normalizes the path: {@code
     * createRelative("../secret.txt")} then resolves outside the location and {@code
     * PathResourceResolver.checkResource} refuses it, where the {@code String} constructor keeps
     * {@code site/../secret.txt} and passes the check.
     */
    static Resource siteLocation(String location) {
        return new FileSystemResource(FileSystems.getDefault(), location.endsWith("/") ? location : location + "/");
    }

    /**
     * The resource handler rejects an empty path before its resolvers run, and {@code /} maps to
     * an empty path. Point it at {@code index.html} so the root is served like any client route.
     */
    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        if (!StringUtils.hasText(location)) {
            return;
        }
        registry.addInterceptor(new HandlerInterceptor() {
            @Override
            public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
                if (handler instanceof ResourceHttpRequestHandler) {
                    Object within = request.getAttribute(HandlerMapping.PATH_WITHIN_HANDLER_MAPPING_ATTRIBUTE);
                    if (within instanceof String path && path.isEmpty()) {
                        request.setAttribute(HandlerMapping.PATH_WITHIN_HANDLER_MAPPING_ATTRIBUTE, INDEX);
                    }
                }
                return true;
            }
        });
    }

    /**
     * Weak on purpose: Tomcat skips compression for a response with a strong ETag, and nginx
     * (the previous server) also weakened it when it gzipped.
     *
     * <p>Every file that is not a hashed asset is {@code no-cache}: the browser revalidates it on
     * each load and its name survives a rebuild, so two builds can share a length and a
     * modification second. Its tag is a digest of the content (a cache validator, not a security
     * control). The digest is read on every GET or HEAD of such a file, not only on a conditional
     * request: a 200 reads the file once for the tag and once for the body. {@code frontend/public}
     * is 46,808 bytes in 12 files and {@code index.html} 489 bytes, so that costs nothing
     * measurable. A hashed asset is
     * immutable for a year and its name changes with its content, so it keeps nginx's shape,
     * last-modified seconds and length in hex, and a multi-MB bundle is never read to build a tag.
     */
    private static String etag(Resource resource) {
        try {
            if (!(resource instanceof WithHeaders served && served.hashedAsset)) {
                try (InputStream content = resource.getInputStream()) {
                    return weak(DigestUtils.md5DigestAsHex(content));
                }
            }
            return weak(Long.toHexString(resource.lastModified() / 1000)
                + "-" + Long.toHexString(resource.contentLength()));
        } catch (IOException e) {
            return null;
        }
    }

    private static String weak(String opaqueTag) {
        return "W/\"" + opaqueTag + "\"";
    }

    private static HttpHeaders cacheHeaders(boolean hashedAsset) {
        HttpHeaders headers = new HttpHeaders();
        if (hashedAsset) {
            headers.set(HttpHeaders.CACHE_CONTROL,
                "max-age=" + ASSET_MAX_AGE.toSeconds() + ", public, immutable");
            headers.setExpires(Instant.now().plus(ASSET_MAX_AGE));
        } else {
            headers.setCacheControl(CacheControl.noCache());
        }
        return headers;
    }

    /** Package-private so {@code SpaResourceResolverTest} can drive it without a servlet container. */
    static final class SpaResourceResolver extends PathResourceResolver {

        @Override
        protected Resource resolveResourceInternal(HttpServletRequest request, String requestPath,
                                                   List<? extends Resource> locations,
                                                   ResourceResolverChain chain) {
            String path = "/" + requestPath;
            if (SpaPaths.isBackend(path)) {
                return null;
            }
            Resource file = super.resolveResourceInternal(request, requestPath, locations, chain);
            if (file != null) {
                return new WithHeaders(file, path);
            }
            if (SpaPaths.hasStaticExtension(path)) {
                return null;
            }
            Resource index = super.resolveResourceInternal(request, INDEX, locations, chain);
            return index == null ? null : new WithHeaders(index, path);
        }
    }

    /**
     * A resolved file plus the response headers chosen for the request path. The headers ride on
     * the resource itself so they are only sent when the file exists, and {@link #hashedAsset}
     * tells the ETag generator, which only sees the resource, how to build its tag. Everything
     * else is the wrapped resource's.
     */
    private static final class WithHeaders extends AbstractResource implements HttpResource {

        private final Resource delegate;
        private final boolean hashedAsset;
        private final HttpHeaders headers;

        WithHeaders(Resource delegate, String requestPath) {
            this.delegate = delegate;
            this.hashedAsset = SpaPaths.isHashedAsset(requestPath);
            this.headers = cacheHeaders(hashedAsset);
        }

        @Override
        public HttpHeaders getResponseHeaders() {
            return headers;
        }

        @Override
        public InputStream getInputStream() throws IOException {
            return delegate.getInputStream();
        }

        @Override
        public boolean exists() {
            return delegate.exists();
        }

        @Override
        public boolean isReadable() {
            return delegate.isReadable();
        }

        @Override
        public long contentLength() throws IOException {
            return delegate.contentLength();
        }

        @Override
        public long lastModified() throws IOException {
            return delegate.lastModified();
        }

        @Override
        public URL getURL() throws IOException {
            return delegate.getURL();
        }

        @Override
        public URI getURI() throws IOException {
            return delegate.getURI();
        }

        @Override
        public File getFile() throws IOException {
            return delegate.getFile();
        }

        @Override
        public String getFilename() {
            return delegate.getFilename();
        }

        @Override
        public Resource createRelative(String relativePath) throws IOException {
            return delegate.createRelative(relativePath);
        }

        @Override
        public String getDescription() {
            return delegate.getDescription();
        }
    }
}

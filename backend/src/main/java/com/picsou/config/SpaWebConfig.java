package com.picsou.config;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.core.io.AbstractResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
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
 *   <li>a missing file with a cacheable asset extension is a 404 (a stale hashed bundle must not
 *       turn into HTML);</li>
 *   <li>anything else is a client-side route and gets {@code index.html}.</li>
 * </ol>
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
            .addResourceLocations("file:" + (location.endsWith("/") ? location : location + "/"))
            .setEtagGenerator(SpaWebConfig::etag)
            .resourceChain(false)
            .addResolver(new SpaResourceResolver());
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
     * (the previous server) also weakened it when it gzipped. Same shape as nginx's value:
     * last-modified seconds and length in hex.
     */
    private static String etag(Resource resource) {
        try {
            return "W/\"" + Long.toHexString(resource.lastModified() / 1000)
                + "-" + Long.toHexString(resource.contentLength()) + "\"";
        } catch (IOException e) {
            return null;
        }
    }

    private static final class SpaResourceResolver extends PathResourceResolver {

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
                return SpaPaths.isHashedAsset(path) ? new LongLived(file) : file;
            }
            if (SpaPaths.hasCachedExtension(path)) {
                return null;
            }
            return super.resolveResourceInternal(request, INDEX, locations, chain);
        }
    }

    /**
     * A file the browser may keep for a year. The headers ride on the resource itself so they are
     * only sent when the file exists: a 404 for a missing bundle must not be cached.
     */
    private static final class LongLived extends AbstractResource implements HttpResource {

        private final Resource delegate;

        LongLived(Resource delegate) {
            this.delegate = delegate;
        }

        @Override
        public HttpHeaders getResponseHeaders() {
            HttpHeaders headers = new HttpHeaders();
            headers.set(HttpHeaders.CACHE_CONTROL,
                "max-age=" + ASSET_MAX_AGE.toSeconds() + ", public, immutable");
            headers.setExpires(Instant.now().plus(ASSET_MAX_AGE));
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

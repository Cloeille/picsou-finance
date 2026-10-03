package com.picsou.adapter;

import com.picsou.adapter.YahooQuotePageParser.Result;
import com.picsou.port.InstrumentLogoPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.core.codec.CodecException;
import org.springframework.core.io.buffer.DataBufferLimitException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.InvalidMediaTypeException;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientException;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.netty.http.client.HttpClient;

import java.io.IOException;
import java.net.URI;
import java.time.Duration;
import java.util.Locale;
import java.util.concurrent.TimeoutException;

/**
 * Downloads a share's or fund's mark from its Yahoo Finance quote page (issue #162).
 *
 * <p>Two requests per ticker on a hit: the quote page (around 1 MB of HTML), then the 50px
 * rendition of the mark that the page links to on {@code s.yimg.com} (a few KB, versus up to
 * 300 KB for the original), plus the dark-background variant when it is a different file. The
 * caller runs this at most once per ticker, so the page weight is paid once per installation.
 *
 * <p>Every answer the page gives is treated as untrusted: the logo URL must be https on
 * {@code s.yimg.com} ({@link YahooQuotePageParser#imageUri}), and the downloaded bytes must be a
 * PNG, JPEG or WebP by their own signature, whatever the header says, and under
 * {@link #MAX_IMAGE_BYTES}. SVG is refused outright: it is a document that can carry script, and
 * these bytes are served from Picsou's own origin.
 */
@Component
public class YahooQuotePageLogoProvider implements InstrumentLogoPort {

    private static final Logger log = LoggerFactory.getLogger(YahooQuotePageLogoProvider.class);

    static final int MAX_IMAGE_BYTES = 256 * 1024;
    /** The page is about 1 MB today; this is a memory bound, not an expectation. */
    static final int MAX_PAGE_BYTES = 4 * 1024 * 1024;
    private static final int MAX_HEADER_BYTES = 64 * 1024;
    private static final Duration PAGE_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration IMAGE_TIMEOUT = Duration.ofSeconds(5);

    private final WebClient webClient;
    private final YahooCooldown cooldown;
    private final YahooFinancePriceProvider yahoo;
    private final CoinGeckoPriceProvider coinGecko;

    @Autowired
    public YahooQuotePageLogoProvider(YahooCooldown cooldown,
                                      YahooFinancePriceProvider yahoo,
                                      CoinGeckoPriceProvider coinGecko) {
        this(clientBuilder().build(), cooldown, yahoo, coinGecko);
    }

    /**
     * Shared with the tests, so they exercise the same body-size limit production runs with.
     *
     * <p>The header limit is raised because the quote page answers with more than Reactor
     * Netty's default 8 KB of response headers (measured live: every lookup failed with "HTTP
     * header is larger than 8192 bytes"). Redirects are not followed, so a page can never steer
     * a request off the hosts asked for. A 3xx is raised as an error rather than read as an empty
     * answer: a redirect to a consent page or a normalised URL says nothing about the mark.
     */
    static WebClient.Builder clientBuilder() {
        HttpClient http = HttpClient.create()
            .followRedirect(false)
            .httpResponseDecoder(spec -> spec.maxHeaderSize(MAX_HEADER_BYTES));
        return WebClient.builder()
            .clientConnector(new ReactorClientHttpConnector(http))
            .defaultStatusHandler(HttpStatusCode::is3xxRedirection, ClientResponse::createException)
            .defaultHeader("User-Agent", "Mozilla/5.0")
            .codecs(c -> c.defaultCodecs().maxInMemorySize(MAX_PAGE_BYTES));
    }

    YahooQuotePageLogoProvider(WebClient webClient, YahooCooldown cooldown,
                               YahooFinancePriceProvider yahoo, CoinGeckoPriceProvider coinGecko) {
        this.webClient = webClient;
        this.cooldown = cooldown;
        this.yahoo = yahoo;
        this.coinGecko = coinGecko;
    }

    /**
     * A symbol Yahoo would quote and CoinGecko would not claim: the same split
     * {@code CompositePriceProvider} prices by, so a coin never reaches the equity page.
     */
    @Override
    public boolean supports(String ticker) {
        return ticker != null && !coinGecko.supports(ticker) && yahoo.supports(ticker);
    }

    @Override
    public Lookup lookup(String ticker) {
        if (!supports(ticker)) return new Lookup.Absent("not a quoted symbol");
        if (cooldown.active()) return new Lookup.RateLimited();
        String symbol = ticker.toUpperCase(Locale.ROOT);

        String html;
        try {
            html = webClient.get()
                .uri("https://finance.yahoo.com/quote/{symbol}/", symbol)
                .accept(MediaType.TEXT_HTML)
                .retrieve()
                .bodyToMono(String.class)
                .timeout(PAGE_TIMEOUT)
                .block();
        } catch (RuntimeException ex) {
            return failure("quote page", symbol, ex, false);
        }

        YahooQuotePageParser.LogoUrls urls;
        switch (YahooQuotePageParser.read(html, symbol)) {
            case Result.Marked marked -> urls = marked.urls();
            case Result.Unmarked unmarked -> {
                log.info("Yahoo quote page for {} carries no logo for that symbol", symbol);
                return new Lookup.Absent("no logo on the quote page");
            }
            case Result.RefusedMark refused -> {
                log.warn("Yahoo quote page for {} links its logo off {} (CDN change?)", symbol, YahooQuotePageParser.IMAGE_HOST);
                return new Lookup.Unavailable("logo URL off the image host");
            }
            case Result.NotQuoted notQuoted -> {
                log.warn("Yahoo quote page for {} does not quote that symbol (layout change or consent page?)", symbol);
                return new Lookup.Unavailable("symbol not quoted on the page");
            }
        }

        Lookup light = download("logo", urls.light(), symbol);
        if (!(light instanceof Lookup.Found found)) return light;

        Image dark = null;
        if (urls.dark() != null && !urls.dark().equals(urls.light())) {
            // A dark variant Yahoo does not have is no reason to lose the light mark. An outage
            // is: storing the light one alone would settle the ticker without its dark variant.
            Lookup darkLookup = download("dark logo", urls.dark(), symbol);
            if (darkLookup instanceof Lookup.Found darkFound) dark = darkFound.light();
            else if (!(darkLookup instanceof Lookup.Absent)) return darkLookup;
        }
        return new Lookup.Found(found.light(), dark);
    }

    /** The image at {@code uri} as {@link Lookup.Found#light()}, or why there is none. */
    private Lookup download(String what, URI uri, String symbol) {
        ResponseEntity<byte[]> response;
        try {
            response = webClient.get()
                .uri(uri)
                .retrieve()
                .toEntity(byte[].class)
                .timeout(IMAGE_TIMEOUT)
                .block();
        } catch (RuntimeException ex) {
            return failure(what, symbol, ex, true);
        }
        if (response == null) return new Lookup.Unavailable("empty response");
        MediaType declared = response.getHeaders().getContentType();
        Lookup verdict = validate(response.getBody(), declared == null ? null : declared.toString());
        if (!(verdict instanceof Lookup.Found)) {
            log.info("Rejected the {} Yahoo serves for {} (type {}, {} bytes): {}", what, symbol, declared,
                response.getBody() == null ? 0 : response.getBody().length, verdict);
        }
        return verdict;
    }

    /**
     * {@link Lookup.Found} (light only) if the bytes carry a PNG, JPEG or WebP signature and fit
     * under {@link #MAX_IMAGE_BYTES}, whatever the header says, unless it says SVG.
     *
     * <p>The bytes decide, not the label: a CDN that serves the same files as
     * {@code application/octet-stream}, without a type, or under the wrong image type changes
     * nothing about the mark, and judging by the header would settle every ticker at once. Since
     * the stored type is the canonical one for the signature, the label never reaches the
     * browser either.
     *
     * <p>{@link Lookup.Absent} only when the refusal is about the mark itself: an image over the
     * cap, or an SVG. A body that is not an image at all (an error or anti-bot page served with a
     * 200, whatever its size, or an empty body) says nothing about the mark and is
     * {@link Lookup.Unavailable}.
     */
    static Lookup validate(byte[] bytes, String declaredContentType) {
        if (bytes == null || bytes.length == 0) return new Lookup.Unavailable("empty image body");
        if ("image/svg+xml".equals(mediaType(declaredContentType))) return new Lookup.Absent("SVG");
        String sniffed = sniff(bytes);
        if (sniffed == null) return new Lookup.Unavailable("not an image: " + declaredContentType);
        if (bytes.length > MAX_IMAGE_BYTES) return new Lookup.Absent("too large");
        return new Lookup.Found(new Image(bytes, sniffed), null);
    }

    /** Type and subtype, lower-cased, without parameters; null when there is no header. */
    private static String mediaType(String contentType) {
        if (contentType == null) return null;
        int semicolon = contentType.indexOf(';');
        return (semicolon < 0 ? contentType : contentType.substring(0, semicolon)).trim().toLowerCase(Locale.ROOT);
    }

    private static String sniff(byte[] b) {
        if (startsWith(b, 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A)) return MediaType.IMAGE_PNG_VALUE;
        if (startsWith(b, 0xFF, 0xD8, 0xFF)) return MediaType.IMAGE_JPEG_VALUE;
        if (b.length >= 12 && startsWith(b, 'R', 'I', 'F', 'F')
            && b[8] == 'W' && b[9] == 'E' && b[10] == 'B' && b[11] == 'P') return "image/webp";
        return null;
    }

    private static boolean startsWith(byte[] b, int... prefix) {
        if (b.length < prefix.length) return false;
        for (int i = 0; i < prefix.length; i++) {
            if ((b[i] & 0xFF) != prefix[i]) return false;
        }
        return true;
    }

    /**
     * Maps a failed request to what the caller should remember. Only a 404 or an oversized image
     * is permanent; every other upstream failure, a 3xx included, is retried later. A 429 or a
     * 5xx also arms the shared cooldown, so the rest of the batch and the next pass stand down
     * too. Anything that is not an upstream failure is a bug here and is rethrown, the house rule
     * {@code CoinGeckoPriceProvider.isExpectedUpstreamFailure} documents.
     */
    private Lookup failure(String what, String symbol, RuntimeException ex, boolean image) {
        Throwable cause = reactor.core.Exceptions.unwrap(ex);
        // Checked first: an over-limit body arrives wrapped in a WebClientResponseException that
        // carries the 200 and the headers it was served with. An image's size is the mark's, but
        // only if it is an image: the bytes were never read, so the header is all there is, and an
        // HTML page that large is an error page. The page's size is Yahoo's layout, the same for
        // every ticker, so it says nothing about this one.
        if (NestedExceptionUtils.getMostSpecificCause(cause) instanceof DataBufferLimitException) {
            String type = cause instanceof WebClientResponseException http
                ? mediaType(http.getHeaders().getFirst(HttpHeaders.CONTENT_TYPE)) : null;
            log.warn("Yahoo's {} for {} exceeds the size cap (type {})", what, symbol, type);
            return image && type != null && type.startsWith("image/")
                ? new Lookup.Absent("too large") : new Lookup.Unavailable(what + " too large");
        }
        if (cause instanceof WebClientResponseException http) {
            int status = http.getStatusCode().value();
            if (status == 429) {
                Duration pause = cooldown.arm(http.getHeaders());
                log.warn("Yahoo rate-limited (429) the {} request for {} -- pausing logo lookups for {}s",
                    what, symbol, pause.toSeconds());
                return new Lookup.RateLimited();
            }
            if (status == 404) {
                log.info("Yahoo has no {} for {} (HTTP 404)", what, symbol);
                return new Lookup.Absent("HTTP 404");
            }
            if (http.getStatusCode().is5xxServerError()) {
                cooldown.arm(null);
            }
            log.warn("Yahoo answered the {} request for {} with HTTP {}", what, symbol, status);
            return new Lookup.Unavailable("HTTP " + status);
        }
        // A connection cut mid-body or a header the codecs cannot read is the upstream misbehaving,
        // not a bug here, and must not be rethrown: the pass would stop on the same ticker hourly.
        if (cause instanceof TimeoutException || cause instanceof WebClientException
            || cause instanceof CodecException || cause instanceof InvalidMediaTypeException
            || NestedExceptionUtils.getMostSpecificCause(cause) instanceof IOException) {
            log.warn("Yahoo {} request for {} did not complete: {}", what, symbol, cause.toString());
            return new Lookup.Unavailable(cause.getClass().getSimpleName());
        }
        throw ex;
    }
}

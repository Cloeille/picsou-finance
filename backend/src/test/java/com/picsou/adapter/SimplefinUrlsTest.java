package com.picsou.adapter;

import com.picsou.exception.SyncException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The only host Picsou may call is SimpleFIN Bridge. These probes try to get another
 * destination past the check, for both the pasted claim URL and the access URL a claim returns.
 */
class SimplefinUrlsTest {

    private static final String BRIDGE = SimplefinUrls.BRIDGE_HOST;
    private static final String PASSWORD = "s3cr3tPassw0rd";
    private static final String USERNAME = "usr98765";
    private static final LocalDate START = LocalDate.of(2026, 1, 1);

    // ---- claim URL: accepted forms -------------------------------------------------------

    @Test
    void aClaimUrlOnTheBridgeIsAccepted() {
        assertThat(SimplefinUrls.claimUri(token("https://" + BRIDGE + "/simplefin/claim/abc")))
            .isEqualTo(URI.create("https://" + BRIDGE + "/simplefin/claim/abc"));
    }

    @Test
    void anUppercaseHostIsAccepted() {
        URI uri = SimplefinUrls.claimUri(token("https://BETA-Bridge.SimpleFIN.org/simplefin/claim/abc"));
        assertThat(uri.getHost()).isEqualToIgnoringCase(BRIDGE);
    }

    @Test
    void anUppercaseSchemeIsAccepted() {
        assertThat(SimplefinUrls.claimUri(token("HTTPS://" + BRIDGE + "/simplefin/claim/abc")).getHost())
            .isEqualTo(BRIDGE);
    }

    @Test
    void aRawHttpsUrlIsAcceptedWithoutBase64() {
        assertThat(SimplefinUrls.claimUri("https://" + BRIDGE + "/simplefin/claim/abc").getHost()).isEqualTo(BRIDGE);
        assertThat(SimplefinUrls.claimUri("HTTPS://" + BRIDGE + "/simplefin/claim/abc").getHost()).isEqualTo(BRIDGE);
    }

    @Test
    void theDefaultHttpsPortIsAccepted() {
        assertThat(SimplefinUrls.claimUri(token("https://" + BRIDGE + ":443/simplefin/claim/abc")).getHost())
            .isEqualTo(BRIDGE);
    }

    @Test
    void whitespaceAroundAndInsideTheTokenIsIgnored() {
        String encoded = token("https://" + BRIDGE + "/simplefin/claim/abc");
        String wrapped = "  \n\t" + encoded.substring(0, 10) + "\r\n" + encoded.substring(10) + " \n";
        assertThat(SimplefinUrls.claimUri(wrapped).getHost()).isEqualTo(BRIDGE);
    }

    @Test
    void unpaddedAndUrlSafeBase64AreBothAccepted() {
        // A '?' at an index that is 2 modulo 3 encodes as '/' in the standard alphabet and '_' in base64url.
        StringBuilder url = new StringBuilder("https://" + BRIDGE + "/simplefin/claim/a");
        while (url.length() % 3 != 2) url.append('a');
        url.append("?x=1");
        byte[] bytes = url.toString().getBytes(StandardCharsets.UTF_8);

        String standard = Base64.getEncoder().encodeToString(bytes);
        assertThat(standard).as("fixture must exercise the alphabet difference").containsAnyOf("/", "+");

        assertThat(SimplefinUrls.claimUri(standard).getHost()).isEqualTo(BRIDGE);
        assertThat(SimplefinUrls.claimUri(Base64.getUrlEncoder().encodeToString(bytes)).getHost()).isEqualTo(BRIDGE);
        assertThat(SimplefinUrls.claimUri(Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)).getHost())
            .isEqualTo(BRIDGE);
        assertThat(SimplefinUrls.claimUri(Base64.getEncoder().withoutPadding().encodeToString(bytes)).getHost())
            .isEqualTo(BRIDGE);
    }

    // ---- claim URL: refused forms --------------------------------------------------------

    /** Every entry is a full URL; each must be refused as a claim whether raw or Base64-wrapped. */
    static final List<String> FOREIGN_DESTINATIONS = List.of(
        // trailing dot: a different DNS name as far as the equality check is concerned
        "https://" + BRIDGE + "./simplefin/claim/abc",
        // userinfo tricks
        "https://" + BRIDGE + "@evil.example/x",
        "https://" + BRIDGE + ":pw@evil.example/x",
        "https://" + BRIDGE + ":443@evil.example/x",
        "https://user:pass@" + BRIDGE + "@evil.example/x",
        "https://evil.example#@" + BRIDGE,
        "https://evil.example?@" + BRIDGE,
        "https://evil.example/@" + BRIDGE,
        "https://evil.example\\@" + BRIDGE,
        "https://" + BRIDGE + "\\@evil.example/x",
        "https://" + BRIDGE + "\\.evil.example/x",
        // lookalike suffix/prefix hosts
        "https://" + BRIDGE + ".evil.example/x",
        "https://evil" + BRIDGE + "/x",
        "https://evil." + BRIDGE + "/x",
        "https://bridge.simplefin.org/x",
        "https://simplefin.org/x",
        // percent-encoding in the authority
        "https://beta%2Dbridge.simplefin.org/x",
        "https://beta-bridge%2Esimplefin.org/x",
        "https://" + BRIDGE + "%2Eevil.example/x",
        "https://" + BRIDGE + "%00.evil.example/x",
        "https://%62eta-bridge.simplefin.org/x",
        // IDN / punycode / full-width look-alikes
        "https://b\u0435ta-bridge.simplefin.org/x",
        "https://xn--bta-bridge-3hd.simplefin.org/x",
        "https://xn--beta-bridge.simplefin.org/x",
        "https://\uff42eta-bridge.simplefin.org/x",
        // IP literals and local names
        "https://[::1]/x",
        "https://[::ffff:10.0.0.5]/x",
        "https://[::ffff:a00:5]/x",
        "https://10.0.0.5/x",
        "https://127.0.0.1/x",
        "https://169.254.169.254/latest/meta-data/",
        "https://2130706433/x",
        "https://0x7f000001/x",
        "https://localhost/x",
        "https://localhost./x",
        // other schemes
        "http://" + BRIDGE + "/x",
        "HTTP://" + BRIDGE + "/x",
        "ftp://" + BRIDGE + "/x",
        "file:///etc/passwd",
        "file://" + BRIDGE + "/etc/passwd",
        "jar:https://" + BRIDGE + "/x!/",
        "jar:file:/tmp/x.jar!/",
        "//" + BRIDGE + "/x",
        "/" + BRIDGE + "/x",
        BRIDGE + "/x",
        "https:" + BRIDGE + "/x",
        "https:/" + BRIDGE + "/x",
        "https:///" + BRIDGE + "/x",
        "javascript:alert(1)",
        "data:text/plain;base64,aHR0cHM6Ly9iZXRhLWJyaWRnZS5zaW1wbGVmaW4ub3Jn"
    );

    @Test
    void everyForeignDestinationIsRefusedAsABase64Claim() {
        List<String> accepted = new ArrayList<>();
        for (String url : FOREIGN_DESTINATIONS) {
            try {
                SimplefinUrls.claimUri(token(url));
                accepted.add(url);
            } catch (SyncException expected) {
                // refused
            }
        }
        assertThat(accepted).as("destinations that got through").isEmpty();
    }

    @Test
    void everyForeignDestinationIsRefusedAsARawClaim() {
        List<String> accepted = new ArrayList<>();
        for (String url : FOREIGN_DESTINATIONS) {
            try {
                SimplefinUrls.claimUri(url);
                accepted.add(url);
            } catch (SyncException expected) {
                // refused
            }
        }
        assertThat(accepted).as("destinations that got through").isEmpty();
    }

    @Test
    void aClaimOnlyFailsWithASyncException() {
        // A refused URL must surface as the friendly SyncException, never as a raw runtime error.
        for (String url : FOREIGN_DESTINATIONS) {
            assertThatThrownBy(() -> SimplefinUrls.claimUri(token(url)))
                .as(url).isInstanceOf(SyncException.class);
        }
    }

    @Test
    void aClaimWithCredentialsIsRefusedEvenOnTheBridge() {
        assertThatThrownBy(() -> SimplefinUrls.claimUri(token("https://u:p@" + BRIDGE + "/claim/abc")))
            .isInstanceOf(SyncException.class).hasMessageContaining("must not contain credentials");
        assertThatThrownBy(() -> SimplefinUrls.claimUri(token("https://u@" + BRIDGE + "/claim/abc")))
            .isInstanceOf(SyncException.class);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "\n", "\t \r\n"})
    void aMissingTokenIsRefused(String token) {
        assertThatThrownBy(() -> SimplefinUrls.claimUri(token))
            .isInstanceOf(SyncException.class).hasMessageContaining("required");
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "not base64!!!",
        "%%%%",
        "aGVsbG8gd29ybGQ=",            // base64 of "hello world"
        "////",                        // valid base64, decodes to bytes that are not a URL
        "AAAA",                        // base64 of three NUL bytes
        "aHR0cHM6Ly9iZXRhLWJyaWRnZS5zaW1wbGVmaW4ub3Jn$",
    })
    void aTokenThatDoesNotDecodeToAClaimUrlIsRefused(String token) {
        assertThatThrownBy(() -> SimplefinUrls.claimUri(token)).isInstanceOf(SyncException.class);
    }

    @Test
    void claimUri_blankToken_isRefused() {
        assertThatThrownBy(() -> SimplefinUrls.claimUri("   ")).isInstanceOf(SyncException.class);
        assertThatThrownBy(() -> SimplefinUrls.claimUri(null)).isInstanceOf(SyncException.class);
    }

    @Test
    void aTokenOverTheLengthCapIsRefusedBeforeDecoding() {
        String oversized = "A".repeat(SimplefinUrls.MAX_TOKEN_CHARS + 1);
        assertThatThrownBy(() -> SimplefinUrls.claimUri(oversized))
            .isInstanceOf(SyncException.class).hasMessageContaining("too long");

        // Whitespace is trimmed first, so a token at exactly the cap plus padding is judged on its trimmed length.
        String atCap = "A".repeat(SimplefinUrls.MAX_TOKEN_CHARS);
        assertThatThrownBy(() -> SimplefinUrls.claimUri("   " + atCap + "   "))
            .isInstanceOf(SyncException.class).hasMessageNotContaining("too long");
    }

    @Test
    void anOversizedRawUrlIsRefused() {
        String raw = "https://" + BRIDGE + "/" + "a".repeat(SimplefinUrls.MAX_TOKEN_CHARS);
        assertThatThrownBy(() -> SimplefinUrls.claimUri(raw))
            .isInstanceOf(SyncException.class).hasMessageContaining("too long");
    }

    @Test
    void aRefusedClaimMessageNeverEchoesTheToken() {
        String secretPath = "claim/SECRET-CLAIM-VALUE-12345";
        List<String> inputs = new ArrayList<>();
        for (String url : FOREIGN_DESTINATIONS) inputs.add(url + "/" + secretPath);
        inputs.add("https://u:p@" + BRIDGE + "/" + secretPath);
        inputs.add("https://" + BRIDGE + "/bad path/" + secretPath);

        for (String url : inputs) {
            for (String candidate : List.of(url, token(url))) {
                try {
                    SimplefinUrls.claimUri(candidate);
                } catch (SyncException ex) {
                    assertThat(everythingPrintable(ex)).as(url)
                        .doesNotContain("SECRET-CLAIM-VALUE-12345")
                        .doesNotContain(Base64.getEncoder().encodeToString(url.getBytes(StandardCharsets.UTF_8)));
                }
            }
        }
    }

    // ---- access URL ----------------------------------------------------------------------

    private static String access(String hostAndPort) {
        return "https://" + USERNAME + ":" + PASSWORD + "@" + hostAndPort + "/simplefin";
    }

    @Test
    void anAccessUrlOnTheBridgeBecomesACredentialFreeAccountsRequest() {
        SimplefinUrls.AccountsRequest request = SimplefinUrls.accountsRequest(access(BRIDGE), START);

        assertThat(request.uri().getHost()).isEqualTo(BRIDGE);
        assertThat(request.uri().getScheme()).isEqualTo("https");
        assertThat(request.uri().getRawUserInfo()).isNull();
        assertThat(request.uri().toString()).doesNotContain(USERNAME).doesNotContain(PASSWORD).doesNotContain("@");
        assertThat(request.uri().getPath()).isEqualTo("/simplefin/accounts");
        assertThat(request.username()).isEqualTo(USERNAME);
        String decoded = new String(
            Base64.getDecoder().decode(request.authorization().substring("Basic ".length())), StandardCharsets.UTF_8);
        assertThat(decoded).isEqualTo(USERNAME + ":" + PASSWORD);
    }

    @Test
    void anUppercaseAccessHostIsAccepted() {
        assertThat(SimplefinUrls.requireAccessUrl(access("BETA-BRIDGE.SIMPLEFIN.ORG"))).isEqualTo(access("BETA-BRIDGE.SIMPLEFIN.ORG"));
        assertThat(SimplefinUrls.accountsRequest(access("BETA-BRIDGE.SIMPLEFIN.ORG"), START).uri().getHost())
            .isEqualToIgnoringCase(BRIDGE);
    }

    @Test
    void anUppercaseAccessSchemeIsAccepted() {
        assertThat(SimplefinUrls.accountsRequest("HTTPS://" + USERNAME + ":" + PASSWORD + "@" + BRIDGE + "/simplefin", START)
            .uri().getScheme()).isEqualToIgnoringCase("https");
    }

    @Test
    void theDefaultPortIsAcceptedOnAnAccessUrl() {
        assertThat(SimplefinUrls.requireAccessUrl(access(BRIDGE + ":443"))).isEqualTo(access(BRIDGE + ":443"));
    }

    @Test
    void anAccessUrlIsReturnedTrimmed() {
        assertThat(SimplefinUrls.requireAccessUrl("  \n" + access(BRIDGE) + "\n  ")).isEqualTo(access(BRIDGE));
    }

    /** Access URLs: same destinations, with credentials on the front where the shape allows it. */
    static List<String> foreignAccessUrls() {
        List<String> urls = new ArrayList<>();
        urls.add(access(BRIDGE + "."));
        urls.add(access("evil.example"));
        urls.add(access(BRIDGE + ".evil.example"));
        urls.add(access("evil." + BRIDGE));
        urls.add(access("bridge.simplefin.org"));
        urls.add(access("[::1]"));
        urls.add(access("[::ffff:10.0.0.5]"));
        urls.add(access("10.0.0.5"));
        urls.add(access("10.0.0.5:8443"));
        urls.add(access("127.0.0.1"));
        urls.add(access("169.254.169.254"));
        urls.add(access("localhost"));
        urls.add(access("b\u0435ta-bridge.simplefin.org"));
        urls.add(access("xn--bta-bridge-3hd.simplefin.org"));
        urls.add(access("beta%2Dbridge.simplefin.org"));
        urls.add(access(BRIDGE + "%2Eevil.example"));
        urls.add(access(BRIDGE + "\\@evil.example"));
        urls.add("https://" + USERNAME + ":" + PASSWORD + "@" + BRIDGE + "@evil.example/simplefin");
        urls.add("https://" + BRIDGE + "@evil.example/simplefin");
        urls.add("https://" + BRIDGE + ":" + PASSWORD + "@evil.example/simplefin");
        urls.add("https://evil.example#@" + BRIDGE);
        urls.add("https://" + USERNAME + ":" + PASSWORD + "@evil.example#@" + BRIDGE);
        urls.add("https://" + USERNAME + ":" + PASSWORD + "@evil.example\\@" + BRIDGE);
        urls.add("http://" + USERNAME + ":" + PASSWORD + "@" + BRIDGE + "/simplefin");
        urls.add("HTTP://" + USERNAME + ":" + PASSWORD + "@" + BRIDGE + "/simplefin");
        urls.add("ftp://" + USERNAME + ":" + PASSWORD + "@" + BRIDGE + "/simplefin");
        urls.add("file://" + USERNAME + ":" + PASSWORD + "@" + BRIDGE + "/etc/passwd");
        urls.add("jar:https://" + USERNAME + ":" + PASSWORD + "@" + BRIDGE + "/x!/");
        urls.add("//" + USERNAME + ":" + PASSWORD + "@" + BRIDGE + "/simplefin");
        return urls;
    }

    @Test
    void everyForeignAccessUrlIsRefusedWithASyncExceptionThatKeepsTheCredentialsOut() {
        for (String url : foreignAccessUrls()) {
            assertThatThrownBy(() -> SimplefinUrls.requireAccessUrl(url)).as("requireAccessUrl " + url)
                .isInstanceOf(SyncException.class)
                .satisfies(ex -> assertThat(everythingPrintable(ex)).doesNotContain(PASSWORD).doesNotContain(USERNAME));
            assertThatThrownBy(() -> SimplefinUrls.accountsRequest(url, START)).as("accountsRequest " + url)
                .isInstanceOf(SyncException.class)
                .satisfies(ex -> assertThat(everythingPrintable(ex)).doesNotContain(PASSWORD).doesNotContain(USERNAME));
        }
    }

    @Test
    void anAccessUrlWithoutUsableCredentialsIsRefused() {
        for (String url : List.of(
            "https://" + BRIDGE + "/simplefin",
            "https://@" + BRIDGE + "/simplefin",
            "https://:@" + BRIDGE + "/simplefin",
            "https://" + USERNAME + "@" + BRIDGE + "/simplefin",
            "https://" + USERNAME + ":@" + BRIDGE + "/simplefin",
            "https://:" + PASSWORD + "@" + BRIDGE + "/simplefin")) {
            assertThatThrownBy(() -> SimplefinUrls.requireAccessUrl(url)).as(url)
                .isInstanceOf(SyncException.class)
                .satisfies(ex -> assertThat(everythingPrintable(ex)).doesNotContain(PASSWORD).doesNotContain(USERNAME));
        }
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "\n"})
    void aMissingAccessUrlIsRefused(String url) {
        assertThatThrownBy(() -> SimplefinUrls.requireAccessUrl(url)).isInstanceOf(SyncException.class);
        assertThatThrownBy(() -> SimplefinUrls.accountsRequest(url, START)).isInstanceOf(SyncException.class);
    }

    @Test
    void anAccessUrlOverTheLengthCapIsRefused() {
        String url = "https://" + USERNAME + ":" + "p".repeat(SimplefinUrls.MAX_ACCESS_URL_CHARS) + "@" + BRIDGE + "/simplefin";
        assertThatThrownBy(() -> SimplefinUrls.requireAccessUrl(url))
            .isInstanceOf(SyncException.class)
            .satisfies(ex -> assertThat(everythingPrintable(ex)).doesNotContain("ppppp"));
    }

    @Test
    void aMalformedPercentEscapeInTheCredentialsIsRefusedWithoutEchoingThem() {
        // URLDecoder's IllegalArgumentException quotes the offending text, which would be the password.
        String url = "https://" + USERNAME + ":" + PASSWORD + "%zz@" + BRIDGE + "/simplefin";
        assertThatThrownBy(() -> SimplefinUrls.requireAccessUrl(url))
            .isInstanceOf(SyncException.class)
            .satisfies(ex -> assertThat(everythingPrintable(ex)).doesNotContain(PASSWORD));
    }

    @Test
    void theAccountsRequestDropsQueryAndFragmentFromTheAccessUrl() {
        SimplefinUrls.AccountsRequest request = SimplefinUrls.accountsRequest(
            "https://" + USERNAME + ":" + PASSWORD + "@" + BRIDGE + "/simplefin?redirect=https://evil.example#frag", START);

        assertThat(request.uri().getHost()).isEqualTo(BRIDGE);
        assertThat(request.uri().getRawQuery()).startsWith("version=2&start-date=").doesNotContain("evil");
        assertThat(request.uri().getFragment()).isNull();
    }

    private static String token(String url) {
        return Base64.getEncoder().encodeToString(url.getBytes(StandardCharsets.UTF_8));
    }

    /** Message and every cause's message and class: what a log line or a stack trace would print. */
    static String everythingPrintable(Throwable ex) {
        StringBuilder out = new StringBuilder();
        for (Throwable t = ex; t != null; t = t.getCause()) {
            out.append(t.getClass().getName()).append(": ").append(t.getMessage()).append('\n');
            if (t.getCause() == t) break;
        }
        return out.toString();
    }
}

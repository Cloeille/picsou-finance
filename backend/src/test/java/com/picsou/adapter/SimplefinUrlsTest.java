package com.picsou.adapter;

import com.picsou.exception.SyncException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.Base64;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The only destination Picsou may call is SimpleFIN Bridge over https on its default port.
 * This class owns the allowlist matrix, for both the pasted claim URL and the access URL a claim
 * returns; the client and service tests only prove they call into it.
 */
class SimplefinUrlsTest {

    private static final String BRIDGE = SimplefinUrls.BRIDGE_HOST;
    private static final String USERNAME = "usr98765";
    private static final String PASSWORD = "s3cr3tPassw0rd";
    private static final String CLAIM_SECRET = "SECRET-CLAIM-VALUE-12345";
    private static final String PORT_REFUSED = "Picsou only connects to SimpleFIN Bridge (" + BRIDGE + ").";
    private static final LocalDate START = LocalDate.of(2026, 1, 1);

    /** Authorities that are not the Bridge. Each is spliced into a claim URL and into an access URL. */
    static List<String> foreignHosts() {
        return List.of(
            "evil.example", BRIDGE + ".", BRIDGE + ".evil.example", "evil" + BRIDGE, "evil." + BRIDGE,
            "bridge.simplefin.org", "simplefin.org",
            // percent-encoding in the authority
            "beta%2Dbridge.simplefin.org", "beta-bridge%2Esimplefin.org", BRIDGE + "%2Eevil.example",
            BRIDGE + "%00.evil.example", "%62eta-bridge.simplefin.org",
            // IDN, punycode and full-width look-alikes
            "b\u0435ta-bridge.simplefin.org", "xn--bta-bridge-3hd.simplefin.org", "\uff42eta-bridge.simplefin.org",
            // IP literals and local names
            "[::1]", "[::ffff:10.0.0.5]", "[::ffff:a00:5]", "10.0.0.5", "10.0.0.5:8443", "127.0.0.1",
            "169.254.169.254", "2130706433", "0x7f000001", "localhost", "localhost.",
            // userinfo, fragment, query and backslash tricks
            BRIDGE + "@evil.example", BRIDGE + ":pw@evil.example", BRIDGE + ":443@evil.example",
            "u:p@" + BRIDGE + "@evil.example", "evil.example#@" + BRIDGE, "evil.example?@" + BRIDGE,
            "evil.example/@" + BRIDGE, "evil.example\\@" + BRIDGE, BRIDGE + "\\@evil.example",
            BRIDGE + "\\.evil.example");
    }

    /** Claim URLs that are not https on the Bridge for another reason than the host. */
    static List<String> nonHttpsClaimUrls() {
        return List.of(
            "http://" + BRIDGE + "/x", "HTTP://" + BRIDGE + "/x", "ftp://" + BRIDGE + "/x",
            "file:///etc/passwd", "file://" + BRIDGE + "/etc/passwd",
            "jar:https://" + BRIDGE + "/x!/", "jar:file:/tmp/x.jar!/",
            "//" + BRIDGE + "/x", "/" + BRIDGE + "/x", BRIDGE + "/x",
            "https:" + BRIDGE + "/x", "https:/" + BRIDGE + "/x", "https:///" + BRIDGE + "/x",
            "javascript:alert(1)", "data:text/plain;base64,aHR0cHM6Ly9iZXRhLWJyaWRnZS5zaW1wbGVmaW4ub3Jn");
    }

    static List<String> nonHttpsAccessUrls() {
        String creds = USERNAME + ":" + PASSWORD + "@";
        return List.of(
            "http://" + creds + BRIDGE + "/simplefin", "HTTP://" + creds + BRIDGE + "/simplefin",
            "ftp://" + creds + BRIDGE + "/simplefin", "file://" + creds + BRIDGE + "/etc/passwd",
            "jar:https://" + creds + BRIDGE + "/x!/", "//" + creds + BRIDGE + "/simplefin");
    }

    static Stream<Arguments> acceptedClaimTokens() {
        String url = "https://" + BRIDGE + "/simplefin/claim/abc";
        String encoded = token(url);
        // A '?' at an index that is 2 modulo 3 encodes as '/' in the standard alphabet and '_' in base64url.
        StringBuilder padded = new StringBuilder("https://" + BRIDGE + "/simplefin/claim/a");
        while (padded.length() % 3 != 2) padded.append('a');
        byte[] bytes = padded.append("?x=1").toString().getBytes(StandardCharsets.UTF_8);
        assertThat(Base64.getEncoder().encodeToString(bytes)).containsAnyOf("/", "+");
        return Stream.of(
            Arguments.of("base64", encoded),
            Arguments.of("raw https", url),
            Arguments.of("uppercase scheme", "HTTPS://" + BRIDGE + "/simplefin/claim/abc"),
            Arguments.of("uppercase host", token("https://BETA-Bridge.SimpleFIN.org/simplefin/claim/abc")),
            Arguments.of("explicit 443", token("https://" + BRIDGE + ":443/simplefin/claim/abc")),
            Arguments.of("whitespace around and inside",
                "  \n\t" + encoded.substring(0, 10) + "\r\n" + encoded.substring(10) + " \n"),
            Arguments.of("standard alphabet", Base64.getEncoder().encodeToString(bytes)),
            Arguments.of("standard unpadded", Base64.getEncoder().withoutPadding().encodeToString(bytes)),
            Arguments.of("url-safe", Base64.getUrlEncoder().encodeToString(bytes)),
            Arguments.of("url-safe unpadded", Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)));
    }

    static Stream<Arguments> acceptedAccessUrls() {
        return Stream.of(
            Arguments.of("bridge host", accessUrl(BRIDGE)),
            Arguments.of("uppercase host", accessUrl("BETA-BRIDGE.SIMPLEFIN.ORG")),
            Arguments.of("uppercase scheme", "HTTPS://" + USERNAME + ":" + PASSWORD + "@" + BRIDGE + "/simplefin"),
            Arguments.of("explicit 443", accessUrl(BRIDGE + ":443")));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("acceptedClaimTokens")
    void claimUri_acceptedForm_pointsAtTheBridge(String form, String setupToken) {
        assertThat(SimplefinUrls.claimUri(setupToken).getHost()).isEqualToIgnoringCase(BRIDGE);
    }

    @ParameterizedTest
    @MethodSource("foreignHosts")
    void claimUri_foreignHost_isRefusedWithoutEchoingTheToken(String host) {
        String url = "https://" + host + "/claim/" + CLAIM_SECRET;

        assertThatThrownBy(() -> SimplefinUrls.claimUri(url))
            .isInstanceOf(SyncException.class)
            .satisfies(ex -> assertThat(everythingPrintable(ex)).doesNotContain(CLAIM_SECRET));
    }

    @ParameterizedTest
    @MethodSource("foreignHosts")
    void claimUri_foreignHostInBase64_isRefusedWithoutEchoingTheToken(String host) {
        String encoded = token("https://" + host + "/claim/" + CLAIM_SECRET);

        assertThatThrownBy(() -> SimplefinUrls.claimUri(encoded))
            .isInstanceOf(SyncException.class)
            .satisfies(ex -> assertThat(everythingPrintable(ex)).doesNotContain(CLAIM_SECRET).doesNotContain(encoded));
    }

    @ParameterizedTest
    @MethodSource("nonHttpsClaimUrls")
    void claimUri_nonHttpsUrl_isRefused(String url) {
        assertThatThrownBy(() -> SimplefinUrls.claimUri(token(url))).isInstanceOf(SyncException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {":8443", ":80", ":444"})
    void claimUri_nonStandardPortOnTheBridge_isRefused(String port) {
        assertThatThrownBy(() -> SimplefinUrls.claimUri(token("https://" + BRIDGE + port + "/simplefin/claim/abc")))
            .isInstanceOf(SyncException.class).hasMessage(PORT_REFUSED);
    }

    @Test
    void claimUri_credentialsEvenOnTheBridge_areRefused() {
        assertThatThrownBy(() -> SimplefinUrls.claimUri(token("https://u:p@" + BRIDGE + "/claim/abc")))
            .isInstanceOf(SyncException.class).hasMessageContaining("must not contain credentials");
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "\n", "\t \r\n"})
    void claimUri_blankToken_isRefused(String setupToken) {
        assertThatThrownBy(() -> SimplefinUrls.claimUri(setupToken))
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
    void claimUri_tokenThatIsNotAClaimUrl_isRefused(String setupToken) {
        assertThatThrownBy(() -> SimplefinUrls.claimUri(setupToken)).isInstanceOf(SyncException.class);
    }

    @Test
    void claimUri_tokenOverTheCap_isRefusedBeforeDecoding() {
        String oversized = "A".repeat(SimplefinUrls.MAX_TOKEN_CHARS + 1);

        assertThatThrownBy(() -> SimplefinUrls.claimUri(oversized))
            .isInstanceOf(SyncException.class).hasMessageContaining("too long");
    }

    @Test
    void claimUri_tokenAtTheCapWithPadding_isJudgedOnItsTrimmedLength() {
        String atCap = "A".repeat(SimplefinUrls.MAX_TOKEN_CHARS);

        assertThatThrownBy(() -> SimplefinUrls.claimUri("   " + atCap + "   "))
            .isInstanceOf(SyncException.class).hasMessageNotContaining("too long");
    }

    @Test
    void claimUri_oversizedRawUrl_isRefused() {
        String raw = "https://" + BRIDGE + "/" + "a".repeat(SimplefinUrls.MAX_TOKEN_CHARS);

        assertThatThrownBy(() -> SimplefinUrls.claimUri(raw))
            .isInstanceOf(SyncException.class).hasMessageContaining("too long");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("acceptedAccessUrls")
    void requireAccessUrl_acceptedForm_isReturnedUnchanged(String form, String accessUrl) {
        assertThat(SimplefinUrls.requireAccessUrl(accessUrl)).isEqualTo(accessUrl);
    }

    @Test
    void requireAccessUrl_surroundingWhitespace_isTrimmed() {
        assertThat(SimplefinUrls.requireAccessUrl("  \n" + accessUrl(BRIDGE) + "\n  ")).isEqualTo(accessUrl(BRIDGE));
    }

    @Test
    void accountsRequest_accessUrl_becomesACredentialFreeUriPlusBasicHeader() {
        SimplefinUrls.AccountsRequest request = SimplefinUrls.accountsRequest(accessUrl(BRIDGE), START);

        assertThat(request.uri().getHost()).isEqualTo(BRIDGE);
        assertThat(request.uri().getScheme()).isEqualTo("https");
        assertThat(request.uri().getRawUserInfo()).isNull();
        assertThat(request.uri().getPath()).isEqualTo("/simplefin/accounts");
        assertThat(request.uri().toString()).doesNotContain(USERNAME).doesNotContain(PASSWORD).doesNotContain("@");
        assertThat(request.username()).isEqualTo(USERNAME);
        assertThat(basicCredentials(request.authorization())).isEqualTo(USERNAME + ":" + PASSWORD);
    }

    @Test
    void accountsRequest_queryAndFragmentOnTheAccessUrl_areDropped() {
        SimplefinUrls.AccountsRequest request = SimplefinUrls.accountsRequest(
            accessUrl(BRIDGE) + "?redirect=https://evil.example#frag", START);

        assertThat(request.uri().getHost()).isEqualTo(BRIDGE);
        assertThat(request.uri().getRawQuery()).startsWith("version=2&start-date=").doesNotContain("evil");
        assertThat(request.uri().getFragment()).isNull();
    }

    @Test
    void accountsRequest_plusInThePassword_isNotTurnedIntoASpace() {
        String header = SimplefinUrls.accountsRequest(
            "https://user:p+ss%2Bword@" + BRIDGE + "/simplefin", START).authorization();

        assertThat(basicCredentials(header)).isEqualTo("user:p+ss+word");
    }

    @ParameterizedTest
    @MethodSource("foreignHosts")
    void requireAccessUrl_foreignHost_isRefusedWithoutEchoingTheCredentials(String host) {
        assertThatThrownBy(() -> SimplefinUrls.requireAccessUrl(accessUrl(host)))
            .isInstanceOf(SyncException.class)
            .satisfies(ex -> assertThat(everythingPrintable(ex)).doesNotContain(PASSWORD).doesNotContain(USERNAME));
    }

    @ParameterizedTest
    @MethodSource("nonHttpsAccessUrls")
    void requireAccessUrl_nonHttpsUrl_isRefusedWithoutEchoingTheCredentials(String url) {
        assertThatThrownBy(() -> SimplefinUrls.requireAccessUrl(url))
            .isInstanceOf(SyncException.class)
            .satisfies(ex -> assertThat(everythingPrintable(ex)).doesNotContain(PASSWORD).doesNotContain(USERNAME));
    }

    @ParameterizedTest
    @ValueSource(strings = {":8443", ":80", ":444"})
    void requireAccessUrl_nonStandardPortOnTheBridge_isRefused(String port) {
        assertThatThrownBy(() -> SimplefinUrls.requireAccessUrl(accessUrl(BRIDGE + port)))
            .isInstanceOf(SyncException.class).hasMessage(PORT_REFUSED);
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "https://" + BRIDGE + "/simplefin",
        "https://@" + BRIDGE + "/simplefin",
        "https://:@" + BRIDGE + "/simplefin",
        "https://" + USERNAME + "@" + BRIDGE + "/simplefin",
        "https://" + USERNAME + ":@" + BRIDGE + "/simplefin",
        "https://:" + PASSWORD + "@" + BRIDGE + "/simplefin",
    })
    void requireAccessUrl_withoutUsableCredentials_isRefused(String url) {
        assertThatThrownBy(() -> SimplefinUrls.requireAccessUrl(url))
            .isInstanceOf(SyncException.class)
            .satisfies(ex -> assertThat(everythingPrintable(ex)).doesNotContain(PASSWORD).doesNotContain(USERNAME));
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "\n"})
    void accessUrl_missing_isRefused(String url) {
        assertThatThrownBy(() -> SimplefinUrls.requireAccessUrl(url)).isInstanceOf(SyncException.class);
        assertThatThrownBy(() -> SimplefinUrls.accountsRequest(url, START)).isInstanceOf(SyncException.class);
    }

    @Test
    void requireAccessUrl_overTheCap_isRefusedWithoutEchoingIt() {
        String url = "https://" + USERNAME + ":" + "p".repeat(SimplefinUrls.MAX_ACCESS_URL_CHARS) + "@" + BRIDGE + "/simplefin";

        assertThatThrownBy(() -> SimplefinUrls.requireAccessUrl(url))
            .isInstanceOf(SyncException.class)
            .satisfies(ex -> assertThat(everythingPrintable(ex)).doesNotContain("ppppp"));
    }

    @Test
    void requireAccessUrl_malformedPercentEscape_isRefusedWithoutEchoingTheCredentials() {
        // URLDecoder's IllegalArgumentException quotes the offending text, which would be the password.
        String url = "https://" + USERNAME + ":" + PASSWORD + "%zz@" + BRIDGE + "/simplefin";

        assertThatThrownBy(() -> SimplefinUrls.requireAccessUrl(url))
            .isInstanceOf(SyncException.class)
            .satisfies(ex -> assertThat(everythingPrintable(ex)).doesNotContain(PASSWORD));
    }

    private static String accessUrl(String authority) {
        return "https://" + USERNAME + ":" + PASSWORD + "@" + authority + "/simplefin";
    }

    private static String token(String url) {
        return Base64.getEncoder().encodeToString(url.getBytes(StandardCharsets.UTF_8));
    }

    private static String basicCredentials(String authorization) {
        return new String(Base64.getDecoder().decode(authorization.substring("Basic ".length())), StandardCharsets.UTF_8);
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

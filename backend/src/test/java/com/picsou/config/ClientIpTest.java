package com.picsou.config;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link ClientIp#resolve} is the trusted-IP contract every rate limiter now depends on — see
 * {@code docs/features/security-cors-cookies.md} ("client IP trust"). These cases mirror the
 * attack this class defends against: a spoofed X-Forwarded-For must never influence the
 * resolved key, and a header that fails to parse as a plain IP literal must fail closed to
 * {@code getRemoteAddr()} rather than be trusted verbatim.
 */
class ClientIpTest {

    @Test
    void resolve_prefersXRealIp_overSpoofedXForwardedFor() {
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.setRemoteAddr("172.18.0.2"); // e.g. tainted by ForwardedHeaderFilter from the spoofed XFF below
        req.addHeader("X-Forwarded-For", "1.2.3.4, 5.6.7.8"); // attacker rotates this per request
        req.addHeader("X-Real-IP", "203.0.113.9"); // set by nginx, not attacker-reachable

        assertThat(ClientIp.resolve(req)).isEqualTo("203.0.113.9");
    }

    @Test
    void resolve_fallsBackToRemoteAddr_whenNoHeadersPresent() {
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.setRemoteAddr("127.0.0.1"); // dev / test: no proxy in front, ForwardedHeaderFilter is a no-op

        assertThat(ClientIp.resolve(req)).isEqualTo("127.0.0.1");
    }

    @Test
    void resolve_fallsBackToRemoteAddr_whenXRealIpIsGarbage() {
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.setRemoteAddr("10.0.0.5");
        req.addHeader("X-Real-IP", "garbage<script>");

        assertThat(ClientIp.resolve(req)).isEqualTo("10.0.0.5");
    }

    @Test
    void resolve_fallsBackToRemoteAddr_whenXRealIpIsBlankOrAbsent() {
        MockHttpServletRequest blank = new MockHttpServletRequest();
        blank.setRemoteAddr("10.0.0.5");
        blank.addHeader("X-Real-IP", "   ");
        assertThat(ClientIp.resolve(blank)).isEqualTo("10.0.0.5");

        MockHttpServletRequest empty = new MockHttpServletRequest();
        empty.setRemoteAddr("10.0.0.6");
        empty.addHeader("X-Real-IP", "");
        assertThat(ClientIp.resolve(empty)).isEqualTo("10.0.0.6");
    }

    @Test
    void resolve_fallsBackToRemoteAddr_whenXRealIpCarriesMultipleValues() {
        // Unlike XFF, our nginx never emits a comma-joined X-Real-IP -- a value shaped like
        // one is not from our proxy and must not be trusted verbatim.
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.setRemoteAddr("10.0.0.5");
        req.addHeader("X-Real-IP", "203.0.113.9, 1.2.3.4");

        assertThat(ClientIp.resolve(req)).isEqualTo("10.0.0.5");
    }

    @Test
    void resolve_fallsBackToRemoteAddr_whenXRealIpExceedsMaxLiteralLength() {
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.setRemoteAddr("10.0.0.5");
        req.addHeader("X-Real-IP", "1".repeat(500));

        assertThat(ClientIp.resolve(req)).isEqualTo("10.0.0.5");
    }

    @Test
    void resolve_trustsWellFormedIPv4Literals_atBoundariesAndOrdinary() {
        assertThat(resolveWithXRealIp("0.0.0.0")).isEqualTo("0.0.0.0");             // min
        assertThat(resolveWithXRealIp("255.255.255.255")).isEqualTo("255.255.255.255"); // max
        assertThat(resolveWithXRealIp("203.0.113.9")).isEqualTo("203.0.113.9");     // ordinary
        assertThat(resolveWithXRealIp("10.0.0.1")).isEqualTo("10.0.0.1");
    }

    @Test
    void resolve_rejectsMalformedIPv4_fallsBackToRemoteAddr() {
        assertThat(resolveWithXRealIp("256.1.1.1")).isEqualTo("10.0.0.5");   // octet out of range
        assertThat(resolveWithXRealIp("1.1.1")).isEqualTo("10.0.0.5");       // too few octets
        assertThat(resolveWithXRealIp("1.1.1.1.1")).isEqualTo("10.0.0.5");   // too many octets
        assertThat(resolveWithXRealIp("01.1.1.1")).isEqualTo("10.0.0.5");    // leading zero
        assertThat(resolveWithXRealIp("1.1.1.-1")).isEqualTo("10.0.0.5");    // negative
        assertThat(resolveWithXRealIp("1.1.1.")).isEqualTo("10.0.0.5");      // trailing dot
    }

    @Test
    void resolve_trustsWellFormedIPv6Literals() {
        assertThat(resolveWithXRealIp("::1")).isEqualTo("::1"); // loopback, min form
        assertThat(resolveWithXRealIp("2001:db8::1")).isEqualTo("2001:db8::1"); // compressed
        assertThat(resolveWithXRealIp("::ffff:192.0.2.1")).isEqualTo("::ffff:192.0.2.1"); // IPv4-mapped
        assertThat(resolveWithXRealIp("fe80::1")).isEqualTo("fe80::1");
        // fully expanded, max-length form
        assertThat(resolveWithXRealIp("2001:0db8:0000:0000:0000:0000:0000:0001"))
            .isEqualTo("2001:0db8:0000:0000:0000:0000:0000:0001");
    }

    @Test
    void resolve_rejectsMalformedIPv6_fallsBackToRemoteAddr() {
        assertThat(resolveWithXRealIp("gggg::1")).isEqualTo("10.0.0.5");             // non-hex chars
        assertThat(resolveWithXRealIp("1::2::3")).isEqualTo("10.0.0.5");             // two "::" compressions
        assertThat(resolveWithXRealIp("1:2:3:4:5:6:7:8:9")).isEqualTo("10.0.0.5");   // too many groups
        assertThat(resolveWithXRealIp("fe80::1%eth0")).isEqualTo("10.0.0.5");        // zone id -- unsupported
    }

    /** Builds a request whose remoteAddr is the fixed sentinel "10.0.0.5" and given X-Real-IP header. */
    private static String resolveWithXRealIp(String xRealIp) {
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.setRemoteAddr("10.0.0.5");
        req.addHeader("X-Real-IP", xRealIp);
        return ClientIp.resolve(req);
    }
}

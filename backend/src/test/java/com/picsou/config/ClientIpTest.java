package com.picsou.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.assertj.core.api.Assertions.assertThat;

/** Header interpretation belongs to the container's trusted-proxy policy, never this helper. */
class ClientIpTest {

    @Test
    void resolve_ignoresForwardedHeaders_fromDirectClient() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("198.51.100.20");
        request.addHeader("X-Real-IP", "203.0.113.9");
        request.addHeader("X-Forwarded-For", "203.0.113.10");
        request.addHeader("Forwarded", "for=203.0.113.11");

        assertThat(ClientIp.resolve(request)).isEqualTo("198.51.100.20");
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "0.0.0.0", "255.255.255.255", "203.0.113.9", "10.0.0.1",
        "::1", "2001:db8::1", "::ffff:192.0.2.1", "fe80::1",
        "2001:0db8:0000:0000:0000:0000:0000:0001",
        "garbage<script>", "   ", "", "203.0.113.9, 1.2.3.4",
        "256.1.1.1", "1.1.1", "1.1.1.1.1", "01.1.1.1", "1.1.1.-1", "1.1.1.",
        "gggg::1", "1::2::3", "1:2:3:4:5:6:7:8:9", "fe80::1%eth0"
    })
    void resolve_ignoresLiteralAndMalformedHeaderValues(String header) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("198.51.100.20");
        request.addHeader("X-Real-IP", header);
        request.addHeader("X-Forwarded-For", header);

        assertThat(ClientIp.resolve(request)).isEqualTo("198.51.100.20");
    }

    @Test
    void resolve_ignoresOversizedAndRepeatedHeaders() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("198.51.100.20");
        request.addHeader("X-Real-IP", "1".repeat(500));
        request.addHeader("X-Real-IP", "203.0.113.9");
        request.addHeader("X-Forwarded-For", "203.0.113.10");
        request.addHeader("X-Forwarded-For", "203.0.113.11");

        assertThat(ClientIp.resolve(request)).isEqualTo("198.51.100.20");
    }

    @ParameterizedTest
    @ValueSource(strings = {"127.0.0.1", "198.51.100.20", "2001:db8::20", "::ffff:192.0.2.1"})
    void resolve_returnsTheContainerAddress_whenNoHeadersArePresent(String address) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr(address);

        assertThat(ClientIp.resolve(request)).isEqualTo(address);
    }
}

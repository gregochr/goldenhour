package com.gregochr.goldenhour.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link ClientAddress}: {@code CF-Connecting-IP}, else the first {@code X-Forwarded-For} hop, else
 * {@code remoteAddr}, with blanks skipped and the result capped.
 */
class ClientAddressTest {

    private static MockHttpServletRequest request(String cf, String xff, String remote) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        if (cf != null) {
            request.addHeader("CF-Connecting-IP", cf);
        }
        if (xff != null) {
            request.addHeader("X-Forwarded-For", xff);
        }
        request.setRemoteAddr(remote);
        return request;
    }

    @Test
    @DisplayName("CF-Connecting-IP wins over X-Forwarded-For and remoteAddr")
    void cloudflareHeaderWins() {
        assertThat(ClientAddress.of(request("203.0.113.7", "198.51.100.1", "172.18.0.4")))
                .isEqualTo("203.0.113.7");
    }

    @Test
    @DisplayName("without CF-Connecting-IP, the FIRST X-Forwarded-For hop is used")
    void firstForwardedHop() {
        assertThat(ClientAddress.of(request(null, "198.51.100.1, 10.0.0.2, 172.18.0.4", "172.18.0.9")))
                .isEqualTo("198.51.100.1");
    }

    @Test
    @DisplayName("a single X-Forwarded-For value is used as is, trimmed")
    void singleForwardedValue() {
        assertThat(ClientAddress.of(request(null, "  198.51.100.1  ", "172.18.0.9"))).isEqualTo("198.51.100.1");
    }

    @Test
    @DisplayName("with neither header, remoteAddr is used")
    void remoteAddrFallback() {
        assertThat(ClientAddress.of(request(null, null, "172.18.0.4"))).isEqualTo("172.18.0.4");
    }

    @Test
    @DisplayName("a blank CF-Connecting-IP and a blank first hop are skipped")
    void blanksAreSkipped() {
        assertThat(ClientAddress.of(request("   ", null, "172.18.0.4"))).isEqualTo("172.18.0.4");
        assertThat(ClientAddress.of(request(null, " , 198.51.100.1", "172.18.0.4"))).isEqualTo("172.18.0.4");
    }

    @Test
    @DisplayName("an address over 64 characters is cut to 64 so a forged header cannot choose a huge key")
    void longAddressIsCapped() {
        String forged = "9".repeat(5_000);

        assertThat(ClientAddress.of(request(forged, null, "172.18.0.4"))).isEqualTo("9".repeat(64));
    }

    @Test
    @DisplayName("an IPv6 address passes through unchanged")
    void ipv6() {
        assertThat(ClientAddress.of(request("2001:db8::1", null, "172.18.0.4"))).isEqualTo("2001:db8::1");
    }

    @Test
    @DisplayName("no address anywhere is the literal 'unknown', never null or blank")
    void unknownWhenNothingIsPresent() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("");

        assertThat(ClientAddress.of(request)).isEqualTo("unknown");
    }
}

package com.gregochr.goldenhour.config;

import com.gregochr.goldenhour.service.ask.AskProperties;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link AskBodyLimitFilter}: the body of {@code POST /api/ask} is bounded at a size derived from the
 * permitted thread for both the stream and the reader, the boundary is exact, the declared charset
 * cannot break it, and nothing but a POST is wrapped.
 */
class AskBodyLimitFilterTest {

    private final AskProperties properties = new AskProperties();
    private final AskBodyLimitFilter filter = new AskBodyLimitFilter(properties);

    private int limit() {
        return AskBodyLimitFilter.limitFor(properties.getThread().getMaxExchanges());
    }

    private HttpServletRequest wrapped(MockHttpServletRequest request) throws Exception {
        AtomicReference<HttpServletRequest> seen = new AtomicReference<>();
        filter.doFilter(request, new MockHttpServletResponse(), (req, res) -> seen.set((HttpServletRequest) req));
        return seen.get();
    }

    private static MockHttpServletRequest post(int bytes) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/ask");
        request.setContent("a".repeat(bytes).getBytes(StandardCharsets.UTF_8));
        return request;
    }

    @Test
    @DisplayName("the limit is 4 KiB plus 10 KiB per permitted exchange: 84 KiB at the default cap of 8, "
            + "204 KiB at the largest cap of 20, and it follows the configured cap")
    void limitIsDerivedFromTheCap() {
        assertThat(AskBodyLimitFilter.limitFor(8)).isEqualTo(84 * 1024);
        assertThat(AskBodyLimitFilter.limitFor(20)).isEqualTo(204 * 1024);
        assertThat(limit()).isEqualTo(84 * 1024);
        properties.getThread().setMaxExchanges(20);
        assertThat(limit()).isEqualTo(204 * 1024);
    }

    @Test
    @DisplayName("a worst-case body is under the limit at the default cap and at the largest cap: every "
            + "exchange at its maximum, all astral and escaped, plus an outer body with 20 region ids")
    void worstCaseBodyFits() throws Exception {
        for (int cap : new int[] {8, 20}) {
            properties.getThread().setMaxExchanges(cap);
            byte[] body = worstCaseBody(cap).getBytes(StandardCharsets.UTF_8);

            assertThat(body.length).as("cap %d", cap).isLessThan(limit());
            assertThat(AskBodyLimitFilter.PER_EXCHANGE_BYTES - worstCaseExchange().length())
                    .as("per-exchange margin").isGreaterThan(256);
            assertThat(limit() - body.length).as("margin at cap %d", cap).isGreaterThan(cap * 256);
            assertThat(wrapped(post(body)).getInputStream().readAllBytes()).hasSize(body.length);
            assertThatThrownBy(() -> wrapped(post(limit() + 1)).getInputStream().readAllBytes())
                    .isInstanceOf(IOException.class);
        }
    }

    /** One exchange at the contract's maxima, each code point an escaped surrogate pair (12 bytes). */
    private static String worstCaseExchange() {
        String astral = "\\uD83C\\uDF05";
        String picks = String.join(",", java.util.Collections.nCopies(3,
                "{\"locationId\":9223372036854775807,\"windowId\":\"2026-10-11_sunrise\"}"));
        String events = String.join(",", java.util.Collections.nCopies(10,
                "{\"type\":\"" + "A".repeat(64) + "\",\"date\":\"2026-10-11\"}"));
        return "{\"question\":\"" + astral.repeat(200) + "\",\"summary\":\"" + astral.repeat(500)
                + "\",\"picks\":[" + picks + "],\"events\":[" + events
                + "],\"generatedAt\":\"2026-10-10T14:17:40.123456789\",\"ready\":false}";
    }

    private static String worstCaseBody(int exchanges) {
        String regionIds = String.join(",", java.util.Collections.nCopies(20, "9223372036854775807"));
        return "{\"question\":\"" + "\\uD83C\\uDF05".repeat(160) + "\",\"windowId\":\"2026-10-11_sunrise\","
                + "\"regionIds\":[" + regionIds + "],\"view\":\"coming-up\",\"thread\":["
                + String.join(",", java.util.Collections.nCopies(exchanges, worstCaseExchange())) + "]}";
    }

    private static MockHttpServletRequest post(byte[] content) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/ask");
        request.setContent(content);
        return request;
    }

    @Test
    @DisplayName("a body of exactly the limit is delivered whole, byte by byte and in bulk")
    void atTheLimit() throws Exception {
        int limit = limit();

        assertThat(wrapped(post(limit)).getInputStream().readAllBytes()).hasSize(limit);
        HttpServletRequest single = wrapped(post(limit));
        int count = 0;
        while (single.getInputStream().read() >= 0) {
            count++;
        }
        assertThat(count).isEqualTo(limit);
    }

    @Test
    @DisplayName("one byte over the limit fails the read that passes it, whether read in bulk or singly")
    void overTheLimit() throws Exception {
        int over = limit() + 1;

        assertThatThrownBy(() -> wrapped(post(over)).getInputStream().readAllBytes())
                .isInstanceOf(IOException.class).hasMessageContaining("larger than");
        HttpServletRequest single = wrapped(post(over));
        assertThatThrownBy(() -> {
            while (single.getInputStream().read() >= 0) {
                continue;
            }
        }).isInstanceOf(IOException.class);
    }

    @Test
    @DisplayName("the reader is bounded too, and an unknown declared charset falls back to UTF-8")
    void readerAndBogusCharset() throws Exception {
        MockHttpServletRequest request = post(10);
        request.addHeader("Content-Type", "application/json; charset=not-a-charset");
        try (BufferedReader reader = wrapped(request).getReader()) {
            assertThat(reader.readLine()).isEqualTo("a".repeat(10));
        }
        HttpServletRequest big = wrapped(post(limit() + 1));
        assertThatThrownBy(() -> big.getReader().readLine()).isInstanceOf(IOException.class);
    }

    @Test
    @DisplayName("the same stream is returned on every call, so the count is never reset by asking again")
    void streamIsStable() throws Exception {
        HttpServletRequest request = wrapped(post(5));

        assertThat(request.getInputStream()).isSameAs(request.getInputStream());
    }

    @Test
    @DisplayName("the stream reports the state of the underlying stream: ready, then finished once drained")
    void stateIsDelegated() throws Exception {
        HttpServletRequest request = wrapped(post(3));
        jakarta.servlet.ServletInputStream stream = request.getInputStream();

        assertThat(stream.isFinished()).isFalse();
        assertThat(stream.isReady()).isTrue();
        assertThat(stream.readAllBytes()).hasSize(3);
        assertThat(stream.isFinished()).isTrue();
        assertThatThrownBy(() -> stream.setReadListener(null)).isInstanceOf(RuntimeException.class);
    }

    @Test
    @DisplayName("only a POST is wrapped: a GET reaches the chain as it came")
    void getIsNotWrapped() throws Exception {
        MockHttpServletRequest get = new MockHttpServletRequest("GET", "/api/ask");

        assertThat(wrapped(get)).isSameAs(get);
    }
}

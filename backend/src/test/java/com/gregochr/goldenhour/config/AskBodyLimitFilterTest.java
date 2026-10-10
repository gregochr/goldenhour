package com.gregochr.goldenhour.config;

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
 * {@link AskBodyLimitFilter}: the body of {@code POST /api/ask} is bounded at 16 KiB for both the
 * stream and the reader, the boundary is exact, the declared charset cannot break it, and nothing but
 * a POST is wrapped.
 */
class AskBodyLimitFilterTest {

    private final AskBodyLimitFilter filter = new AskBodyLimitFilter();

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
    @DisplayName("the limit is 16 KiB: room for a full eight-exchange thread, which 8 KiB could not hold")
    void limitIsSixteenKiB() {
        assertThat(AskBodyLimitFilter.MAX_BODY_BYTES).isEqualTo(16 * 1024);
    }

    @Test
    @DisplayName("a body of exactly the limit is delivered whole, byte by byte and in bulk")
    void atTheLimit() throws Exception {
        int limit = AskBodyLimitFilter.MAX_BODY_BYTES;

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
        int over = AskBodyLimitFilter.MAX_BODY_BYTES + 1;

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
        HttpServletRequest big = wrapped(post(AskBodyLimitFilter.MAX_BODY_BYTES + 1));
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

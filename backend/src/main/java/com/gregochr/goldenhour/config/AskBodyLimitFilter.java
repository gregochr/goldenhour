package com.gregochr.goldenhour.config;

import com.gregochr.goldenhour.service.ask.AskProperties;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpMethod;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

/**
 * Bounds the body of {@code POST /api/ask} to {@link #limitFor a size derived from the permitted thread}.
 *
 * <p><b>Why a bound is needed.</b> Nothing else limits it: Tomcat's {@code maxPostSize}
 * ({@code server.tomcat.max-http-form-post-size}, 2 MB) applies only to form-encoded bodies,
 * {@code maxSwallowSize} (2 MB) only to what is read to discard after a response, and this
 * application sets neither a request-size property nor a multipart limit. A JSON body is therefore
 * read by Jackson to its end, bounded only by its stream-read constraints (20 million characters in a
 * string). So an authenticated client could make the server buffer megabytes per request. This
 * filter is the endpoint's own bound, set for this endpoint rather than globally so no other
 * endpoint's behaviour moves.
 *
 * <p><b>Why the limit is derived, not flat.</b> A fresh question is a question of at most 200 characters
 * (the sanitiser refuses a raw input over 1,000 UTF-16 units, which is at most 6,000 bytes even if every
 * unit is written as a six-character JSON escape), a window id, at most 20 region ids and a view: well under
 * 2 KiB in practice. A follow-up also carries the session's earlier exchanges (plan
 * {@code ask-thread-plan.md} §2.1), and the contract admits an exchange of up to 500 code points of summary
 * and 200 of question, three picks, ten events and a {@code generatedAt}. JSON may escape any character
 * as a six-character backslash-u escape: 6 bytes per UTF-16 unit, so 12 per astral code point, which puts
 * the worst single exchange near 9 KiB. A flat bound therefore cannot hold what
 * {@code photocast.ask.thread.max-exchanges} (up to 20) permits, and would refuse such a request in this
 * filter before the service could apply the configured cap.
 * The limit is {@value #BASE_BYTES} bytes for the outer body plus {@value #PER_EXCHANGE_BYTES} bytes per
 * permitted exchange: 84 KiB at the default cap of 8, 204 KiB at the largest cap of 20. It is read from the
 * properties on each request and still caps what one request can make the server read.
 *
 * <p><b>How.</b> The request is wrapped so that reading past the limit throws an {@link IOException}
 * from the body stream, whether the size was declared in {@code Content-Length} or the body is
 * chunked. Spring turns that into an unreadable body, which {@code AskController} answers as 400
 * {@code INVALID} with a fixed sentence. It runs <em>after</em> Spring Security (so an anonymous
 * request is 401 first) and the rate limit counts the request before the body is read at all.
 */
public class AskBodyLimitFilter extends OncePerRequestFilter {

    /**
     * Bytes allowed for everything but the thread: the question (up to 6,000 bytes escaped), a window id,
     * 20 region ids and a view.
     */
    public static final int BASE_BYTES = 4 * 1024;

    /**
     * Bytes allowed per permitted exchange, sized to the worst case the contract admits: a 500-code-point
     * summary and a 200-code-point question, all astral and escaped (12 bytes each, 8,400), plus three
     * picks, ten events, a {@code generatedAt} and the keys, rounded up.
     */
    public static final int PER_EXCHANGE_BYTES = 10 * 1024;

    private final AskProperties properties;

    /**
     * Creates the filter.
     *
     * @param properties the Ask settings, read on each request for the thread cap
     */
    public AskBodyLimitFilter(AskProperties properties) {
        this.properties = properties;
    }

    /**
     * The largest request body accepted for a thread cap.
     *
     * @param maxExchanges {@code photocast.ask.thread.max-exchanges}
     * @return {@link #BASE_BYTES} plus {@link #PER_EXCHANGE_BYTES} for each permitted exchange
     */
    public static int limitFor(int maxExchanges) {
        return BASE_BYTES + maxExchanges * PER_EXCHANGE_BYTES;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !HttpMethod.POST.matches(request.getMethod());
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
            FilterChain chain) throws ServletException, IOException {
        chain.doFilter(new LimitedRequest(request, limitFor(properties.getThread().getMaxExchanges())), response);
    }

    /** A request whose body stream and reader refuse to deliver more than the limit. */
    private static final class LimitedRequest extends HttpServletRequestWrapper {

        private final int limit;
        private ServletInputStream stream;

        LimitedRequest(HttpServletRequest request, int limit) {
            super(request);
            this.limit = limit;
        }

        @Override
        public ServletInputStream getInputStream() throws IOException {
            if (stream == null) {
                stream = new LimitedStream(super.getInputStream(), limit);
            }
            return stream;
        }

        @Override
        public BufferedReader getReader() throws IOException {
            return new BufferedReader(new InputStreamReader(getInputStream(), declaredCharset()));
        }

        /** The request's declared charset, or UTF-8 when none is declared or the name is not one. */
        private Charset declaredCharset() {
            String encoding = getCharacterEncoding();
            if (encoding == null) {
                return StandardCharsets.UTF_8;
            }
            try {
                return Charset.forName(encoding);
            } catch (IllegalArgumentException e) {
                return StandardCharsets.UTF_8;
            }
        }
    }

    /** Counts the bytes handed out and fails the read that would pass the limit. */
    private static final class LimitedStream extends ServletInputStream {

        private final ServletInputStream delegate;
        private final int limit;
        private int delivered;

        LimitedStream(ServletInputStream delegate, int limit) {
            this.delegate = delegate;
            this.limit = limit;
        }

        @Override
        public int read() throws IOException {
            int next = delegate.read();
            if (next >= 0) {
                count(1);
            }
            return next;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            int read = delegate.read(buffer, offset, length);
            if (read > 0) {
                count(read);
            }
            return read;
        }

        private void count(int bytes) throws IOException {
            delivered += bytes;
            if (delivered > limit) {
                throw new IOException("The request body is larger than " + limit + " bytes");
            }
        }

        @Override
        public boolean isFinished() {
            return delegate.isFinished();
        }

        @Override
        public boolean isReady() {
            return delegate.isReady();
        }

        @Override
        public void setReadListener(ReadListener listener) {
            delegate.setReadListener(listener);
        }
    }
}

package com.gregochr.goldenhour.config;

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
 * Bounds the body of {@code POST /api/ask} to {@value #MAX_BODY_BYTES} bytes.
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
 * <p><b>Why 16 KiB.</b> A fresh question is a question of at most 200 characters (the sanitiser refuses a
 * raw input over 1,000 UTF-16 units, which is at most 6,000 bytes even if every unit is written
 * as a six-character JSON escape), a window id, at most 20 region ids and a view: well under 2 KiB in
 * practice. A follow-up also carries the session's earlier exchanges (plan
 * {@code ask-thread-plan.md} §2.1): each is about 285 bytes of JSON structure (three pick objects, an
 * event, a {@code generatedAt} and the keys) plus a question of up to 200 characters and a summary of up
 * to 500 code points, which with multibyte characters is up to about 1 KiB; eight of them plus the outer
 * body with 20 region ids can reach about 8.2 KiB, so the old 8 KiB bound would have refused a
 * legitimate full thread. 16 KiB leaves room and still caps what one request can make the server read.
 *
 * <p><b>How.</b> The request is wrapped so that reading past the limit throws an {@link IOException}
 * from the body stream, whether the size was declared in {@code Content-Length} or the body is
 * chunked. Spring turns that into an unreadable body, which {@code AskController} answers as 400
 * {@code INVALID} with a fixed sentence. It runs <em>after</em> Spring Security (so an anonymous
 * request is 401 first) and the rate limit counts the request before the body is read at all.
 */
public class AskBodyLimitFilter extends OncePerRequestFilter {

    /** The largest request body accepted, in bytes. */
    public static final int MAX_BODY_BYTES = 16 * 1024;

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !HttpMethod.POST.matches(request.getMethod());
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
            FilterChain chain) throws ServletException, IOException {
        chain.doFilter(new LimitedRequest(request), response);
    }

    /** A request whose body stream and reader refuse to deliver more than the limit. */
    private static final class LimitedRequest extends HttpServletRequestWrapper {

        private ServletInputStream stream;

        LimitedRequest(HttpServletRequest request) {
            super(request);
        }

        @Override
        public ServletInputStream getInputStream() throws IOException {
            if (stream == null) {
                stream = new LimitedStream(super.getInputStream());
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
        private int delivered;

        LimitedStream(ServletInputStream delegate) {
            this.delegate = delegate;
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
            if (delivered > MAX_BODY_BYTES) {
                throw new IOException("The request body is larger than " + MAX_BODY_BYTES + " bytes");
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

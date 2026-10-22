package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.service.AuthAttemptLimiter.Window;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Decides whether a request that should carry a Turnstile token may proceed, with a bounded fallback
 * for a browser where the Turnstile script cannot load (login-registration plan §2 #4).
 *
 * <ul>
 *   <li>Turnstile not configured (local development): {@link Outcome#OK_VERIFIED}; the limiter is not
 *       consulted.</li>
 *   <li>A token present: it is verified; a token that fails is {@link Outcome#CAPTCHA_FAILED} and is
 *       <em>never</em> a fallback.</li>
 *   <li>No token: the fallback, {@link Outcome#OK_FALLBACK} only when all three bounds admit, checked
 *       outermost first and stopping at the first refusal: the endpoint's hourly global ceiling, then the
 *       client address (20 per 15 minutes), then the identifier (5 per 15 minutes). The identifier is the
 *       one key the caller chooses, so it is touched only by a request the non-rotatable bounds have
 *       already admitted; a refused request creates no entry in the later caches.</li>
 * </ul>
 *
 * <p>Registration is deliberately not served by this gate (plan §4 #1): the fallback cannot tell a
 * blocked browser from a bot that omits the token.
 */
@Component
public class TurnstileGate {

    private static final Logger LOG = LoggerFactory.getLogger(TurnstileGate.class);

    /** Fallback attempts admitted per client address in a quarter hour. */
    static final int CLIENT_LIMIT = 20;

    /** Fallback attempts admitted per identifier in a quarter hour. */
    static final int IDENTIFIER_LIMIT = 5;

    /** The identifier is attacker-chosen text and a cache key, so it is cut to this many characters. */
    static final int MAX_IDENTIFIER_LENGTH = 256;

    /** The endpoints the gate guards, each with its hourly global ceiling on the fallback. */
    public enum Endpoint {
        /** {@code POST /api/auth/login}. */
        LOGIN("login", 60),
        /** {@code POST /api/auth/change-password}. */
        CHANGE_PASSWORD("change-password", 20),
        /** {@code POST /api/auth/forgot-password}. */
        FORGOT("forgot", 10);

        private final String keyName;
        private final int globalPerHour;

        Endpoint(String keyName, int globalPerHour) {
            this.keyName = keyName;
            this.globalPerHour = globalPerHour;
        }

        /**
         * Returns the name used in limiter keys.
         *
         * @return the key name
         */
        public String keyName() {
            return keyName;
        }

        /**
         * Returns the hourly ceiling on tokenless requests to this endpoint across every caller.
         *
         * @return the ceiling
         */
        public int globalPerHour() {
            return globalPerHour;
        }
    }

    /** What the gate decided. */
    public enum Outcome {
        /** Turnstile is not configured, or the token verified. */
        OK_VERIFIED,
        /** No token, and every fallback bound admitted. */
        OK_FALLBACK,
        /** A token was sent and Cloudflare rejected it. */
        CAPTCHA_FAILED,
        /** No token, and a fallback bound was reached. */
        RATE_LIMITED
    }

    private final TurnstileService turnstileService;
    private final AuthAttemptLimiter limiter;

    /**
     * Constructs the gate.
     *
     * @param turnstileService the Cloudflare verifier
     * @param limiter          the attempt counters
     */
    public TurnstileGate(TurnstileService turnstileService, AuthAttemptLimiter limiter) {
        this.turnstileService = turnstileService;
        this.limiter = limiter;
    }

    /**
     * Admits or refuses a request.
     *
     * @param token       the client's Turnstile token, possibly null or blank
     * @param identityKey the identifier the request is for (username, email); normalised here
     * @param clientKey   the caller's address ({@link ClientAddress#of})
     * @param endpoint    the endpoint being guarded
     * @return the decision
     */
    public Outcome admit(String token, String identityKey, String clientKey, Endpoint endpoint) {
        if (!turnstileService.isConfigured()) {
            return Outcome.OK_VERIFIED;
        }
        if (token != null && !token.isBlank()) {
            return turnstileService.verify(token) ? Outcome.OK_VERIFIED : Outcome.CAPTCHA_FAILED;
        }

        String prefix = "fallback:" + endpoint.keyName();
        if (!limiter.tryAcquire(prefix + ":global", endpoint.globalPerHour(), Window.HOUR)) {
            LOG.info("Turnstile fallback refused: endpoint={} bound=global", endpoint.keyName());
            return Outcome.RATE_LIMITED;
        }
        if (!limiter.tryAcquire(prefix + ":client:" + clientKey, CLIENT_LIMIT, Window.QUARTER_HOUR)) {
            LOG.info("Turnstile fallback refused: endpoint={} bound=client", endpoint.keyName());
            return Outcome.RATE_LIMITED;
        }
        String identifier = AuthAttemptLimiter.normalise(identityKey);
        if (identifier.length() > MAX_IDENTIFIER_LENGTH) {
            identifier = identifier.substring(0, MAX_IDENTIFIER_LENGTH);
        }
        if (!limiter.tryAcquire(prefix + ":id:" + identifier, IDENTIFIER_LIMIT, Window.QUARTER_HOUR)) {
            LOG.info("Turnstile fallback refused: endpoint={} bound=identifier", endpoint.keyName());
            return Outcome.RATE_LIMITED;
        }
        return Outcome.OK_FALLBACK;
    }
}

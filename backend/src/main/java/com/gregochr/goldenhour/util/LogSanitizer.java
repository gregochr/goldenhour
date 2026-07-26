package com.gregochr.goldenhour.util;

/**
 * Neutralises untrusted values before they are written to the application log.
 *
 * <p>A value that reaches a log statement straight from a request body, header or path
 * can carry carriage returns and line feeds. Those let a caller forge whole log lines —
 * inventing a plausible-looking {@code Login failed} or {@code Registration complete}
 * entry that never happened — which corrupts the audit trail the logs exist to provide.
 *
 * <p>Sanitising replaces every control character (CR and LF included) with
 * {@value #REPLACEMENT} and truncates to {@value #MAX_LOGGED_LENGTH} characters, so a
 * long value cannot flood the log either. The result always renders as a single line.
 */
public final class LogSanitizer {

    /** Longest sanitised value written to the log; longer values are truncated. */
    private static final int MAX_LOGGED_LENGTH = 200;

    /** Stand-in for each stripped control character. */
    private static final String REPLACEMENT = "_";

    /** Marker appended when a value is truncated. */
    private static final String TRUNCATION_MARKER = "...";

    /**
     * Everything a log line may keep: letters, digits, punctuation, symbols and plain spaces.
     * Anything else — every control character, every line break, every exotic Unicode separator —
     * is replaced.
     *
     * <p>Deliberately an allow-list rather than a denylist of {@code [\r\n]}. A denylist only stops
     * the separators you thought of: U+2028 LINE SEPARATOR ends a line for some log viewers and is
     * not {@code \r} or {@code \n}. It is also the only form static analysis credits — CodeQL's
     * log-injection barrier accepts a negated class or a bare {@code \n}/{@code \r}/{@code \R}, and
     * silently ignores a denylist class, so a correct denylist fix still reports as unfixed.
     */
    private static final String LOG_SAFE_ALLOW_LIST = "[^\\p{L}\\p{N}\\p{P}\\p{S}\\p{Zs}]";

    /** Rendering for a null value, so callers need no null check of their own. */
    private static final String NULL_LABEL = "null";

    private LogSanitizer() {
    }

    /**
     * Flattens an untrusted value to a single, length-bounded log-safe line.
     *
     * @param value the untrusted value, may be null
     * @return the sanitised value, or {@code "null"} if {@code value} is null
     */
    public static String sanitize(String value) {
        if (value == null) {
            return NULL_LABEL;
        }
        String bounded = value.length() <= MAX_LOGGED_LENGTH
                ? value
                : value.substring(0, MAX_LOGGED_LENGTH) + TRUNCATION_MARKER;
        // Bound first, neutralise last, and return that call directly, so the sanitised value is
        // what the caller receives rather than something derived from it further down.
        return bounded.replaceAll(LOG_SAFE_ALLOW_LIST, REPLACEMENT);
    }
}

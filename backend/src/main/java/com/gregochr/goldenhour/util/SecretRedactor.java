package com.gregochr.goldenhour.util;

import java.util.regex.Pattern;

/**
 * Masks API keys carried in URL query strings before text reaches a log line, the admin API-call log
 * or a health detail.
 *
 * <p>Spring's {@code ResourceAccessException} message embeds the full request URL, so a vendor key sent as
 * a query parameter would otherwise leak wherever the message is echoed.
 */
public final class SecretRedactor {

    /** The replacement written in place of a key value. */
    private static final String MASK = "***";

    /**
     * A {@code key}, {@code apikey} or {@code api_key} parameter, only when it starts the text or follows
     * {@code ?} or {@code &} (so {@code monkey=1} is untouched). The value runs to the next {@code &},
     * whitespace, quote or end of text.
     */
    private static final Pattern KEY_PARAM = Pattern.compile(
            "(^|[?&])((?:api_?)?key=)[^&\\s\"']*", Pattern.CASE_INSENSITIVE);

    private SecretRedactor() {
    }

    /**
     * Replaces the value of any {@code key=}, {@code apikey=} or {@code api_key=} query parameter with
     * {@code ***}.
     *
     * @param value text that may contain a URL, or {@code null}
     * @return the text with key values masked, or {@code null} if {@code value} is {@code null}
     */
    public static String redactKeys(String value) {
        if (value == null) {
            return null;
        }
        return KEY_PARAM.matcher(value).replaceAll("$1$2" + MASK);
    }
}

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

    /** An {@code Authorization: Bearer ...} header line or a bare {@code bearer ...} token, any case. */
    private static final Pattern BEARER = Pattern.compile(
            "(bearer\\s+)[A-Za-z0-9._~+/=-]{8,}", Pattern.CASE_INSENSITIVE);

    /** GitHub token shapes: fine-grained {@code github_pat_...} and the classic {@code ghp_/gho_/ghu_/ghs_/ghr_}. */
    private static final Pattern GITHUB_TOKEN = Pattern.compile(
            "\\b(?:github_pat_[A-Za-z0-9_]{10,}|gh[pousr]_[A-Za-z0-9]{10,})");

    /**
     * Signed-URL query parameters ({@code sig}, {@code se}, {@code skoid}, {@code sktid}, {@code sv}), only after
     * {@code ?} or {@code &}. A blob URL's signature rides these.
     */
    private static final Pattern SIGNED_URL_PARAM = Pattern.compile(
            "([?&](?:sig|se|skoid|sktid|sv)=)[^&\\s\"']*", Pattern.CASE_INSENSITIVE);

    private SecretRedactor() {
    }

    /**
     * Replaces the value of any {@code key=}, {@code apikey=} or {@code api_key=} query parameter with
     * {@code ***}, and also masks bearer tokens, GitHub token shapes and the signed-URL parameters
     * ({@code sig=}, {@code se=}, {@code skoid=}, {@code sktid=}, {@code sv=}).
     *
     * @param value text that may contain a URL, or {@code null}
     * @return the text with key values masked, or {@code null} if {@code value} is {@code null}
     */
    public static String redactKeys(String value) {
        if (value == null) {
            return null;
        }
        String masked = KEY_PARAM.matcher(value).replaceAll("$1$2" + MASK);
        masked = SIGNED_URL_PARAM.matcher(masked).replaceAll("$1" + MASK);
        masked = BEARER.matcher(masked).replaceAll("$1" + MASK);
        return GITHUB_TOKEN.matcher(masked).replaceAll(MASK);
    }
}

package com.gregochr.goldenhour.util;

import java.util.regex.Pattern;

/**
 * Shape check for user-supplied email addresses, safe to run on untrusted input.
 *
 * <p>The pattern is deliberately unambiguous: the local part stops at {@code @}, and each
 * domain label excludes the dot that separates labels. No input can be split between two
 * adjacent quantifiers, so matching is linear in the length of the input rather than
 * quadratic — the property that makes the equivalent naive pattern
 * ({@code ^[^\s@]+@[^\s@]+\.[^\s@]+$}) a denial-of-service vector on a public endpoint.
 * A length cap applied before matching bounds the work regardless.
 *
 * <p>This is a shape check, not proof of deliverability; only sending mail proves that.
 */
public final class EmailValidator {

    /** Maximum address length accepted, per the RFC 5321 forward-path limit. */
    private static final int MAX_EMAIL_LENGTH = 254;

    /** Linear-time address pattern: local part, {@code @}, then two or more dot-separated labels. */
    private static final Pattern EMAIL_PATTERN =
            Pattern.compile("^[^\\s@]+@[^\\s@.]+(?:\\.[^\\s@.]+)+$");

    private EmailValidator() {
    }

    /**
     * Reports whether the given value has the shape of an email address.
     *
     * <p>The value is trimmed before matching, so surrounding whitespace is tolerated.
     *
     * @param email the candidate address, may be null
     * @return true if the trimmed value is a plausible email address
     */
    public static boolean isValid(String email) {
        if (email == null) {
            return false;
        }
        String trimmed = email.trim();
        if (trimmed.isEmpty() || trimmed.length() > MAX_EMAIL_LENGTH) {
            return false;
        }
        return EMAIL_PATTERN.matcher(trimmed).matches();
    }
}

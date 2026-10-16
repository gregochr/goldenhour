package com.gregochr.goldenhour.service;

import jakarta.servlet.http.HttpServletRequest;

/**
 * Reads the caller's address for the attempt limiter (login-registration plan §1 #23).
 *
 * <p>The backend's own {@code remoteAddr} is the nginx container; nginx forwards the client's headers
 * and sets {@code X-Forwarded-For}. The rule, in order: {@code CF-Connecting-IP}, else the first
 * {@code X-Forwarded-For} hop, else {@code remoteAddr}. A caller who reaches the host directly can
 * forge both headers, so the address is only ever an <em>additional</em> bound, never the only one.
 * The value is a cache key, so it is capped at {@value #MAX_LENGTH} characters (an IPv6 address is at
 * most 45).
 */
public final class ClientAddress {

    /** The longest address kept. */
    static final int MAX_LENGTH = 64;

    /** What is used when the request carries no address at all. */
    static final String UNKNOWN = "unknown";

    private ClientAddress() {
    }

    /**
     * Returns the caller's address for use in a limiter key.
     *
     * @param request the request
     * @return a non-blank address, at most {@value #MAX_LENGTH} characters
     */
    public static String of(HttpServletRequest request) {
        String address = clean(request.getHeader("CF-Connecting-IP"));
        if (address == null) {
            String forwarded = request.getHeader("X-Forwarded-For");
            if (forwarded != null) {
                address = clean(forwarded.split(",", 2)[0]);
            }
        }
        if (address == null) {
            address = clean(request.getRemoteAddr());
        }
        return address == null ? UNKNOWN : address;
    }

    private static String clean(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        return trimmed.length() > MAX_LENGTH ? trimmed.substring(0, MAX_LENGTH) : trimmed;
    }
}

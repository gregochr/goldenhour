package com.gregochr.goldenhour.config;

/**
 * Holds the current Terms &amp; Conditions version string.
 *
 * <p>When the Terms are updated, change {@link #CURRENT_TERMS_VERSION} here.
 * The app can then detect users on an older version and prompt re-acceptance.
 */
public final class TermsConstants {

    /** The version identifier for the current Terms &amp; Conditions (matches the landing page date). */
    public static final String CURRENT_TERMS_VERSION = "April 2026";

    private TermsConstants() {
    }
}

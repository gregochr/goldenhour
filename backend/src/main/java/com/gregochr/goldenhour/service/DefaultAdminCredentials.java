package com.gregochr.goldenhour.service;

/**
 * The published default administrator credential: {@code admin} / {@code golden2026}.
 *
 * <p>It is seeded by the {@code V10} migration and by {@link LocalAdminSeeder}, and it is written in the
 * README. It is a local-development convenience only: {@link DefaultCredentialGuard} neutralises it on every
 * profile except {@code local}.
 */
public final class DefaultAdminCredentials {

    /** The seeded administrator's username. */
    public static final String USERNAME = "admin";

    /** The seeded administrator's published password. */
    public static final String PASSWORD = "golden2026";

    private DefaultAdminCredentials() {
    }
}

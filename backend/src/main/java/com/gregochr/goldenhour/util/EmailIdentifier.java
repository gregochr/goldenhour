package com.gregochr.goldenhour.util;

import com.gregochr.goldenhour.entity.AppUserEntity;
import com.gregochr.goldenhour.repository.AppUserRepository;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * The one home of how an email address typed by a person is matched to an account
 * (login-registration plan §1 #5, §2 #10).
 *
 * <p>Emails are unique but case-SENSITIVE in the database, and the match used to be Java
 * {@code equalsIgnoreCase} over every user, which folds {@code ı} to {@code I} and then mailed the TYPED
 * address — so a confusable address could receive someone else's token. The rules, in order:
 * <ol>
 *   <li>the identifier is trimmed;</li>
 *   <li>an EXACT match first ({@code findByEmail}, no folding of any kind) — the comparison that is safe
 *       for every address and the only one a non-ASCII address ever gets;</li>
 *   <li>only when the trimmed identifier is pure ASCII and the exact match found nothing, a
 *       case-insensitive match: an explicit {@code LOWER(email) = :key} with the key lower-cased in Java
 *       with {@link Locale#ROOT};</li>
 *   <li>only rows of the requested {@link Kind} count (active for sign-in and reset, pending for
 *       resend); two or more case-insensitive matches are no match;</li>
 *   <li>callers mail the STORED {@code user.getEmail()}, never the request's text.</li>
 * </ol>
 */
public final class EmailIdentifier {

    /** Which accounts a lookup may return. */
    public enum Kind {
        /** Enabled and holding a password: the accounts that can sign in or reset. */
        ACTIVE,
        /** Disabled with the empty password marker: a registration awaiting its verification link. */
        PENDING
    }

    private EmailIdentifier() {
    }

    /**
     * Normalises an address to the key the email reservation table and the case-insensitive queries use:
     * trimmed and lower-cased with {@link Locale#ROOT}, so the key never depends on the JVM or database
     * locale. A null is the empty string.
     *
     * @param email the address as typed or stored
     * @return the key form
     */
    public static String normalise(String email) {
        return email == null ? "" : email.trim().toLowerCase(Locale.ROOT);
    }

    /**
     * Reports whether every character is 7-bit ASCII.
     *
     * @param value the text to test
     * @return true for ASCII-only (including the empty string)
     */
    public static boolean isAscii(String value) {
        if (value == null) {
            return false;
        }
        for (int i = 0; i < value.length(); i++) {
            if (value.charAt(i) > 0x7F) {
                return false;
            }
        }
        return true;
    }

    /**
     * Reports whether an account is of the requested kind.
     *
     * @param user the account
     * @param kind the kind
     * @return true if the account counts
     */
    public static boolean admits(AppUserEntity user, Kind kind) {
        String password = user.getPassword();
        boolean pending = !user.isEnabled() && password != null && password.isEmpty();
        boolean active = user.isEnabled() && password != null && !password.isEmpty();
        return kind == Kind.ACTIVE ? active : pending;
    }

    /**
     * Resolves the one account an email address identifies, or none.
     *
     * @param identifier the address as typed; may be null or blank
     * @param users      the repository to look in
     * @param kind       which accounts may match
     * @return the account, or empty when there is no match or the case-insensitive match is ambiguous
     */
    public static Optional<AppUserEntity> resolve(String identifier, AppUserRepository users, Kind kind) {
        if (identifier == null) {
            return Optional.empty();
        }
        String trimmed = identifier.trim();
        if (trimmed.isEmpty()) {
            return Optional.empty();
        }
        Optional<AppUserEntity> exact = users.findByEmail(trimmed);
        if (exact.isPresent()) {
            return exact.filter(user -> admits(user, kind));
        }
        if (!isAscii(trimmed)) {
            return Optional.empty();
        }
        String key = normalise(trimmed);
        List<AppUserEntity> matches = kind == Kind.ACTIVE
                ? users.findActiveByEmailLower(key)
                : users.findPendingByEmailLower(key);
        return matches.size() == 1 ? Optional.of(matches.get(0)) : Optional.empty();
    }
}

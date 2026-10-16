package com.gregochr.goldenhour.util;

import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;

import java.util.Locale;
import java.util.Optional;

/**
 * Tells apart the two unique constraints an account write can lose a race on
 * (login-registration plan §2 #14 rule 10).
 *
 * <p>Two concurrent registrations of one USERNAME both pass {@code existsByUsername} and the loser's
 * violation is on {@code app_user.username}, not on the email reservation; the controllers answer
 * "Username already taken" for that and "Email already registered" for a violation of the reservation
 * table's key (or of the legacy exact-match email index). The decision is made by constraint name, taken
 * from Hibernate's own {@link ConstraintViolationException} when the cause chain has one and from the
 * driver's message text otherwise (H2 and Postgres both name the violated index or constraint there).
 */
public final class UniqueViolation {

    /** Which constraint was violated. */
    public enum Kind {
        /** The normalised-email reservation, or the exact-match {@code app_user.email} index. */
        EMAIL,
        /** The {@code app_user.username} unique key. */
        USERNAME,
        /** Anything else — the caller rethrows. */
        OTHER
    }

    private UniqueViolation() {
    }

    /** The 409 sentence for a lost race on the email reservation. */
    public static final String EMAIL_TAKEN = "Email already registered";

    /** The 409 sentence for a lost race on the username key. */
    public static final String USERNAME_TAKEN = "Username already taken";

    /**
     * Returns the 409 sentence for a violation, or empty when it is neither of the two account keys —
     * in which case the caller rethrows (it is a bug, not a conflict).
     *
     * @param ex the violation caught around a service call
     * @return {@link #EMAIL_TAKEN}, {@link #USERNAME_TAKEN}, or empty
     */
    public static Optional<String> conflictMessage(DataIntegrityViolationException ex) {
        return switch (classify(ex)) {
            case EMAIL -> Optional.of(EMAIL_TAKEN);
            case USERNAME -> Optional.of(USERNAME_TAKEN);
            case OTHER -> Optional.empty();
        };
    }

    /**
     * Classifies a data-integrity violation.
     *
     * @param ex the violation caught around a service call
     * @return the constraint family it belongs to
     */
    public static Kind classify(DataIntegrityViolationException ex) {
        StringBuilder evidence = new StringBuilder();
        for (Throwable t = ex; t != null; t = t.getCause()) {
            if (t instanceof ConstraintViolationException cve && cve.getConstraintName() != null) {
                evidence.append(cve.getConstraintName()).append(' ');
            }
            if (t.getMessage() != null) {
                evidence.append(t.getMessage()).append(' ');
            }
            if (t.getCause() == t) {
                break;
            }
        }
        String text = evidence.toString().toLowerCase(Locale.ROOT);
        if (text.contains("app_user_email_claim") || text.contains("idx_app_user_email")) {
            return Kind.EMAIL;
        }
        if (text.contains("username")) {
            return Kind.USERNAME;
        }
        return Kind.OTHER;
    }
}

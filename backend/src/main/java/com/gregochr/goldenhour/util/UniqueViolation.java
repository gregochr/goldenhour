package com.gregochr.goldenhour.util;

import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;

import java.sql.SQLException;
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

    /** The cause chain is walked at most this deep, so a cyclic chain cannot loop. */
    private static final int MAX_CAUSE_DEPTH = 10;

    /**
     * Classifies a data-integrity violation.
     *
     * <p>The evidence is the violated constraint's NAME, never the wrapper messages: Hibernate's and
     * Spring's messages end with the SQL text ({@code insert into app_user (email, username …)}), which
     * names columns the violation had nothing to do with, and a driver's detail line carries the offending
     * VALUE (a username can be called {@code idx_app_user_email}). So the name comes from Hibernate's own
     * {@link ConstraintViolationException} when the chain has one, and otherwise from the driver's
     * {@link SQLException} message cut before its detail line, its value list and its SQL, and only when
     * that message says the violation was of a unique key.
     *
     * @param ex the violation caught around a service call
     * @return the constraint family it belongs to
     */
    public static Kind classify(DataIntegrityViolationException ex) {
        String evidence = null;
        int depth = 0;
        for (Throwable t = ex; t != null && depth < MAX_CAUSE_DEPTH && evidence == null;
                t = t.getCause(), depth++) {
            if (t instanceof ConstraintViolationException cve && cve.getConstraintName() != null) {
                evidence = cve.getConstraintName();
            } else if (t instanceof SQLException sql && sql.getMessage() != null) {
                evidence = constraintPart(sql.getMessage());
            }
        }
        if (evidence == null) {
            return Kind.OTHER;
        }
        String text = evidence.toLowerCase(Locale.ROOT);
        if (text.contains("app_user_email_claim") || text.contains("idx_app_user_email")) {
            return Kind.EMAIL;
        }
        if (text.contains("username")) {
            return Kind.USERNAME;
        }
        return Kind.OTHER;
    }

    /**
     * Cuts a driver message down to the part that names the violated key, or to nothing when it is not a
     * unique-key violation at all.
     *
     * @param message the driver's message
     * @return the leading part of the first line, before any value list or SQL; null if not a unique
     *         violation
     */
    private static String constraintPart(String message) {
        String firstLine = message.lines().findFirst().orElse("");
        String lower = firstLine.toLowerCase(Locale.ROOT);
        if (!lower.contains("unique") && !lower.contains("duplicate")) {
            return null;
        }
        int values = lower.indexOf(" values");
        int bracket = lower.indexOf(" [");
        int cut = firstLine.length();
        if (values >= 0) {
            cut = Math.min(cut, values);
        }
        if (bracket >= 0) {
            cut = Math.min(cut, bracket);
        }
        return firstLine.substring(0, cut);
    }
}

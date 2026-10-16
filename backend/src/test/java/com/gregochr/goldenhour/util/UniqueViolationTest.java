package com.gregochr.goldenhour.util;

import com.gregochr.goldenhour.util.UniqueViolation.Kind;
import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

import java.sql.SQLException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link UniqueViolation}: telling the email reservation apart from the username key
 * (login-registration plan §2 #14 rule 10).
 */
class UniqueViolationTest {

    private static DataIntegrityViolationException viaDriver(String driverMessage) {
        return new DataIntegrityViolationException("could not execute statement",
                new SQLException(driverMessage));
    }

    @Test
    @DisplayName("Postgres naming the reservation's primary key is an EMAIL violation")
    void postgresReservationKey_isEmail() {
        assertThat(UniqueViolation.classify(viaDriver(
                "ERROR: duplicate key value violates unique constraint \"pk_app_user_email_claim\"")))
                .isEqualTo(Kind.EMAIL);
    }

    @Test
    @DisplayName("the legacy exact-match email index is an EMAIL violation too")
    void legacyEmailIndex_isEmail() {
        assertThat(UniqueViolation.classify(viaDriver(
                "ERROR: duplicate key value violates unique constraint \"idx_app_user_email\"")))
                .isEqualTo(Kind.EMAIL);
    }

    @Test
    @DisplayName("Postgres naming the username key is a USERNAME violation, even though the statement text "
            + "mentions the email column")
    void usernameKey_isUsername() {
        assertThat(UniqueViolation.classify(viaDriver(
                "ERROR: duplicate key value violates unique constraint \"app_user_username_key\" "
                        + "Detail: Key (username)=(bob) already exists. "
                        + "[insert into app_user (email, username) values (?, ?)]")))
                .isEqualTo(Kind.USERNAME);
    }

    @Test
    @DisplayName("H2's index text is classified the same way")
    void h2IndexText() {
        assertThat(UniqueViolation.classify(viaDriver(
                "Unique index or primary key violation: \"PUBLIC.PK_APP_USER_EMAIL_CLAIM_INDEX_A ON "
                        + "PUBLIC.APP_USER_EMAIL_CLAIM(EMAIL_LOWER NULLS FIRST)\"")))
                .isEqualTo(Kind.EMAIL);
        assertThat(UniqueViolation.classify(viaDriver(
                "Unique index or primary key violation: \"PUBLIC.UK_X_INDEX_4 ON "
                        + "PUBLIC.APP_USER(USERNAME NULLS FIRST)\"")))
                .isEqualTo(Kind.USERNAME);
    }

    @Test
    @DisplayName("Hibernate's own constraint name is read from the cause chain")
    void hibernateConstraintName_isRead() {
        ConstraintViolationException hibernate = new ConstraintViolationException("m",
                new SQLException("opaque"), "pk_app_user_email_claim");

        assertThat(UniqueViolation.classify(new DataIntegrityViolationException("x", hibernate)))
                .isEqualTo(Kind.EMAIL);
    }

    @Test
    @DisplayName("any other violation is OTHER and has no conflict sentence — the caller rethrows")
    void otherViolation_isOther() {
        DataIntegrityViolationException other =
                viaDriver("null value in column \"role\" violates not-null constraint");

        assertThat(UniqueViolation.classify(other)).isEqualTo(Kind.OTHER);
        assertThat(UniqueViolation.conflictMessage(other)).isEmpty();
    }

    @Test
    @DisplayName("the 409 sentences are the agreed ones")
    void conflictSentences() {
        assertThat(UniqueViolation.conflictMessage(viaDriver("violates \"pk_app_user_email_claim\"")))
                .contains("Email already registered");
        assertThat(UniqueViolation.conflictMessage(viaDriver("violates \"app_user_username_key\"")))
                .contains("Username already taken");
    }

    @Test
    @DisplayName("a self-referencing cause chain terminates")
    void cyclicCauseChain_terminates() {
        SQLException selfCaused = new SQLException("loop") {
            @Override
            public synchronized Throwable getCause() {
                return this;
            }
        };

        assertThat(UniqueViolation.classify(new DataIntegrityViolationException("x", selfCaused)))
                .isEqualTo(Kind.OTHER);
    }
}

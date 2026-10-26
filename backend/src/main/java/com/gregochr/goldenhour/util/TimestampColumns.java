package com.gregochr.goldenhour.util;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.Date;

/**
 * Reads a timestamp column off a NATIVE scalar query result.
 *
 * <p>A native query hands back whatever Java type the JDBC driver and dialect choose for a
 * {@code TIMESTAMPTZ} column — an {@code Instant}, an {@code OffsetDateTime}, a {@code java.sql.Timestamp}
 * — and which one differs between Postgres and H2. The credential projection
 * ({@code AppUserRepository#lockAccount}) must read the same instant from either.
 */
public final class TimestampColumns {

    private TimestampColumns() {
    }

    /**
     * Converts a native-query timestamp value to an {@link Instant}.
     *
     * @param value the column value, possibly null
     * @return the instant, or null for a null column
     * @throws IllegalStateException if the value is of a type no driver is expected to return
     */
    public static Instant toInstant(Object value) {
        return switch (value) {
            case null -> null;
            case Instant instant -> instant;
            case OffsetDateTime offset -> offset.toInstant();
            case ZonedDateTime zoned -> zoned.toInstant();
            case Timestamp timestamp -> timestamp.toInstant();
            case LocalDateTime local -> local.toInstant(ZoneOffset.UTC);
            case Date date -> date.toInstant();
            default -> throw new IllegalStateException("Unreadable timestamp column: " + value.getClass());
        };
    }

    /**
     * Converts a native-query value of a zone-less {@code TIMESTAMP} column to a {@link LocalDateTime} in the
     * JVM default zone (the frame such columns are written in).
     *
     * @param value the column value, possibly null
     * @return the local date-time, or null for a null column
     * @throws IllegalStateException if the value is of a type no driver is expected to return
     */
    public static LocalDateTime toLocalDateTime(Object value) {
        return switch (value) {
            case null -> null;
            case LocalDateTime local -> local;
            case Timestamp timestamp -> timestamp.toLocalDateTime();
            default -> throw new IllegalStateException("Unreadable timestamp column: " + value.getClass());
        };
    }
}

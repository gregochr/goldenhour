package com.gregochr.goldenhour.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.Date;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests for {@link TimestampColumns}: the same instant is read whatever type a driver hands a
 * {@code TIMESTAMPTZ} back as.
 */
class TimestampColumnsTest {

    private static final Instant INSTANT = Instant.parse("2026-10-16T12:00:00.250Z");

    @Test
    @DisplayName("an Instant, an OffsetDateTime in any offset, a ZonedDateTime and a Timestamp all read as "
            + "the same instant")
    void everyDriverTypeReadsTheSameInstant() {
        assertThat(TimestampColumns.toInstant(INSTANT)).isEqualTo(INSTANT);
        assertThat(TimestampColumns.toInstant(OffsetDateTime.ofInstant(INSTANT, ZoneOffset.ofHours(5))))
                .isEqualTo(INSTANT);
        assertThat(TimestampColumns.toInstant(ZonedDateTime.ofInstant(INSTANT, ZoneId.of("Europe/London"))))
                .isEqualTo(INSTANT);
        assertThat(TimestampColumns.toInstant(Timestamp.from(INSTANT))).isEqualTo(INSTANT);
        assertThat(TimestampColumns.toInstant(Date.from(INSTANT))).isEqualTo(INSTANT);
    }

    @Test
    @DisplayName("a zone-less LocalDateTime is read as UTC, the column's own convention")
    void localDateTimeIsUtc() {
        assertThat(TimestampColumns.toInstant(LocalDateTime.of(2026, 10, 16, 12, 0, 0, 250_000_000)))
                .isEqualTo(INSTANT);
    }

    @Test
    @DisplayName("a zone-less TIMESTAMP reads as the same local date-time from a Timestamp or a LocalDateTime")
    void toLocalDateTimeReadsBothDriverTypes() {
        LocalDateTime value = LocalDateTime.of(2031, 6, 30, 10, 0);

        assertThat(TimestampColumns.toLocalDateTime(Timestamp.valueOf(value))).isEqualTo(value);
        assertThat(TimestampColumns.toLocalDateTime(value)).isEqualTo(value);
        assertThat(TimestampColumns.toLocalDateTime(null)).isNull();
        assertThatThrownBy(() -> TimestampColumns.toLocalDateTime("2031-06-30"))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("java.lang.String");
    }

    @Test
    @DisplayName("a null column is a null instant")
    void nullIsNull() {
        assertThat(TimestampColumns.toInstant(null)).isNull();
    }

    @Test
    @DisplayName("a type no driver returns is refused loudly, never read as a wrong instant")
    void unknownTypeIsRefused() {
        assertThatThrownBy(() -> TimestampColumns.toInstant("2026-10-16T12:00:00Z"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("java.lang.String");
    }
}

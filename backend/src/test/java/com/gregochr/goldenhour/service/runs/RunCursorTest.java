package com.gregochr.goldenhour.service.runs;

import com.gregochr.goldenhour.model.runs.RunGroup;
import com.gregochr.goldenhour.model.runs.RunKind;
import com.gregochr.goldenhour.model.runs.RunListItem;
import com.gregochr.goldenhour.model.runs.RunListStatus;
import com.gregochr.goldenhour.model.runs.RunRange;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Base64;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests for the cursor, the list order, and the request-value parsers.
 */
class RunCursorTest {

    private static final Instant T = Instant.parse("2026-10-31T10:00:00.123Z");

    private static RunListItem item(RunKind kind, long id, Instant startedAt) {
        return new RunListItem(kind, id, RunGroup.OTHER, "n", startedAt, null, null, RunListStatus.DONE, null,
                null, 0L, null, null, null, null, null, null, null, null, null, null, null);
    }

    @Test
    @DisplayName("a cursor round-trips, with sub-second precision")
    void roundTrip() {
        RunCursor cursor = new RunCursor(T, RunKind.JOB, 42L);

        assertThat(RunCursor.decode(cursor.encode())).isEqualTo(cursor);
        assertThat(RunCursor.of(item(RunKind.CYCLE, 7, T))).isEqualTo(new RunCursor(T, RunKind.CYCLE, 7L));
    }

    @Test
    @DisplayName("a malformed cursor is an IllegalArgumentException")
    void malformed() {
        String wrongParts = Base64.getUrlEncoder().withoutPadding().encodeToString("a|b".getBytes());
        String badKind = Base64.getUrlEncoder().withoutPadding()
                .encodeToString((T + "|NOPE|1").getBytes());
        for (String bad : new String[] {"!!!", wrongParts, badKind, ""}) {
            assertThatThrownBy(() -> RunCursor.decode(bad)).isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    @DisplayName("order is newest first, then kind descending, then id descending")
    void order() {
        RunListItem newest = item(RunKind.CYCLE, 1, T.plusSeconds(5));
        RunListItem jobTie = item(RunKind.JOB, 5, T);
        RunListItem jobTieLowerId = item(RunKind.JOB, 4, T);
        RunListItem cycleTie = item(RunKind.CYCLE, 99, T);
        RunListItem oldest = item(RunKind.JOB, 100, T.minusSeconds(1));

        List<RunListItem> sorted = List.of(oldest, cycleTie, jobTieLowerId, newest, jobTie).stream()
                .sorted(RunCursor.ORDER).toList();

        assertThat(sorted).containsExactly(newest, jobTie, jobTieLowerId, cycleTie, oldest);
    }

    @Test
    @DisplayName("a cursor is followed only by rows strictly after it in that order")
    void isFollowedBy() {
        RunCursor cursor = new RunCursor(T, RunKind.JOB, 5L);

        assertThat(cursor.isFollowedBy(item(RunKind.JOB, 5, T))).isFalse();
        assertThat(cursor.isFollowedBy(item(RunKind.JOB, 6, T))).isFalse();
        assertThat(cursor.isFollowedBy(item(RunKind.JOB, 4, T))).isTrue();
        assertThat(cursor.isFollowedBy(item(RunKind.CYCLE, 900, T))).isTrue();
        assertThat(cursor.isFollowedBy(item(RunKind.JOB, 1, T.minusMillis(1)))).isTrue();
        assertThat(cursor.isFollowedBy(item(RunKind.CYCLE, 1, T.plusMillis(1)))).isFalse();
    }

    @Test
    @DisplayName("range and kind parse case-insensitively; ALL or blank kind is no filter; unknowns throw")
    void parsers() {
        assertThat(RunRange.parse("today")).isEqualTo(RunRange.TODAY);
        assertThat(RunRange.parse(" Week ")).isEqualTo(RunRange.WEEK);
        assertThat(RunRange.MONTH.daysBack()).isEqualTo(29);
        assertThat(RunRange.WEEK.daysBack()).isEqualTo(6);
        assertThat(RunRange.TODAY.daysBack()).isZero();
        assertThatThrownBy(() -> RunRange.parse("YEAR")).isInstanceOf(IllegalArgumentException.class);

        assertThat(RunGroup.parseKind("ALL")).isNull();
        assertThat(RunGroup.parseKind("all")).isNull();
        assertThat(RunGroup.parseKind(null)).isNull();
        assertThat(RunGroup.parseKind(" ")).isNull();
        assertThat(RunGroup.parseKind("aurora")).isEqualTo(RunGroup.AURORA);
        assertThatThrownBy(() -> RunGroup.parseKind("NOPE")).isInstanceOf(IllegalArgumentException.class);
    }
}

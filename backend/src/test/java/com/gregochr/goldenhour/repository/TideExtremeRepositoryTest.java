package com.gregochr.goldenhour.repository;

import com.gregochr.goldenhour.entity.TideExtremeEntity;
import com.gregochr.goldenhour.entity.TideExtremeType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Repository slice tests for the {@link TideExtremeRepository} statistics queries.
 * Uses an H2 in-memory database with schema generated from JPA entities.
 *
 * <p>These exist because the cutoff that keeps the spring and king thresholds independent
 * of the WorldTides fetch horizon lives in the JPQL {@code WHERE} clause. A unit test with a
 * mocked repository can assert which cutoff {@code TideService} passes, but only a real query
 * can show that passing it excludes anything.
 */
@DataJpaTest
class TideExtremeRepositoryTest {

    private static final Long LOCATION_ID = 42L;
    private static final Long OTHER_LOCATION_ID = 43L;

    /** Stands in for "today 00:00 UTC" — the bound {@code TideService.getTideStats} applies. */
    private static final LocalDateTime CUTOFF = LocalDateTime.of(2026, 8, 8, 0, 0);

    @Autowired
    private TideExtremeRepository repository;

    @BeforeEach
    void setUp() {
        // Three past HIGH extremes, rising: 2.0, 3.0, 4.0 — mean 3.0
        repository.save(extreme(CUTOFF.minusDays(3).withHour(6), TideExtremeType.HIGH, "2.000"));
        repository.save(extreme(CUTOFF.minusDays(2).withHour(7), TideExtremeType.HIGH, "3.000"));
        repository.save(extreme(CUTOFF.minusDays(1).withHour(8), TideExtremeType.HIGH, "4.000"));
        // One past LOW, so the LOW aggregate is non-empty
        repository.save(extreme(CUTOFF.minusDays(1).withHour(2), TideExtremeType.LOW, "0.500"));
    }

    @Test
    @DisplayName("Height stats exclude extremes at or after the cutoff, so a longer forward "
            + "fetch window cannot move the average")
    void findHeightStatsBefore_excludesFutureExtremes() {
        Object[] pastOnly = unwrap(repository.findHeightStatsByLocationIdAndTypeBefore(
                LOCATION_ID, TideExtremeType.HIGH, CUTOFF));
        assertThat((Long) pastOnly[3]).isEqualTo(3L);
        assertThat(toBigDecimal(pastOnly[0])).isEqualByComparingTo("3.000");
        assertThat(toBigDecimal(pastOnly[1])).isEqualByComparingTo("4.000");

        // A forward fetch lands two much higher extremes inside the new horizon.
        // Unbounded, these would drag the mean from 3.0 to 5.0 and the max from 4.0 to 9.0 —
        // which is exactly how extending FETCH_LENGTH_SECONDS used to move the spring threshold.
        repository.save(extreme(CUTOFF.plusDays(40), TideExtremeType.HIGH, "8.000"));
        repository.save(extreme(CUTOFF.plusDays(80), TideExtremeType.HIGH, "9.000"));

        Object[] afterForwardFetch = unwrap(repository.findHeightStatsByLocationIdAndTypeBefore(
                LOCATION_ID, TideExtremeType.HIGH, CUTOFF));
        assertThat((Long) afterForwardFetch[3]).isEqualTo(3L);
        assertThat(toBigDecimal(afterForwardFetch[0])).isEqualByComparingTo("3.000");
        assertThat(toBigDecimal(afterForwardFetch[1])).isEqualByComparingTo("4.000");
    }

    @Test
    @DisplayName("An extreme exactly at the cutoff is excluded — the bound is strictly before")
    void findHeightStatsBefore_isExclusiveAtTheBoundary() {
        repository.save(extreme(CUTOFF, TideExtremeType.HIGH, "9.000"));

        Object[] atBoundary = unwrap(repository.findHeightStatsByLocationIdAndTypeBefore(
                LOCATION_ID, TideExtremeType.HIGH, CUTOFF));
        assertThat((Long) atBoundary[3]).isEqualTo(3L);

        // One second earlier and it is history, so it counts.
        repository.save(extreme(CUTOFF.minusSeconds(1), TideExtremeType.HIGH, "9.000"));
        Object[] justInside = unwrap(repository.findHeightStatsByLocationIdAndTypeBefore(
                LOCATION_ID, TideExtremeType.HIGH, CUTOFF));
        assertThat((Long) justInside[3]).isEqualTo(4L);
    }

    @Test
    @DisplayName("Sorted heights exclude future extremes, so the P95 is drawn from the same "
            + "population as the mean")
    void findHeightsBefore_excludesFutureExtremes() {
        repository.save(extreme(CUTOFF.plusDays(40), TideExtremeType.HIGH, "8.000"));

        List<BigDecimal> heights = repository.findHeightsByLocationIdAndTypeBeforeOrderByHeightAsc(
                LOCATION_ID, TideExtremeType.HIGH, CUTOFF);

        assertThat(heights).hasSize(3);
        assertThat(heights).doesNotContain(new BigDecimal("8.000"));
        assertThat(heights.getFirst()).isEqualByComparingTo("2.000");
        assertThat(heights.getLast()).isEqualByComparingTo("4.000");
    }

    @Test
    @DisplayName("Both statistics queries stay scoped to one location and one tide type")
    void statsQueries_doNotLeakAcrossLocationOrType() {
        repository.save(extreme(CUTOFF.minusDays(1), TideExtremeType.HIGH, "7.000"));
        repository.save(TideExtremeEntity.builder()
                .locationId(OTHER_LOCATION_ID)
                .eventTime(CUTOFF.minusDays(1))
                .heightMetres(new BigDecimal("7.000"))
                .type(TideExtremeType.HIGH)
                .fetchedAt(CUTOFF.minusDays(1))
                .build());

        List<BigDecimal> heights = repository.findHeightsByLocationIdAndTypeBeforeOrderByHeightAsc(
                LOCATION_ID, TideExtremeType.HIGH, CUTOFF);
        assertThat(heights).hasSize(4);

        List<BigDecimal> lows = repository.findHeightsByLocationIdAndTypeBeforeOrderByHeightAsc(
                LOCATION_ID, TideExtremeType.LOW, CUTOFF);
        assertThat(lows).hasSize(1);
        assertThat(lows.getFirst()).isEqualByComparingTo("0.500");
    }

    @Test
    @DisplayName("A location whose only extremes are in the future reports a zero-count sample "
            + "rather than borrowing another location's history")
    void findHeightStatsBefore_futureOnlyLocation_returnsZeroCount() {
        repository.save(TideExtremeEntity.builder()
                .locationId(OTHER_LOCATION_ID)
                .eventTime(CUTOFF.plusDays(5))
                .heightMetres(new BigDecimal("5.000"))
                .type(TideExtremeType.HIGH)
                .fetchedAt(CUTOFF)
                .build());

        Object[] stats = unwrap(repository.findHeightStatsByLocationIdAndTypeBefore(
                OTHER_LOCATION_ID, TideExtremeType.HIGH, CUTOFF));

        // This is the newly added coastal location case: no history yet, so no threshold.
        // getTideStats turns a zero count into Optional.empty() rather than a fabricated mean.
        assertThat((Long) stats[3]).isZero();
        assertThat(stats[0]).isNull();
    }

    private static TideExtremeEntity extreme(LocalDateTime at, TideExtremeType type, String height) {
        return TideExtremeEntity.builder()
                .locationId(LOCATION_ID)
                .eventTime(at)
                .heightMetres(new BigDecimal(height))
                .type(type)
                .fetchedAt(at.minusDays(1))
                .build();
    }

    /** H2 may return {@code Object[1]{Object[4]}} for the aggregate projection. */
    private static Object[] unwrap(Object[] raw) {
        if (raw.length == 1 && raw[0] instanceof Object[] nested) {
            return nested;
        }
        return raw;
    }

    private static BigDecimal toBigDecimal(Object value) {
        return value instanceof BigDecimal decimal ? decimal : BigDecimal.valueOf((Double) value);
    }
}

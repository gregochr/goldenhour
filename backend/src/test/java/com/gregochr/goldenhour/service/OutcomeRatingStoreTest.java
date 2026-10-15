package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.entity.OutcomeRatingEntity;
import com.gregochr.goldenhour.entity.TargetType;
import com.gregochr.goldenhour.repository.OutcomeRatingRepository;
import com.gregochr.goldenhour.service.OutcomeRatingStore.RatedRun;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link OutcomeRatingStore} on H2 with the schema generated from the entity: insert, replace in
 * place, the first-rating insert race (12 threads, one row), and the two branches that are not a
 * race. The tests run outside a test transaction ({@code NOT_SUPPORTED}) so each repository call
 * commits as it does in production and the threads genuinely race — the very property the store's
 * "no {@code @Transactional}" rule protects. The production SQL dialect is exercised by V177's
 * Testcontainers test in CI.
 */
@DataJpaTest
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class OutcomeRatingStoreTest {

    private static final LocalDate DAY = LocalDate.of(2026, 10, 14);
    private static final Instant EVENT = Instant.parse("2026-10-14T06:41:00Z");
    private static final Instant T1 = Instant.parse("2026-10-14T09:00:00Z");
    private static final Instant T2 = Instant.parse("2026-10-14T10:00:00Z");
    private static final Instant RUN_AT = Instant.parse("2026-10-14T01:05:00Z");
    private static final RatedRun RUN = new RatedRun(7L, RUN_AT, 99L, 4);
    private static final long USER = 11L;
    private static final long LOCATION = 22L;

    @Autowired
    private OutcomeRatingRepository repository;

    private OutcomeRatingStore store() {
        return new OutcomeRatingStore(repository);
    }

    @AfterEach
    void clean() {
        repository.deleteAll();
    }

    @Test
    @DisplayName("a first rating inserts one row carrying every column, created and updated at the same instant")
    void firstRating_insertsEveryColumn() {
        store().upsert(USER, LOCATION, DAY, TargetType.SUNRISE, 4, EVENT, RUN, T1);

        List<OutcomeRatingEntity> rows = repository.findAll();
        assertThat(rows).hasSize(1);
        OutcomeRatingEntity row = rows.getFirst();
        assertThat(row.getUserId()).isEqualTo(USER);
        assertThat(row.getLocationId()).isEqualTo(LOCATION);
        assertThat(row.getEventDate()).isEqualTo(DAY);
        assertThat(row.getEventType()).isEqualTo(TargetType.SUNRISE);
        assertThat(row.getRating()).isEqualTo(4);
        assertThat(row.getEventTime()).isEqualTo(EVENT);
        assertThat(row.getPipelineRunId()).isEqualTo(7L);
        assertThat(row.getForecastRunAt()).isEqualTo(RUN_AT);
        assertThat(row.getForecastEvaluationId()).isEqualTo(99L);
        assertThat(row.getForecastRating()).isEqualTo(4);
        assertThat(row.getCreatedAt()).isEqualTo(T1);
        assertThat(row.getUpdatedAt()).isEqualTo(T1);
    }

    @Test
    @DisplayName("'didn't see it' and a slot with no forecast behind it store NULLs, not zeros")
    void notSeen_withNoForecast_storesNulls() {
        store().upsert(USER, LOCATION, DAY, TargetType.SUNSET, null, EVENT, RatedRun.NONE, T1);

        OutcomeRatingEntity row = repository.findAll().getFirst();
        assertThat(row.getRating()).isNull();
        assertThat(row.getPipelineRunId()).isNull();
        assertThat(row.getForecastRunAt()).isNull();
        assertThat(row.getForecastEvaluationId()).isNull();
        assertThat(row.getForecastRating()).isNull();
        assertThat(row.getEventTime()).isEqualTo(EVENT);
    }

    @Test
    @DisplayName("a second rating replaces the first in place: one row, every changeable column new, "
            + "created_at kept")
    void secondRating_replacesInPlace() {
        store().upsert(USER, LOCATION, DAY, TargetType.SUNRISE, 4, EVENT, RUN, T1);

        store().upsert(USER, LOCATION, DAY, TargetType.SUNRISE, null, EVENT.plusSeconds(60),
                RatedRun.NONE, T2);

        assertThat(repository.count()).isEqualTo(1);
        OutcomeRatingEntity row = repository.findAll().getFirst();
        assertThat(row.getRating()).as("'didn't see it' replaced the 4").isNull();
        assertThat(row.getEventTime()).isEqualTo(EVENT.plusSeconds(60));
        assertThat(row.getPipelineRunId()).isNull();
        assertThat(row.getForecastEvaluationId()).isNull();
        assertThat(row.getForecastRating()).isNull();
        assertThat(row.getCreatedAt()).isEqualTo(T1);
        assertThat(row.getUpdatedAt()).isEqualTo(T2);
    }

    @Test
    @DisplayName("another user, another location, the other event type and another date are other rows")
    void otherKeys_areOtherRows() {
        store().upsert(USER, LOCATION, DAY, TargetType.SUNRISE, 4, EVENT, RUN, T1);
        store().upsert(USER + 1, LOCATION, DAY, TargetType.SUNRISE, 2, EVENT, RUN, T1);
        store().upsert(USER, LOCATION + 1, DAY, TargetType.SUNRISE, 3, EVENT, RUN, T1);
        store().upsert(USER, LOCATION, DAY, TargetType.SUNSET, 5, EVENT, RUN, T1);
        store().upsert(USER, LOCATION, DAY.minusDays(1), TargetType.SUNRISE, 1, EVENT, RUN, T1);

        assertThat(repository.findAll()).extracting(OutcomeRatingEntity::getRating)
                .containsExactlyInAnyOrder(4, 2, 3, 5, 1);
    }

    @Test
    @DisplayName("twelve first ratings racing for one slot leave exactly one row, and nobody throws")
    void firstRatingRace_leavesOneRow() throws Exception {
        int threads = 12;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            int rating = 1 + (i % 5);
            Instant at = T1.plusSeconds(i);
            futures.add(pool.submit(() -> {
                start.await();
                store().upsert(USER, LOCATION, DAY, TargetType.SUNRISE, rating, EVENT, RUN, at);
                return null;
            }));
        }
        start.countDown();
        for (Future<?> future : futures) {
            future.get();
        }
        pool.shutdown();

        assertThat(repository.count()).isEqualTo(1);
        assertThat(repository.findAll().getFirst().getRating()).isBetween(1, 5);
    }

    @Test
    @DisplayName("an insert that fails while the row still does not exist is rethrown, not read as a race")
    void insertFailureWithNoRow_isRethrown() {
        OutcomeRatingRepository mocked = mock(OutcomeRatingRepository.class);
        when(mocked.existsByUserIdAndLocationIdAndEventDateAndEventType(anyLong(), anyLong(), any(), any()))
                .thenReturn(false);
        DataIntegrityViolationException fk = new DataIntegrityViolationException("foreign key");
        when(mocked.insertRow(anyLong(), anyLong(), any(), any(), any(), any(), any(), any(), any(),
                any(), any())).thenThrow(fk);

        assertThatThrownBy(() -> new OutcomeRatingStore(mocked)
                .upsert(USER, LOCATION, DAY, TargetType.SUNRISE, 3, EVENT, RUN, T1))
                .isSameAs(fk);
        verify(mocked, never()).updateRow(anyLong(), anyLong(), any(), any(), any(), any(), any(), any(),
                any(), any(), any());
    }

    @Test
    @DisplayName("a lost race (insert fails, row exists now) falls through to the update")
    void lostRace_updatesTheWinnersRow() {
        OutcomeRatingRepository mocked = mock(OutcomeRatingRepository.class);
        when(mocked.existsByUserIdAndLocationIdAndEventDateAndEventType(anyLong(), anyLong(), any(), any()))
                .thenReturn(false, true);
        when(mocked.insertRow(anyLong(), anyLong(), any(), any(), any(), any(), any(), any(), any(),
                any(), any())).thenThrow(new DataIntegrityViolationException("uq_outcome_rating_slot"));
        when(mocked.updateRow(anyLong(), anyLong(), any(), any(), any(), any(), any(), any(), any(),
                any(), any())).thenReturn(1);

        new OutcomeRatingStore(mocked).upsert(USER, LOCATION, DAY, TargetType.SUNRISE, 3, EVENT, RUN, T1);

        verify(mocked, times(1)).updateRow(USER, LOCATION, DAY, TargetType.SUNRISE, 3, EVENT, 7L, RUN_AT,
                99L, 4, T1);
    }

    @Test
    @DisplayName("an update that touches no row (the row vanished) is an error, never a silent success")
    void updateTouchingNothing_throws() {
        OutcomeRatingRepository mocked = mock(OutcomeRatingRepository.class);
        when(mocked.existsByUserIdAndLocationIdAndEventDateAndEventType(anyLong(), anyLong(), any(), any()))
                .thenReturn(true);
        when(mocked.updateRow(anyLong(), anyLong(), any(), any(), any(), any(), any(), any(), any(),
                any(), any())).thenReturn(0);

        assertThatThrownBy(() -> new OutcomeRatingStore(mocked)
                .upsert(USER, LOCATION, DAY, TargetType.SUNRISE, 3, EVENT, RUN, T1))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("rating of location 22");
    }
}

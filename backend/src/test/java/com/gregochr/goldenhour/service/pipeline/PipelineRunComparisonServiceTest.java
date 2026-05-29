package com.gregochr.goldenhour.service.pipeline;

import com.gregochr.goldenhour.entity.CycleType;
import com.gregochr.goldenhour.entity.PipelineRunEntity;
import com.gregochr.goldenhour.entity.PipelineRunPickEntity;
import com.gregochr.goldenhour.model.PipelineRunPickComparison;
import com.gregochr.goldenhour.repository.PipelineRunPickRepository;
import com.gregochr.goldenhour.repository.PipelineRunRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link PipelineRunComparisonService} — the intraday-vs-nightly
 * best-bet comparison that proves the refresh's worth.
 */
@ExtendWith(MockitoExtension.class)
class PipelineRunComparisonServiceTest {

    private static final Instant NIGHTLY_TRIGGER = Instant.parse("2026-05-26T01:00:00Z");
    private static final Instant INTRADAY_TRIGGER = Instant.parse("2026-05-26T14:00:00Z");
    private static final Long INTRADAY_ID = 50L;
    private static final Long NIGHTLY_ID = 42L;

    @Mock
    private PipelineRunRepository pipelineRunRepository;
    @Mock
    private PipelineRunPickRepository pickRepository;

    private PipelineRunComparisonService service;

    @BeforeEach
    void setUp() {
        service = new PipelineRunComparisonService(pipelineRunRepository, pickRepository);
    }

    private PipelineRunEntity intradayRun() {
        PipelineRunEntity run = new PipelineRunEntity(CycleType.INTRADAY, INTRADAY_TRIGGER);
        run.setId(INTRADAY_ID);
        return run;
    }

    private PipelineRunEntity nightlyRun() {
        PipelineRunEntity run = new PipelineRunEntity(CycleType.NIGHTLY, NIGHTLY_TRIGGER);
        run.setId(NIGHTLY_ID);
        return run;
    }

    private PipelineRunPickEntity pick(int rank, String region, LocalDate date,
            String eventType, Double rating) {
        PipelineRunPickEntity p = new PipelineRunPickEntity();
        p.setPickRank(rank);
        p.setRegion(region);
        p.setEventDate(date);
        p.setEventType(eventType);
        p.setClaudeAverageRating(rating);
        p.setHeadline(region + " " + eventType);
        return p;
    }

    private void stubBaselineFound() {
        when(pipelineRunRepository
                .findFirstByCycleTypeAndTriggerTimeBetweenOrderByTriggerTimeDesc(
                        eq(CycleType.NIGHTLY), any(), eq(INTRADAY_TRIGGER)))
                .thenReturn(Optional.of(nightlyRun()));
    }

    @Test
    @DisplayName("a NIGHTLY run yields no comparison — it is itself the baseline")
    void nightlyRun_noComparison() {
        assertThat(service.compareToSameDayNightly(nightlyRun())).isNull();
    }

    @Test
    @DisplayName("an intraday run with no same-day baseline yields no comparison")
    void intradayNoBaseline_noComparison() {
        when(pipelineRunRepository
                .findFirstByCycleTypeAndTriggerTimeBetweenOrderByTriggerTimeDesc(
                        eq(CycleType.NIGHTLY), any(), eq(INTRADAY_TRIGGER)))
                .thenReturn(Optional.empty());

        assertThat(service.compareToSameDayNightly(intradayRun())).isNull();
    }

    @Test
    @DisplayName("Plan A region change is flagged REGION; identical Plan B is unchanged")
    void planAChanged_planBSame() {
        stubBaselineFound();
        LocalDate d = LocalDate.of(2026, 5, 26);
        when(pickRepository.findByPipelineRunIdOrderByPickRankAsc(INTRADAY_ID))
                .thenReturn(List.of(
                        pick(1, "Coast", d, "sunset", 4.2),
                        pick(2, "Lakes", d.plusDays(1), "sunrise", 3.5)));
        when(pickRepository.findByPipelineRunIdOrderByPickRankAsc(NIGHTLY_ID))
                .thenReturn(List.of(
                        pick(1, "Hills", d, "sunset", 4.1),
                        pick(2, "Lakes", d.plusDays(1), "sunrise", 3.5)));

        PipelineRunPickComparison c = service.compareToSameDayNightly(intradayRun());

        assertThat(c).isNotNull();
        assertThat(c.baselineRunId()).isEqualTo(NIGHTLY_ID);
        assertThat(c.diffs()).hasSize(2);
        PipelineRunPickComparison.PickDiff planA = c.diffs().get(0);
        assertThat(planA.rank()).isEqualTo(1);
        assertThat(planA.changed()).isTrue();
        // Region differs; rating delta 0.1 is below the 0.5 threshold so RATING is NOT flagged.
        assertThat(planA.changedDimensions()).containsExactly("REGION");
        assertThat(c.diffs().get(1).changed()).isFalse();
    }

    @Test
    @DisplayName("rating change at/above the 0.5 threshold is flagged RATING")
    void ratingChange_aboveThreshold_flagged() {
        stubBaselineFound();
        LocalDate d = LocalDate.of(2026, 5, 26);
        when(pickRepository.findByPipelineRunIdOrderByPickRankAsc(INTRADAY_ID))
                .thenReturn(List.of(pick(1, "Coast", d, "sunset", 4.0)));
        when(pickRepository.findByPipelineRunIdOrderByPickRankAsc(NIGHTLY_ID))
                .thenReturn(List.of(pick(1, "Coast", d, "sunset", 3.5)));

        PipelineRunPickComparison c = service.compareToSameDayNightly(intradayRun());

        assertThat(c.diffs().get(0).changedDimensions()).containsExactly("RATING");
        assertThat(c.diffs().get(0).changed()).isTrue();
    }

    @Test
    @DisplayName("a rating change just below 0.5 is treated as noise (no RATING flag)")
    void ratingChange_belowThreshold_notFlagged() {
        stubBaselineFound();
        LocalDate d = LocalDate.of(2026, 5, 26);
        when(pickRepository.findByPipelineRunIdOrderByPickRankAsc(INTRADAY_ID))
                .thenReturn(List.of(pick(1, "Coast", d, "sunset", 4.0)));
        when(pickRepository.findByPipelineRunIdOrderByPickRankAsc(NIGHTLY_ID))
                .thenReturn(List.of(pick(1, "Coast", d, "sunset", 3.6)));

        PipelineRunPickComparison c = service.compareToSameDayNightly(intradayRun());

        assertThat(c.diffs().get(0).changed()).isFalse();
        assertThat(c.diffs().get(0).changedDimensions()).isEmpty();
    }

    @Test
    @DisplayName("a pick present in one run but not the other is flagged PRESENCE")
    void presenceMismatch_flagged() {
        stubBaselineFound();
        LocalDate d = LocalDate.of(2026, 5, 26);
        // Intraday surfaced a Plan B; the nightly run had none.
        when(pickRepository.findByPipelineRunIdOrderByPickRankAsc(INTRADAY_ID))
                .thenReturn(List.of(
                        pick(1, "Coast", d, "sunset", 4.0),
                        pick(2, "Lakes", d.plusDays(1), "sunrise", 3.0)));
        when(pickRepository.findByPipelineRunIdOrderByPickRankAsc(NIGHTLY_ID))
                .thenReturn(List.of(pick(1, "Coast", d, "sunset", 4.0)));

        PipelineRunPickComparison c = service.compareToSameDayNightly(intradayRun());

        PipelineRunPickComparison.PickDiff planB = c.diffs().get(1);
        assertThat(planB.changed()).isTrue();
        assertThat(planB.changedDimensions()).containsExactly("PRESENCE");
        assertThat(planB.intraday()).isNotNull();
        assertThat(planB.nightly()).isNull();
    }

    @Test
    @DisplayName("event type comparison is case-insensitive (no spurious EVENT flag)")
    void eventType_caseInsensitive() {
        stubBaselineFound();
        LocalDate d = LocalDate.of(2026, 5, 26);
        when(pickRepository.findByPipelineRunIdOrderByPickRankAsc(INTRADAY_ID))
                .thenReturn(List.of(pick(1, "Coast", d, "SUNSET", 4.0)));
        when(pickRepository.findByPipelineRunIdOrderByPickRankAsc(NIGHTLY_ID))
                .thenReturn(List.of(pick(1, "Coast", d, "sunset", 4.0)));

        PipelineRunPickComparison c = service.compareToSameDayNightly(intradayRun());

        assertThat(c.diffs().get(0).changed()).isFalse();
    }

    @Test
    @DisplayName("both runs with no picks → no comparison (nothing to diff)")
    void noPicksEitherSide_noComparison() {
        stubBaselineFound();
        lenient().when(pickRepository.findByPipelineRunIdOrderByPickRankAsc(any()))
                .thenReturn(List.of());

        assertThat(service.compareToSameDayNightly(intradayRun())).isNull();
    }
}

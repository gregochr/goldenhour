package com.gregochr.goldenhour.service.pipeline;

import com.gregochr.goldenhour.entity.PipelineRunPickEntity;
import com.gregochr.goldenhour.entity.TargetType;
import com.gregochr.goldenhour.model.BestBet;
import com.gregochr.goldenhour.model.BriefingEvaluationResult;
import com.gregochr.goldenhour.model.Confidence;
import com.gregochr.goldenhour.model.DiffersBy;
import com.gregochr.goldenhour.model.Relationship;
import com.gregochr.goldenhour.repository.PipelineRunPickRepository;
import com.gregochr.goldenhour.service.BriefingEvaluationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link PipelineRunPickService}.
 *
 * <p>The service has three responsibilities, each exercised below:
 * <ol>
 *   <li>Map {@link BestBet} → {@link PipelineRunPickEntity} (event-id parsing,
 *       differs-by formatting, confidence-name capture).</li>
 *   <li>Snapshot the region's {@code claudeAverageRating} at persist time
 *       (the cross-run comparison primitive — boundary tests at each null-
 *       returning branch).</li>
 *   <li>Be robust: null pipelineRunId, null/empty picks, per-pick DB failure,
 *       contract violation by the rating-lookup helpers must never throw out
 *       of {@link PipelineRunPickService#persist}.</li>
 * </ol>
 *
 * <p>Per test-improvement-standards.md: no {@code any()} in {@code verify(...)}
 * argument positions where a specific value is known, no {@code lenient()},
 * boundary tests at each conditional in {@code lookupAverageRating}.
 */
@ExtendWith(MockitoExtension.class)
class PipelineRunPickServiceTest {

    private static final Long RUN_ID = 99L;
    private static final Instant T0 = Instant.parse("2026-05-26T14:00:00Z");
    private static final LocalDate EVENT_DATE = LocalDate.of(2026, 5, 26);
    private static final String REGION = "Northumberland";

    @Mock
    private PipelineRunPickRepository repository;

    @Mock
    private BriefingEvaluationService briefingEvaluationService;

    private PipelineRunPickService service;

    @BeforeEach
    void setUp() {
        service = new PipelineRunPickService(repository, briefingEvaluationService,
                Clock.fixed(T0, ZoneOffset.UTC));
    }

    /** Convenience builder for a SUNSET pick in the canonical region. */
    private BestBet sunsetPick(int rank, Relationship relationship, List<DiffersBy> differsBy) {
        return new BestBet(rank, "headline-" + rank, "detail-" + rank,
                "2026-05-26_sunset", REGION, Confidence.HIGH, null,
                "Today", "sunset", "20:50",
                relationship, differsBy);
    }

    private BriefingEvaluationResult scored(String location, int rating) {
        return new BriefingEvaluationResult(location, rating, null, null, null);
    }

    @Nested
    @DisplayName("Mapping (BestBet → entity)")
    class Mapping {

        @Test
        @DisplayName("rank 1 pick persists with parsed date, captured confidence, recorded_at")
        void rank1_canonical_mapping() {
            BestBet pick = sunsetPick(1, null, List.of());
            when(briefingEvaluationService.getCachedScores(REGION, EVENT_DATE, TargetType.SUNSET))
                    .thenReturn(Map.of());

            service.persist(RUN_ID, List.of(pick));

            ArgumentCaptor<PipelineRunPickEntity> captor =
                    ArgumentCaptor.forClass(PipelineRunPickEntity.class);
            verify(repository).save(captor.capture());
            PipelineRunPickEntity saved = captor.getValue();
            assertThat(saved.getPipelineRunId()).isEqualTo(RUN_ID);
            assertThat(saved.getPickRank()).isEqualTo(1);
            assertThat(saved.getHeadline()).isEqualTo("headline-1");
            assertThat(saved.getDetail()).isEqualTo("detail-1");
            assertThat(saved.getEventId()).isEqualTo("2026-05-26_sunset");
            assertThat(saved.getEventDate()).isEqualTo(EVENT_DATE);
            assertThat(saved.getEventType()).isEqualTo("sunset");
            assertThat(saved.getRegion()).isEqualTo(REGION);
            assertThat(saved.getConfidence()).isEqualTo("HIGH");
            assertThat(saved.getRelationship()).isNull();
            assertThat(saved.getDiffersBy()).isNull();
            assertThat(saved.getRecordedAt()).isEqualTo(T0);
        }

        @Test
        @DisplayName("rank 2 SAME_SLOT pick stores relationship, empty differsBy stays null")
        void rank2_same_slot() {
            BestBet pick = sunsetPick(2, Relationship.SAME_SLOT, List.of());
            when(briefingEvaluationService.getCachedScores(REGION, EVENT_DATE, TargetType.SUNSET))
                    .thenReturn(Map.of());

            service.persist(RUN_ID, List.of(pick));

            ArgumentCaptor<PipelineRunPickEntity> captor =
                    ArgumentCaptor.forClass(PipelineRunPickEntity.class);
            verify(repository).save(captor.capture());
            assertThat(captor.getValue().getRelationship()).isEqualTo(Relationship.SAME_SLOT);
            assertThat(captor.getValue().getDiffersBy()).isNull();
        }

        @Test
        @DisplayName("rank 2 DIFFERENT_SLOT pick joins differsBy dimensions as CSV")
        void rank2_different_slot_differsby() {
            BestBet pick = sunsetPick(2, Relationship.DIFFERENT_SLOT,
                    List.of(DiffersBy.DATE, DiffersBy.EVENT));
            when(briefingEvaluationService.getCachedScores(REGION, EVENT_DATE, TargetType.SUNSET))
                    .thenReturn(Map.of());

            service.persist(RUN_ID, List.of(pick));

            ArgumentCaptor<PipelineRunPickEntity> captor =
                    ArgumentCaptor.forClass(PipelineRunPickEntity.class);
            verify(repository).save(captor.capture());
            assertThat(captor.getValue().getRelationship()).isEqualTo(Relationship.DIFFERENT_SLOT);
            assertThat(captor.getValue().getDiffersBy()).isEqualTo("DATE,EVENT");
        }

        @Test
        @DisplayName("two picks → two save calls in rank order")
        void two_picks_saved() {
            BestBet rank1 = sunsetPick(1, null, List.of());
            BestBet rank2 = sunsetPick(2, Relationship.SAME_SLOT, List.of());
            when(briefingEvaluationService.getCachedScores(REGION, EVENT_DATE, TargetType.SUNSET))
                    .thenReturn(Map.of());

            service.persist(RUN_ID, List.of(rank1, rank2));

            verify(repository, times(2)).save(any(PipelineRunPickEntity.class));
        }
    }

    @Nested
    @DisplayName("Rating snapshot (claudeAverageRating)")
    class RatingSnapshot {

        @Test
        @DisplayName("computes average from cached scores (snapshot equals advisor's view)")
        void averages_cached_ratings() {
            // Three locations rated 4, 5, 3 → average 4.0
            Map<String, BriefingEvaluationResult> cached = new LinkedHashMap<>();
            cached.put("A", scored("A", 4));
            cached.put("B", scored("B", 5));
            cached.put("C", scored("C", 3));
            when(briefingEvaluationService.getCachedScores(REGION, EVENT_DATE, TargetType.SUNSET))
                    .thenReturn(cached);

            service.persist(RUN_ID, List.of(sunsetPick(1, null, List.of())));

            ArgumentCaptor<PipelineRunPickEntity> captor =
                    ArgumentCaptor.forClass(PipelineRunPickEntity.class);
            verify(repository).save(captor.capture());
            assertThat(captor.getValue().getClaudeAverageRating()).isEqualTo(4.0);
        }

        @Test
        @DisplayName("no cached scores → null rating (graceful degrade to confidence-only)")
        void no_cached_scores_null_rating() {
            when(briefingEvaluationService.getCachedScores(REGION, EVENT_DATE, TargetType.SUNSET))
                    .thenReturn(Map.of());

            service.persist(RUN_ID, List.of(sunsetPick(1, null, List.of())));

            ArgumentCaptor<PipelineRunPickEntity> captor =
                    ArgumentCaptor.forClass(PipelineRunPickEntity.class);
            verify(repository).save(captor.capture());
            assertThat(captor.getValue().getClaudeAverageRating()).isNull();
        }

        @Test
        @DisplayName("aurora pick → no cached-scores lookup (no targetType match), null rating")
        void aurora_pick_skips_lookup() {
            BestBet aurora = new BestBet(1, "headline", "detail",
                    "2026-05-26_aurora", REGION, Confidence.HIGH, null,
                    null, "aurora", "after dark");

            service.persist(RUN_ID, List.of(aurora));

            // No lookup against briefingEvaluationService — aurora picks have no
            // region-level (region, date, SUNRISE|SUNSET) cached rating.
            verifyNoInteractions(briefingEvaluationService);
            ArgumentCaptor<PipelineRunPickEntity> captor =
                    ArgumentCaptor.forClass(PipelineRunPickEntity.class);
            verify(repository).save(captor.capture());
            assertThat(captor.getValue().getClaudeAverageRating()).isNull();
            assertThat(captor.getValue().getEventType()).isEqualTo("aurora");
            assertThat(captor.getValue().getEventDate()).isEqualTo(EVENT_DATE);
        }

        @Test
        @DisplayName("stay-home pick (null event, null region) → null rating, persists with nulls")
        void stay_home_pick() {
            BestBet stayHome = new BestBet(1, "stay home",
                    "everywhere standdown — edit last week's shots",
                    null, null, Confidence.HIGH, null,
                    null, null, null);

            service.persist(RUN_ID, List.of(stayHome));

            verifyNoInteractions(briefingEvaluationService);
            ArgumentCaptor<PipelineRunPickEntity> captor =
                    ArgumentCaptor.forClass(PipelineRunPickEntity.class);
            verify(repository).save(captor.capture());
            assertThat(captor.getValue().getRegion()).isNull();
            assertThat(captor.getValue().getEventId()).isNull();
            assertThat(captor.getValue().getEventDate()).isNull();
            assertThat(captor.getValue().getClaudeAverageRating()).isNull();
        }
    }

    @Nested
    @DisplayName("Robustness")
    class Robustness {

        @Test
        @DisplayName("null pipelineRunId → no-op, no repo or eval-service call")
        void null_run_id_is_no_op() {
            service.persist(null, List.of(sunsetPick(1, null, List.of())));

            verifyNoInteractions(repository);
            verifyNoInteractions(briefingEvaluationService);
        }

        @Test
        @DisplayName("null picks → no-op")
        void null_picks_is_no_op() {
            service.persist(RUN_ID, null);

            verifyNoInteractions(repository);
            verifyNoInteractions(briefingEvaluationService);
        }

        @Test
        @DisplayName("empty picks → no-op")
        void empty_picks_is_no_op() {
            service.persist(RUN_ID, List.of());

            verifyNoInteractions(repository);
        }

        @Test
        @DisplayName("per-pick DB failure does NOT prevent saving the other picks")
        void one_pick_db_failure_does_not_abort() {
            BestBet rank1 = sunsetPick(1, null, List.of());
            BestBet rank2 = sunsetPick(2, Relationship.SAME_SLOT, List.of());
            when(briefingEvaluationService.getCachedScores(REGION, EVENT_DATE, TargetType.SUNSET))
                    .thenReturn(Map.of());
            // First save throws; second must still be attempted.
            when(repository.save(any(PipelineRunPickEntity.class)))
                    .thenThrow(new RuntimeException("simulated DB failure"))
                    .thenAnswer(invocation -> invocation.getArgument(0));

            service.persist(RUN_ID, List.of(rank1, rank2));

            verify(repository, times(2)).save(any(PipelineRunPickEntity.class));
        }
    }

    @Nested
    @DisplayName("Helper functions — boundary tests")
    class HelperBoundaries {

        @Test
        @DisplayName("parseEventDate handles canonical, null, malformed inputs")
        void parse_event_date() {
            assertThat(PipelineRunPickService.parseEventDate("2026-05-26_sunset"))
                    .isEqualTo(LocalDate.of(2026, 5, 26));
            assertThat(PipelineRunPickService.parseEventDate(null)).isNull();
            assertThat(PipelineRunPickService.parseEventDate("")).isNull();
            assertThat(PipelineRunPickService.parseEventDate("no-underscore")).isNull();
            assertThat(PipelineRunPickService.parseEventDate("_sunset")).isNull();
            assertThat(PipelineRunPickService.parseEventDate("not-a-date_sunset")).isNull();
        }

        @Test
        @DisplayName("parseTargetType: sunrise/sunset round-trip, aurora/null/unknown → null")
        void parse_target_type() {
            assertThat(PipelineRunPickService.parseTargetType("sunset")).isEqualTo(TargetType.SUNSET);
            assertThat(PipelineRunPickService.parseTargetType("sunrise")).isEqualTo(TargetType.SUNRISE);
            assertThat(PipelineRunPickService.parseTargetType("SUNSET")).isEqualTo(TargetType.SUNSET);
            assertThat(PipelineRunPickService.parseTargetType("aurora")).isNull();
            assertThat(PipelineRunPickService.parseTargetType(null)).isNull();
            assertThat(PipelineRunPickService.parseTargetType("nonsense")).isNull();
        }

        @Test
        @DisplayName("formatDiffersBy: null/empty → null; single dim; multiple dims joined as CSV")
        void format_differs_by() {
            assertThat(PipelineRunPickService.formatDiffersBy(null)).isNull();
            assertThat(PipelineRunPickService.formatDiffersBy(List.of())).isNull();
            assertThat(PipelineRunPickService.formatDiffersBy(List.of(DiffersBy.DATE)))
                    .isEqualTo("DATE");
            assertThat(PipelineRunPickService.formatDiffersBy(
                    List.of(DiffersBy.DATE, DiffersBy.EVENT, DiffersBy.REGION)))
                    .isEqualTo("DATE,EVENT,REGION");
        }
    }

    @Test
    @DisplayName("save is not called for null-pipelineRunId paths (no false-positive writes)")
    void no_save_on_null_run_id_paths() {
        service.persist(null, List.of(sunsetPick(1, null, List.of())));
        service.persist(RUN_ID, null);
        service.persist(RUN_ID, List.of());

        verify(repository, never()).save(any(PipelineRunPickEntity.class));
    }

    @Test
    @DisplayName("repository.save called with the constructed entity (not a wholesale mock match)")
    void save_arg_is_the_entity_we_built() {
        BestBet pick = sunsetPick(1, null, List.of());
        when(briefingEvaluationService.getCachedScores(REGION, EVENT_DATE, TargetType.SUNSET))
                .thenReturn(Map.of());

        service.persist(RUN_ID, List.of(pick));

        // Specific verify: arg is the entity we built with RUN_ID + rank 1.
        // Not any(); this pins the exact call shape.
        verify(repository).save(org.mockito.ArgumentMatchers.argThat(e ->
                e.getPipelineRunId().equals(RUN_ID) && e.getPickRank() == 1));
    }

    @Test
    @DisplayName("briefingEvaluationService is called with the exact (region, date, targetType) "
            + "the advisor would see")
    void cached_scores_lookup_uses_pick_coordinates() {
        BestBet pick = sunsetPick(1, null, List.of());
        when(briefingEvaluationService.getCachedScores(REGION, EVENT_DATE, TargetType.SUNSET))
                .thenReturn(Map.of());

        service.persist(RUN_ID, List.of(pick));

        // Specific (region, date, targetType) — not any() — so a regression that
        // passes the wrong arguments is loud.
        verify(briefingEvaluationService).getCachedScores(
                eq(REGION), eq(EVENT_DATE), eq(TargetType.SUNSET));
    }
}

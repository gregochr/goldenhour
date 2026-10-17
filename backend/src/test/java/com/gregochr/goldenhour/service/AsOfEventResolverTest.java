package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.entity.ForecastEvaluationEntity;
import com.gregochr.goldenhour.entity.LocationEntity;
import com.gregochr.goldenhour.entity.LocationType;
import com.gregochr.goldenhour.entity.PipelineRunEntity;
import com.gregochr.goldenhour.entity.RegionEntity;
import com.gregochr.goldenhour.entity.TargetType;
import com.gregochr.goldenhour.entity.TriageDetails;
import com.gregochr.goldenhour.model.BriefingEvaluationResult;
import com.gregochr.goldenhour.model.TriageReason;
import com.gregochr.goldenhour.repository.ForecastEvaluationRepository;
import com.gregochr.goldenhour.repository.ForecastRunDispositionRepository;
import com.gregochr.goldenhour.repository.PipelineRunRepository;
import com.gregochr.goldenhour.service.AsOfEventResolver.AsOfEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link AsOfEventResolver}: the forecast a reader had been shown at a slot's event, with exact
 * instants. Every case below is one of the provenance rules the twelve Codex rounds on the plan
 * (§5a) produced; a failing one means a rating would be recorded — or a passed window drawn —
 * against a forecast the reader never saw.
 */
@ExtendWith(MockitoExtension.class)
class AsOfEventResolverTest {

    private static final LocalDate DAY = LocalDate.of(2026, 10, 14);
    private static final TargetType TYPE = TargetType.SUNRISE;
    private static final String REGION = "North Pennines";
    /** The event: 06:41 UTC. */
    private static final Instant EVENT = Instant.parse("2026-10-14T06:41:00Z");
    private static final Instant BEFORE = EVENT.minusSeconds(3600);
    private static final Instant AFTER = EVENT.plusSeconds(60);
    private static final Instant CYCLE = Instant.parse("2026-10-14T01:05:00Z");

    @Mock
    private EvaluationViewService evaluationViewService;
    @Mock
    private BriefingEvaluationService briefingEvaluationService;
    @Mock
    private ForecastEvaluationRepository forecastEvaluationRepository;
    @Mock
    private ForecastRunDispositionRepository dispositionRepository;
    @Mock
    private PipelineRunRepository pipelineRunRepository;

    private AsOfEventResolver resolver;
    private final RegionEntity region = RegionEntity.builder().id(1L).name(REGION).build();

    @BeforeEach
    void setUp() {
        resolver = new AsOfEventResolver(evaluationViewService, briefingEvaluationService,
                forecastEvaluationRepository, dispositionRepository, pipelineRunRepository);
    }

    // ── fixtures ────────────────────────────────────────────────────────────────

    private LocationEntity place(long id, String name, LocationType... types) {
        return LocationEntity.builder().id(id).name(name).lat(54.7).lon(-1.5).region(region)
                .locationType(Set.of(types)).build();
    }

    private LocationEntity sky() {
        return place(10L, "Hartside", LocationType.LANDSCAPE);
    }

    private static BriefingEvaluationResult served(String name, Integer rating, Instant evaluatedAt,
            Instant submittedAt) {
        return new BriefingEvaluationResult(name, rating, 60, 55, "Summary", null, null, "Headline",
                evaluatedAt, rating, false, false, submittedAt);
    }

    private static ForecastEvaluationEntity row(long id, LocationEntity location, LocalDateTime runAt,
            Instant scoredAt, Integer rating) {
        return ForecastEvaluationEntity.builder().id(id).location(location).targetDate(DAY)
                .targetType(TYPE).forecastRunAt(runAt).scoredAt(scoredAt).rating(rating).build();
    }

    private static LocalDateTime utc(Instant instant) {
        return LocalDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    private void servedIs(String name, BriefingEvaluationResult result) {
        when(evaluationViewService.getLiveScoresForEnrichment(REGION, DAY, TYPE))
                .thenReturn(result == null ? Map.of() : Map.of(name, result));
    }

    private void poolIs(ForecastEvaluationEntity... rows) {
        when(forecastEvaluationRepository.findScoredByEventInstant(anyCollection(), eq(DAY), eq(TYPE), any()))
                .thenReturn(List.of(rows));
    }

    private void skips(String name, Instant... at) {
        List<Object[]> out = new ArrayList<>();
        for (Instant i : at) {
            out.add(new Object[] {name, i});
        }
        when(dispositionRepository.findStabilitySkipInstantsUpTo(anyCollection(), eq(DAY), eq("SUNRISE"), any()))
                .thenReturn(out);
    }

    private void cycle(Instant trigger, Long id) {
        PipelineRunEntity run = new PipelineRunEntity();
        run.setId(id);
        when(pipelineRunRepository.findFirstByTriggerTimeOrderByIdAsc(trigger)).thenReturn(Optional.of(run));
    }

    // ── step 1: the served result ───────────────────────────────────────────────

    @Nested
    @DisplayName("step 1 - the served result")
    class Step1 {

        @Test
        @DisplayName("a cached result that arrived before the event, whose submittedAt is a cycle's trigger "
                + "time: the cycle's id, the submission instant, the served star")
        void cycleResult() {
            LocationEntity loc = sky();
            servedIs("Hartside", served("Hartside", 4, BEFORE, CYCLE));
            cycle(CYCLE, 77L);

            AsOfEvent out = resolver.resolve(loc, DAY, TYPE, EVENT);

            assertThat(out.runAt()).isEqualTo(CYCLE);
            assertThat(out.pipelineRunId()).isEqualTo(77L);
            assertThat(out.forecastRating()).isEqualTo(4);
            assertThat(out.result().rating()).isEqualTo(4);
            assertThat(out.forecastEvaluationId()).as("no scored row existed").isNull();
        }

        @Test
        @DisplayName("an ad-hoc batch result (a submittedAt no cycle has): the instant, no cycle id")
        void adHocBatchResult() {
            Instant adHoc = Instant.parse("2026-10-14T03:30:00Z");
            servedIs("Hartside", served("Hartside", 3, BEFORE, adHoc));
            when(pipelineRunRepository.findFirstByTriggerTimeOrderByIdAsc(adHoc)).thenReturn(Optional.empty());

            AsOfEvent out = resolver.resolve(sky(), DAY, TYPE, EVENT);

            assertThat(out.runAt()).isEqualTo(adHoc);
            assertThat(out.pipelineRunId()).isNull();
            assertThat(out.forecastRating()).isEqualTo(3);
        }

        @Test
        @DisplayName("a legacy result with evaluatedAt only: its arrival is the run, and no cycle is looked up")
        void legacyResultWithEvaluatedAtOnly() {
            servedIs("Hartside", served("Hartside", 5, BEFORE, null));

            AsOfEvent out = resolver.resolve(sky(), DAY, TYPE, EVENT);

            assertThat(out.runAt()).isEqualTo(BEFORE);
            assertThat(out.pipelineRunId()).isNull();
            verify(pipelineRunRepository, never()).findFirstByTriggerTimeOrderByIdAsc(any());
        }

        @Test
        @DisplayName("a result with no evaluatedAt at all (built from a forecast row, or legacy) is skipped")
        void resultWithoutEvaluatedAtIsSkipped() {
            servedIs("Hartside", served("Hartside", 4, null, CYCLE));

            AsOfEvent out = resolver.resolve(sky(), DAY, TYPE, EVENT);

            assertThat(out).isEqualTo(AsOfEvent.NONE);
        }

        @Test
        @DisplayName("the arrival boundary: evaluatedAt == the event is taken, one nanosecond later is set aside")
        void arrivalBoundary() {
            servedIs("Hartside", served("Hartside", 4, EVENT, CYCLE));
            cycle(CYCLE, 77L);
            assertThat(resolver.resolve(sky(), DAY, TYPE, EVENT).result()).isNotNull();

            servedIs("Hartside", served("Hartside", 4, EVENT.plusNanos(1), CYCLE));
            assertThat(resolver.resolve(sky(), DAY, TYPE, EVENT).result()).isNull();
        }

        @Test
        @DisplayName("a result submitted before the event but ARRIVED after it is set aside: the latest row "
                + "scored by the event stands in - never the late batch's own row")
        void lateBatchIsSetAside() {
            LocationEntity loc = sky();
            servedIs("Hartside", served("Hartside", 5, AFTER, CYCLE));
            ForecastEvaluationEntity lateBatchOwn = row(3L, loc, utc(BEFORE.minusSeconds(60)), AFTER, 5);
            ForecastEvaluationEntity earlier = row(2L, loc, utc(Instant.parse("2026-10-14T01:00:00Z")),
                    Instant.parse("2026-10-14T01:20:00Z"), 3);
            poolIs(lateBatchOwn, earlier);

            AsOfEvent out = resolver.resolve(loc, DAY, TYPE, EVENT);

            assertThat(out.result()).isNull();
            assertThat(out.forecastEvaluationId()).isEqualTo(2L);
            assertThat(out.forecastRating()).isEqualTo(3);
            assertThat(out.runAt()).isEqualTo(Instant.parse("2026-10-14T01:00:00Z"));
            assertThat(out.pipelineRunId()).isNull();
        }

        @Test
        @DisplayName("a retracted result (the live accessor does not return it) falls to step 2")
        void retractedResultFallsToStep2() {
            LocationEntity loc = sky();
            servedIs("Hartside", null);
            poolIs(row(2L, loc, utc(BEFORE), BEFORE, 2));

            AsOfEvent out = resolver.resolve(loc, DAY, TYPE, EVENT);

            assertThat(out.result()).isNull();
            assertThat(out.forecastEvaluationId()).isEqualTo(2L);
            assertThat(out.forecastRating()).isEqualTo(2);
        }

        @Test
        @DisplayName("an unregioned location skips step 1 altogether - the served read is never asked")
        void unregionedSkipsStep1() {
            LocationEntity loc = LocationEntity.builder().id(11L).name("Nowhere").lat(1).lon(1)
                    .locationType(Set.of(LocationType.LANDSCAPE)).build();
            poolIs(row(2L, loc, utc(BEFORE), BEFORE, 4));

            AsOfEvent out = resolver.resolve(loc, DAY, TYPE, EVENT);

            verify(evaluationViewService, never()).getLiveScoresForEnrichment(any(), any(), any());
            assertThat(out.result()).isNull();
            assertThat(out.forecastRating()).isEqualTo(4);
        }

        @Test
        @DisplayName("the served result's star is what is captured, even when the step-2 row says otherwise: "
                + "a recombined 3 over a sky row's 4, with the row's id still recorded")
        void servedStarBeatsTheRowsStar() {
            LocationEntity loc = place(12L, "Gibbet Hill", LocationType.LANDSCAPE);
            servedIs("Gibbet Hill", served("Gibbet Hill", 3, BEFORE, CYCLE));
            cycle(CYCLE, 77L);
            poolIs(row(5L, loc, utc(CYCLE), BEFORE, 4));

            AsOfEvent out = resolver.resolve(loc, DAY, TYPE, EVENT);

            assertThat(out.forecastRating()).isEqualTo(3);
            assertThat(out.forecastEvaluationId()).as("resolved on every call, step 1 answering or not")
                    .isEqualTo(5L);
            assertThat(out.runAt()).as("step 1 supplies the run").isEqualTo(CYCLE);
            assertThat(out.pipelineRunId()).isEqualTo(77L);
        }
    }

    // ── canopy-bearing places ───────────────────────────────────────────────────

    @Nested
    @DisplayName("a canopy-bearing place (WOODLAND or BLUEBELL)")
    class Canopy {

        @Test
        @DisplayName("whose cached entry was last written AFTER the event is set aside: step 2, and its star "
                + "is not recoverable (null), never the sky row's")
        void entryWrittenAfterEvent_isSetAside() {
            LocationEntity loc = place(20L, "Allen Banks", LocationType.LANDSCAPE, LocationType.BLUEBELL);
            servedIs("Allen Banks", served("Allen Banks", 4, BEFORE, CYCLE));
            when(briefingEvaluationService.getCachedEntryUpdatedAt(REGION, DAY, TYPE))
                    .thenReturn(Optional.of(AFTER));
            poolIs(row(8L, loc, utc(CYCLE), BEFORE, 4));

            AsOfEvent out = resolver.resolve(loc, DAY, TYPE, EVENT);

            assertThat(out.result()).isNull();
            assertThat(out.forecastEvaluationId()).isEqualTo(8L);
            assertThat(out.forecastRating()).as("the composite star is gone from every table").isNull();
            assertThat(out.runAt()).isEqualTo(utc(CYCLE).toInstant(ZoneOffset.UTC));
        }

        @Test
        @DisplayName("whose cached entry was last written before the event is taken, with the served star")
        void entryWrittenBeforeEvent_isTaken() {
            LocationEntity loc = place(21L, "Hamsterley", LocationType.WOODLAND);
            servedIs("Hamsterley", served("Hamsterley", 3, BEFORE, CYCLE));
            cycle(CYCLE, 77L);
            when(briefingEvaluationService.getCachedEntryUpdatedAt(REGION, DAY, TYPE))
                    .thenReturn(Optional.of(BEFORE.plusSeconds(10)));

            AsOfEvent out = resolver.resolve(loc, DAY, TYPE, EVENT);

            assertThat(out.result()).isNotNull();
            assertThat(out.forecastRating()).isEqualTo(3);
            assertThat(out.pipelineRunId()).isEqualTo(77L);
        }

        @Test
        @DisplayName("whose cached entry was written exactly AT the event is taken")
        void entryWrittenAtEvent_isTaken() {
            LocationEntity loc = place(23L, "Hamsterley", LocationType.WOODLAND);
            servedIs("Hamsterley", served("Hamsterley", 3, BEFORE, CYCLE));
            cycle(CYCLE, 77L);
            when(briefingEvaluationService.getCachedEntryUpdatedAt(REGION, DAY, TYPE))
                    .thenReturn(Optional.of(EVENT));

            assertThat(resolver.resolve(loc, DAY, TYPE, EVENT).result()).isNotNull();
        }

        @Test
        @DisplayName("with no durable cache row cannot show its composite was available: step 2")
        void noDurableEntry_isSetAside() {
            LocationEntity loc = place(22L, "Dipton Wood", LocationType.BLUEBELL);
            servedIs("Dipton Wood", served("Dipton Wood", 4, BEFORE, CYCLE));
            when(briefingEvaluationService.getCachedEntryUpdatedAt(REGION, DAY, TYPE)).thenReturn(Optional.empty());

            assertThat(resolver.resolve(loc, DAY, TYPE, EVENT)).isEqualTo(AsOfEvent.NONE);
        }

        @Test
        @DisplayName("a single-lane sky place never reads the cache row, and its fallback star is the row's; "
                + "a canopy-bearing place's is null")
        void fallbackStarOnlyForSkyOnlyPlaces() {
            LocationEntity skyOnly = sky();
            servedIs("Hartside", null);
            poolIs(row(2L, skyOnly, utc(BEFORE), BEFORE, 4));
            assertThat(resolver.resolve(skyOnly, DAY, TYPE, EVENT).forecastRating()).isEqualTo(4);
            verify(briefingEvaluationService, never()).getCachedEntryUpdatedAt(any(), any(), any());

            for (LocationType canopy : List.of(LocationType.BLUEBELL, LocationType.WOODLAND)) {
                LocationEntity loc = place(30L, "Hartside", LocationType.LANDSCAPE, canopy);
                poolIs(row(2L, loc, utc(BEFORE), BEFORE, 4));
                AsOfEvent out = resolver.resolve(loc, DAY, TYPE, EVENT);
                assertThat(out.forecastRating()).as("%s place", canopy).isNull();
                assertThat(out.forecastEvaluationId()).isEqualTo(2L);
            }
        }

        @Test
        @DisplayName("isCanopyBearing: WOODLAND or BLUEBELL, nothing else, and a null type set is not canopy")
        void isCanopyBearing() {
            assertThat(AsOfEventResolver.isCanopyBearing(place(1L, "a", LocationType.WOODLAND))).isTrue();
            assertThat(AsOfEventResolver.isCanopyBearing(place(1L, "a", LocationType.BLUEBELL))).isTrue();
            assertThat(AsOfEventResolver.isCanopyBearing(
                    place(1L, "a", LocationType.LANDSCAPE, LocationType.SEASCAPE, LocationType.WILDLIFE)))
                    .isFalse();
            LocationEntity noTypes = LocationEntity.builder().id(1L).name("a").lat(1).lon(1).build();
            noTypes.setLocationType(null);
            assertThat(AsOfEventResolver.isCanopyBearing(noTypes)).isFalse();
        }
    }

    // ── step 2: the forecast_evaluation candidate ───────────────────────────────

    @Nested
    @DisplayName("step 2 - the forecast_evaluation row")
    class Step2 {

        @Test
        @DisplayName("with nothing served, a row scored before the event: its submission instant, no cycle id")
        void rowScoredBeforeEvent() {
            LocationEntity loc = sky();
            servedIs("Hartside", null);
            poolIs(row(2L, loc, utc(Instant.parse("2026-10-14T02:00:00Z")),
                    Instant.parse("2026-10-14T02:30:00Z"), 4));

            AsOfEvent out = resolver.resolve(loc, DAY, TYPE, EVENT);

            assertThat(out.runAt()).isEqualTo(Instant.parse("2026-10-14T02:00:00Z"));
            assertThat(out.pipelineRunId()).isNull();
            assertThat(out.forecastEvaluationId()).isEqualTo(2L);
            assertThat(out.forecastRow().getId()).isEqualTo(2L);
        }

        @Test
        @DisplayName("a row scored after the event is not a candidate, whatever its submission instant: nulls")
        void rowScoredAfterEvent() {
            LocationEntity loc = sky();
            servedIs("Hartside", null);
            poolIs(row(2L, loc, utc(BEFORE), AFTER, 4));

            assertThat(resolver.resolve(loc, DAY, TYPE, EVENT)).isEqualTo(AsOfEvent.NONE);
        }

        @Test
        @DisplayName("a legacy row with a null scored_at is never a candidate, however early its submission")
        void legacyRowWithoutScoredAt() {
            LocationEntity loc = sky();
            servedIs("Hartside", null);
            poolIs(row(2L, loc, utc(EVENT.minusSeconds(86_400)), null, 4));

            assertThat(resolver.resolve(loc, DAY, TYPE, EVENT)).isEqualTo(AsOfEvent.NONE);
        }

        @Test
        @DisplayName("a row with nothing to say (no rating, no triage reason) is skipped for an earlier one "
                + "that has")
        void emptyRowIsSkipped() {
            LocationEntity loc = sky();
            servedIs("Hartside", null);
            ForecastEvaluationEntity empty = row(3L, loc, utc(BEFORE), BEFORE, null);
            ForecastEvaluationEntity rated = row(2L, loc, utc(BEFORE.minusSeconds(600)),
                    BEFORE.minusSeconds(500), 2);
            poolIs(empty, rated);

            assertThat(resolver.resolve(loc, DAY, TYPE, EVENT).forecastEvaluationId()).isEqualTo(2L);
        }

        @Test
        @DisplayName("a triage-only row is a decision the reader saw: it is the candidate, with no star")
        void triageRowIsACandidate() {
            LocationEntity loc = sky();
            servedIs("Hartside", null);
            ForecastEvaluationEntity triaged = row(4L, loc, utc(BEFORE), BEFORE, null);
            triaged.setTriage(new TriageDetails(TriageReason.HIGH_CLOUD, "Low cloud 90%"));
            poolIs(triaged);

            AsOfEvent out = resolver.resolve(loc, DAY, TYPE, EVENT);

            assertThat(out.forecastEvaluationId()).isEqualTo(4L);
            assertThat(out.forecastRating()).isNull();
            assertThat(out.runAt()).isEqualTo(BEFORE);
        }

        @Test
        @DisplayName("a row SUBMITTED before a stability skip but scored after it, both before the event, "
                + "is retracted - the skip is compared with the submission instant, not scored_at")
        void submittedBeforeSkipScoredAfter_isRetracted() {
            LocationEntity loc = sky();
            servedIs("Hartside", null);
            Instant skip = Instant.parse("2026-10-14T03:00:00Z");
            poolIs(row(2L, loc, utc(Instant.parse("2026-10-14T02:30:00Z")),
                    Instant.parse("2026-10-14T04:00:00Z"), 4));
            skips("Hartside", skip);

            assertThat(resolver.resolve(loc, DAY, TYPE, EVENT)).isEqualTo(AsOfEvent.NONE);
        }

        @Test
        @DisplayName("a row submitted AFTER the skip stands; and a skip recorded AFTER the event is ignored")
        void rowAfterSkipStands_andALateSkipIsIgnored() {
            LocationEntity loc = sky();
            servedIs("Hartside", null);
            ForecastEvaluationEntity afterSkip = row(2L, loc, utc(Instant.parse("2026-10-14T03:30:00Z")),
                    Instant.parse("2026-10-14T03:45:00Z"), 4);
            poolIs(afterSkip);
            skips("Hartside", Instant.parse("2026-10-14T03:00:00Z"));
            assertThat(resolver.resolve(loc, DAY, TYPE, EVENT).forecastEvaluationId()).isEqualTo(2L);

            ForecastEvaluationEntity beforeSkip = row(3L, loc, utc(Instant.parse("2026-10-14T02:00:00Z")),
                    Instant.parse("2026-10-14T02:10:00Z"), 4);
            poolIs(beforeSkip);
            skips("Hartside", AFTER);
            assertThat(resolver.resolve(loc, DAY, TYPE, EVENT).forecastEvaluationId())
                    .as("a skip after the event retracts what the reader saw later, not then").isEqualTo(3L);
        }

        @Test
        @DisplayName("the skip boundary: a skip exactly at the row's submission instant does not retract it")
        void skipAtSubmissionInstant_doesNotRetract() {
            LocationEntity loc = sky();
            servedIs("Hartside", null);
            Instant same = Instant.parse("2026-10-14T03:00:00Z");
            poolIs(row(2L, loc, utc(same), same, 4));
            skips("Hartside", same);

            assertThat(resolver.resolve(loc, DAY, TYPE, EVENT).forecastEvaluationId()).isEqualTo(2L);
        }

        @Test
        @DisplayName("the event boundaries: a skip recorded AT the event still retracts an earlier-submitted "
                + "row, and a row scored AT the event is available")
        void boundariesAtTheEventInstant() {
            LocationEntity loc = sky();
            servedIs("Hartside", null);
            poolIs(row(2L, loc, utc(BEFORE), BEFORE, 4));
            skips("Hartside", EVENT);
            assertThat(resolver.resolve(loc, DAY, TYPE, EVENT)).as("skip at the event retracts")
                    .isEqualTo(AsOfEvent.NONE);

            poolIs(row(3L, loc, utc(BEFORE), EVENT, 4));
            skips("Hartside");
            assertThat(resolver.resolve(loc, DAY, TYPE, EVENT).forecastEvaluationId())
                    .as("scored exactly at the event").isEqualTo(3L);
        }

        @Test
        @DisplayName("served result answers while its same-slot candidate row is retracted: the id is null, "
                + "the served star and run stand")
        void servedAnswersWhileCandidateRetracted() {
            LocationEntity loc = sky();
            servedIs("Hartside", served("Hartside", 4, BEFORE, CYCLE));
            cycle(CYCLE, 77L);
            poolIs(row(2L, loc, utc(Instant.parse("2026-10-14T02:00:00Z")),
                    Instant.parse("2026-10-14T02:10:00Z"), 3));
            skips("Hartside", Instant.parse("2026-10-14T03:00:00Z"));

            AsOfEvent out = resolver.resolve(loc, DAY, TYPE, EVENT);

            assertThat(out.result()).isNotNull();
            assertThat(out.forecastEvaluationId()).isNull();
            assertThat(out.forecastRow()).isNull();
            assertThat(out.forecastRating()).isEqualTo(4);
            assertThat(out.pipelineRunId()).isEqualTo(77L);
        }

        @Test
        @DisplayName("a skip of ANOTHER place never retracts this place's row")
        void anotherPlacesSkipIsIgnored() {
            LocationEntity loc = sky();
            servedIs("Hartside", null);
            poolIs(row(2L, loc, utc(Instant.parse("2026-10-14T02:00:00Z")),
                    Instant.parse("2026-10-14T02:10:00Z"), 4));
            skips("Someone Else", Instant.parse("2026-10-14T03:00:00Z"));

            assertThat(resolver.resolve(loc, DAY, TYPE, EVENT).forecastEvaluationId()).isEqualTo(2L);
        }

        @Test
        @DisplayName("a retracted-then-live pair: the later submission stands over the earlier retracted one")
        void newestUnretractedWins() {
            LocationEntity loc = sky();
            servedIs("Hartside", null);
            ForecastEvaluationEntity newer = row(3L, loc, utc(Instant.parse("2026-10-14T04:00:00Z")),
                    Instant.parse("2026-10-14T04:10:00Z"), 5);
            ForecastEvaluationEntity older = row(2L, loc, utc(Instant.parse("2026-10-14T02:00:00Z")),
                    Instant.parse("2026-10-14T02:10:00Z"), 3);
            poolIs(newer, older);
            skips("Hartside", Instant.parse("2026-10-14T03:00:00Z"));

            AsOfEvent out = resolver.resolve(loc, DAY, TYPE, EVENT);

            assertThat(out.forecastEvaluationId()).isEqualTo(3L);
            assertThat(out.forecastRating()).isEqualTo(5);
        }
    }

    // ── the batch shape ─────────────────────────────────────────────────────────

    @Nested
    @DisplayName("resolveAll - one read for the roster's day")
    class Batch {

        @Test
        @DisplayName("every place is answered from ONE pool read, ONE skip read and ONE bulk served read, "
                + "each against its own event instant")
        void rosterInOneRead() {
            LocationEntity a = place(10L, "A", LocationType.LANDSCAPE);
            LocationEntity b = place(11L, "B", LocationType.LANDSCAPE);
            LocationEntity c = place(12L, "C", LocationType.LANDSCAPE);
            Instant earlyEvent = EVENT;
            Instant lateEvent = EVENT.plusSeconds(1200);
            when(evaluationViewService.getLiveScoresForEnrichmentBulk(DAY, DAY, Set.of(TYPE)))
                    .thenReturn(Map.of(REGION + "|" + DAY + "|" + TYPE,
                            Map.of("A", served("A", 4, BEFORE, CYCLE))));
            cycle(CYCLE, 77L);
            // B's only row is scored between the two events: available to C's later event, not to B's.
            Instant between = EVENT.plusSeconds(600);
            when(forecastEvaluationRepository.findScoredByEventInstant(anyCollection(), eq(DAY), eq(TYPE),
                    eq(lateEvent))).thenReturn(List.of(
                            row(2L, b, utc(BEFORE), between, 3), row(3L, c, utc(BEFORE), between, 2)));
            when(dispositionRepository.findStabilitySkipInstantsUpTo(anyCollection(), eq(DAY), eq("SUNRISE"),
                    eq(lateEvent))).thenReturn(List.of());

            Map<Long, AsOfEvent> out = resolver.resolveAll(List.of(a, b, c), DAY, TYPE,
                    loc -> loc.getId() == 12L ? lateEvent : earlyEvent);

            assertThat(out.get(10L).forecastRating()).isEqualTo(4);
            assertThat(out.get(10L).pipelineRunId()).isEqualTo(77L);
            assertThat(out.get(11L)).as("scored after B's own event").isEqualTo(AsOfEvent.NONE);
            assertThat(out.get(12L).forecastEvaluationId()).as("scored before C's later event").isEqualTo(3L);
            verify(forecastEvaluationRepository, times(1)).findScoredByEventInstant(any(), any(), any(), any());
            verify(dispositionRepository, times(1)).findStabilitySkipInstantsUpTo(any(), any(), any(), any());
            verify(evaluationViewService, times(1)).getLiveScoresForEnrichmentBulk(any(), any(), any());
            verify(evaluationViewService, never()).getLiveScoresForEnrichment(any(), any(), any());
        }

        @Test
        @DisplayName("a skip between two places' events retracts only the later place's row, and one "
                + "canopy cache read serves every canopy place of the region")
        void perSlotSkipsAndTheCanopyRegionRead() {
            LocationEntity a = place(10L, "A", LocationType.LANDSCAPE);
            LocationEntity b = place(11L, "B", LocationType.LANDSCAPE);
            LocationEntity wood1 = place(12L, "W1", LocationType.WOODLAND);
            LocationEntity wood2 = place(13L, "W2", LocationType.BLUEBELL);
            Instant lateEvent = EVENT.plusSeconds(1200);
            Instant skip = EVENT.plusSeconds(600);
            Instant runAt = EVENT.minusSeconds(7200);
            when(evaluationViewService.getLiveScoresForEnrichmentBulk(DAY, DAY, Set.of(TYPE)))
                    .thenReturn(Map.of(REGION + "|" + DAY + "|" + TYPE, Map.of(
                            "W1", served("W1", 3, BEFORE, CYCLE), "W2", served("W2", 2, BEFORE, CYCLE))));
            cycle(CYCLE, 77L);
            when(briefingEvaluationService.getCachedEntryUpdatedAt(REGION, DAY, TYPE))
                    .thenReturn(Optional.of(BEFORE));
            when(forecastEvaluationRepository.findScoredByEventInstant(anyCollection(), eq(DAY), eq(TYPE),
                    eq(lateEvent))).thenReturn(List.of(
                            row(2L, a, utc(runAt), runAt.plusSeconds(60), 3),
                            row(3L, b, utc(runAt), runAt.plusSeconds(60), 3)));
            when(dispositionRepository.findStabilitySkipInstantsUpTo(anyCollection(), eq(DAY), eq("SUNRISE"),
                    eq(lateEvent))).thenReturn(List.<Object[]>of(
                            new Object[] {"A", skip}, new Object[] {"B", skip}));

            Map<Long, AsOfEvent> out = resolver.resolveAll(List.of(a, b, wood1, wood2), DAY, TYPE,
                    loc -> loc.getId() == 11L ? lateEvent : EVENT);

            assertThat(out.get(10L).forecastEvaluationId())
                    .as("the skip is AFTER A's own event, so it is ignored for A").isEqualTo(2L);
            assertThat(out.get(11L)).as("the skip precedes B's event and its row's submission").isEqualTo(
                    AsOfEvent.NONE);
            assertThat(out.get(12L).forecastRating()).isEqualTo(3);
            assertThat(out.get(13L).forecastRating()).isEqualTo(2);
            verify(briefingEvaluationService, times(1)).getCachedEntryUpdatedAt(any(), any(), any());
        }

        @Test
        @DisplayName("a place with no event instant resolves to NONE, is left out of the reads, and an all-null "
                + "roster touches no repository at all")
        void nullEventInstants() {
            LocationEntity a = place(10L, "A", LocationType.LANDSCAPE);
            LocationEntity b = place(11L, "B", LocationType.LANDSCAPE);
            when(forecastEvaluationRepository.findScoredByEventInstant(
                    eq(List.of(10L)), eq(DAY), eq(TYPE), any())).thenReturn(List.of());
            when(evaluationViewService.getLiveScoresForEnrichmentBulk(DAY, DAY, Set.of(TYPE)))
                    .thenReturn(Map.of());

            Map<Long, AsOfEvent> out = resolver.resolveAll(List.of(a, b), DAY, TYPE,
                    loc -> loc.getId() == 10L ? EVENT : null);

            assertThat(out).containsEntry(11L, AsOfEvent.NONE).containsEntry(10L, AsOfEvent.NONE);

            Map<Long, AsOfEvent> none = resolver.resolveAll(List.of(a, b), DAY, TYPE, loc -> null);
            assertThat(none).hasSize(2).containsValue(AsOfEvent.NONE);
            verify(forecastEvaluationRepository, times(1)).findScoredByEventInstant(any(), any(), any(), any());
        }

        @Test
        @DisplayName("places sharing a served submission instant share ONE cycle lookup")
        void cycleLookupIsMemoised() {
            LocationEntity a = place(10L, "A", LocationType.LANDSCAPE);
            LocationEntity b = place(11L, "B", LocationType.LANDSCAPE);
            when(evaluationViewService.getLiveScoresForEnrichmentBulk(DAY, DAY, Set.of(TYPE)))
                    .thenReturn(Map.of(REGION + "|" + DAY + "|" + TYPE, Map.of(
                            "A", served("A", 4, BEFORE, CYCLE), "B", served("B", 2, BEFORE, CYCLE))));
            cycle(CYCLE, 77L);

            Map<Long, AsOfEvent> out = resolver.resolveAll(List.of(a, b), DAY, TYPE, loc -> EVENT);

            assertThat(out.get(10L).pipelineRunId()).isEqualTo(77L);
            assertThat(out.get(11L).pipelineRunId()).isEqualTo(77L);
            verify(pipelineRunRepository, times(1)).findFirstByTriggerTimeOrderByIdAsc(CYCLE);
        }

        @Test
        @DisplayName("resolve() with no event instant answers NONE without reading anything")
        void resolveWithNullInstant() {
            assertThat(resolver.resolve(sky(), DAY, TYPE, null)).isEqualTo(AsOfEvent.NONE);
            verify(forecastEvaluationRepository, never()).findScoredByEventInstant(any(), any(), any(), any());
        }
    }
}

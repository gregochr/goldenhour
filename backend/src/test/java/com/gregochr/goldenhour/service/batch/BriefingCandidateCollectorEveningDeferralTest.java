package com.gregochr.goldenhour.service.batch;

import com.gregochr.goldenhour.config.FreshnessProperties;
import com.gregochr.goldenhour.entity.DispositionCategory;
import com.gregochr.goldenhour.entity.LocationEntity;
import com.gregochr.goldenhour.entity.TargetType;
import com.gregochr.goldenhour.model.BriefingDay;
import com.gregochr.goldenhour.model.BriefingEvaluationResult;
import com.gregochr.goldenhour.model.BriefingEventSummary;
import com.gregochr.goldenhour.model.BriefingRegion;
import com.gregochr.goldenhour.model.BriefingSlot;
import com.gregochr.goldenhour.model.DailyBriefingResponse;
import com.gregochr.goldenhour.model.Verdict;
import com.gregochr.goldenhour.service.BriefingEvaluationService;
import com.gregochr.goldenhour.service.FreshnessResolver;
import com.gregochr.goldenhour.service.LocationService;
import com.gregochr.goldenhour.service.StabilitySnapshotProvider;
import com.gregochr.goldenhour.service.TravelDayService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;

/**
 * Proves the intraday cycle leaves an already-rated tomorrow's sunrise to tonight's evening refresh
 * ({@link CandidateCollectionStrategy#defersRatedSlot}) while a slot with no rating, any other slot,
 * and every slot when the evening is not due are collected exactly as before.
 */
@ExtendWith(MockitoExtension.class)
class BriefingCandidateCollectorEveningDeferralTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 1, 15);
    private static final LocalDate TOMORROW = TODAY.plusDays(1);
    private static final Clock CLOCK = Clock.fixed(
            TODAY.atTime(12, 0).toInstant(ZoneOffset.UTC), ZoneOffset.UTC);

    @Mock private TravelDayService travelDayService;
    @Mock private LocationService locationService;
    @Mock private BriefingEvaluationService briefingEvaluationService;
    @Mock private StabilitySnapshotProvider stabilitySnapshotProvider;

    private BriefingCandidateCollector collector;

    @BeforeEach
    void setUp() {
        FreshnessProperties props = new FreshnessProperties();
        props.setSettledHours(36);
        props.setTransitionalHours(12);
        props.setUnsettledHours(4);
        props.setSafetyFloorHours(2);
        props.getHorizon().setT0Hours(2);
        props.getHorizon().setT1Hours(3);
        collector = new BriefingCandidateCollector(travelDayService, locationService,
                briefingEvaluationService, new FreshnessResolver(props),
                stabilitySnapshotProvider, CLOCK);
        LocationEntity bamburgh = new LocationEntity();
        bamburgh.setName("Bamburgh");
        bamburgh.setId(42L);
        lenient().when(locationService.findAllEnabled()).thenReturn(List.of(bamburgh));
        lenient().when(briefingEvaluationService.hasFreshEvaluation(anyString(), any()))
                .thenReturn(false);
        lenient().when(briefingEvaluationService.getCachedScores(anyString(), any(), any()))
                .thenReturn(Map.of());
    }

    private DailyBriefingResponse briefing(LocalDate date, TargetType type) {
        BriefingSlot slot = new BriefingSlot(
                "Bamburgh", date.atTime(8, 0), Verdict.GO, null, null, List.of());
        BriefingRegion region = new BriefingRegion(
                "North East", Verdict.GO, null, List.of(), List.of(slot),
                null, null, null, null, null, null);
        BriefingEventSummary summary = new BriefingEventSummary(type, List.of(region), List.of());
        return new DailyBriefingResponse(LocalDateTime.now(), null,
                List.of(new BriefingDay(date, List.of(summary))), List.of(),
                null, null, false, null, List.of(), List.of());
    }

    private void stored(TargetType type, BriefingEvaluationResult result) {
        lenient().when(briefingEvaluationService.getCachedScores("North East", TOMORROW, type))
                .thenReturn(Map.of("Bamburgh", result));
    }

    private static BriefingEvaluationResult rated() {
        return new BriefingEvaluationResult("Bamburgh", 4, 70, 60, "Good", null, null, null);
    }

    @Test
    @DisplayName("evening due and a stored rating: T+1 sunrise is skipped as NO_REFRESH_NEEDED with the reason")
    void ratedSunrise_isDeferred() {
        stored(TargetType.SUNRISE, rated());

        BriefingCandidateCollector.Result result = collector.collectForecastCandidates(
                briefing(TOMORROW, TargetType.SUNRISE),
                new IntradayCandidateCollectionStrategy(CLOCK, true));

        assertThat(result.candidates()).isEmpty();
        assertThat(result.dispositions()).singleElement().satisfies(d -> {
            assertThat(d.category()).isEqualTo(DispositionCategory.SKIPPED_NO_REFRESH_NEEDED);
            assertThat(d.detail()).isEqualTo("tonight's evening refresh re-rates tomorrow's sunrise");
            assertThat(d.locationId()).isEqualTo(42L);
            assertThat(d.daysAhead()).isEqualTo(1);
        });
    }

    @Test
    @DisplayName("evening due but no stored rating: T+1 sunrise is collected, so coverage stays rated")
    void unratedSunrise_isCollected() {
        BriefingCandidateCollector.Result result = collector.collectForecastCandidates(
                briefing(TOMORROW, TargetType.SUNRISE),
                new IntradayCandidateCollectionStrategy(CLOCK, true));

        assertThat(result.candidates()).singleElement().satisfies(c ->
                assertThat(c.location().getName()).isEqualTo("Bamburgh"));
        assertThat(result.dispositions()).isEmpty();
    }

    @Test
    @DisplayName("a stored stand-down (no rating) or a retracted result is not a rating: collected")
    void standDownAndRetractedAreNotRatings() {
        BriefingEvaluationResult standDown = new BriefingEvaluationResult(
                "Bamburgh", null, null, null, null, null, null, null);
        stored(TargetType.SUNRISE, standDown);
        assertThat(collector.collectForecastCandidates(briefing(TOMORROW, TargetType.SUNRISE),
                new IntradayCandidateCollectionStrategy(CLOCK, true)).candidates()).hasSize(1);

        stored(TargetType.SUNRISE, BriefingEvaluationResult.retracted("Bamburgh"));
        assertThat(collector.collectForecastCandidates(briefing(TOMORROW, TargetType.SUNRISE),
                new IntradayCandidateCollectionStrategy(CLOCK, true)).candidates()).hasSize(1);
    }

    @Test
    @DisplayName("evening not due: an already-rated T+1 sunrise is collected as before")
    void eveningNotDue_ratedSunriseCollected() {
        BriefingCandidateCollector.Result result = collector.collectForecastCandidates(
                briefing(TOMORROW, TargetType.SUNRISE),
                new IntradayCandidateCollectionStrategy(CLOCK, false));

        assertThat(result.candidates()).hasSize(1);
        assertThat(result.dispositions()).isEmpty();
    }

    @Test
    @DisplayName("evening due: an already-rated T+1 sunset is still collected")
    void ratedSunset_stillCollected() {
        stored(TargetType.SUNSET, rated());

        BriefingCandidateCollector.Result result = collector.collectForecastCandidates(
                briefing(TOMORROW, TargetType.SUNSET),
                new IntradayCandidateCollectionStrategy(CLOCK, true));

        assertThat(result.candidates()).hasSize(1);
        assertThat(result.dispositions()).isEmpty();
    }

    @Test
    @DisplayName("evening due: today's sunset is still collected")
    void todaySunset_stillCollected() {
        BriefingCandidateCollector.Result result = collector.collectForecastCandidates(
                briefing(TODAY, TargetType.SUNSET),
                new IntradayCandidateCollectionStrategy(CLOCK, true));

        assertThat(result.candidates()).hasSize(1);
        assertThat(result.dispositions()).isEmpty();
    }
}

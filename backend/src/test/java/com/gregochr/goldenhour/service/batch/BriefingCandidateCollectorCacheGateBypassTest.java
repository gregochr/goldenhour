package com.gregochr.goldenhour.service.batch;

import com.gregochr.goldenhour.config.FreshnessProperties;
import com.gregochr.goldenhour.entity.LocationEntity;
import com.gregochr.goldenhour.entity.TargetType;
import com.gregochr.goldenhour.model.BriefingDay;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * Proves {@link CandidateCollectionStrategy#bypassesCacheGate()} is read by
 * {@link BriefingCandidateCollector}: a strategy that answers {@code true} evaluates a region whose
 * cached evaluation is still fresh, and the default answer leaves the CACHED skip as it was.
 */
@ExtendWith(MockitoExtension.class)
class BriefingCandidateCollectorCacheGateBypassTest {

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
        props.getHorizon().setT1Hours(8);
        collector = new BriefingCandidateCollector(travelDayService, locationService,
                briefingEvaluationService, new FreshnessResolver(props),
                stabilitySnapshotProvider, CLOCK);
    }

    private DailyBriefingResponse tomorrowsSunrise() {
        BriefingSlot slot = new BriefingSlot(
                "Bamburgh", TOMORROW.atTime(8, 0), Verdict.GO, null, null, List.of());
        BriefingRegion region = new BriefingRegion(
                "North East", Verdict.GO, null, List.of(), List.of(slot),
                null, null, null, null, null, null);
        BriefingEventSummary summary = new BriefingEventSummary(
                TargetType.SUNRISE, List.of(region), List.of());
        return new DailyBriefingResponse(LocalDateTime.now(), null,
                List.of(new BriefingDay(TOMORROW, List.of(summary))), List.of(),
                null, null, false, null, List.of(), List.of());
    }

    private static CandidateCollectionStrategy acceptAll(boolean bypass) {
        return new CandidateCollectionStrategy() {
            @Override
            public boolean includes(LocalDate date, TargetType targetType) {
                return true;
            }

            @Override
            public boolean bypassesCacheGate() {
                return bypass;
            }
        };
    }

    @Test
    @DisplayName("the default answer is false, so a strategy that says nothing keeps the CACHED skip")
    void defaultKeepsTheCachedSkip() {
        when(briefingEvaluationService.hasFreshEvaluation(anyString(), any())).thenReturn(true);

        BriefingCandidateCollector.Result result =
                collector.collectForecastCandidates(tomorrowsSunrise(), acceptAll(false));

        assertThat(result.candidates()).isEmpty();
        assertThat(result.dispositions()).singleElement().satisfies(d ->
                assertThat(d.category().name()).isEqualTo("SKIPPED_CACHED"));
    }

    @Test
    @DisplayName("the evening's strategy evaluates tomorrow's sunrise for a region with a fresh "
            + "cached entry, where the intraday strategy skips the same slot as cached")
    void eveningEvaluatesWhatIntradaySkips() {
        when(briefingEvaluationService.hasFreshEvaluation(anyString(), any())).thenReturn(true);
        LocationEntity bamburgh = new LocationEntity();
        bamburgh.setName("Bamburgh");
        bamburgh.setId(42L);
        lenient().when(locationService.findAllEnabled()).thenReturn(List.of(bamburgh));

        BriefingCandidateCollector.Result intraday = collector.collectForecastCandidates(
                tomorrowsSunrise(), new IntradayCandidateCollectionStrategy(CLOCK));
        BriefingCandidateCollector.Result evening = collector.collectForecastCandidates(
                tomorrowsSunrise(), new EveningCandidateCollectionStrategy(CLOCK));

        assertThat(intraday.candidates()).isEmpty();
        assertThat(intraday.dispositions()).singleElement().satisfies(d ->
                assertThat(d.category().name()).isEqualTo("SKIPPED_CACHED"));
        assertThat(evening.candidates()).singleElement().satisfies(c ->
                assertThat(c.location().getName()).isEqualTo("Bamburgh"));
        assertThat(evening.dispositions()).isEmpty();
    }

    @Test
    @DisplayName("a strategy that bypasses the cache gate evaluates a region with a fresh cached entry")
    void bypassSkipsNoCachedRegion() {
        // Lenient: the bypass must never consult the cache, so this stub goes unused. Were the gate
        // read, the region would be skipped and the assertions below would fail.
        lenient().when(briefingEvaluationService.hasFreshEvaluation(anyString(), any()))
                .thenReturn(true);
        LocationEntity bamburgh = new LocationEntity();
        bamburgh.setName("Bamburgh");
        bamburgh.setId(42L);
        when(locationService.findAllEnabled()).thenReturn(List.of(bamburgh));

        BriefingCandidateCollector.Result result =
                collector.collectForecastCandidates(tomorrowsSunrise(), acceptAll(true));

        assertThat(result.candidates()).singleElement().satisfies(c ->
                assertThat(c.location().getName()).isEqualTo("Bamburgh"));
        assertThat(result.dispositions()).isEmpty();
    }
}

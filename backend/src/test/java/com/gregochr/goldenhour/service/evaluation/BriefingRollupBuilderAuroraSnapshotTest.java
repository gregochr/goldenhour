package com.gregochr.goldenhour.service.evaluation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gregochr.goldenhour.entity.AlertLevel;
import com.gregochr.goldenhour.entity.LocationEntity;
import com.gregochr.goldenhour.entity.RegionEntity;
import com.gregochr.goldenhour.model.AuroraForecastScore;
import com.gregochr.goldenhour.model.RollupResult;
import com.gregochr.goldenhour.service.BriefingEvaluationService;
import com.gregochr.goldenhour.service.StabilitySnapshotProvider;
import com.gregochr.goldenhour.service.TravelDayService;
import com.gregochr.goldenhour.service.aurora.AuroraStateCache;
import com.gregochr.goldenhour.service.aurora.TriggerType;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Pins that the aurora block the best-bet rollup writes describes one state of the aurora
 * machine, even when a CLEAR lands between the eligibility check and {@code appendAuroraEvent}'s
 * use of it.
 *
 * <p>Written with a REAL {@link AuroraStateCache}, not the mock the rest of
 * {@link BriefingRollupBuilderTest} uses: the transition under test is the machine's own — a CLEAR
 * nulls the level and resets the location counts together — and a mock would only restate whichever
 * field a test remembered to flip. The CLEAR is made from inside the
 * {@link TravelDayService#isTravelDay} stub, the one collaborator call {@code buildRollupJson} makes
 * between reading the alert level and (previously) re-reading it in {@code appendAuroraEvent} —
 * mirroring {@code AuroraControllerStatusSnapshotTest}'s "transition inside the stub" technique for
 * the same class of bug in {@code AuroraController.getStatus}. The other two live-code windows
 * ({@code isActive()} vs the first {@code getCurrentLevel()}, and that read vs the
 * {@code isAlertWorthy()} re-read) are back-to-back field reads with no call in between and are not
 * exercised here — nothing can run inside them to land a transition mid-way.
 */
@ExtendWith(MockitoExtension.class)
class BriefingRollupBuilderAuroraSnapshotTest {

    /** A fixed clock, well away from any real "today" so the fixture doesn't depend on when it runs. */
    private static final LocalDate DATE = LocalDate.of(2027, 4, 20);
    private static final LocalDateTime NOW = LocalDateTime.of(DATE, LocalTime.of(3, 0));

    @Mock private TravelDayService travelDayService;
    @Mock private BriefingEvaluationService briefingEvaluationService;
    @Mock private StabilitySnapshotProvider stabilitySnapshotProvider;

    private final ObjectMapper mapper = new ObjectMapper();
    private AuroraStateCache auroraStateCache;
    private BriefingRollupBuilder builder;

    @BeforeEach
    void setUp() {
        auroraStateCache = new AuroraStateCache();
        builder = new BriefingRollupBuilder(mapper,
                Clock.fixed(NOW.toInstant(ZoneOffset.UTC), ZoneOffset.UTC),
                travelDayService, briefingEvaluationService, stabilitySnapshotProvider,
                auroraStateCache, new AuroraRegionSelector(auroraStateCache));
    }

    /**
     * An alert as the polling job leaves one after a NOTIFY: active, triggered, counted, and
     * scored at a location whose region {@link AuroraRegionSelector#bestAuroraRegion} can derive —
     * without a scored location the region is null either way, and would not exercise the CLEAR's
     * effect on it at all.
     */
    private void startAlert() {
        auroraStateCache.evaluate(AlertLevel.MODERATE);
        auroraStateCache.updateTrigger(TriggerType.REALTIME, 5.3);
        auroraStateCache.updateLocationCounts(12, 7);
        auroraStateCache.updateScores(List.of(score(1L, "Kielder", "Northumberland")));
    }

    private static AuroraForecastScore score(long id, String locationName, String regionName) {
        LocationEntity location = LocationEntity.builder()
                .id(id).name(locationName).lat(55.2).lon(-2.5).bortleClass(2)
                .region(RegionEntity.builder().name(regionName).build())
                .build();
        return new AuroraForecastScore(location, 4, AlertLevel.MODERATE, 20, "★★★★ summary", "detail");
    }

    @Test
    @DisplayName("a CLEAR landing during the travel-day check is written as the running alert, not thrown")
    void clearDuringTravelDayCheck_writesTheRunningAlertRatherThanThrowing() throws Exception {
        startAlert();
        when(travelDayService.isTravelDay(DATE)).thenAnswer(invocation -> {
            auroraStateCache.evaluate(AlertLevel.QUIET); // the polling job's CLEAR, mid-DB-call
            return false;
        });

        RollupResult result = builder.buildRollupJson(List.of(), NOW);

        // Control: the CLEAR really landed inside the travel-day check, not before or after it —
        // including that it emptied the cached scores appendAuroraEvent's derived region reads.
        assertThat(auroraStateCache.isActive()).isFalse();
        assertThat(auroraStateCache.getCurrentLevel()).isNull();
        assertThat(auroraStateCache.getDarkSkyLocationCount()).isZero();
        assertThat(auroraStateCache.getClearLocationCount()).isNull();
        assertThat(auroraStateCache.getCachedScores()).isEmpty();

        // One state throughout: the alert that was active when buildRollupJson decided to include
        // it. Broken, appendAuroraEvent re-read the now-null level and threw a NullPointerException
        // instead of returning a rollup at all — and, even once that no longer throws, re-derived
        // the region from the now-empty cached scores instead of the ones behind this decision,
        // silently dropping a destination the advisor prompt had already earned.
        JsonNode root = mapper.readTree(result.json());
        JsonNode auroraEvent = root.get("events").get(0);
        assertThat(auroraEvent.get("alertLevel").asText()).isEqualTo("MODERATE");
        assertThat(auroraEvent.get("kp").asDouble()).isEqualTo(5.3);
        assertThat(auroraEvent.get("darkSkyLocationCount").asInt()).isEqualTo(12);
        assertThat(auroraEvent.get("clearLocationCount").asInt()).isEqualTo(7);
        assertThat(auroraEvent.get("region").asText()).isEqualTo("Northumberland");
        assertThat(result.validEvents()).contains(DATE + "_aurora");
        assertThat(result.validRegions()).contains("Northumberland");
    }

    @Test
    @DisplayName("no transition during the travel-day check still reports the alert normally")
    void noTransitionDuringTravelDayCheck_reportsTheAlertNormally() throws Exception {
        startAlert();
        when(travelDayService.isTravelDay(DATE)).thenReturn(false);

        RollupResult result = builder.buildRollupJson(List.of(), NOW);

        JsonNode root = mapper.readTree(result.json());
        JsonNode auroraEvent = root.get("events").get(0);
        assertThat(auroraEvent.get("alertLevel").asText()).isEqualTo("MODERATE");
        assertThat(auroraEvent.get("kp").asDouble()).isEqualTo(5.3);
        assertThat(auroraEvent.get("darkSkyLocationCount").asInt()).isEqualTo(12);
        assertThat(auroraEvent.get("clearLocationCount").asInt()).isEqualTo(7);
        assertThat(auroraEvent.get("region").asText()).isEqualTo("Northumberland");
    }
}

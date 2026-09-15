package com.gregochr.goldenhour.controller;

import com.gregochr.goldenhour.client.NoaaSwpcClient;
import com.gregochr.goldenhour.entity.AlertLevel;
import com.gregochr.goldenhour.entity.LocationEntity;
import com.gregochr.goldenhour.model.AuroraForecastScore;
import com.gregochr.goldenhour.model.AuroraStatusResponse;
import com.gregochr.goldenhour.model.CurrentNight;
import com.gregochr.goldenhour.model.KpReading;
import com.gregochr.goldenhour.service.aurora.AuroraForecastRunService;
import com.gregochr.goldenhour.service.aurora.AuroraStateCache;
import com.gregochr.goldenhour.service.aurora.TriggerType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * Pins that one {@code GET /api/aurora/status} response describes one state of the aurora state
 * machine, even when the polling job moves the machine while the request waits on NOAA.
 *
 * <p>Written as a plain unit test with a REAL {@link AuroraStateCache}, rather than through
 * {@code MockMvc} and the shared context's mocked one: the transition under test is the machine's
 * own — a CLEAR resets the level, the flag, the scores, the counts and {@code activeSince} together
 * and leaves the trigger alone — and a mock would restate only whichever of those fields a test
 * remembered to flip. Each transition is made from inside the FIRST NOAA stub, {@code fetchKp}, so
 * any read after the calls begin sees it: the claim is "before the NOAA calls", and a read placed
 * between two of them would pass a test that only moved the machine during the last.
 */
@ExtendWith(MockitoExtension.class)
class AuroraControllerStatusSnapshotTest {

    /** When the stubbed live Kp was measured; nothing reads it but the response's {@code updatedAt}. */
    private static final ZonedDateTime READING_TIME =
            ZonedDateTime.of(2026, 9, 14, 13, 0, 0, 0, ZoneOffset.UTC);

    @Mock
    private NoaaSwpcClient noaaClient;

    @Mock
    private AuroraForecastRunService forecastRunService;

    private AuroraStateCache stateCache;
    private AuroraController controller;

    @BeforeEach
    void setUp() {
        stateCache = new AuroraStateCache();
        controller = new AuroraController(stateCache, noaaClient, forecastRunService);
    }

    @Test
    @DisplayName("an alert that clears while the request waits on NOAA is answered as the alert throughout")
    void clearDuringNoaaCalls_answersAsTheRunningAlert() {
        startAlert();
        Instant detectedAt = stateCache.getActiveSince();
        when(noaaClient.fetchKp()).thenAnswer(invocation -> {
            stateCache.evaluate(AlertLevel.QUIET); // the polling job's CLEAR
            return List.of();
        });

        AuroraStatusResponse status = controller.getStatus().getBody();

        // Control: the CLEAR really landed during the request — without it this proves nothing.
        assertThat(stateCache.isActive()).isFalse();
        // One state throughout: the alert the request started under. Broken, the level said MODERATE
        // while the flag, the counts and the detection time had already cleared.
        assertThat(status.level()).isEqualTo(AlertLevel.MODERATE);
        assertThat(status.active()).isTrue();
        assertThat(status.eligibleLocations()).isEqualTo(2);
        assertThat(status.darkSkyLocationCount()).isEqualTo(12);
        assertThat(status.clearLocationCount()).isEqualTo(7);
        assertThat(status.detectedAt()).isEqualTo(detectedAt);
    }

    @Test
    @DisplayName("an alert that begins while the request waits on NOAA is answered as the quiet state throughout")
    void notifyDuringNoaaCalls_answersAsTheQuietState() {
        // The machine has never alerted, as after a restart. Once one has, CLEAR leaves the last
        // trigger in place, so in production the fields before a NOTIFY usually hold the previous
        // alert's trigger rather than null — null is used here because it makes any leak unmissable.
        when(noaaClient.fetchKp()).thenAnswer(invocation -> {
            // The polling job's NOTIFY, and the trigger `AuroraOrchestrator.scoreAndCache` records
            // after it — straight after on the real-time path (`run`), a NOAA fetch later on the
            // forecast lookahead's.
            stateCache.evaluate(AlertLevel.MODERATE);
            stateCache.updateTrigger(TriggerType.REALTIME, 5.3);
            return List.of();
        });

        AuroraStatusResponse status = controller.getStatus().getBody();

        // Control: the NOTIFY really landed during the request.
        assertThat(stateCache.isActive()).isTrue();
        // One state throughout: the quiet one. Broken, QUIET came out beside an active flag, a
        // detection time, the new trigger and a G1 storm scale derived from its Kp.
        assertThat(status.level()).isEqualTo(AlertLevel.QUIET);
        assertThat(status.active()).isFalse();
        assertThat(status.detectedAt()).isNull();
        assertThat(status.triggerType()).isNull();
        assertThat(status.forecastKp()).isNull();
        assertThat(status.gScale()).isNull();
    }

    @Test
    @DisplayName("a simulation started while the request waits on NOAA is answered as the quiet state throughout")
    void simulationStartedDuringNoaaCalls_answersAsTheQuietState() {
        when(noaaClient.fetchKp()).thenAnswer(invocation -> {
            // An admin's POST /api/aurora/admin/simulate, which moves the machine without the FSM,
            // landing while this request fetches a live Kp high enough to carry a storm scale beside
            // a quiet machine — the real-time path that would act on it runs only at night.
            stateCache.activateSimulation(AlertLevel.STRONG,
                    new AuroraStateCache.SimulatedNoaaData(7.3, 60.0, -9.5, "G3"));
            return List.of(new KpReading(READING_TIME, 5.7));
        });

        AuroraStatusResponse status = controller.getStatus().getBody();

        // Control: the simulation really started during the request.
        assertThat(stateCache.isSimulated()).isTrue();
        // One state throughout: the quiet, real one the request started under, with the live reading
        // it went on to fetch and the storm scale a real response derives from it. Broken, that
        // reading came out marked simulated, active, carrying the simulation's forecast trigger, and
        // with no storm scale, because the simulated flag read late skipped its derivation.
        assertThat(status.simulated()).isFalse();
        assertThat(status.level()).isEqualTo(AlertLevel.QUIET);
        assertThat(status.active()).isFalse();
        assertThat(status.triggerType()).isNull();
        assertThat(status.kp()).isEqualTo(5.7);
        assertThat(status.gScale()).isEqualTo("G1");
    }

    @Test
    @DisplayName("dawn passing while the request waits on NOAA is answered as the night the request began in")
    void dawnDuringNoaaCalls_answersWithTheNightTheRequestBeganIn() {
        // The night is the clock's, not the machine's, but the client orders answers by when their
        // requests were made all the same, so it must be as old as its request. Dawn passes inside
        // the first NOAA stub: from then on the service names tonight's night, ending tomorrow.
        CurrentNight lastNight = new CurrentNight(LocalDate.of(2026, 9, 13), Instant.parse("2026-09-14T04:58:00Z"));
        CurrentNight tonight = new CurrentNight(LocalDate.of(2026, 9, 14), Instant.parse("2026-09-15T05:00:00Z"));
        CurrentNight[] clock = {lastNight};
        when(forecastRunService.currentNight()).thenAnswer(invocation -> clock[0]);
        when(noaaClient.fetchKp()).thenAnswer(invocation -> {
            clock[0] = tonight;
            return List.of();
        });

        AuroraStatusResponse status = controller.getStatus().getBody();

        // Control: dawn really passed during the request.
        assertThat(clock[0]).isEqualTo(tonight);
        // The night the request began in, date and end together. Broken — read after the NOAA
        // calls — a request made before dawn answered for tonight.
        assertThat(status.currentNightDate()).isEqualTo(LocalDate.of(2026, 9, 13));
        assertThat(status.currentNightEndsAt()).isEqualTo(Instant.parse("2026-09-14T04:58:00Z"));
    }

    /** An alert as the polling job leaves one after a NOTIFY: active, scored, triggered, counted. */
    private void startAlert() {
        stateCache.evaluate(AlertLevel.MODERATE);
        stateCache.updateScores(List.of(score(1L, "Kielder"), score(2L, "Cheviot")));
        stateCache.updateTrigger(TriggerType.REALTIME, 5.3);
        stateCache.updateLocationCounts(12, 7);
    }

    private static AuroraForecastScore score(long id, String name) {
        LocationEntity location = LocationEntity.builder()
                .id(id).name(name).lat(55.2).lon(-2.5).bortleClass(2).build();
        return new AuroraForecastScore(location, 4, AlertLevel.MODERATE, 20, "★★★★ summary", "detail");
    }
}

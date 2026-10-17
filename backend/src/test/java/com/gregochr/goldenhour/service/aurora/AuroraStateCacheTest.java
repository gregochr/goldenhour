package com.gregochr.goldenhour.service.aurora;

import com.gregochr.goldenhour.entity.AlertLevel;
import com.gregochr.goldenhour.model.AuroraForecastScore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Named;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Named.named;

/**
 * Unit tests for {@link AuroraStateCache} state machine lifecycle.
 *
 * <p>The machine runs on a clock the test moves by hand, from 22:00 on 14 January 2027, so every
 * {@code activeSince} is an exact instant.
 */
class AuroraStateCacheTest {

    private static final Instant START = Instant.parse("2027-01-14T22:00:00Z");

    /** Bound on every cross-thread wait, so a regression fails rather than hangs. */
    private static final long WAIT_SECONDS = 5;

    private final MovableClock clock = new MovableClock();
    private AuroraStateCache cache;

    @BeforeEach
    void setUp() {
        cache = new AuroraStateCache(clock);
    }

    // -------------------------------------------------------------------------
    // Initial state
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("Initial state is IDLE — not active, no level, no scores")
    void initialState() {
        assertThat(cache.isActive()).isFalse();
        assertThat(cache.getCurrentLevel()).isNull();
        assertThat(cache.getCachedScores()).isEmpty();
    }

    // -------------------------------------------------------------------------
    // IDLE → alertable transitions
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("IDLE + AMBER → NOTIFY, transitions to ACTIVE")
    void idle_amber_emitsNotify() {
        var eval = cache.evaluate(AlertLevel.MODERATE);

        assertThat(eval.action()).isEqualTo(AuroraStateCache.Action.NOTIFY);
        assertThat(eval.currentLevel()).isEqualTo(AlertLevel.MODERATE);
        assertThat(eval.previousLevel()).isNull();
        assertThat(cache.isActive()).isTrue();
        assertThat(cache.getCurrentLevel()).isEqualTo(AlertLevel.MODERATE);
    }

    @Test
    @DisplayName("IDLE + RED → NOTIFY, transitions to ACTIVE")
    void idle_red_emitsNotify() {
        var eval = cache.evaluate(AlertLevel.STRONG);

        assertThat(eval.action()).isEqualTo(AuroraStateCache.Action.NOTIFY);
        assertThat(eval.currentLevel()).isEqualTo(AlertLevel.STRONG);
        assertThat(cache.isActive()).isTrue();
    }

    // -------------------------------------------------------------------------
    // IDLE non-alertable stays IDLE
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("IDLE + GREEN → NONE, stays IDLE")
    void idle_green_emitsNone() {
        var eval = cache.evaluate(AlertLevel.QUIET);

        assertThat(eval.action()).isEqualTo(AuroraStateCache.Action.NONE);
        assertThat(cache.isActive()).isFalse();
    }

    @Test
    @DisplayName("IDLE + YELLOW → NONE, stays IDLE")
    void idle_yellow_emitsNone() {
        var eval = cache.evaluate(AlertLevel.MINOR);

        assertThat(eval.action()).isEqualTo(AuroraStateCache.Action.NONE);
        assertThat(cache.isActive()).isFalse();
    }

    // -------------------------------------------------------------------------
    // ACTIVE → same level
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("ACTIVE (AMBER) + AMBER → SUPPRESS, stays ACTIVE at AMBER")
    void active_amber_sameLevel_emitsSuppressed() {
        cache.evaluate(AlertLevel.MODERATE);

        var eval = cache.evaluate(AlertLevel.MODERATE);

        assertThat(eval.action()).isEqualTo(AuroraStateCache.Action.SUPPRESS);
        assertThat(eval.currentLevel()).isEqualTo(AlertLevel.MODERATE);
        assertThat(cache.isActive()).isTrue();
    }

    // -------------------------------------------------------------------------
    // ACTIVE → escalation
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("ACTIVE (AMBER) + RED → NOTIFY (escalation), currentLevel updates to RED")
    void active_amber_red_emitsNotify_escalation() {
        cache.evaluate(AlertLevel.MODERATE);

        var eval = cache.evaluate(AlertLevel.STRONG);

        assertThat(eval.action()).isEqualTo(AuroraStateCache.Action.NOTIFY);
        assertThat(eval.currentLevel()).isEqualTo(AlertLevel.STRONG);
        assertThat(eval.previousLevel()).isEqualTo(AlertLevel.MODERATE);
        assertThat(cache.getCurrentLevel()).isEqualTo(AlertLevel.STRONG);
        assertThat(cache.isActive()).isTrue();
    }

    // -------------------------------------------------------------------------
    // ACTIVE → de-escalation within alertable range
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("ACTIVE (RED) + AMBER → SUPPRESS (de-escalation within alertable)")
    void active_red_amber_emitsSuppressed() {
        cache.evaluate(AlertLevel.STRONG);

        var eval = cache.evaluate(AlertLevel.MODERATE);

        assertThat(eval.action()).isEqualTo(AuroraStateCache.Action.SUPPRESS);
        assertThat(eval.currentLevel()).isEqualTo(AlertLevel.STRONG);
        assertThat(cache.isActive()).isTrue();
    }

    // -------------------------------------------------------------------------
    // ACTIVE → CLEAR
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("ACTIVE (AMBER) + GREEN → CLEAR, transitions to IDLE")
    void active_amber_green_emitsClear() {
        cache.evaluate(AlertLevel.MODERATE);

        var eval = cache.evaluate(AlertLevel.QUIET);

        assertThat(eval.action()).isEqualTo(AuroraStateCache.Action.CLEAR);
        assertThat(eval.previousLevel()).isEqualTo(AlertLevel.MODERATE);
        assertThat(cache.isActive()).isFalse();
        assertThat(cache.getCurrentLevel()).isNull();
        assertThat(cache.getCachedScores()).isEmpty();
    }

    @Test
    @DisplayName("ACTIVE (RED) + YELLOW → CLEAR, transitions to IDLE")
    void active_red_yellow_emitsClear() {
        cache.evaluate(AlertLevel.STRONG);

        var eval = cache.evaluate(AlertLevel.MINOR);

        assertThat(eval.action()).isEqualTo(AuroraStateCache.Action.CLEAR);
        assertThat(cache.isActive()).isFalse();
    }

    // -------------------------------------------------------------------------
    // Full lifecycle
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("Full lifecycle: GREEN → AMBER → AMBER → RED → AMBER → YELLOW → GREEN → AMBER")
    void fullLifecycle() {
        // Quiescent
        assertThat(cache.evaluate(AlertLevel.QUIET).action())
                .isEqualTo(AuroraStateCache.Action.NONE);

        // First alert
        assertThat(cache.evaluate(AlertLevel.MODERATE).action())
                .isEqualTo(AuroraStateCache.Action.NOTIFY);
        assertThat(cache.isActive()).isTrue();

        // Duplicate — suppress
        assertThat(cache.evaluate(AlertLevel.MODERATE).action())
                .isEqualTo(AuroraStateCache.Action.SUPPRESS);

        // Escalation — notify again
        assertThat(cache.evaluate(AlertLevel.STRONG).action())
                .isEqualTo(AuroraStateCache.Action.NOTIFY);
        assertThat(cache.getCurrentLevel()).isEqualTo(AlertLevel.STRONG);

        // De-escalation within alertable — suppress
        assertThat(cache.evaluate(AlertLevel.MODERATE).action())
                .isEqualTo(AuroraStateCache.Action.SUPPRESS);
        assertThat(cache.isActive()).isTrue();

        // Event ends
        assertThat(cache.evaluate(AlertLevel.MINOR).action())
                .isEqualTo(AuroraStateCache.Action.CLEAR);
        assertThat(cache.isActive()).isFalse();

        // Quiet again
        assertThat(cache.evaluate(AlertLevel.QUIET).action())
                .isEqualTo(AuroraStateCache.Action.NONE);

        // New event
        assertThat(cache.evaluate(AlertLevel.MODERATE).action())
                .isEqualTo(AuroraStateCache.Action.NOTIFY);
        assertThat(cache.isActive()).isTrue();
    }

    @Test
    @DisplayName("Brief spike: AMBER → GREEN → AMBER produces two separate NOTIFYs")
    void briefSpike_twoSeparateNotifies() {
        var first = cache.evaluate(AlertLevel.MODERATE);
        assertThat(first.action()).isEqualTo(AuroraStateCache.Action.NOTIFY);

        cache.evaluate(AlertLevel.QUIET);   // CLEAR
        assertThat(cache.isActive()).isFalse();

        var second = cache.evaluate(AlertLevel.MODERATE);
        assertThat(second.action()).isEqualTo(AuroraStateCache.Action.NOTIFY);
        assertThat(cache.isActive()).isTrue();
    }

    // -------------------------------------------------------------------------
    // Score caching
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("updateScores stores scores; CLEAR wipes them")
    void scoresCachedAndCleared() {
        cache.evaluate(AlertLevel.MODERATE);
        var scores = List.of(stubScore(), stubScore());
        cache.updateScores(scores);

        assertThat(cache.getCachedScores()).hasSize(2);

        cache.evaluate(AlertLevel.QUIET);
        assertThat(cache.getCachedScores()).isEmpty();
    }

    // -------------------------------------------------------------------------
    // Reset
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("reset() returns state machine to IDLE regardless of current state")
    void reset_fromActive_returnsToIdle() {
        cache.evaluate(AlertLevel.STRONG);
        cache.updateScores(List.of(stubScore()));

        cache.reset();

        assertThat(cache.isActive()).isFalse();
        assertThat(cache.getCurrentLevel()).isNull();
        assertThat(cache.getCachedScores()).isEmpty();
    }

    @Test
    @DisplayName("reset() is idempotent when already IDLE")
    void reset_fromIdle_isIdempotent() {
        cache.reset();

        assertThat(cache.isActive()).isFalse();
        assertThat(cache.getCurrentLevel()).isNull();
    }

    // -------------------------------------------------------------------------
    // Trigger metadata
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("updateTrigger stores trigger type and kp; getters return them")
    void updateTrigger_storesTypeAndKp() {
        cache.updateTrigger(TriggerType.FORECAST_LOOKAHEAD, 6.0);

        assertThat(cache.getLastTriggerType()).isEqualTo(TriggerType.FORECAST_LOOKAHEAD);
        assertThat(cache.getLastTriggerKp()).isEqualTo(6.0);
    }

    @Test
    @DisplayName("reset() clears trigger metadata")
    void reset_clearsTriggerMetadata() {
        cache.updateTrigger(TriggerType.REALTIME, 5.0);
        cache.reset();

        assertThat(cache.getLastTriggerType()).isNull();
        assertThat(cache.getLastTriggerKp()).isNull();
    }

    // -------------------------------------------------------------------------
    // Detection timestamp (activeSince)
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("activeSince is null initially")
    void activeSince_initiallyNull() {
        assertThat(cache.getActiveSince()).isNull();
    }

    @Test
    @DisplayName("IDLE + MODERATE sets activeSince to the clock's instant")
    void activeSince_setOnFirstNotify() {
        cache.evaluate(AlertLevel.MODERATE);

        assertThat(cache.getActiveSince()).isEqualTo(START);
    }

    @Test
    @DisplayName("Escalation (MODERATE → STRONG) moves activeSince to the escalation's instant")
    void activeSince_updatedOnEscalation() {
        cache.evaluate(AlertLevel.MODERATE);
        clock.advance(Duration.ofMinutes(10));

        cache.evaluate(AlertLevel.STRONG);

        assertThat(cache.getActiveSince()).isEqualTo(START.plus(Duration.ofMinutes(10)));
    }

    @Test
    @DisplayName("SUPPRESS (same level) does not change activeSince")
    void activeSince_unchangedOnSuppress() {
        cache.evaluate(AlertLevel.MODERATE);
        clock.advance(Duration.ofMinutes(10));

        cache.evaluate(AlertLevel.MODERATE);

        assertThat(cache.getActiveSince()).isEqualTo(START);
    }

    @Test
    @DisplayName("a simulation sets activeSince to the clock's instant")
    void activeSince_setBySimulation() {
        cache.activateSimulation(AlertLevel.STRONG,
                new AuroraStateCache.SimulatedNoaaData(7.0, 40.0, -8.0, "G3"));

        assertThat(cache.getActiveSince()).isEqualTo(START);
    }

    @Test
    @DisplayName("CLEAR resets activeSince to null")
    void activeSince_clearedOnClear() {
        cache.evaluate(AlertLevel.MODERATE);
        assertThat(cache.getActiveSince()).isNotNull();

        cache.evaluate(AlertLevel.QUIET);
        assertThat(cache.getActiveSince()).isNull();
    }

    @Test
    @DisplayName("reset() clears activeSince")
    void reset_clearsActiveSince() {
        cache.evaluate(AlertLevel.STRONG);
        assertThat(cache.getActiveSince()).isNotNull();

        cache.reset();
        assertThat(cache.getActiveSince()).isNull();
    }

    @Test
    @DisplayName("New event after CLEAR sets fresh activeSince")
    void activeSince_freshAfterClearAndReactivation() {
        cache.evaluate(AlertLevel.MODERATE);
        clock.advance(Duration.ofMinutes(10));
        cache.evaluate(AlertLevel.QUIET);   // CLEAR
        clock.advance(Duration.ofMinutes(10));

        cache.evaluate(AlertLevel.MODERATE); // New event

        assertThat(cache.getActiveSince()).isEqualTo(START.plus(Duration.ofMinutes(20)));
    }

    // -------------------------------------------------------------------------
    // Location counts
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("updateLocationCounts stores dark sky and clear counts")
    void updateLocationCounts_storesCounts() {
        cache.updateLocationCounts(45, 12);

        assertThat(cache.getDarkSkyLocationCount()).isEqualTo(45);
        assertThat(cache.getClearLocationCount()).isEqualTo(12);
    }

    @Test
    @DisplayName("CLEAR resets location counts")
    void clear_resetsLocationCounts() {
        cache.evaluate(AlertLevel.MODERATE);
        cache.updateLocationCounts(45, 12);

        cache.evaluate(AlertLevel.QUIET);

        assertThat(cache.getDarkSkyLocationCount()).isZero();
        assertThat(cache.getClearLocationCount()).isNull();
    }

    @Test
    @DisplayName("reset() clears location counts")
    void reset_clearsLocationCounts() {
        cache.updateLocationCounts(30, 8);
        cache.reset();

        assertThat(cache.getDarkSkyLocationCount()).isZero();
        assertThat(cache.getClearLocationCount()).isNull();
    }

    @Test
    @DisplayName("Initial location counts are 0 and null")
    void initialLocationCounts() {
        assertThat(cache.getDarkSkyLocationCount()).isZero();
        assertThat(cache.getClearLocationCount()).isNull();
    }

    // -------------------------------------------------------------------------
    // Simulation — an admin's fake alert, which the first real reading ends
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("activateSimulation raises an alert carrying the fake NOAA data")
    void activateSimulation_raisesAnAlertCarryingTheFakeData() {
        cache.activateSimulation(AlertLevel.STRONG, simulated(7.0));

        assertThat(cache.getSimulatedData()).isEqualTo(simulated(7.0));
        assertThat(cache.isActive()).isTrue();
        assertThat(cache.getCurrentLevel()).isEqualTo(AlertLevel.STRONG);
        assertThat(cache.getLastTriggerType()).isEqualTo(TriggerType.FORECAST_LOOKAHEAD);
        assertThat(cache.getLastTriggerKp()).isEqualTo(7.0);
    }

    /**
     * A simulation replaces the whole state. The clear count's own javadoc says it is null "during
     * simulation"; kept, a simulated banner printed a real triage's "7 dark-sky locations clear", and
     * the map served the replaced alert's scored pins under the fake Kp.
     */
    @ParameterizedTest(name = "over {0}")
    @MethodSource("statesASimulationReplaces")
    @DisplayName("a simulation keeps nothing of the state it replaces")
    void activateSimulation_keepsNothingOfTheStateItReplaces(Consumer<AuroraStateCache> before) {
        before.accept(cache);

        cache.activateSimulation(AlertLevel.STRONG, simulated(7.0));

        assertThat(everythingButWhen(cache)).containsExactly(true, AlertLevel.STRONG, List.of(),
                TriggerType.FORECAST_LOOKAHEAD, 7.0, 0, null, simulated(7.0));
    }

    static Stream<Named<Consumer<AuroraStateCache>>> statesASimulationReplaces() {
        return Stream.of(
                named("a scored real alert", AuroraStateCacheTest::realAlert),
                named("another simulation, with a scoring landed under it", c -> {
                    c.activateSimulation(AlertLevel.MODERATE, simulated(5.0));
                    landAScoring(c);
                }));
    }

    @ParameterizedTest(name = "a real {0} reading")
    @EnumSource(value = AlertLevel.class, names = {"QUIET", "MINOR"})
    @DisplayName("a real reading below alert level clears a simulation, and everything it planted")
    void realClear_endsTheSimulation(AlertLevel reading) {
        cache.activateSimulation(AlertLevel.STRONG, simulated(7.0));

        var eval = cache.evaluate(reading);

        assertThat(eval.action()).isEqualTo(AuroraStateCache.Action.CLEAR);
        assertThat(eval.previousLevel()).isEqualTo(AlertLevel.STRONG);
        assertThat(cache.isActive()).isFalse();
        // Broken, the CLEAR ended the alert and left the simulation standing behind it: the banner
        // went away, the fake Kp/OVATION/Bz/G-scale and the trigger they planted did not.
        assertThat(cache.getSimulatedData()).isNull();
        assertThat(cache.getLastTriggerType()).isNull();
        assertThat(cache.getLastTriggerKp()).isNull();
    }

    /**
     * A simulated alert was never a real one, so a real reading answers as it would from IDLE: a new
     * alert, starting clean — which is what gets it scored — with no previous level. Every simulated
     * level against every alert level, the simulated level derived exactly as
     * {@code POST /api/aurora/admin/simulate} derives it. Broken, every case kept the simulation,
     * and the three at or below the simulated level were SUPPRESSed, so never scored.
     */
    @ParameterizedTest(name = "simulated Kp {0}, then a real {1} reading")
    @CsvSource({
            "2.0, MODERATE",  // a simulated QUIET
            "2.0, STRONG",
            "4.0, MODERATE",  // a simulated MINOR
            "4.0, STRONG",
            "5.0, MODERATE",  // the simulated level itself — was SUPPRESSed
            "5.0, STRONG",    // above it — was notified with the simulation still standing
            "7.0, MODERATE",  // below it — was SUPPRESSed
            "7.0, STRONG",    // the simulated level itself — was SUPPRESSed
    })
    @DisplayName("a real alert during a simulation is a new alert, starting clean, and ends it")
    void realAlert_duringSimulation_isANewAlertStartingClean(double simulatedKp, AlertLevel reading) {
        cache.activateSimulation(AlertLevel.fromKp(simulatedKp), simulated(simulatedKp));
        landAScoring(cache); // as a batch result, or a scoring still in flight, can land under it

        var eval = cache.evaluate(reading);

        assertThat(eval.action()).isEqualTo(AuroraStateCache.Action.NOTIFY);
        assertThat(eval.currentLevel()).isEqualTo(reading);
        assertThat(eval.previousLevel()).isNull();
        // Nothing of the simulation, nor of what landed under it: the orchestrator records the real
        // alert's own trigger, counts and scores next.
        assertThat(everythingButWhen(cache)).containsExactly(true, reading, List.of(), null, null, 0, null, null);
    }

    @Test
    @DisplayName("after a real CLEAR ends a simulation, the next real alert is not simulated")
    void simulationClearedByARealReading_theNextRealAlertIsNotSimulated() {
        cache.activateSimulation(AlertLevel.STRONG, simulated(7.0));
        cache.evaluate(AlertLevel.QUIET); // the first night-time real-time poll
        // In between: idle and not simulated, which is what the Job Runs button and the forecast
        // preview read. Asserted here because the takeover below would end a simulation that a
        // broken CLEAR had left standing, and hide it.
        assertThat(cache.getSimulatedData()).isNull();

        var eval = cache.evaluate(AlertLevel.MODERATE); // a real alert, later

        assertThat(eval.action()).isEqualTo(AuroraStateCache.Action.NOTIFY);
        assertThat(cache.getSimulatedData()).isNull();
    }

    @Test
    @DisplayName("CLEAR drops the trigger: an idle machine has none, however it got there")
    void clear_dropsTheTrigger() {
        cache.evaluate(AlertLevel.MODERATE);
        cache.updateTrigger(TriggerType.REALTIME, 5.3);

        cache.evaluate(AlertLevel.QUIET);

        assertThat(cache.getLastTriggerType()).isNull();
        assertThat(cache.getLastTriggerKp()).isNull();
    }

    /**
     * IDLE is one state, whichever transition reached it. Each route used to reset only the fields
     * its author remembered: CLEAR left the trigger and the simulation behind, while reset() took
     * both, so the two idles differed in exactly the fields a later alert could inherit.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("routesToIdle")
    @DisplayName("every route to IDLE lands on the state a fresh machine starts in")
    void everyRouteToIdle_landsOnTheFreshState(Consumer<AuroraStateCache> route) {
        route.accept(cache);

        assertThat(everything(cache)).isEqualTo(everything(new AuroraStateCache()));
    }

    static Stream<Named<Consumer<AuroraStateCache>>> routesToIdle() {
        return Stream.of(
                named("a real alert, cleared", c -> {
                    realAlert(c);
                    c.evaluate(AlertLevel.QUIET);
                }),
                named("a simulation, cleared", c -> {
                    c.activateSimulation(AlertLevel.STRONG, simulated(7.0));
                    landAScoring(c);
                    c.evaluate(AlertLevel.MINOR);
                }),
                named("a simulation, ended from the admin screen", c -> {
                    c.activateSimulation(AlertLevel.STRONG, simulated(7.0));
                    landAScoring(c);
                    c.endSimulation();
                }),
                named("a real alert, reset", c -> {
                    realAlert(c);
                    c.reset();
                }),
                named("a simulation, reset", c -> {
                    c.activateSimulation(AlertLevel.STRONG, simulated(7.0));
                    c.reset();
                }));
    }

    @Test
    @DisplayName("endSimulation ends a running simulation, and says so")
    void endSimulation_endsARunningSimulation() {
        cache.activateSimulation(AlertLevel.STRONG, simulated(7.0));

        assertThat(cache.endSimulation()).isTrue();
        assertThat(everything(cache)).isEqualTo(everything(new AuroraStateCache()));
    }

    /**
     * A Clear pressed on a screen still showing the simulation — its status can be five minutes old —
     * after a real alert has taken the simulation over must leave that real alert alone. A reset
     * there wiped it, scores and all, for every Pro user until the next poll paid to score it again.
     */
    @Test
    @DisplayName("endSimulation leaves a real alert that took the simulation over alone")
    void endSimulation_afterARealAlertTookOver_leavesTheRealAlert() {
        cache.activateSimulation(AlertLevel.STRONG, simulated(7.0));
        cache.evaluate(AlertLevel.MODERATE); // a real alert takes the simulation over
        landAScoring(cache);                 // and the orchestrator scores it
        List<Object> theRealAlert = everything(cache);

        assertThat(cache.endSimulation()).isFalse();
        assertThat(everything(cache)).isEqualTo(theRealAlert);
    }

    // -------------------------------------------------------------------------
    // wouldNotify and wouldClear — evaluate's NOTIFY and CLEAR, asked in advance
    // -------------------------------------------------------------------------

    @ParameterizedTest(name = "from {0}, {1}: wouldNotify {2}, wouldClear {3}")
    @CsvSource({
            "IDLE,      QUIET,    false, false",
            "IDLE,      MINOR,    false, false",
            "IDLE,      MODERATE, true,  false",
            "IDLE,      STRONG,   true,  false",
            "MODERATE,  QUIET,    false, true",
            "MODERATE,  MINOR,    false, true",
            "MODERATE,  MODERATE, false, false",
            "MODERATE,  STRONG,   true,  false",
            "STRONG,    QUIET,    false, true",
            "STRONG,    MINOR,    false, true",
            "STRONG,    MODERATE, false, false",
            "STRONG,    STRONG,   false, false",
            "SIM_QUIET,    QUIET,    false, true",
            "SIM_QUIET,    MODERATE, true,  false",
            "SIM_MINOR,    MINOR,    false, true",
            "SIM_MINOR,    MODERATE, true,  false",
            // A real reading during a simulation is a takeover, never measured against the simulated
            // level: these two used to read false, false (SUPPRESS) before the takeover rule — a
            // real MODERATE or STRONG reading at exactly the level the admin simulated was suppressed
            // and never scored, which is the defect this class exists to fix.
            "SIM_MODERATE, MODERATE, true,  false",
            "SIM_MODERATE, STRONG,   true,  false",
            "SIM_STRONG,   STRONG,   true,  false",
            "SIM_STRONG,   MINOR,    false, true",
    })
    @DisplayName("wouldNotify and wouldClear answer from IDLE, ACTIVE and simulated states")
    void wouldNotifyAndWouldClear_truthTable(String prior, AlertLevel incoming, boolean notify,
            boolean clear) {
        AuroraStateCache machine = machineIn(prior);

        assertThat(machine.wouldNotify(incoming)).as("wouldNotify").isEqualTo(notify);
        assertThat(machine.wouldClear(incoming)).as("wouldClear").isEqualTo(clear);
    }

    @Test
    @DisplayName("wouldNotify and wouldClear agree with evaluate from every state, and change nothing")
    void predictions_matchEvaluate_andLeaveStateAlone() {
        // A daylight poll asks wouldNotify before evaluating, to fetch a scoring's data only when a
        // scoring is coming; a night poll asks wouldClear, to hold an alert while the reading that
        // decides it is due. Were either to disagree with evaluate, a NOTIFY would fetch late or a
        // SUPPRESS pay for a fetch, and an alert would be held that should end, or end on an estimate.
        // Simulations at every level are here on purpose: a change to how evaluate treats a simulation
        // that forgets the predictions goes red here.
        for (String prior : new String[] {"IDLE", "MODERATE", "STRONG",
                "SIM_QUIET", "SIM_MINOR", "SIM_MODERATE", "SIM_STRONG"}) {
            for (AlertLevel incoming : AlertLevel.values()) {
                AuroraStateCache machine = machineIn(prior);
                boolean activeBefore = machine.isActive();
                AlertLevel levelBefore = machine.getCurrentLevel();

                boolean notify = machine.wouldNotify(incoming);
                boolean clear = machine.wouldClear(incoming);

                assertThat(machine.isActive()).as("asking changed the state").isEqualTo(activeBefore);
                assertThat(machine.getCurrentLevel()).as("asking changed the level").isEqualTo(levelBefore);
                AuroraStateCache.Action action = machine.evaluate(incoming).action();
                assertThat(notify).as("wouldNotify from %s, %s", prior, incoming)
                        .isEqualTo(action == AuroraStateCache.Action.NOTIFY);
                assertThat(clear).as("wouldClear from %s, %s", prior, incoming)
                        .isEqualTo(action == AuroraStateCache.Action.CLEAR);
            }
        }
    }

    @Test
    @DisplayName("wouldNotify is true for a same-level reading during a simulation, like evaluate")
    void wouldNotify_fromASimulation_matchesEvaluate() {
        cache.activateSimulation(AlertLevel.MODERATE,
                new AuroraStateCache.SimulatedNoaaData(5.0, 30.0, -5.0, "G1"));

        // A takeover, not an escalation: the real MODERATE reading is not measured against the
        // simulated MODERATE at all, so it NOTIFYs even though the severities are equal.
        assertThat(cache.wouldNotify(AlertLevel.MODERATE)).isTrue();
        assertThat(cache.wouldNotify(AlertLevel.STRONG)).isTrue();
        assertThat(cache.evaluate(AlertLevel.STRONG).action()).isEqualTo(AuroraStateCache.Action.NOTIFY);
    }

    // -------------------------------------------------------------------------
    // A real CLEAR ends a lingering simulation (an admin never called simulate/clear)
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("A real CLEAR ends a lingering admin simulation")
    void clear_endsALingeringSimulation() {
        cache.activateSimulation(AlertLevel.STRONG,
                new AuroraStateCache.SimulatedNoaaData(7.0, 40.0, -8.0, "G3"));
        assertThat(cache.getSimulatedData()).isNotNull();

        cache.evaluate(AlertLevel.QUIET); // real reading, not a simulation — CLEAR

        assertThat(cache.getSimulatedData()).isNull();
    }

    @Test
    @DisplayName("A real alert after that CLEAR is never reported as simulated")
    void notify_afterClearingALingeringSimulation_isNotSimulated() {
        // The scenario a stale simulation would otherwise hide a genuine alert behind: an admin
        // activates a simulation and never explicitly clears it, a real quiet reading ends it, and
        // a later real alert reactivates the machine. Without the CLEAR-branch fix, the simulation
        // stayed set through both steps, so every reader gated on it (hot topics, the best-bet
        // prompt) would silently suppress this genuine alert.
        cache.activateSimulation(AlertLevel.STRONG,
                new AuroraStateCache.SimulatedNoaaData(7.0, 40.0, -8.0, "G3"));
        cache.evaluate(AlertLevel.QUIET); // real CLEAR

        AuroraStateCache.Evaluation evaluation = cache.evaluate(AlertLevel.MODERATE); // real NOTIFY

        assertThat(evaluation.action()).isEqualTo(AuroraStateCache.Action.NOTIFY);
        assertThat(evaluation.currentLevel()).isEqualTo(AlertLevel.MODERATE);
        assertThat(cache.getSimulatedData()).isNull();
    }

    /**
     * A machine on this test's clock: {@code IDLE}, ACTIVE at a level ({@code MODERATE},
     * {@code STRONG}), or simulating at one ({@code SIM_QUIET}, {@code SIM_MINOR}, ...).
     */
    private AuroraStateCache machineIn(String prior) {
        AuroraStateCache machine = new AuroraStateCache(clock);
        if (prior.startsWith("SIM_")) {
            machine.activateSimulation(AlertLevel.valueOf(prior.substring("SIM_".length())),
                    new AuroraStateCache.SimulatedNoaaData(3.0, 10.0, 0.0, null));
        } else if (!"IDLE".equals(prior)) {
            machine.evaluate(AlertLevel.valueOf(prior));
        }
        return machine;
    }

    /** A clock the test moves by hand. */
    private static final class MovableClock extends Clock {

        private Instant instant = START;

        void advance(Duration by) {
            instant = instant.plus(by);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return Clock.fixed(instant, zone);
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }

    // -------------------------------------------------------------------------
    // One transition decides on the state it finds, not the state it arrived to
    // -------------------------------------------------------------------------

    /**
     * An evaluation queued behind a simulation's start must see that simulation. Decided before it
     * waited, it would find no simulation, then SUPPRESS a real MODERATE against the simulated STRONG
     * it met when it got in — the defect this class was fixed for, back under contention.
     */
    @Test
    @DisplayName("an evaluation that waited behind a simulation's start ends that simulation")
    void evaluationThatWaitedBehindASimulationsStart_endsIt() throws InterruptedException {
        var eval = queueBehindTheLock(() -> cache.evaluate(AlertLevel.MODERATE),
                () -> cache.activateSimulation(AlertLevel.STRONG, simulated(7.0)));

        assertThat(eval.action()).isEqualTo(AuroraStateCache.Action.NOTIFY);
        assertThat(eval.previousLevel()).isNull();
        assertThat(cache.getSimulatedData()).isNull();
        assertThat(cache.getCurrentLevel()).isEqualTo(AlertLevel.MODERATE);
    }

    /**
     * Two evaluations of one alert — a poll and an admin run, say — notify once, however they meet.
     * Deciding "IDLE" before waiting, the second would NOTIFY again and pay for a second scoring.
     */
    @Test
    @DisplayName("two evaluations of one alert from IDLE notify once, even when one waits on the other")
    void twoEvaluationsOfOneAlertFromIdle_notifyOnce() throws InterruptedException {
        List<AuroraStateCache.Evaluation> first = new ArrayList<>();
        var second = queueBehindTheLock(() -> cache.evaluate(AlertLevel.MODERATE),
                () -> first.add(cache.evaluate(AlertLevel.MODERATE)));

        assertThat(first).extracting(AuroraStateCache.Evaluation::action)
                .containsExactly(AuroraStateCache.Action.NOTIFY);
        assertThat(second.action()).isEqualTo(AuroraStateCache.Action.SUPPRESS);
    }

    /**
     * Queues {@code queued} on a second thread behind the lock held here, runs {@code meanwhile} on
     * this thread — the lock is re-entrant, so that transition happens at once — then lets
     * {@code queued} in and returns what it produced.
     */
    private <T> T queueBehindTheLock(Supplier<T> queued, Runnable meanwhile)
            throws InterruptedException {
        ReentrantLock lock = cache.transitionLock();
        AtomicReference<T> result = new AtomicReference<>();
        Thread runner = new Thread(() -> result.set(queued.get()), "aurora-lock-waiter");
        runner.setDaemon(true);

        lock.lock();
        try {
            runner.start();
            await().atMost(WAIT_SECONDS, TimeUnit.SECONDS)
                    .until(() -> lock.hasQueuedThread(runner) || !runner.isAlive());
            assertThat(lock.hasQueuedThread(runner)).as("the call did not wait for the lock").isTrue();
            meanwhile.run();
        } finally {
            lock.unlock();
        }

        runner.join(TimeUnit.SECONDS.toMillis(WAIT_SECONDS));
        assertThat(runner.isAlive()).as("the call never finished").isFalse();
        return result.get();
    }

    /**
     * {@code wouldNotify} takes the lock too, so a poll's peek queued behind an admin's simulate
     * call answers for the simulation that had already started by the time it was let in, not for
     * the IDLE machine it was called against — a takeover, so a real MODERATE reading now NOTIFIES.
     */
    @Test
    @DisplayName("wouldNotify waits for a transition in progress")
    void wouldNotify_waitsForTheTransitionInProgress() throws InterruptedException {
        boolean notify = queueBehindTheLock(() -> cache.wouldNotify(AlertLevel.MODERATE),
                () -> cache.activateSimulation(AlertLevel.STRONG, simulated(7.0)));

        assertThat(notify).isTrue();
    }

    /**
     * {@code wouldClear} takes the lock too: queued behind a real NOTIFY, it answers for the alert
     * that had already started, not for the IDLE machine it was called against.
     */
    @Test
    @DisplayName("wouldClear waits for a transition in progress")
    void wouldClear_waitsForTheTransitionInProgress() throws InterruptedException {
        boolean clear = queueBehindTheLock(() -> cache.wouldClear(AlertLevel.QUIET),
                () -> cache.evaluate(AlertLevel.MODERATE));

        assertThat(clear).isTrue();
    }

    // -------------------------------------------------------------------------
    // Writers — one transition at a time
    // -------------------------------------------------------------------------

    /**
     * Every write waits for a transition already in progress, then goes through and lets go.
     *
     * <p>The machine is written from the polling job's thread, from request threads and from the
     * batch result processor, and its transitions share fields: a CLEAR and a simulation both write
     * the level, the trigger and the simulation, so interleaved they could keep the simulated level
     * and lose the simulation — a fake alert served as a real one. No write calls out to anything a
     * test could park inside, so the test holds the lock itself. It holds it on this thread and runs
     * the write on another: the lock is re-entrant, so a write on the holding thread would walk
     * straight through and prove nothing. Every wait is bounded, so a regression fails, not hangs.
     *
     * <p>What it catches is a write that takes no lock or never lets it go. A write that waits for
     * the lock and then writes after releasing it looks the same from out here; only a hook inside
     * the transition could tell them apart, so that shape is left to review.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("writes")
    @DisplayName("every write waits for the transition in progress, then goes through and lets go")
    void everyWrite_waitsForTheTransitionInProgress(Write write) throws InterruptedException {
        write.arrange().accept(cache);
        List<Object> before = everything(cache);
        ReentrantLock lock = cache.transitionLock();
        Thread writer = new Thread(() -> write.apply().accept(cache), "aurora-state-writer");
        writer.setDaemon(true); // a writer stuck behind a regression must not keep the JVM alive

        lock.lock();
        try {
            writer.start();
            await().atMost(WAIT_SECONDS, TimeUnit.SECONDS)
                    .until(() -> lock.hasQueuedThread(writer) || !writer.isAlive());
            assertThat(lock.hasQueuedThread(writer)).as("the write did not wait for the lock").isTrue();
            assertThat(everything(cache)).as("written through a held lock").isEqualTo(before);
        } finally {
            lock.unlock();
        }

        writer.join(TimeUnit.SECONDS.toMillis(WAIT_SECONDS));
        assertThat(writer.isAlive()).as("the write never finished").isFalse();
        assertThat(everything(cache)).as("the write never landed").isNotEqualTo(before);
        assertThat(lock.isLocked()).as("the write kept the lock").isFalse();
    }

    /** Every write, and every branch of {@code evaluate} — a branch outside the lock is its own bug. */
    static Stream<Write> writes() {
        Consumer<AuroraStateCache> fresh = c -> { };
        Consumer<AuroraStateCache> simulating = c -> c.activateSimulation(AlertLevel.STRONG, simulated(7.0));
        return Stream.of(
                new Write("evaluate, a new alert", fresh, c -> c.evaluate(AlertLevel.MODERATE)),
                new Write("evaluate, an escalation", c -> c.evaluate(AlertLevel.MODERATE),
                        c -> c.evaluate(AlertLevel.STRONG)),
                new Write("evaluate, clearing a real alert", AuroraStateCacheTest::realAlert,
                        c -> c.evaluate(AlertLevel.QUIET)),
                new Write("evaluate, clearing a simulation", simulating, c -> c.evaluate(AlertLevel.MINOR)),
                new Write("evaluate, a real alert ending a simulation", simulating,
                        c -> c.evaluate(AlertLevel.MODERATE)),
                new Write("activateSimulation", fresh,
                        c -> c.activateSimulation(AlertLevel.STRONG, simulated(7.0))),
                new Write("endSimulation", simulating, AuroraStateCache::endSimulation),
                new Write("reset", AuroraStateCacheTest::realAlert, AuroraStateCache::reset),
                new Write("updateScores", fresh, c -> c.updateScores(
                        List.of(new AuroraForecastScore(null, 2, AlertLevel.MODERATE, 60, "★★", "d")))),
                new Write("updateTrigger", fresh, c -> c.updateTrigger(TriggerType.FORECAST_LOOKAHEAD, 6.0)),
                new Write("updateLocationCounts", fresh, c -> c.updateLocationCounts(30, 8)));
    }

    /**
     * One write the machine takes, and the state it is taken from.
     *
     * @param name    the display name
     * @param arrange puts the machine in the state the write starts from
     * @param apply   the write itself
     */
    record Write(String name, Consumer<AuroraStateCache> arrange, Consumer<AuroraStateCache> apply) {
        @Override
        public String toString() {
            return name;
        }
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private AuroraForecastScore stubScore() {
        return new AuroraForecastScore(null, 3, AlertLevel.MODERATE, 30, "★★★ Moderate", "detail");
    }

    /** Fake NOAA values as an admin enters them; only the Kp varies between tests. */
    private static AuroraStateCache.SimulatedNoaaData simulated(double kp) {
        return new AuroraStateCache.SimulatedNoaaData(kp, 45.0, -12.0, "G3");
    }

    /** A real alert as the polling job leaves one after a NOTIFY: triggered, counted, scored. */
    private static void realAlert(AuroraStateCache c) {
        c.evaluate(AlertLevel.MODERATE);
        landAScoring(c);
    }

    /**
     * A scoring's writes — trigger, counts, scores — as the orchestrator makes them after a NOTIFY.
     * The same writes can land on a state that has moved on: the batch result path writes scores
     * whatever the state, and a scoring still in flight lands after whatever came in the meantime.
     */
    private static void landAScoring(AuroraStateCache c) {
        c.updateTrigger(TriggerType.REALTIME, 5.3);
        c.updateLocationCounts(12, 7);
        c.updateScores(List.of(new AuroraForecastScore(null, 4, AlertLevel.MODERATE, 20, "★★★★", "d")));
    }

    /** Everything the machine exposes, so two states can be compared whole. */
    private static List<Object> everything(AuroraStateCache c) {
        return Arrays.asList(c.isActive(), c.getCurrentLevel(), c.getActiveSince(), c.getCachedScores(),
                c.getLastTriggerType(), c.getLastTriggerKp(), c.getDarkSkyLocationCount(),
                c.getClearLocationCount(), c.getSimulatedData());
    }

    /**
     * Everything but {@code activeSince}, which reads the wall clock: active, level, scores, trigger
     * type, trigger Kp, dark-sky count, clear count, simulation — in that order.
     */
    private static List<Object> everythingButWhen(AuroraStateCache c) {
        return Arrays.asList(c.isActive(), c.getCurrentLevel(), c.getCachedScores(),
                c.getLastTriggerType(), c.getLastTriggerKp(), c.getDarkSkyLocationCount(),
                c.getClearLocationCount(), c.getSimulatedData());
    }
}

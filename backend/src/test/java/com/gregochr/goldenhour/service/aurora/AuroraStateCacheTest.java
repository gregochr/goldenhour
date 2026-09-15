package com.gregochr.goldenhour.service.aurora;

import com.gregochr.goldenhour.entity.AlertLevel;
import com.gregochr.goldenhour.model.AuroraForecastScore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link AuroraStateCache} state machine lifecycle.
 *
 * <p>The machine runs on a clock the test moves by hand, from 22:00 on 14 January 2027, so every
 * {@code activeSince} is an exact instant.
 */
class AuroraStateCacheTest {

    private static final Instant START = Instant.parse("2027-01-14T22:00:00Z");

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
            "SIM_MINOR, MINOR,    false, true",
            "SIM_MINOR, MODERATE, true,  false",
    })
    @DisplayName("wouldNotify and wouldClear answer from every state for every level")
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
        for (String prior : new String[] {"IDLE", "MODERATE", "STRONG", "SIM_QUIET", "SIM_MINOR"}) {
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
    @DisplayName("wouldNotify is true for an escalation from a simulation, like evaluate")
    void wouldNotify_fromASimulation_matchesEvaluate() {
        cache.activateSimulation(AlertLevel.MODERATE,
                new AuroraStateCache.SimulatedNoaaData(5.0, 30.0, -5.0, "G1"));

        assertThat(cache.wouldNotify(AlertLevel.MODERATE)).isFalse();
        assertThat(cache.wouldNotify(AlertLevel.STRONG)).isTrue();
        assertThat(cache.evaluate(AlertLevel.STRONG).action()).isEqualTo(AuroraStateCache.Action.NOTIFY);
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
    // Helpers
    // -------------------------------------------------------------------------

    private AuroraForecastScore stubScore() {
        return new AuroraForecastScore(null, 3, AlertLevel.MODERATE, 30, "★★★ Moderate", "detail");
    }
}

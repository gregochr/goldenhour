package com.gregochr.goldenhour.service.aurora;

import com.gregochr.goldenhour.entity.AlertLevel;
import com.gregochr.goldenhour.model.AuroraForecastScore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link AuroraStateCache} state machine lifecycle.
 */
class AuroraStateCacheTest {

    private AuroraStateCache cache;

    @BeforeEach
    void setUp() {
        cache = new AuroraStateCache();
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
    // Helpers
    // -------------------------------------------------------------------------

    private AuroraForecastScore stubScore() {
        return new AuroraForecastScore(null, 3, AlertLevel.MODERATE, 30, "★★★ Moderate", "detail");
    }
}

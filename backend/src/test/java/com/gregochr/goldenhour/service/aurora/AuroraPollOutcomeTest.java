package com.gregochr.goldenhour.service.aurora;

import com.gregochr.goldenhour.entity.AlertLevel;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link AuroraPollOutcome}'s own rules: a level always says where it came from, and
 * an outcome always has an action.
 */
class AuroraPollOutcomeTest {

    @Test
    @DisplayName("a NOAA read that threw derives no level, attributes nothing and consults nothing")
    void noaaReadFailed_hasNoLevelNoTriggerAndNoAction() {
        AuroraPollOutcome outcome = AuroraPollOutcome.noaaReadFailed(true);

        assertThat(outcome.dark()).isTrue();
        assertThat(outcome.level()).isNull();
        assertThat(outcome.trigger()).isNull();
        assertThat(outcome.action()).isEqualTo(AuroraStateCache.Action.NONE);
    }

    @Test
    @DisplayName("a level without the signal it came from is rejected")
    void levelWithoutTrigger_isRejected() {
        assertThatThrownBy(() -> new AuroraPollOutcome(true, AlertLevel.MODERATE,
                AuroraStateCache.Action.NOTIFY, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("a signal without a level is rejected")
    void triggerWithoutLevel_isRejected() {
        assertThatThrownBy(() -> new AuroraPollOutcome(false, null, AuroraStateCache.Action.NONE,
                TriggerType.FORECAST_LOOKAHEAD))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("an outcome without an action is rejected")
    void missingAction_isRejected() {
        assertThatThrownBy(() -> new AuroraPollOutcome(false, AlertLevel.QUIET, null,
                TriggerType.FORECAST_LOOKAHEAD))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("action");
    }

    @Test
    @DisplayName("a hold is a night poll that consulted nothing, at a level below MODERATE")
    void held_isANightPollsNoneBelowModerate() {
        AuroraPollOutcome outcome = AuroraPollOutcome.held(AlertLevel.MINOR, TriggerType.REALTIME);

        assertThat(outcome).isEqualTo(new AuroraPollOutcome(true, AlertLevel.MINOR,
                AuroraStateCache.Action.NONE, TriggerType.REALTIME, true));
        assertThat(new AuroraPollOutcome(true, AlertLevel.MINOR, AuroraStateCache.Action.NONE,
                TriggerType.REALTIME).held())
                .as("the four-part outcome holds nothing")
                .isFalse();
    }

    @Test
    @DisplayName("a hold in daylight, with an action, at MODERATE or without a level is rejected")
    void held_otherwise_isRejected() {
        assertThatThrownBy(() -> new AuroraPollOutcome(false, AlertLevel.MINOR,
                AuroraStateCache.Action.NONE, TriggerType.FORECAST_LOOKAHEAD, true))
                .as("in daylight").isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AuroraPollOutcome(true, AlertLevel.MINOR,
                AuroraStateCache.Action.SUPPRESS, TriggerType.REALTIME, true))
                .as("with an action").isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> AuroraPollOutcome.held(AlertLevel.MODERATE, TriggerType.REALTIME))
                .as("at MODERATE").isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AuroraPollOutcome(true, null, AuroraStateCache.Action.NONE,
                null, true))
                .as("without a level").isInstanceOf(IllegalArgumentException.class);
    }
}

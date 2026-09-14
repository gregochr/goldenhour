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
    @DisplayName("an unreadable NOAA derives no level, attributes nothing and consults nothing")
    void noaaUnavailable_hasNoLevelNoTriggerAndNoAction() {
        AuroraPollOutcome outcome = AuroraPollOutcome.noaaUnavailable(true);

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
}

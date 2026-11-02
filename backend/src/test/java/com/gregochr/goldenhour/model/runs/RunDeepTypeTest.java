package com.gregochr.goldenhour.model.runs;

import com.gregochr.goldenhour.entity.CycleType;
import com.gregochr.goldenhour.entity.RunType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The display-type mapping is total over every {@link RunType} and {@link CycleType}, and the cycles are told apart.
 */
class RunDeepTypeTest {

    @ParameterizedTest
    @EnumSource(RunType.class)
    @DisplayName("every run type maps to a display type")
    void everyRunTypeMaps(RunType runType) {
        assertThat(RunDeepType.of(runType)).isNotNull();
        assertThat(RunDeepType.of(runType).label()).isNotBlank();
    }

    @Test
    @DisplayName("briefing and ask-ready keep their own type; every other run type is OTHER")
    void mapping() {
        assertThat(RunDeepType.of(RunType.BRIEFING)).isEqualTo(RunDeepType.BRIEFING);
        assertThat(RunDeepType.of(RunType.ASK_READY)).isEqualTo(RunDeepType.ASK_READY);
        List<RunType> others = List.of(RunType.SCHEDULED_BATCH, RunType.TIDE, RunType.WEATHER,
                RunType.LIGHT_POLLUTION, RunType.AURORA_EVALUATION, RunType.ASK, RunType.BATCH_NEAR_TERM,
                RunType.BATCH_FAR_TERM, RunType.BRIEFING_BEST_BET, RunType.BRIEFING_GLOSS, RunType.AURORA_GLOSS,
                RunType.BLUEBELL_GLOSS);
        for (RunType other : others) {
            assertThat(RunDeepType.of(other)).as(other.name()).isEqualTo(RunDeepType.OTHER);
        }
    }

    @ParameterizedTest
    @EnumSource(CycleType.class)
    @DisplayName("every cycle type maps to a cycle display type")
    void everyCycleMaps(CycleType cycleType) {
        assertThat(RunDeepType.ofCycle(cycleType).isCycle()).isTrue();
    }

    @Test
    @DisplayName("the three cycles, and only they, are cycles; labels are served by the server")
    void cyclesAndLabels() {
        assertThat(RunDeepType.ofCycle(CycleType.NIGHTLY)).isEqualTo(RunDeepType.OVERNIGHT);
        assertThat(RunDeepType.ofCycle(CycleType.INTRADAY)).isEqualTo(RunDeepType.AFTERNOON);
        assertThat(RunDeepType.ofCycle(CycleType.EVENING)).isEqualTo(RunDeepType.EVENING);
        assertThat(RunDeepType.SKY_EVAL.isCycle()).isFalse();
        assertThat(RunDeepType.OTHER.isCycle()).isFalse();
        assertThat(RunDeepType.AFTERNOON.label()).isEqualTo("Afternoon refresh");
        assertThat(RunDeepType.ASK_READY.label()).isEqualTo("Ask ready answers");
    }

    @Test
    @DisplayName("only PARTIAL and FAILED need attention, and a DEGRADED cycle is served as PARTIAL")
    void statusMapping() {
        assertThat(RunDeepStatus.PARTIAL.needsAttention()).isTrue();
        assertThat(RunDeepStatus.FAILED.needsAttention()).isTrue();
        assertThat(RunDeepStatus.DONE.needsAttention()).isFalse();
        assertThat(RunDeepStatus.RUNNING.needsAttention()).isFalse();
        assertThat(RunDeepStatus.SCHEDULED.needsAttention()).isFalse();
        for (RunListStatus status : RunListStatus.values()) {
            assertThat(RunDeepStatus.from(status)).isNotNull();
        }
        assertThat(RunDeepStatus.from(RunListStatus.DEGRADED)).isEqualTo(RunDeepStatus.PARTIAL);
    }
}

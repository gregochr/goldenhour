package com.gregochr.goldenhour.model;

import com.gregochr.goldenhour.entity.TargetType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The reach-verdicts body's defensive copies (reach-scoped-card-plan R2): a caller's later change to what it passed
 * cannot alter a served response, and an absent collection reads as empty rather than null.
 */
class ReachVerdictResponseTest {

    private static ReachVerdictResponse.Window window(List<ReachVerdictResponse.RegionVerdict> regions) {
        return new ReachVerdictResponse.Window(LocalDate.of(2026, 10, 1), TargetType.SUNRISE,
                DisplayVerdict.MAYBE, 3.0, 3.0, Confidence.MEDIUM, false, null, null, null, regions);
    }

    @Test
    @DisplayName("absent tiers and absent regions read as empty, never null")
    void nullsReadAsEmpty() {
        assertThat(new ReachVerdictResponse(true, "v", null).tiers()).isEmpty();
        assertThat(window(null).regions()).isEmpty();
    }

    @Test
    @DisplayName("tiers keep their insertion order and are a copy that cannot be changed")
    void tiersAreAnOrderedImmutableCopy() {
        Map<String, List<ReachVerdictResponse.Window>> source = new LinkedHashMap<>();
        source.put("150", List.of());
        source.put("45", List.of());
        source.put("90", List.of());

        ReachVerdictResponse response = new ReachVerdictResponse(true, "v", source);
        source.put("1", List.of());

        assertThat(response.tiers().keySet()).containsExactly("150", "45", "90");
        assertThatThrownBy(() -> response.tiers().put("2", List.of()))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    @DisplayName("a window's regions are a copy that cannot be changed")
    void regionsAreAnImmutableCopy() {
        List<ReachVerdictResponse.RegionVerdict> source = new ArrayList<>();
        source.add(new ReachVerdictResponse.RegionVerdict("Lakes", DisplayVerdict.MAYBE, 3.0));

        ReachVerdictResponse.Window window = window(source);
        source.clear();

        assertThat(window.regions()).hasSize(1);
        assertThatThrownBy(() -> window.regions().clear()).isInstanceOf(UnsupportedOperationException.class);
    }
}

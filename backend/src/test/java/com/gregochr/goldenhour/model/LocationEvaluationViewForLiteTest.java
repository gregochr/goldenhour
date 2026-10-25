package com.gregochr.goldenhour.model;

import static org.assertj.core.api.Assertions.assertThat;

import com.gregochr.goldenhour.entity.TargetType;
import java.lang.reflect.RecordComponent;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link LocationEvaluationView#forLite()}: the strip table of
 * pro-features-gate-plan.md §6 item 1.
 */
class LocationEvaluationViewForLiteTest {

    private static final Set<String> STRIPPED = Set.of("summary", "fierySkyPotential",
            "goldenHourPotential");
    private static final Set<String> KEPT = Set.of("locationId", "locationName", "regionId",
            "regionName", "date", "targetType", "source", "rating", "triageReason", "triageMessage",
            "evaluationModel", "evaluatedAt", "displayVerdict", "goldenHourStart", "goldenHourEnd",
            "blueHourStart", "blueHourEnd");

    private static LocationEvaluationView full() {
        LocalDateTime t = LocalDateTime.of(2026, 10, 25, 16, 30);
        return new LocationEvaluationView(7L, "Bamburgh", 3L, "North East", LocalDate.of(2026, 10, 25),
                TargetType.SUNSET, LocationEvaluationView.Source.CACHED_EVALUATION, 4,
                "Clear sky over the castle.", 81, 66, TriageReason.HIGH_CLOUD, "Cloud is too thick",
                "SONNET", Instant.parse("2026-10-25T06:00:00Z"), DisplayVerdict.WORTH_IT,
                t, t.plusMinutes(40), t.plusHours(1), t.plusHours(2));
    }

    @Test
    @DisplayName("Every LocationEvaluationView component is classified kept or stripped")
    void everyComponentIsClassified() {
        Set<String> all = new HashSet<>(STRIPPED);
        all.addAll(KEPT);

        assertThat(Arrays.stream(LocationEvaluationView.class.getRecordComponents())
                .map(RecordComponent::getName).collect(Collectors.toSet()))
                .as("a new component must be classified here and handled in forLite()")
                .containsExactlyInAnyOrderElementsOf(all);
    }

    @Test
    @DisplayName("forLite nulls exactly the stripped components and keeps the rest verbatim")
    void forLiteNullsExactlyTheStrippedComponents() throws Exception {
        LocationEvaluationView full = full();
        LocationEvaluationView lite = full.forLite();

        for (RecordComponent c : LocationEvaluationView.class.getRecordComponents()) {
            Object value = c.getAccessor().invoke(lite);
            if (STRIPPED.contains(c.getName())) {
                assertThat(value).as(c.getName()).isNull();
                assertThat(c.getAccessor().invoke(full)).as(c.getName() + " in the fixture").isNotNull();
            } else {
                assertThat(value).as(c.getName()).isEqualTo(c.getAccessor().invoke(full));
            }
        }
    }

    @Test
    @DisplayName("forLite leaves the original view untouched")
    void forLiteDoesNotMutateTheOriginal() {
        LocationEvaluationView full = full();

        full.forLite();

        assertThat(full).isEqualTo(full());
    }
}

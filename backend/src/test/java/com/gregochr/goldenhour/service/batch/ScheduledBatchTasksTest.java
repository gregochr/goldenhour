package com.gregochr.goldenhour.service.batch;

import com.gregochr.goldenhour.entity.EvaluationModel;
import com.gregochr.goldenhour.entity.LocationEntity;
import com.gregochr.goldenhour.entity.TargetType;
import com.gregochr.goldenhour.service.evaluation.EvaluationTask;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link ScheduledBatchTasks}: emptiness and size across all six buckets, and
 * the weather-prefetch defaulting.
 */
class ScheduledBatchTasksTest {

    private static EvaluationTask.Forecast task(String name) {
        LocationEntity loc = new LocationEntity();
        loc.setId(42L);
        loc.setName(name);
        return new EvaluationTask.Forecast(loc, LocalDate.of(2026, 6, 12), TargetType.SUNSET,
                EvaluationModel.SONNET,
                com.gregochr.goldenhour.TestAtmosphericData.builder().locationName(name)
                        .solarEventTime(java.time.LocalDateTime.of(2026, 6, 12, 21, 0))
                        .targetType(TargetType.SUNSET).build(),
                EvaluationTask.Forecast.PromptKind.SKY, null);
    }

    private static ScheduledBatchTasks withOnlyBucket(int bucket) {
        List<List<EvaluationTask.Forecast>> b = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            b.add(i == bucket ? List.of(task("t" + i)) : List.of());
        }
        return new ScheduledBatchTasks(b.get(0), b.get(1), b.get(2), b.get(3), b.get(4), b.get(5),
                List.of());
    }

    @Test
    @DisplayName("empty() has no tasks, size zero and the none() prefetch report")
    void emptyFactory() {
        ScheduledBatchTasks empty = ScheduledBatchTasks.empty();

        assertThat(empty.isEmpty()).isTrue();
        assertThat(empty.totalSize()).isZero();
        assertThat(empty.dispositions()).isEmpty();
        assertThat(empty.weatherPrefetch()).isEqualTo(WeatherPrefetchReport.none());
    }

    @Test
    @DisplayName("a task in any single one of the six buckets makes the set non-empty, size one")
    void anyBucketMakesNonEmpty() {
        for (int bucket = 0; bucket < 6; bucket++) {
            ScheduledBatchTasks tasks = withOnlyBucket(bucket);
            assertThat(tasks.isEmpty()).as("bucket %d", bucket).isFalse();
            assertThat(tasks.totalSize()).as("bucket %d", bucket).isEqualTo(1);
        }
    }

    @Test
    @DisplayName("totalSize sums every bucket")
    void totalSizeSumsBuckets() {
        ScheduledBatchTasks tasks = new ScheduledBatchTasks(
                List.of(task("a"), task("b")), List.of(task("c")), List.of(), List.of(task("d")),
                List.of(task("e")), List.of(task("f"), task("g")), List.of());

        assertThat(tasks.totalSize()).isEqualTo(7);
    }

    @Test
    @DisplayName("an explicit weather prefetch report is kept")
    void explicitPrefetchKept() {
        WeatherPrefetchReport none = WeatherPrefetchReport.none();
        ScheduledBatchTasks tasks = new ScheduledBatchTasks(List.of(), List.of(), List.of(),
                List.of(), List.of(), List.of(), List.of(), none);

        assertThat(tasks.weatherPrefetch()).isSameAs(none);
    }

    @Test
    @DisplayName("a null weather prefetch report is replaced by none()")
    void nullPrefetchDefaulted() {
        ScheduledBatchTasks tasks = new ScheduledBatchTasks(List.of(), List.of(), List.of(),
                List.of(), List.of(), List.of(), List.of(), null);

        assertThat(tasks.weatherPrefetch()).isEqualTo(WeatherPrefetchReport.none());
    }
}

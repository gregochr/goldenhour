package com.gregochr.goldenhour.service.evaluation;

import com.gregochr.goldenhour.TestAtmosphericData;
import com.gregochr.goldenhour.entity.AlertLevel;
import com.gregochr.goldenhour.entity.EvaluationModel;
import com.gregochr.goldenhour.entity.LocationEntity;
import com.gregochr.goldenhour.entity.TargetType;
import com.gregochr.goldenhour.model.AtmosphericData;
import com.gregochr.goldenhour.model.SpaceWeatherData;
import com.gregochr.goldenhour.service.aurora.TriggerType;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

/**
 * Unit tests for {@link EvaluationTask} sealed hierarchy.
 *
 * <p>The engine never reflects on internal fields — these tests guard the public
 * contract: required-field validation, the {@code taskKey} format, and {@link
 * CustomIdFactory} round-trip equivalence.
 */
class EvaluationTaskTest {

    private static final LocalDate DATE = LocalDate.of(2026, 4, 16);
    private static final AtmosphericData ATMOSPHERIC = TestAtmosphericData.defaults();
    private static final SpaceWeatherData SPACE_WEATHER = new SpaceWeatherData(
            List.of(), List.of(), null, List.of(), List.of());

    private static LocationEntity persistedLocation(long id, String name) {
        LocationEntity loc = new LocationEntity();
        loc.setId(id);
        loc.setName(name);
        loc.setLat(54.6029);
        loc.setLon(-3.0980);
        return loc;
    }

    @Test
    void forecastTaskBuildsWithValidInputs() {
        EvaluationTask.Forecast task = new EvaluationTask.Forecast(
                persistedLocation(42L, "Castlerigg"),
                DATE, TargetType.SUNRISE,
                EvaluationModel.HAIKU, ATMOSPHERIC);

        assertThat(task.location().getId()).isEqualTo(42L);
        assertThat(task.date()).isEqualTo(DATE);
        assertThat(task.targetType()).isEqualTo(TargetType.SUNRISE);
        assertThat(task.model()).isEqualTo(EvaluationModel.HAIKU);
        assertThat(task.data()).isSameAs(ATMOSPHERIC);
    }

    @Test
    void forecastTaskKeyEncodesIdentityFields() {
        EvaluationTask.Forecast task = new EvaluationTask.Forecast(
                persistedLocation(7L, "Bamburgh"),
                DATE, TargetType.SUNSET,
                EvaluationModel.SONNET, ATMOSPHERIC);

        assertThat(task.taskKey()).isEqualTo("7/2026-04-16/SUNSET");
    }

    @Test
    void forecastTaskRejectsTransientLocation() {
        LocationEntity unsaved = new LocationEntity();
        unsaved.setName("Not-yet-persisted");

        assertThatIllegalArgumentException().isThrownBy(() -> new EvaluationTask.Forecast(
                unsaved, DATE, TargetType.SUNRISE,
                EvaluationModel.HAIKU, ATMOSPHERIC))
                .withMessageContaining("non-null id");
    }

    @Test
    void forecastTaskRejectsNullArguments() {
        LocationEntity loc = persistedLocation(42L, "Castlerigg");

        assertThatNullPointerException().isThrownBy(() -> new EvaluationTask.Forecast(
                null, DATE, TargetType.SUNRISE, EvaluationModel.HAIKU, ATMOSPHERIC));
        assertThatNullPointerException().isThrownBy(() -> new EvaluationTask.Forecast(
                loc, null, TargetType.SUNRISE, EvaluationModel.HAIKU, ATMOSPHERIC));
        assertThatNullPointerException().isThrownBy(() -> new EvaluationTask.Forecast(
                loc, DATE, null, EvaluationModel.HAIKU, ATMOSPHERIC));
        assertThatNullPointerException().isThrownBy(() -> new EvaluationTask.Forecast(
                loc, DATE, TargetType.SUNRISE, null, ATMOSPHERIC));
        assertThatNullPointerException().isThrownBy(() -> new EvaluationTask.Forecast(
                loc, DATE, TargetType.SUNRISE, EvaluationModel.HAIKU, null));
    }

    @Test
    void forecastTaskRoundTripsViaCustomIdFactory() {
        LocationEntity loc = persistedLocation(42L, "Castlerigg");
        EvaluationTask.Forecast task = new EvaluationTask.Forecast(
                loc, DATE, TargetType.SUNRISE,
                EvaluationModel.HAIKU, ATMOSPHERIC);

        String customId = CustomIdFactory.forForecast(
                task.location().getId(), task.date(), task.targetType());
        ParsedCustomId parsed = CustomIdFactory.parse(customId);

        assertThat(parsed).isInstanceOf(ParsedCustomId.Forecast.class);
        ParsedCustomId.Forecast f = (ParsedCustomId.Forecast) parsed;
        assertThat(f.locationId()).isEqualTo(task.location().getId());
        assertThat(f.date()).isEqualTo(task.date());
        assertThat(f.targetType()).isEqualTo(task.targetType());
    }

    @Test
    void auroraTaskBuildsWithValidInputs() {
        LocationEntity north = persistedLocation(99L, "Northumberland Coast");

        EvaluationTask.Aurora task = new EvaluationTask.Aurora(
                AlertLevel.MODERATE, DATE,
                EvaluationModel.HAIKU,
                List.of(north),
                Map.of(north, 35),
                SPACE_WEATHER,
                TriggerType.REALTIME,
                null);

        assertThat(task.alertLevel()).isEqualTo(AlertLevel.MODERATE);
        assertThat(task.date()).isEqualTo(DATE);
        assertThat(task.viableLocations()).containsExactly(north);
        assertThat(task.cloudByLocation()).containsEntry(north, 35);
        assertThat(task.triggerType()).isEqualTo(TriggerType.REALTIME);
        assertThat(task.tonightWindow()).isNull();
    }

    @Test
    void auroraTaskKeyEncodesAlertLevelAndDate() {
        LocationEntity north = persistedLocation(99L, "Northumberland Coast");

        EvaluationTask.Aurora task = new EvaluationTask.Aurora(
                AlertLevel.STRONG, DATE,
                EvaluationModel.HAIKU,
                List.of(north),
                Map.of(north, 25),
                SPACE_WEATHER,
                TriggerType.FORECAST_LOOKAHEAD,
                null);

        assertThat(task.taskKey()).isEqualTo("au/STRONG/2026-04-16");
    }

    @Test
    void auroraTaskRejectsEmptyLocationList() {
        assertThatIllegalArgumentException().isThrownBy(() -> new EvaluationTask.Aurora(
                AlertLevel.MODERATE, DATE,
                EvaluationModel.HAIKU,
                List.of(),
                Map.of(),
                SPACE_WEATHER,
                TriggerType.REALTIME,
                null))
                .withMessageContaining("at least one viable location");
    }

    @Test
    void auroraTaskRejectsNullArguments() {
        LocationEntity loc = persistedLocation(99L, "Northumberland");
        List<LocationEntity> locations = List.of(loc);
        Map<LocationEntity, Integer> cloud = Map.of(loc, 30);

        assertThatNullPointerException().isThrownBy(() -> new EvaluationTask.Aurora(
                null, DATE, EvaluationModel.HAIKU, locations, cloud, SPACE_WEATHER,
                TriggerType.REALTIME, null));
        assertThatNullPointerException().isThrownBy(() -> new EvaluationTask.Aurora(
                AlertLevel.MODERATE, null, EvaluationModel.HAIKU, locations, cloud,
                SPACE_WEATHER, TriggerType.REALTIME, null));
        assertThatNullPointerException().isThrownBy(() -> new EvaluationTask.Aurora(
                AlertLevel.MODERATE, DATE, null, locations, cloud, SPACE_WEATHER,
                TriggerType.REALTIME, null));
        assertThatNullPointerException().isThrownBy(() -> new EvaluationTask.Aurora(
                AlertLevel.MODERATE, DATE, EvaluationModel.HAIKU, null, cloud,
                SPACE_WEATHER, TriggerType.REALTIME, null));
        assertThatNullPointerException().isThrownBy(() -> new EvaluationTask.Aurora(
                AlertLevel.MODERATE, DATE, EvaluationModel.HAIKU, locations, null,
                SPACE_WEATHER, TriggerType.REALTIME, null));
        assertThatNullPointerException().isThrownBy(() -> new EvaluationTask.Aurora(
                AlertLevel.MODERATE, DATE, EvaluationModel.HAIKU, locations, cloud,
                null, TriggerType.REALTIME, null));
        assertThatNullPointerException().isThrownBy(() -> new EvaluationTask.Aurora(
                AlertLevel.MODERATE, DATE, EvaluationModel.HAIKU, locations, cloud,
                SPACE_WEATHER, null, null));
    }

    @Test
    void auroraTaskRoundTripsViaCustomIdFactory() {
        LocationEntity loc = persistedLocation(99L, "Northumberland");
        EvaluationTask.Aurora task = new EvaluationTask.Aurora(
                AlertLevel.MODERATE, DATE,
                EvaluationModel.HAIKU,
                List.of(loc),
                Map.of(loc, 30),
                SPACE_WEATHER,
                TriggerType.FORECAST_LOOKAHEAD,
                null);

        String customId = CustomIdFactory.forAurora(task.alertLevel(), task.date());
        ParsedCustomId parsed = CustomIdFactory.parse(customId);

        assertThat(parsed).isInstanceOf(ParsedCustomId.Aurora.class);
        ParsedCustomId.Aurora a = (ParsedCustomId.Aurora) parsed;
        assertThat(a.alertLevel()).isEqualTo(task.alertLevel());
        assertThat(a.date()).isEqualTo(task.date());
    }
}

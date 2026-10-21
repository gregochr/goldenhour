package com.gregochr.goldenhour.model;

import com.gregochr.goldenhour.TestAtmosphericData;
import com.gregochr.goldenhour.entity.ForecastStability;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the valley-mist fields on {@link AtmosphericData}: absent by default, set by
 * {@code withValleyMist}, and carried through every other {@code withX} copy so a later augmentation
 * step cannot silently drop a reading made earlier in {@code fetchWeatherAndTriage}.
 */
class AtmosphericDataMistTest {

    @Test
    @DisplayName("the mist fields are absent unless something set them")
    void absentByDefault() {
        AtmosphericData data = TestAtmosphericData.defaults();

        assertThat(data.mistScore()).isNull();
        assertThat(data.mistPotential()).isNull();
    }

    @Test
    @DisplayName("every shorter constructor leaves the mist fields absent")
    void compatibilityConstructorsLeaveMistAbsent() {
        AtmosphericData base = TestAtmosphericData.defaults();

        AtmosphericData eleven = new AtmosphericData(base.locationName(), base.solarEventTime(),
                base.targetType(), base.cloud(), base.weather(), base.aerosol(), base.comfort(),
                null, null, null, null);
        AtmosphericData twelve = new AtmosphericData(base.locationName(), base.solarEventTime(),
                base.targetType(), base.cloud(), base.weather(), base.aerosol(), base.comfort(),
                null, null, null, null, (PressureTrend) null);

        assertThat(eleven.mistScore()).isNull();
        assertThat(eleven.mistPotential()).isNull();
        assertThat(twelve.mistScore()).isNull();
        assertThat(twelve.mistPotential()).isNull();
    }

    @Test
    @DisplayName("withValleyMist sets the score and band and leaves every other field alone")
    void withValleyMist_setsOnlyTheMistFields() {
        AtmosphericData base = TestAtmosphericData.builder().inversionScore(7.0).build();

        AtmosphericData result = base.withValleyMist(6.5, MistPotential.LIKELY);

        assertThat(result.mistScore()).isEqualTo(6.5);
        assertThat(result.mistPotential()).isEqualTo(MistPotential.LIKELY);
        assertThat(result.inversionScore()).isEqualTo(7.0);
        assertThat(result.locationName()).isEqualTo(base.locationName());
        assertThat(result.weather()).isSameAs(base.weather());
    }

    @Test
    @DisplayName("every other withX copy carries the mist reading forward")
    void otherCopiesCarryTheMistReading() {
        AtmosphericData scored = TestAtmosphericData.defaults().withValleyMist(6.5, MistPotential.LIKELY);

        AtmosphericData[] copies = {
            scored.withDirectionalCloud(null),
            scored.withTide(null),
            scored.withCloudApproach(null),
            scored.withLocationOrientation("sunrise-optimised"),
            scored.withSurge(null, null, null),
            scored.withInversionScore(8.0),
            scored.withBluebellConditionScore(null),
            scored.withStability(ForecastStability.SETTLED, "test"),
        };

        for (AtmosphericData copy : copies) {
            assertThat(copy.mistScore()).isEqualTo(6.5);
            assertThat(copy.mistPotential()).isEqualTo(MistPotential.LIKELY);
        }
    }
}

package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.TestAtmosphericData;
import com.gregochr.goldenhour.entity.LocationEntity;
import com.gregochr.goldenhour.entity.TideType;
import com.gregochr.goldenhour.model.AtmosphericData;
import com.gregochr.goldenhour.model.CoastalMistBand;
import com.gregochr.goldenhour.model.CoastalMistResult;
import com.gregochr.goldenhour.model.ComfortData;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link CoastalMistCalculator}: the band edges (visibility 4,999 / 5,000 and 10,000 / 10,001 m; gap 1.5 / 1.6
 * after rounding to 0.1 C), the null inputs, and the coastal predicate.
 */
class CoastalMistCalculatorTest {

    /** Builds a slot with the given visibility (m), temperature and dew point (C), and low cloud (%). */
    private static AtmosphericData slot(int visibilityMetres, Double temperature, Double dewPoint, int lowCloud) {
        return TestAtmosphericData.builder().visibility(visibilityMetres).temperature(temperature)
                .dewPoint(dewPoint).lowCloud(lowCloud).build();
    }

    @ParameterizedTest(name = "{0} m, gap {1} C -> {2}")
    @CsvSource({
        "4999, 1.0, THICK",
        "5000, 1.0, LIGHT",
        "10000, 1.0, LIGHT",
        "10001, 1.0, NONE",
        "4999, 9.0, THICK",
        "5000, 1.5, LIGHT",
        "5000, 1.6, NONE",
        "7500, 0.0, LIGHT",
        "25000, 0.2, NONE",
        "1000, 0.0, THICK"
    })
    @DisplayName("the visibility edges are inclusive at 5,000 and 10,000 m, and THICK ignores the gap")
    void visibilityEdges(int visibility, double gap, CoastalMistBand expected) {
        AtmosphericData data = slot(visibility, gap, 0.0, 30);

        CoastalMistResult result = CoastalMistCalculator.calculate(data);

        assertThat(result.band()).isEqualTo(expected);
        assertThat(result.visibilityMetres()).isEqualTo(visibility);
        assertThat(result.lowCloudPct()).isEqualTo(30);
    }

    @Test
    @DisplayName("a gap of 1.54 rounds to 1.5 and is LIGHT; 1.55 rounds to 1.6 and is NONE, as valley mist rounds")
    void gapIsRoundedToTenthsFirst() {
        CoastalMistResult justIn = CoastalMistCalculator.calculate(slot(7000, 11.54, 10.0, 20));
        CoastalMistResult justOut = CoastalMistCalculator.calculate(slot(7000, 11.55, 10.0, 20));

        assertThat(justIn.gapC()).isEqualTo(1.5);
        assertThat(justIn.band()).isEqualTo(CoastalMistBand.LIGHT);
        assertThat(justOut.gapC()).isEqualTo(1.6);
        assertThat(justOut.band()).isEqualTo(CoastalMistBand.NONE);
    }

    @Test
    @DisplayName("a null dew point means no gap: NONE at 5 km or more, THICK below it")
    void nullDewPoint() {
        CoastalMistResult clear = CoastalMistCalculator.calculate(slot(7000, 5.0, null, 20));
        CoastalMistResult thick = CoastalMistCalculator.calculate(slot(3000, 5.0, null, 20));

        assertThat(clear.band()).isEqualTo(CoastalMistBand.NONE);
        assertThat(clear.gapC()).isNull();
        assertThat(thick.band()).isEqualTo(CoastalMistBand.THICK);
    }

    @Test
    @DisplayName("a null temperature means no gap: NONE at 5 km or more")
    void nullTemperature() {
        AtmosphericData data = slot(7000, 5.0, 4.5, 20);
        AtmosphericData noTemperature = new AtmosphericData(data.locationName(), data.solarEventTime(),
                data.targetType(), data.cloud(), data.weather(), data.aerosol(),
                new ComfortData(null, null, null), null, null, null, null);

        CoastalMistResult result = CoastalMistCalculator.calculate(noTemperature);

        assertThat(result.band()).isEqualTo(CoastalMistBand.NONE);
        assertThat(result.gapC()).isNull();
        assertThat(result.visibilityMetres()).isEqualTo(7000);
    }

    @Test
    @DisplayName("null comfort, weather and cloud give NONE with null readings, never an exception")
    void nullBlocks() {
        AtmosphericData empty = new AtmosphericData("x", null, null, null, null, null, null, null, null, null, null);

        CoastalMistResult result = CoastalMistCalculator.calculate(empty);

        assertThat(result.band()).isEqualTo(CoastalMistBand.NONE);
        assertThat(result.visibilityMetres()).isNull();
        assertThat(result.gapC()).isNull();
        assertThat(result.lowCloudPct()).isNull();
    }

    @Test
    @DisplayName("null data is NONE with null readings")
    void nullData() {
        CoastalMistResult result = CoastalMistCalculator.calculate(null);

        assertThat(result.band()).isEqualTo(CoastalMistBand.NONE);
        assertThat(result.visibilityMetres()).isNull();
    }

    @Test
    @DisplayName("the result carries the low cloud the event slot read")
    void carriesLowCloud() {
        AtmosphericData data = slot(7000, 5.0, 4.5, 85);

        CoastalMistResult result = CoastalMistCalculator.calculate(data);

        assertThat(result.lowCloudPct()).isEqualTo(85);
    }

    @Test
    @DisplayName("a coastal site that is not mist_prone applies")
    void coastalNonMistProneApplies() {
        LocationEntity location = LocationEntity.builder().tideType(Set.of(TideType.HIGH)).mistProne(false).build();

        assertThat(CoastalMistCalculator.appliesTo(location)).isTrue();
    }

    @Test
    @DisplayName("a mist_prone coastal site does not apply: one mist signal per site")
    void mistProneCoastalDoesNotApply() {
        LocationEntity location = LocationEntity.builder().tideType(Set.of(TideType.HIGH)).mistProne(true).build();

        assertThat(CoastalMistCalculator.appliesTo(location)).isFalse();
    }

    @Test
    @DisplayName("a site with no tide types does not apply, mist_prone or not")
    void noTideTypesDoesNotApply() {
        LocationEntity inland = LocationEntity.builder().mistProne(false).build();
        LocationEntity inlandMistProne = LocationEntity.builder().mistProne(true).build();

        assertThat(CoastalMistCalculator.appliesTo(inland)).isFalse();
        assertThat(CoastalMistCalculator.appliesTo(inlandMistProne)).isFalse();
        assertThat(CoastalMistCalculator.appliesTo(null)).isFalse();
    }
}

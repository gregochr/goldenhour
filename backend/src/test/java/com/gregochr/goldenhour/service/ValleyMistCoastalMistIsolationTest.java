package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.BrathayValleyMistFixture;
import com.gregochr.goldenhour.PenrithValleyMistFixture;
import com.gregochr.goldenhour.config.CoastalMistProperties;
import com.gregochr.goldenhour.config.InversionProperties;
import com.gregochr.goldenhour.entity.LocationEntity;
import com.gregochr.goldenhour.entity.TideType;
import com.gregochr.goldenhour.model.AtmosphericData;
import com.gregochr.goldenhour.model.CoastalMistBand;
import com.gregochr.goldenhour.model.CoastalMistResult;
import com.gregochr.goldenhour.model.MistPotential;
import com.gregochr.goldenhour.model.SeasonalWindow;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.time.LocalDate;
import java.time.MonthDay;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.Mockito.mock;

/**
 * Isolation guard (coastal-mist-plan section 3, rule 1): the valley mist score and band must not move with the
 * coastal mist carrier, whatever its band, nor with the coastal mist switch.
 *
 * <p>Runs the REAL production chain on the real Brathay and Stainton fixtures, in the order
 * {@code ForecastService} runs it: inversion, valley mist, then coastal mist. A sibling of
 * {@link ValleyMistIsolationTest} and {@link ValleyMistInversionV2IsolationTest}, which stay as they were.
 */
class ValleyMistCoastalMistIsolationTest {

    private static final LocalDate DATE = LocalDate.of(2026, 10, 17);

    private CoastalMistProperties properties;
    private ForecastDataAugmentor augmentor;

    @BeforeEach
    void setUp() {
        properties = new CoastalMistProperties();
        augmentor = new ForecastDataAugmentor(mock(OpenMeteoService.class), mock(SolarService.class),
                mock(TideService.class), mock(TideFactDeriver.class),
                mock(WeatherAugmentedTideService.class), mock(SurgeCalibrationLogger.class),
                mock(BluebellConditionService.class),
                new SeasonalWindow(MonthDay.of(4, 18), MonthDay.of(5, 18), "BLUEBELL"),
                new InversionProperties(), properties);
    }

    private AtmosphericData valleyMist(AtmosphericData parsed) {
        return augmentor.augmentWithValleyMist(augmentor.augmentWithInversionScore(parsed, null, false), true);
    }

    @Test
    @DisplayName("the baseline is the pinned Brathay reading, 7.03 LIKELY, with no coastal carrier")
    void baselineIsPinned() {
        AtmosphericData baseline = valleyMist(
                BrathayValleyMistFixture.sunriseAt(BrathayValleyMistFixture.SUNRISE_READS_0700));

        assertThat(baseline.mistScore()).isCloseTo(7.03, within(0.01));
        assertThat(baseline.mistPotential()).isEqualTo(MistPotential.LIKELY);
        assertThat(baseline.coastalMist()).isNull();
    }

    @ParameterizedTest(name = "carrier band {0}")
    @EnumSource(CoastalMistBand.class)
    @DisplayName("valley mist is identical with each coastal band injected before the valley mist step")
    void valleyMistIsIdenticalWithEachBand(CoastalMistBand band) {
        AtmosphericData parsed = BrathayValleyMistFixture.sunriseAt(BrathayValleyMistFixture.SUNRISE_READS_0700);
        AtmosphericData expected = valleyMist(parsed);

        AtmosphericData actual = valleyMist(parsed.withCoastalMist(new CoastalMistResult(band, 3_000, 0.1, 95)));

        assertThat(actual.mistScore()).isEqualTo(expected.mistScore());
        assertThat(actual.mistPotential()).isEqualTo(expected.mistPotential());
        assertThat(actual.coastalMist().band()).isEqualTo(band);
    }

    @ParameterizedTest(name = "Stainton, carrier band {0}")
    @EnumSource(CoastalMistBand.class)
    @DisplayName("Stainton (5.75 POSSIBLE): valley mist is identical with each coastal band")
    void stainton_valleyMistIsIdenticalWithEachBand(CoastalMistBand band) {
        AtmosphericData parsed = PenrithValleyMistFixture.sunrise(PenrithValleyMistFixture.STAINTON);
        AtmosphericData expected = valleyMist(parsed);
        assertThat(expected.mistScore()).isCloseTo(5.75, within(0.01));

        AtmosphericData actual = valleyMist(parsed.withCoastalMist(new CoastalMistResult(band, 7_000, 0.9, 20)));

        assertThat(actual.mistScore()).isEqualTo(expected.mistScore());
        assertThat(actual.mistPotential()).isEqualTo(expected.mistPotential());
    }

    @Test
    @DisplayName("running the coastal step after valley mist, switch on or off, leaves the mist reading alone")
    void coastalStepAfterValleyMistDoesNotMoveIt() {
        AtmosphericData scored = valleyMist(
                BrathayValleyMistFixture.sunriseAt(BrathayValleyMistFixture.SUNRISE_READS_0700));
        // A coastal, NON-mist-prone location: the only kind the coastal step computes for.
        LocationEntity coastal = LocationEntity.builder().tideType(Set.of(TideType.HIGH)).mistProne(false).build();

        AtmosphericData on = augmentor.augmentWithCoastalMist(scored, coastal, DATE);
        properties.setEnabled(false);
        AtmosphericData off = augmentor.augmentWithCoastalMist(scored, coastal, DATE);

        assertThat(on.coastalMist()).isNotNull();
        assertThat(off.coastalMist()).isNull();
        assertThat(on.mistScore()).isEqualTo(scored.mistScore());
        assertThat(on.mistPotential()).isEqualTo(scored.mistPotential());
        assertThat(off.mistScore()).isEqualTo(scored.mistScore());
        assertThat(off.mistPotential()).isEqualTo(scored.mistPotential());
    }

    @Test
    @DisplayName("a mist_prone site gets only the valley mist signal, never a coastal carrier, even with tide types")
    void mistProneSiteGetsOnlyValleyMist() {
        AtmosphericData scored = valleyMist(
                BrathayValleyMistFixture.sunriseAt(BrathayValleyMistFixture.SUNRISE_READS_0700));
        LocationEntity mistProneCoastal = LocationEntity.builder()
                .tideType(Set.of(TideType.HIGH)).mistProne(true).build();

        AtmosphericData result = augmentor.augmentWithCoastalMist(scored, mistProneCoastal, DATE);

        assertThat(result).isSameAs(scored);
        assertThat(result.coastalMist()).isNull();
        assertThat(result.mistPotential()).isEqualTo(MistPotential.LIKELY);
    }
}

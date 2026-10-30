package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.BrathayValleyMistFixture;
import com.gregochr.goldenhour.PenrithValleyMistFixture;
import com.gregochr.goldenhour.config.InversionProperties;
import com.gregochr.goldenhour.entity.TargetType;
import com.gregochr.goldenhour.model.AtmosphericData;
import com.gregochr.goldenhour.model.InversionProfile;
import com.gregochr.goldenhour.model.InversionV2Result;
import com.gregochr.goldenhour.model.MistPotential;
import com.gregochr.goldenhour.model.SeasonalWindow;
import com.gregochr.goldenhour.service.InversionV2TestSupport.H;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.MonthDay;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.Mockito.mock;

/**
 * The v2 inversion inputs (the valley profile, its failure and absence, the switch) must not move valley mist
 * (inversion-v2-plan section 3, rule 1; Phase 1b "the isolation test gains the profile as an inversion-only
 * input"). A sibling of {@link ValleyMistIsolationTest}, which stays as Phase 0 wrote it: this class runs the
 * same real chain ({@code augmentWithInversionScore} then {@code augmentWithValleyMist}) on the real Brathay
 * and Stainton fixtures, varying only the v2 carrier, the v2 switch and the viewpoint, and asserts the mist
 * score and band equal the baseline EXACTLY.
 */
class ValleyMistInversionV2IsolationTest {

    /** A whole-day valley profile built around the fixture's date: a deep, saturated, calm sea under a warm cap. */
    private static InversionProfile wholeProfile(LocalDate day, Function<H, H> shape) {
        List<InversionProfile.Hour> hours = new ArrayList<>();
        for (int hour = 0; hour < 24; hour++) {
            hours.add(shape.apply(new H(day.atTime(hour, 0))).build());
        }
        return new InversionProfile(54.5, -3.1, 80.0, 80, null, hours);
    }

    private static final Function<H, H> DEEP_SEA = h -> h.temps(6.0, 5.0, 9.0, 11.0, 10.0);
    private static final Function<H, H> NO_LEVELS = h -> h.temps(null, null, null, null, null);
    private static final Function<H, H> NO_RISE = h -> h.t2(9.0).temps(8.0, 7.0, 6.0, 5.0, 4.0);
    private static final Function<H, H> SLOT_UNREADABLE = h -> h.dew(null).wind(null).temps(6.0, 5.0, 9.0, 11.0, 10.0);

    /** One v2 situation: how the carrier looks, whether v2 is on, and the viewpoint. */
    record Variant(String name, Function<H, H> profile, boolean v2On, Integer elevation, boolean overlooksWater) {
        @Override
        public String toString() {
            return name;
        }
    }

    static List<Variant> variants() {
        return List.of(
                new Variant("profile present, deep sea, summit", DEEP_SEA, true, 950, true),
                new Variant("profile present, deep sea, 335 m site (inside)", DEEP_SEA, true, 335, true),
                new Variant("profile present, no rise", NO_RISE, true, 335, true),
                new Variant("profile present but every level null (failed fetch shape)", NO_LEVELS, true, 335, true),
                new Variant("profile present, event slot unreadable", SLOT_UNREADABLE, true, 335, true),
                new Variant("profile null", null, true, 335, true),
                new Variant("v2 switched off with a profile", DEEP_SEA, false, 950, true),
                new Variant("profile present but site ineligible (no water)", DEEP_SEA, true, 335, false),
                new Variant("profile present but elevation below the gate", DEEP_SEA, true, 150, true),
                new Variant("profile present, elevation null", DEEP_SEA, true, null, true));
    }

    private static ForecastDataAugmentor augmentor(boolean v2On) {
        InversionProperties properties = new InversionProperties();
        properties.getV2().setEnabled(v2On);
        return new ForecastDataAugmentor(mock(OpenMeteoService.class), mock(SolarService.class),
                mock(TideService.class), mock(TideFactDeriver.class),
                mock(WeatherAugmentedTideService.class), mock(SurgeCalibrationLogger.class),
                mock(BluebellConditionService.class),
                new SeasonalWindow(MonthDay.of(4, 18), MonthDay.of(5, 18), "BLUEBELL"), properties);
    }

    static Stream<Arguments> brathay() {
        return Stream.of(BrathayValleyMistFixture.SUNRISE_READS_0700, BrathayValleyMistFixture.SUNRISE_READS_0600)
                .flatMap(sunrise -> variants().stream().map(v -> Arguments.of(sunrise, v)));
    }

    private static AtmosphericData chain(AtmosphericData parsed, Variant variant) {
        AtmosphericData input = parsed;
        if (variant.profile() != null) {
            InversionProfile slice = wholeProfile(parsed.solarEventTime().toLocalDate(), variant.profile())
                    .sliceForEvent(parsed.solarEventTime(), TargetType.SUNRISE);
            input = parsed.withInversionV2(InversionV2Result.ofProfile(slice));
        }
        ForecastDataAugmentor augmentor = augmentor(variant.v2On());
        return augmentor.augmentWithValleyMist(
                augmentor.augmentWithInversionScore(input, variant.elevation(), variant.overlooksWater()), true);
    }

    private static AtmosphericData baseline(AtmosphericData parsed) {
        ForecastDataAugmentor augmentor = augmentor(true);
        return augmentor.augmentWithValleyMist(augmentor.augmentWithInversionScore(parsed, null, false), true);
    }

    @ParameterizedTest(name = "Brathay {0}: {1}")
    @MethodSource("brathay")
    @DisplayName("Brathay: mist score and band are identical whatever the v2 profile, its absence or the switch")
    void brathayMistIsUnmoved(LocalDateTime sunrise, Variant variant) {
        AtmosphericData parsed = BrathayValleyMistFixture.sunriseAt(sunrise);
        AtmosphericData expected = baseline(parsed);

        AtmosphericData actual = chain(parsed, variant);

        assertThat(actual.mistScore()).isEqualTo(expected.mistScore());
        assertThat(actual.mistPotential()).isEqualTo(expected.mistPotential());
    }

    @ParameterizedTest(name = "Stainton: {0}")
    @MethodSource("variants")
    @DisplayName("Stainton (29 Oct, 5.75 POSSIBLE): mist is identical whatever the v2 profile")
    void staintonMistIsUnmoved(Variant variant) {
        AtmosphericData parsed = PenrithValleyMistFixture.sunrise(PenrithValleyMistFixture.STAINTON);
        AtmosphericData expected = baseline(parsed);
        assertThat(expected.mistScore()).isCloseTo(5.75, within(0.01));
        assertThat(expected.mistPotential()).isEqualTo(MistPotential.POSSIBLE);

        AtmosphericData actual = chain(parsed, variant);

        assertThat(actual.mistScore()).isEqualTo(expected.mistScore());
        assertThat(actual.mistPotential()).isEqualTo(expected.mistPotential());
    }

    @Test
    @DisplayName("the variants really do reach the inversion score, so the isolation above is not vacuous")
    void variantsReachTheInversionScore() {
        AtmosphericData parsed = BrathayValleyMistFixture.sunriseAt(BrathayValleyMistFixture.SUNRISE_READS_0700);

        AtmosphericData summit = chain(parsed, variants().get(0));
        AtmosphericData inside = chain(parsed, variants().get(1));
        AtmosphericData noProfile = chain(parsed, variants().get(5));
        AtmosphericData off = chain(parsed, variants().get(6));

        assertThat(summit.inversionV2()).isNotNull();
        assertThat(summit.inversionV2().v2Raw()).isNotNull();
        assertThat(inside.inversionV2().v2Raw()).isEqualTo(6.0);
        assertThat(noProfile.inversionV2()).isNull();
        assertThat(off.inversionV2().v2Raw()).isNull();
        assertThat(chain(parsed, variants().get(7)).inversionScore()).isNull();
    }
}

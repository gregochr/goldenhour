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

    /** A valley profile for the fixture's date and the evening before: a deep, saturated, calm sea under a warm cap. */
    private static InversionProfile wholeProfile(LocalDate day, Function<H, H> shape) {
        // The evening before is included so the night side (from 21:00 local) is cut from it too.
        List<InversionProfile.Hour> hours = new ArrayList<>();
        for (int hour = 0; hour < 48; hour++) {
            hours.add(shape.apply(new H(day.minusDays(1).atTime(0, 0).plusHours(hour))).build());
        }
        return new InversionProfile(54.5, -3.1, 80.0, 80, null, hours);
    }

    private static final Function<H, H> DEEP_SEA = h -> h.temps(6.0, 5.0, 9.0, 11.0, 10.0);
    /** Phase 2 night inputs (mid and high cloud) on the same deep sea, in dense fog so no low-cloud lid counts. */
    private static final Function<H, H> CLEAR_NIGHT = h -> DEEP_SEA.apply(h).vis(900.0).mid(0).high(0);
    private static final Function<H, H> OVERCAST_NIGHT = h -> DEEP_SEA.apply(h).vis(900.0).mid(100).high(100);
    private static final Function<H, H> CLEAR_CALM_MOIST_NIGHT =
            h -> h.time().getHour() >= 12
                    // the evening before: clear, calm and moist
                    ? h.t2(8.0).dew(7.5).wind(1.0).low(0).vis(10000.0).mid(0).high(0).temps(10.5, 9.0, 8.0, 7.0, 6.0)
                    // the event window: dry enough (gap 3) and breezy (2.5 m/s) for no fog evidence
                    : h.t2(8.0).dew(5.0).wind(2.5).low(10).vis(10000.0).mid(0).high(0).temps(10.5, 9.0, 8.0, 7.0, 6.0);
    /** Phase 3 level winds (975, 950, 925, 900, 850 hPa) on the same deep sea: 13 m/s caps at 8, 20 m/s at 6. */
    private static final Function<H, H> WINDY_TOP = h -> DEEP_SEA.apply(h).winds(null, 13.0, 9.0, null, null);
    private static final Function<H, H> GALE_TOP = h -> DEEP_SEA.apply(h).winds(null, 20.0, 9.0, null, null);
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
                new Variant("profile present, elevation null", DEEP_SEA, true, null, true),
                new Variant("clear night, summit", CLEAR_NIGHT, true, 950, true),
                new Variant("overcast night, summit (night cap binds)", OVERCAST_NIGHT, true, 950, true),
                new Variant("overcast night, 335 m site", OVERCAST_NIGHT, true, 335, true),
                new Variant("clear, calm, moist night, surface-based layer (Option B)", CLEAR_CALM_MOIST_NIGHT,
                        true, 450, true),
                new Variant("wind 13 m/s across the top, summit (wind cap 8)", WINDY_TOP, true, 950, true),
                new Variant("wind 20 m/s across the top, summit (wind cap 6)", GALE_TOP, true, 950, true),
                new Variant("wind 20 m/s across the top, 335 m site", GALE_TOP, true, 335, true));
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

    @Test
    @DisplayName("the night variants really do reach the inversion score, so their isolation is not vacuous")
    void nightVariantsReachTheInversionScore() {
        AtmosphericData parsed = BrathayValleyMistFixture.sunriseAt(BrathayValleyMistFixture.SUNRISE_READS_0700);

        InversionV2Result clear = chain(parsed, variants().get(10)).inversionV2();
        InversionV2Result overcast = chain(parsed, variants().get(11)).inversionV2();
        InversionV2Result surface = chain(parsed, variants().get(13)).inversionV2();

        assertThat(clear.nightPercent()).isNotNull();
        assertThat(clear.v2Raw()).isEqualTo(10.0);
        assertThat(overcast.nightMidPercent()).isEqualTo(100.0);
        assertThat(overcast.v2Raw()).isEqualTo(8.0);
        assertThat(overcast.nightCapBound()).isTrue();
        assertThat(surface.optionB()).isTrue();
        assertThat(surface.optionBBound()).isTrue();
        assertThat(surface.caps()).isEqualTo("night-fog(8)");
    }

    /** The one variant whose label starts with {@code prefix}, so the reach tests survive a reorder. */
    private static Variant byName(String prefix) {
        return variants().stream().filter(v -> v.name().startsWith(prefix)).reduce((a, b) -> {
            throw new IllegalStateException("ambiguous variant prefix: " + prefix);
        }).orElseThrow(() -> new IllegalStateException("no variant: " + prefix));
    }

    @Test
    @DisplayName("the wind variants really do reach the inversion score, so their isolation is not vacuous")
    void windVariantsReachTheInversionScore() {
        AtmosphericData parsed = BrathayValleyMistFixture.sunriseAt(BrathayValleyMistFixture.SUNRISE_READS_0700);

        InversionV2Result windy = chain(parsed, byName("wind 13 m/s across the top, summit")).inversionV2();
        InversionV2Result gale = chain(parsed, byName("wind 20 m/s across the top, summit")).inversionV2();
        InversionV2Result site = chain(parsed, byName("wind 20 m/s across the top, 335 m site")).inversionV2();

        assertThat(windy.windMs()).isEqualTo(13.0);
        assertThat(windy.windLevelHpa()).isEqualTo(950);
        assertThat(windy.windCapBound()).isTrue();
        assertThat(windy.v2Raw()).isEqualTo(8.0);
        assertThat(gale.windMs()).isEqualTo(20.0);
        assertThat(gale.v2Raw()).isEqualTo(6.0);
        assertThat(gale.caps()).isEqualTo("summit-wind(6)");
        // A 335 m site is inside the layer (cap 6) already, so the gale binds nothing there.
        assertThat(site.windCapBound()).isFalse();
        assertThat(site.caps()).isEqualTo("inside(6)");
    }
}

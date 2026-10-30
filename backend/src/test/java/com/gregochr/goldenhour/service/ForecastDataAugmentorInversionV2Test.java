package com.gregochr.goldenhour.service;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.gregochr.goldenhour.TestAtmosphericData;
import com.gregochr.goldenhour.config.InversionProperties;
import com.gregochr.goldenhour.entity.TargetType;
import com.gregochr.goldenhour.model.AtmosphericData;
import com.gregochr.goldenhour.model.InversionClearance;
import com.gregochr.goldenhour.model.InversionProfile;
import com.gregochr.goldenhour.model.InversionRegime;
import com.gregochr.goldenhour.model.InversionV2Result;
import com.gregochr.goldenhour.model.SeasonalWindow;
import com.gregochr.goldenhour.service.InversionV2TestSupport.H;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.time.MonthDay;

import static com.gregochr.goldenhour.service.InversionV2TestSupport.EVENT;
import static com.gregochr.goldenhour.service.InversionV2TestSupport.profile;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * The storage rules of {@link ForecastDataAugmentor#augmentWithInversionScore}: the switch both ways, v2 null
 * with a profile, the old score null, the raise cap below and at 600 m, the cap flag, and the log line.
 *
 * <p>The old score is controlled through the viewpoint cell: with a 925 hPa reversal of +3 and a boundary
 * layer of 100 m, a gap of 1.0 (4) + calm wind (3) + 40% low cloud (2) scores 9; 5% low cloud vetoes it to 6;
 * a gap of 2.5 with 5% low cloud scores 5; a missing dew point scores null.
 */
class ForecastDataAugmentorInversionV2Test {

    private static final int H0 = 100;

    private InversionProperties properties;
    private ForecastDataAugmentor augmentor;
    private ListAppender<ILoggingEvent> logs;
    private Logger augmentorLogger;

    @BeforeEach
    void setUp() {
        properties = new InversionProperties();
        augmentor = new ForecastDataAugmentor(mock(OpenMeteoService.class), mock(SolarService.class),
                mock(TideService.class), mock(TideFactDeriver.class),
                mock(WeatherAugmentedTideService.class), mock(SurgeCalibrationLogger.class),
                mock(BluebellConditionService.class),
                new SeasonalWindow(MonthDay.of(4, 18), MonthDay.of(5, 18), "BLUEBELL"), properties);
        logs = new ListAppender<>();
        logs.start();
        augmentorLogger = (Logger) LoggerFactory.getLogger(ForecastDataAugmentor.class);
        augmentorLogger.addAppender(logs);
        augmentorLogger.setLevel(Level.INFO);
    }

    @AfterEach
    void tearDown() {
        augmentorLogger.detachAppender(logs);
    }

    /** The viewpoint cell, scoring {@code old} under the old calculator (see the class comment). */
    private static TestAtmosphericData cell(int old) {
        TestAtmosphericData b = TestAtmosphericData.builder()
                .locationName("Test Fell").solarEventTime(EVENT).targetType(TargetType.SUNRISE)
                .temperature(3.0).dewPoint(2.0).windSpeed(new BigDecimal("1.0")).lowCloud(40)
                .temperature925hPa(6.0).boundaryLayerHeight(100).visibility(20000);
        return switch (old) {
            case 9 -> b;
            case 6 -> b.lowCloud(5);
            case 5 -> b.lowCloud(5).dewPoint(0.5);
            default -> throw new IllegalArgumentException("no cell for old score " + old);
        };
    }

    private static AtmosphericData withProfile(AtmosphericData base, InversionProfile profile) {
        return base.withInversionV2(InversionV2Result.ofProfile(profile));
    }

    /** A shallow, surface-based layer with fog evidence: v2 gives 8 at the edge for any E from 200 m. */
    private static InversionProfile shallowFog() {
        return profile(H0, H.at(7).t2(5.0).dew(4.8).vis(3000.0).temps(7.0, 6.0, 5.0, 4.0, 3.0));
    }

    /** An elevated layer with base 680 m and next level 900 m: v2 gives 10 for a viewpoint of 900 m or more. */
    private static InversionProfile deepSea() {
        return profile(H0, H.at(7).temps(6.0, 5.0, 9.0, 11.0, 10.0));
    }

    @Test
    @DisplayName("without a profile the score is the old calculator's, exactly as before")
    void noProfileKeepsTheOldScore() {
        AtmosphericData result = augmentor.augmentWithInversionScore(cell(9).build(), 500, true);

        assertThat(result.inversionScore()).isEqualTo(9.0);
        assertThat(result.inversionV2()).isNull();
        assertThat(logs.list).noneMatch(e -> e.getFormattedMessage().contains("[INVERSION v2]"));
    }

    @Test
    @DisplayName("an ineligible viewpoint is returned untouched even when a profile is attached")
    void ineligibleViewpointIsUntouched() {
        AtmosphericData base = withProfile(cell(9).build(), shallowFog());

        assertThat(augmentor.augmentWithInversionScore(base, 199, true)).isSameAs(base);
        assertThat(augmentor.augmentWithInversionScore(base, null, true)).isSameAs(base);
        assertThat(augmentor.augmentWithInversionScore(base, 500, false)).isSameAs(base);
    }

    @Test
    @DisplayName("the switch on: v2 drives the stored score and the carrier records old, raw, stored")
    void switchOn() {
        AtmosphericData base = withProfile(cell(9).build(), deepSea());

        AtmosphericData result = augmentor.augmentWithInversionScore(base, 1000, true);

        assertThat(result.inversionScore()).isEqualTo(10.0);
        InversionV2Result v2 = result.inversionV2();
        assertThat(v2.v1Score()).isEqualTo(9.0);
        assertThat(v2.v2Raw()).isEqualTo(10.0);
        assertThat(v2.stored()).isEqualTo(10.0);
        assertThat(v2.v2Drove()).isTrue();
        assertThat(v2.regime()).isEqualTo(InversionRegime.ELEVATED);
        assertThat(v2.clearance()).isEqualTo(InversionClearance.CLEAR);
        assertThat(v2.profile()).isNotNull();
    }

    @Test
    @DisplayName("the switch off: the old score is stored and v2 is not computed")
    void switchOff() {
        properties.getV2().setEnabled(false);
        AtmosphericData base = withProfile(cell(9).build(), deepSea());

        AtmosphericData result = augmentor.augmentWithInversionScore(base, 1000, true);

        assertThat(result.inversionScore()).isEqualTo(9.0);
        assertThat(result.inversionV2().v2Raw()).isNull();
        assertThat(result.inversionV2().v2Drove()).isFalse();
        assertThat(augmentor.inversionV2Enabled()).isFalse();
    }

    @Test
    @DisplayName("v2 cannot score with a profile (slot has no dew point): the old score is stored")
    void v2NullWithProfileFallsBackToOld() {
        AtmosphericData base = withProfile(cell(9).build(),
                profile(H0, H.at(7).dew(null).temps(6.0, 5.0, 9.0, 11.0, 10.0)));

        AtmosphericData result = augmentor.augmentWithInversionScore(base, 1000, true);

        assertThat(result.inversionScore()).isEqualTo(9.0);
        assertThat(result.inversionV2().v2Raw()).isNull();
        assertThat(result.inversionV2().v2Drove()).isFalse();
    }

    @Test
    @DisplayName("old score null, v2 scores: v2 is stored, capped at 6")
    void oldNullStoresV2CappedAtSix() {
        AtmosphericData base = withProfile(cell(9).dewPoint(null).build(), deepSea());

        AtmosphericData result = augmentor.augmentWithInversionScore(base, 1000, true);

        assertThat(result.inversionScore()).isEqualTo(6.0);
        assertThat(result.inversionV2().v2Raw()).isEqualTo(10.0);
        assertThat(result.inversionV2().v1Score()).isNull();
        assertThat(result.inversionV2().caps()).isEqualTo("no-v1(6)");
    }

    @Test
    @DisplayName("old score null and v2 under 6: v2 is stored unchanged")
    void oldNullAndLowV2() {
        AtmosphericData base = withProfile(cell(9).dewPoint(null).build(), deepSea());

        AtmosphericData result = augmentor.augmentWithInversionScore(base, 500, true);

        assertThat(result.inversionScore()).isEqualTo(6.0);
        assertThat(result.inversionV2().caps()).isEqualTo("inside(6)");
    }

    @Test
    @DisplayName("v2 may lower a score freely")
    void v2LowersFreely() {
        AtmosphericData base = withProfile(cell(9).build(), deepSea());

        AtmosphericData result = augmentor.augmentWithInversionScore(base, 500, true);

        assertThat(result.inversionScore()).isEqualTo(6.0);
        assertThat(result.inversionV2().v1Score()).isEqualTo(9.0);
    }

    @Test
    @DisplayName("below 600 m v2 may raise the old score by at most one point")
    void raiseCapBelowSixHundred() {
        AtmosphericData base = withProfile(cell(6).build(), shallowFog());

        AtmosphericData result = augmentor.augmentWithInversionScore(base, 599, true);

        assertThat(result.inversionV2().v2Raw()).isEqualTo(8.0);
        assertThat(result.inversionScore()).isEqualTo(7.0);
        assertThat(result.inversionV2().stored()).isEqualTo(7.0);
        assertThat(result.inversionV2().caps()).isEqualTo("edge(8),raise(7)");
    }

    @Test
    @DisplayName("the raise cap never lifts a below-MODERATE site to STRONG")
    void raiseCapNeverReachesStrongFromBelowModerate() {
        AtmosphericData base = withProfile(cell(5).build(), shallowFog());

        AtmosphericData result = augmentor.augmentWithInversionScore(base, 450, true);

        assertThat(result.inversionScore()).isEqualTo(6.0);
        assertThat(result.inversionScore()).isLessThan(9.0);
    }

    @Test
    @DisplayName("at exactly 600 m the viewpoint is a summit: uncapped")
    void summitIsUncapped() {
        AtmosphericData base = withProfile(cell(6).build(), shallowFog());

        AtmosphericData result = augmentor.augmentWithInversionScore(base, 600, true);

        assertThat(result.inversionScore()).isEqualTo(8.0);
        assertThat(result.inversionV2().caps()).isEqualTo("edge(8)");
    }

    @Test
    @DisplayName("a clear summit well above the old score is stored at v2's value")
    void clearSummitIsStoredAtV2() {
        AtmosphericData base = withProfile(cell(6).build(), deepSea());

        AtmosphericData result = augmentor.augmentWithInversionScore(base, 950, true);

        assertThat(result.inversionScore()).isEqualTo(10.0);
        assertThat(result.inversionV2().v1Score()).isEqualTo(6.0);
    }

    @Test
    @DisplayName("raise-cap-enabled false: a low site is stored at v2's value")
    void raiseCapFlagOff() {
        properties.getV2().setRaiseCapEnabled(false);
        AtmosphericData base = withProfile(cell(6).build(), shallowFog());

        AtmosphericData result = augmentor.augmentWithInversionScore(base, 450, true);

        assertThat(result.inversionScore()).isEqualTo(8.0);
        assertThat(result.inversionV2().caps()).isEqualTo("edge(8)");
    }

    @Test
    @DisplayName("an old score at or above v2's is never raised or changed by the cap")
    void capDoesNotTouchEqualScores() {
        AtmosphericData base = withProfile(cell(9).build(), shallowFog());

        AtmosphericData result = augmentor.augmentWithInversionScore(base, 450, true);

        assertThat(result.inversionScore()).isEqualTo(8.0);
        assertThat(result.inversionV2().caps()).isEqualTo("edge(8)");
    }

    @Test
    @DisplayName("one INFO line per scored slot, in the documented format")
    void logLine() {
        AtmosphericData base = withProfile(cell(6).build(), shallowFog());

        augmentor.augmentWithInversionScore(base, 450, true);

        assertThat(logs.list).filteredOn(e -> e.getFormattedMessage().startsWith("[INVERSION v2]"))
                .singleElement().satisfies(e -> {
                    assertThat(e.getLevel()).isEqualTo(Level.INFO);
                    assertThat(e.getFormattedMessage()).isEqualTo(
                            "[INVERSION v2] Test Fell 2025-10-15 SUNRISE old=6 new=7 regime=SURFACE_BASED "
                            + "base=100 top=450 strength=2.0 viewpoint=450 valley=100 caps=edge(8),raise(7)");
                });
    }
}

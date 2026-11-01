package com.gregochr.goldenhour.service;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.gregochr.goldenhour.TestAtmosphericData;
import com.gregochr.goldenhour.config.CoastalMistProperties;
import com.gregochr.goldenhour.config.InversionProperties;
import com.gregochr.goldenhour.entity.LocationEntity;
import com.gregochr.goldenhour.entity.LunarTideType;
import com.gregochr.goldenhour.entity.TargetType;
import com.gregochr.goldenhour.entity.TideState;
import com.gregochr.goldenhour.entity.TideStatisticalSize;
import com.gregochr.goldenhour.entity.TideType;
import com.gregochr.goldenhour.model.AtmosphericData;
import com.gregochr.goldenhour.model.CoastalMistBand;
import com.gregochr.goldenhour.model.SeasonalWindow;
import com.gregochr.goldenhour.model.TideSnapshot;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.MonthDay;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * {@link ForecastDataAugmentor#augmentWithCoastalMist}: the predicate (tide types and not mist_prone, read from
 * the LOCATION and never from the tide snapshot), the switch, and the {@code [COASTAL MIST]} log line.
 */
class ForecastDataAugmentorCoastalMistTest {

    private static final LocalDate DATE = LocalDate.of(2026, 11, 2);

    private CoastalMistProperties properties;
    private ForecastDataAugmentor augmentor;
    private ListAppender<ILoggingEvent> logs;
    private Logger augmentorLogger;
    private Level originalLevel;

    @BeforeEach
    void setUp() {
        properties = new CoastalMistProperties();
        augmentor = new ForecastDataAugmentor(mock(OpenMeteoService.class), mock(SolarService.class),
                mock(TideService.class), mock(TideFactDeriver.class),
                mock(WeatherAugmentedTideService.class), mock(SurgeCalibrationLogger.class),
                mock(BluebellConditionService.class),
                new SeasonalWindow(MonthDay.of(4, 18), MonthDay.of(5, 18), "BLUEBELL"),
                new InversionProperties(), properties);
        logs = new ListAppender<>();
        logs.start();
        augmentorLogger = (Logger) LoggerFactory.getLogger(ForecastDataAugmentor.class);
        augmentorLogger.addAppender(logs);
        originalLevel = augmentorLogger.getLevel();
        augmentorLogger.setLevel(Level.DEBUG);
    }

    @AfterEach
    void tearDown() {
        augmentorLogger.detachAppender(logs);
        augmentorLogger.setLevel(originalLevel);
    }

    private static LocationEntity location(boolean tidal, boolean mistProne) {
        return LocationEntity.builder().name("Boulmer")
                .tideType(tidal ? Set.of(TideType.HIGH) : Set.of()).mistProne(mistProne).build();
    }

    private static AtmosphericData lightMist() {
        return TestAtmosphericData.builder().locationName("Boulmer").targetType(TargetType.SUNRISE)
                .visibility(7_200).temperature(5.0).dewPoint(4.2).lowCloud(30).build();
    }

    @Test
    @DisplayName("a coastal non-mist-prone site is banded, with no tide snapshot on the data")
    void coastalSiteIsBandedWithoutATideSnapshot() {
        AtmosphericData base = lightMist();
        assertThat(base.tide()).as("a failed tide fetch leaves no snapshot").isNull();

        AtmosphericData result = augmentor.augmentWithCoastalMist(base, location(true, false), DATE);

        assertThat(result.coastalMist().band()).isEqualTo(CoastalMistBand.LIGHT);
        assertThat(result.coastalMist().visibilityMetres()).isEqualTo(7_200);
        assertThat(result.coastalMist().gapC()).isEqualTo(0.8);
        assertThat(result.coastalMist().lowCloudPct()).isEqualTo(30);
    }

    @Test
    @DisplayName("a mist_prone coastal site is returned untouched: one mist signal per site")
    void mistProneCoastalSiteIsNotBanded() {
        AtmosphericData base = lightMist();

        AtmosphericData result = augmentor.augmentWithCoastalMist(base, location(true, true), DATE);

        assertThat(result).isSameAs(base);
        assertThat(result.coastalMist()).isNull();
    }

    @Test
    @DisplayName("a site with no tide types is returned untouched even when it has a tide snapshot")
    void inlandSiteIsNotBanded() {
        TideSnapshot snapshot = new TideSnapshot(TideState.HIGH, LocalDateTime.of(2026, 11, 2, 7, 40),
                new BigDecimal("4.60"), LocalDateTime.of(2026, 11, 2, 13, 55), new BigDecimal("1.40"), true,
                LocalDateTime.of(2026, 11, 2, 7, 40), null, LunarTideType.REGULAR_TIDE, null, false,
                TideStatisticalSize.EXTRA_HIGH);
        AtmosphericData base = lightMist().withTide(snapshot);
        assertThat(base.tide()).as("the data really carries a tide snapshot").isSameAs(snapshot);

        AtmosphericData result = augmentor.augmentWithCoastalMist(base, location(false, false), DATE);

        assertThat(result).isSameAs(base);
        assertThat(result.coastalMist()).isNull();
    }

    @Test
    @DisplayName("with the switch off nothing is computed or logged and the data is returned untouched")
    void switchOffComputesNothing() {
        properties.setEnabled(false);
        AtmosphericData base = lightMist();

        AtmosphericData result = augmentor.augmentWithCoastalMist(base, location(true, false), DATE);

        assertThat(result).isSameAs(base);
        assertThat(result.coastalMist()).isNull();
        assertThat(logs.list).noneMatch(e -> e.getFormattedMessage().contains("[COASTAL MIST]"));
    }

    @Test
    @DisplayName("a LIGHT band logs one INFO [COASTAL MIST] line with site, date, event, vis, gap, low and band")
    void lightBandLogsAtInfo() {
        augmentor.augmentWithCoastalMist(lightMist(), location(true, false), DATE);

        assertThat(logs.list).filteredOn(e -> e.getFormattedMessage().contains("[COASTAL MIST]")).singleElement()
                .satisfies(e -> {
                    assertThat(e.getLevel()).isEqualTo(Level.INFO);
                    assertThat(e.getFormattedMessage()).isEqualTo(
                            "[COASTAL MIST] Boulmer 2026-11-02 SUNRISE vis=7200 gap=0.8 low=30 band=LIGHT");
                });
    }

    @Test
    @DisplayName("a THICK band also logs at INFO")
    void thickBandLogsAtInfo() {
        AtmosphericData thick = TestAtmosphericData.builder().locationName("Boulmer")
                .targetType(TargetType.SUNRISE).visibility(2_000).temperature(5.0).dewPoint(4.9).build();

        augmentor.augmentWithCoastalMist(thick, location(true, false), DATE);

        assertThat(logs.list).filteredOn(e -> e.getFormattedMessage().contains("[COASTAL MIST]")).singleElement()
                .satisfies(e -> {
                    assertThat(e.getLevel()).isEqualTo(Level.INFO);
                    assertThat(e.getFormattedMessage()).contains("band=THICK");
                });
    }

    @Test
    @DisplayName("a NONE band logs at DEBUG, not INFO")
    void noneBandLogsAtDebug() {
        AtmosphericData clear = TestAtmosphericData.builder().locationName("Boulmer")
                .targetType(TargetType.SUNRISE).visibility(25_000).temperature(5.0).dewPoint(1.0).build();

        AtmosphericData result = augmentor.augmentWithCoastalMist(clear, location(true, false), DATE);

        assertThat(result.coastalMist().band()).isEqualTo(CoastalMistBand.NONE);
        assertThat(logs.list).filteredOn(e -> e.getFormattedMessage().contains("[COASTAL MIST]")).singleElement()
                .satisfies(e -> {
                    assertThat(e.getLevel()).isEqualTo(Level.DEBUG);
                    assertThat(e.getFormattedMessage()).contains("band=NONE");
                });
    }
}

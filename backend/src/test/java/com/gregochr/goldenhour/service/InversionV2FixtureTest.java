package com.gregochr.goldenhour.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gregochr.goldenhour.entity.TargetType;
import com.gregochr.goldenhour.model.AerosolData;
import com.gregochr.goldenhour.model.AtmosphericData;
import com.gregochr.goldenhour.model.CloudData;
import com.gregochr.goldenhour.model.ComfortData;
import com.gregochr.goldenhour.model.InversionClearance;
import com.gregochr.goldenhour.model.InversionProfile;
import com.gregochr.goldenhour.model.InversionRegime;
import com.gregochr.goldenhour.model.InversionV2Result;
import com.gregochr.goldenhour.model.OpenMeteoForecastResponse;
import com.gregochr.goldenhour.model.SeasonalWindow;
import com.gregochr.goldenhour.model.WeatherData;
import com.gregochr.goldenhour.util.TimeSlotUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.MonthDay;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * Runs the three Phase 1a fixtures (the historical-forecast API, owner-fetched 2026-10-30) through the real
 * parser path ({@link InversionProfile#from}), the event-slot slice, {@link InversionScoreCalculatorV2} and
 * the augmentor's storage rules, for the expected-results table of inversion-v2-plan section 4.
 *
 * <p><b>The fixtures pin historical cells, not production's.</b> They were fetched WITHOUT
 * {@code elevation=}, so each valley point is the cell Open-Meteo chose for those coordinates; production
 * fetches with {@code elevation=<valley_floor_m>}, a different cell with slightly different temperatures.
 * The valley floor h0 below is the valley cell's own returned elevation. Each row pins what the code computes,
 * not a hand-derived expectation; where the two differ the difference is stated on the row.
 *
 * <p>The old score is the real {@link InversionScoreCalculator} on the viewpoint cell (for the two summit
 * rows and Latrigg that is the fixture's own summit or hill cell; for the 451 m and the Cat Bells-style rows
 * the Keswick valley cell stands in, as the plan's illustration does).
 *
 * <p>Results (expected from the plan's section 4 as amended; old = today's calculator; raw = v2 before the
 * raise cap; stored = after it). All six rows match their expectation:
 * <pre>
 *   Blencathra 868 m, 15 Oct 07:00    expected edge, 7    old 6  raw 7  stored 7  EDGE, base 682, top 1130, 4.6 C
 *   Scafell Pike 978 m, 15 Oct 16:00  expected clear, ~7  old 6  raw 7  stored 7  CLEAR, base 675, top 1122, 4.7 C
 *   451 m site, 15 Oct 07:00          expected inside, 6  old 7  raw 6  stored 6  INSIDE, base 682 (the false positive)
 *   Skiddaw-like 911 m, 4 Nov 08:00   expected in cloud 6 old 6  raw 6  stored 6  in-cloud cap binds
 *   Latrigg 368 m, 4 Nov 08:00        expected inside, 6  old 5  raw 6  stored 6  INSIDE, base 863
 *   Helvellyn 950 m, 15 Jan 09:00     expected clear 6-7  old 5  raw 7  stored 7  CLEAR, base 464, top 900, 2.9 C
 * </pre>
 * The two summit rows with an old score below 7 (Blencathra, Helvellyn) and Scafell Pike are raised by 1 to 2
 * points because summits (viewpoint of 600 m or more) are exempt from the raise cap.
 */
class InversionV2FixtureTest {

    private static final String HELVELLYN = "/inversion-v2/2a-helvellyn-2025-01-15.json";
    private static final String OCT_15 = "/inversion-v2/2b2-scafell-blencathra-2025-10-15.json";
    private static final String SKIDDAW = "/inversion-v2/2c-skiddaw-2024-11-04.json";

    private ForecastDataAugmentor augmentor;

    @BeforeEach
    void setUp() {
        augmentor = new ForecastDataAugmentor(mock(OpenMeteoService.class), mock(SolarService.class),
                mock(TideService.class), mock(TideFactDeriver.class),
                mock(WeatherAugmentedTideService.class), mock(SurgeCalibrationLogger.class),
                mock(BluebellConditionService.class),
                new SeasonalWindow(MonthDay.of(4, 18), MonthDay.of(5, 18), "BLUEBELL"));
    }

    /** One row of the expected-results table. */
    private record Outcome(int old, double raw, double stored, InversionRegime regime,
            InversionClearance clearance, Double base, Double top, Double strength, String caps) {
    }

    private static OpenMeteoForecastResponse[] load(String resource) throws IOException {
        try (InputStream in = InversionV2FixtureTest.class.getResourceAsStream(resource)) {
            assertThat(in).as(resource).isNotNull();
            return new ObjectMapper().readValue(in, OpenMeteoForecastResponse[].class);
        }
    }

    /** The viewpoint cell's own readings at the event slot. */
    private static AtmosphericData viewpoint(OpenMeteoForecastResponse cell, LocalDateTime event, TargetType type) {
        OpenMeteoForecastResponse.Hourly h = cell.getHourly();
        int i = TimeSlotUtils.findBestIndex(h.getTime(), event, type);
        return new AtmosphericData("Fixture viewpoint", event, type,
                new CloudData(h.getCloudCoverLow().get(i), h.getCloudCoverMid().get(i),
                        h.getCloudCoverHigh().get(i)),
                new WeatherData(h.getVisibility().get(i).intValue(), BigDecimal.valueOf(h.getWindSpeed10m().get(i)),
                        0, BigDecimal.ZERO, 0, 0, BigDecimal.ZERO, h.getDewPoint2m().get(i), null, null, null,
                        null, h.getTemperature925hPa().get(i), h.getTemperature850hPa().get(i), null),
                new AerosolData(null, null, null, h.getBoundaryLayerHeight().get(i).intValue()),
                new ComfortData(h.getTemperature2m().get(i), null, null),
                null, null, null, null);
    }

    private Outcome run(OpenMeteoForecastResponse valley, OpenMeteoForecastResponse viewpointCell, int elevation,
            LocalDateTime event, TargetType type) {
        int floor = (int) Math.round(valley.getElevation());
        AtmosphericData base = viewpoint(viewpointCell, event, type);
        double old = augmentor.augmentWithInversionScore(base, elevation, true).inversionScore();

        InversionProfile slice = InversionProfile.from(valley, floor).sliceForEvent(event, type);
        AtmosphericData withProfile = base.withInversionV2(InversionV2Result.ofProfile(slice));
        InversionV2Result raw = InversionScoreCalculatorV2.calculate(withProfile, elevation);
        AtmosphericData stored = augmentor.augmentWithInversionScore(withProfile, elevation, true);
        InversionV2Result r = stored.inversionV2();
        return new Outcome((int) old, raw.v2Raw(), stored.inversionScore(), r.regime(), r.clearance(),
                r.baseMetres(), r.topMetres(), r.strengthCelsius(), r.caps());
    }

    @Test
    @DisplayName("15 Oct 2025 07:00, valley Scales, Blencathra 868 m: at the edge, 7 (as observed)")
    void blencathra() throws IOException {
        OpenMeteoForecastResponse[] cells = load(OCT_15);

        Outcome o = run(cells[4], cells[3], 868, LocalDateTime.of(2025, 10, 15, 6, 41), TargetType.SUNRISE);

        // Expected: edge, cap 8, about 7, base about 683 m. The 06:00 hour has the strongest layer (4.6 degC);
        // its base is the 950 hPa level at 682 m and the next level up is 925 hPa at 901 m, so 868 m is the edge.
        assertThat(o.clearance()).isEqualTo(InversionClearance.EDGE);
        assertThat(o.base()).isEqualTo(682.0);
        assertThat(o.top()).isEqualTo(1130.0);
        assertThat(o.strength()).isEqualTo(4.6);
        assertThat(o.raw()).isEqualTo(7.0);
        // 868 m is a summit (>= 600): the old score of 6 does not cap the raise.
        assertThat(o.old()).isEqualTo(6);
        assertThat(o.stored()).isEqualTo(7.0);
        assertThat(o.caps()).isEqualTo("edge(8)");
    }

    @Test
    @DisplayName("15 Oct 2025 16:00, valley Wasdale, Scafell Pike 978 m: clear, about 7")
    void scafellPike() throws IOException {
        OpenMeteoForecastResponse[] cells = load(OCT_15);

        Outcome o = run(cells[1], cells[0], 978, LocalDateTime.of(2025, 10, 15, 16, 20), TargetType.SUNSET);

        // Expected: base about 675 m, next level 894 m, clear, strength about 4.7, about 7.
        assertThat(o.clearance()).isEqualTo(InversionClearance.CLEAR);
        assertThat(o.base()).isEqualTo(675.0);
        assertThat(o.strength()).isEqualTo(4.7);
        assertThat(o.raw()).isEqualTo(7.0);
        assertThat(o.old()).isEqualTo(6);
        assertThat(o.stored()).isEqualTo(7.0);
    }

    @Test
    @DisplayName("15 Oct 2025 07:00, valley Keswick, a 451 m site: inside, 6 (today's calculator says 7)")
    void a451MetreSite() throws IOException {
        OpenMeteoForecastResponse[] cells = load(OCT_15);

        Outcome o = run(cells[2], cells[2], 451, LocalDateTime.of(2025, 10, 15, 6, 41), TargetType.SUNRISE);

        // The false positive the series exists to remove: the old calculator reads 7 (MODERATE).
        assertThat(o.old()).isEqualTo(7);
        assertThat(o.clearance()).isEqualTo(InversionClearance.INSIDE);
        assertThat(o.base()).isEqualTo(682.0);
        assertThat(o.raw()).isEqualTo(6.0);
        assertThat(o.stored()).isEqualTo(6.0);
        assertThat(o.caps()).isEqualTo("inside(6)");
    }

    @Test
    @DisplayName("4 Nov 2024 08:00, valley Keswick, Skiddaw-like 911 m with the cell in cloud: 6")
    void skiddaw() throws IOException {
        OpenMeteoForecastResponse[] cells = load(SKIDDAW);

        Outcome o = run(cells[2], cells[0], 911, LocalDateTime.of(2024, 11, 4, 7, 30), TargetType.SUNRISE);

        // The 2c fetch has no 900 hPa; the profile must still score. The summit cell has 96% low cloud and
        // 2.6 km visibility, so the in-cloud cap binds even though 911 m is only at the edge of the layer.
        assertThat(o.caps()).contains("in-cloud(6)");
        assertThat(o.raw()).isEqualTo(6.0);
        assertThat(o.stored()).isEqualTo(6.0);
        assertThat(o.old()).isEqualTo(6);
    }

    @Test
    @DisplayName("4 Nov 2024 08:00, valley Keswick, Latrigg 368 m: inside, 6")
    void latrigg() throws IOException {
        OpenMeteoForecastResponse[] cells = load(SKIDDAW);

        Outcome o = run(cells[2], cells[1], 368, LocalDateTime.of(2024, 11, 4, 7, 30), TargetType.SUNRISE);

        assertThat(o.clearance()).isEqualTo(InversionClearance.INSIDE);
        assertThat(o.raw()).isEqualTo(6.0);
        // Old 5: the raise cap (a site under 600 m) allows at most 6, which is what v2 scored anyway.
        assertThat(o.old()).isEqualTo(5);
        assertThat(o.stored()).isEqualTo(6.0);
    }

    @Test
    @DisplayName("15 Jan 2025 09:00, valley Glenridding, Helvellyn 950 m: clear, about 7")
    void helvellyn() throws IOException {
        OpenMeteoForecastResponse[] cells = load(HELVELLYN);

        Outcome o = run(cells[1], cells[0], 950, LocalDateTime.of(2025, 1, 15, 8, 15), TargetType.SUNRISE);

        // Expected: base about 469-682 m, clear, about 6-7. A confirmed inversion morning: the old score read
        // the summit's own clear cell and said 5, which is why summits are exempt from the raise cap.
        assertThat(o.clearance()).isEqualTo(InversionClearance.CLEAR);
        assertThat(o.base()).isEqualTo(464.0);
        assertThat(o.raw()).isEqualTo(7.0);
        assertThat(o.old()).isEqualTo(5);
        assertThat(o.stored()).isEqualTo(7.0);
    }
}

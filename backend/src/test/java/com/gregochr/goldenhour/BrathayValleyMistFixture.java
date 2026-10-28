package com.gregochr.goldenhour;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gregochr.goldenhour.entity.TargetType;
import com.gregochr.goldenhour.model.AerosolData;
import com.gregochr.goldenhour.model.AtmosphericData;
import com.gregochr.goldenhour.model.OpenMeteoAirQualityResponse;
import com.gregochr.goldenhour.model.OpenMeteoForecastResponse;
import com.gregochr.goldenhour.model.WeatherData;
import com.gregochr.goldenhour.service.OpenMeteoResponseParser;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The owner's real River Brathay forecast (16-20 Oct 2026, see
 * {@code src/test/resources/valley-mist/README.md}) turned into {@link AtmosphericData} by the
 * PRODUCTION parser, {@link OpenMeteoResponseParser#extractAtmosphericData}, never a hand mapper.
 *
 * <p>The file lacks several fields the parser requires, so the deserialised response is patched
 * first. Every patch is a placeholder chosen so it cannot move valley mist, and is commented where
 * it is applied. Shared by the inversion v2 Phase 0 mist guards
 * ({@code docs/engineering/inversion-v2-plan.md}).
 */
public final class BrathayValleyMistFixture {

    /** Classpath location of the owner's file. */
    public static final String RESOURCE = "/valley-mist/open-meteo-brathay-2026-10-16_20.json";

    /** Sunrise on 17 Oct 2026 at Brathay, UTC (06:41.8): the parser reads the 07:00 slot. */
    public static final LocalDateTime SUNRISE_READS_0700 = LocalDateTime.of(2026, 10, 17, 6, 41);

    /** A sunrise time an hour earlier, which resolves to the 06:00 slot. */
    public static final LocalDateTime SUNRISE_READS_0600 = LocalDateTime.of(2026, 10, 17, 5, 41);

    /** Metres per second in one mile per hour. */
    private static final double MPH_TO_MS = 0.44704;

    private static final double PLACEHOLDER_VISIBILITY_M = 20_000.0;

    private static final int PLACEHOLDER_WIND_DIRECTION = 270;

    private static final double PLACEHOLDER_BOUNDARY_LAYER_M = 100.0;

    private BrathayValleyMistFixture() {
    }

    /**
     * Loads and patches the file.
     *
     * @return the forecast response with every field the parser requires populated
     */
    public static OpenMeteoForecastResponse forecast() {
        OpenMeteoForecastResponse response;
        try (InputStream in = BrathayValleyMistFixture.class.getResourceAsStream(RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException("missing test resource " + RESOURCE);
            }
            response = new ObjectMapper().readValue(in, OpenMeteoForecastResponse.class);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        OpenMeteoForecastResponse.Hourly h = response.getHourly();
        int n = h.getTime().size();

        // PATCH visibility: absent from the file. 20,000 m every hour, never 0: visibilityMetres
        // is a primitive int and anything under 300 reads FOGBOUND. 20 km is clear air, so the
        // confirmation term (under 10 km) stays at zero.
        h.setVisibility(constant(n, PLACEHOLDER_VISIBILITY_M));

        // PATCH precipitation: absent. 0.0 everywhere keeps the wet-ground term at zero.
        h.setPrecipitation(constant(n, 0.0));

        // PATCH total cloud_cover: absent. The mist trend reads TOTAL cloud, so use the maximum of
        // the three layers per hour (exact here because mid and high are 0 overnight).
        List<Integer> total = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            total.add(Math.max(h.getCloudCoverLow().get(i),
                    Math.max(h.getCloudCoverMid().get(i), h.getCloudCoverHigh().get(i))));
        }
        h.setCloudCover(total);

        // PATCH wind_speed_10m: the file is in mph (hourly_units "mp/h"); production requests m/s.
        List<Double> windMs = new ArrayList<>(n);
        for (Double mph : h.getWindSpeed10m()) {
            windMs.add(mph * MPH_TO_MS);
        }
        h.setWindSpeed10m(windMs);

        // PATCH wind_direction_10m: absent, required by the parser; mist never reads direction.
        h.setWindDirection10m(new ArrayList<>(Collections.nCopies(n, PLACEHOLDER_WIND_DIRECTION)));

        // PATCH weather_code: absent, required by the parser; 0 (clear sky), unread by mist.
        h.setWeatherCode(new ArrayList<>(Collections.nCopies(n, 0)));

        // PATCH shortwave_radiation: absent, required by the parser; 0.0, unread by mist.
        h.setShortwaveRadiation(constant(n, 0.0));

        // PATCH boundary_layer_height: absent, required by the parser. 100 m is a plausible stable
        // night-time layer; mist never reads it and the inversion clearance gate passes at 335 m.
        h.setBoundaryLayerHeight(constant(n, PLACEHOLDER_BOUNDARY_LAYER_M));

        return response;
    }

    /**
     * An air-quality response with an empty-but-non-null hourly block: the parser dereferences
     * {@code getHourly()} unguarded, and every aerosol series being null reads as absent data.
     *
     * @return the placeholder air-quality response
     */
    public static OpenMeteoAirQualityResponse airQuality() {
        OpenMeteoAirQualityResponse airQuality = new OpenMeteoAirQualityResponse();
        airQuality.setHourly(new OpenMeteoAirQualityResponse.Hourly());
        return airQuality;
    }

    /**
     * Runs the real {@code extractAtmosphericData} for a SUNRISE at the given UTC time.
     *
     * @param sunriseUtc the UTC sunrise; the parser takes the earliest slot at or after it
     * @return the parsed atmospheric data, with no inversion or mist score yet
     */
    public static AtmosphericData sunriseAt(LocalDateTime sunriseUtc) {
        return OpenMeteoResponseParser.extractAtmosphericData(
                forecast(), airQuality(), "River Brathay", sunriseUtc, TargetType.SUNRISE);
    }

    /**
     * Copies the data with different pressure-level temperatures and boundary layer height,
     * leaving every other field as parsed. Used to vary the inputs mist must not read.
     *
     * @param base                the parsed data
     * @param temperature925      the 925 hPa temperature to set (may be null)
     * @param temperature850      the 850 hPa temperature to set (may be null)
     * @param boundaryLayerMetres the boundary layer height to set
     * @return a copy with those inputs replaced
     */
    public static AtmosphericData withUpperAir(AtmosphericData base, Double temperature925,
            Double temperature850, int boundaryLayerMetres) {
        WeatherData w = base.weather();
        WeatherData weather = new WeatherData(w.visibilityMetres(), w.windSpeedMs(),
                w.windDirectionDegrees(), w.precipitationMm(), w.humidityPercent(), w.weatherCode(),
                w.shortwaveRadiationWm2(), w.dewPointCelsius(), w.pressureHpa(), w.snowfallCm(),
                w.snowDepthMetres(), w.freezingLevelMetres(), temperature925, temperature850,
                w.precedingPrecipitationMm());
        AerosolData a = base.aerosol();
        AerosolData aerosol = new AerosolData(a.pm25(), a.dustUgm3(), a.aerosolOpticalDepth(),
                boundaryLayerMetres);
        return new AtmosphericData(base.locationName(), base.solarEventTime(), base.targetType(),
                base.cloud(), weather, aerosol, base.comfort(), base.directionalCloud(), base.tide(),
                base.cloudApproach(), base.mistTrend(), base.pressureTrend());
    }

    private static List<Double> constant(int n, double value) {
        return new ArrayList<>(Collections.nCopies(n, value));
    }
}

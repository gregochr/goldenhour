package com.gregochr.goldenhour;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gregochr.goldenhour.entity.TargetType;
import com.gregochr.goldenhour.model.AtmosphericData;
import com.gregochr.goldenhour.model.OpenMeteoForecastResponse;
import com.gregochr.goldenhour.service.OpenMeteoResponseParser;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.time.LocalDateTime;
import java.util.List;

/**
 * The owner's real Open-Meteo forecast for Stainton (mist observed) and Keswick (clear) on
 * 29 Oct 2026 (see {@code src/test/resources/valley-mist/README.md}) turned into
 * {@link AtmosphericData} by the PRODUCTION parser, {@link OpenMeteoResponseParser}.
 *
 * <p>Unlike the Brathay file, this one carries every field the parser requires (cloud layers,
 * visibility, wind speed and direction, precipitation, humidity, weather code, shortwave radiation,
 * boundary layer height) with real values, so NO series is patched. The only placeholder is the
 * empty air-quality response, shared with {@link BrathayValleyMistFixture#airQuality()}. The file
 * is a JSON array of two responses; the index picks the location.
 */
public final class PenrithValleyMistFixture {

    /** Classpath location of the owner's file (a JSON array of two responses). */
    public static final String RESOURCE = "/valley-mist/open-meteo-stainton-keswick-2026-10-27_29.json";

    /** Array index of Stainton (54.6478, -2.810852; returned elevation 214 m). */
    public static final int STAINTON = 0;

    /** Array index of Keswick (54.592644, -3.1208649; returned elevation 92 m). */
    public static final int KESWICK = 1;

    /** Sunrise on 29 Oct 2026 at both points, UTC (07:05): the parser reads the 08:00 slot. */
    public static final LocalDateTime SUNRISE = LocalDateTime.of(2026, 10, 29, 7, 5);

    private PenrithValleyMistFixture() {
    }

    /**
     * Deserialises one element of the array, unpatched.
     *
     * @param location {@link #STAINTON} or {@link #KESWICK}
     * @return that location's forecast response
     */
    public static OpenMeteoForecastResponse forecast(int location) {
        try (InputStream in = PenrithValleyMistFixture.class.getResourceAsStream(RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException("missing test resource " + RESOURCE);
            }
            List<OpenMeteoForecastResponse> all = new ObjectMapper().readValue(in,
                    new TypeReference<List<OpenMeteoForecastResponse>>() { });
            return all.get(location);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Runs the real {@code extractAtmosphericData} for the 29 Oct SUNRISE at the given location.
     *
     * @param location {@link #STAINTON} or {@link #KESWICK}
     * @return the parsed atmospheric data, with no inversion or mist score yet
     */
    public static AtmosphericData sunrise(int location) {
        return OpenMeteoResponseParser.extractAtmosphericData(forecast(location),
                BrathayValleyMistFixture.airQuality(),
                location == STAINTON ? "Stainton" : "Keswick", SUNRISE, TargetType.SUNRISE);
    }
}

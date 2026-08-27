package com.gregochr.goldenhour.service.evaluation;

import com.gregochr.goldenhour.TestAtmosphericData;
import com.gregochr.goldenhour.entity.TargetType;
import com.gregochr.goldenhour.model.AerosolData;
import com.gregochr.goldenhour.model.AtmosphericData;
import com.gregochr.goldenhour.model.OpenMeteoAirQualityResponse;
import com.gregochr.goldenhour.model.OpenMeteoForecastResponse;
import com.gregochr.goldenhour.service.OpenMeteoResponseParser;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Guards the rule that an air-quality reading nobody took is never reported as a measurement.
 *
 * <p>The air-quality endpoint returns a shorter window than the forecast endpoint — measured live
 * on 2026-08-27, 120 hours against 168, with a null tail from index 109 — so usable aerosol data
 * ends around T+4 13:00 UTC. Every slot past that had its PM2.5, dust and AOD converted from
 * {@code null} to {@link BigDecimal#ZERO}, which the system prompt then graded against
 * {@code AOD thresholds: 0.05-0.15 clean (baseline)}. Absence was being read as exceptionally
 * clean air.
 */
class AbsentAerosolTest {

    private static final LocalDateTime EVENT = LocalDateTime.of(2026, 8, 27, 20, 0);
    private static final List<String> TIMES = List.of(
            "2026-08-27T19:00", "2026-08-27T20:00", "2026-08-27T21:00");

    @Nested
    @DisplayName("Parser")
    class Parser {

        @Test
        @DisplayName("an air-quality array that ends before the event yields null, never zero")
        void shortAirQualityArray_yieldsNullNotZero() {
            // The real shape of the bug: air quality simply stops short of the forecast window.
            OpenMeteoAirQualityResponse aq = airQuality(
                    List.of("2026-08-27T19:00"), List.of(8.0), List.of(3.0), List.of(0.12));

            AerosolData aerosol = OpenMeteoResponseParser.extractAtmosphericData(
                    forecast(), aq, "Castlerigg", EVENT, TargetType.SUNSET).aerosol();

            assertThat(aerosol.pm25()).isNull();
            assertThat(aerosol.dustUgm3()).isNull();
            assertThat(aerosol.aerosolOpticalDepth())
                    .as("AOD 0.000 sits BELOW the prompt's 0.05 clean baseline, so a zero here is "
                            + "not a neutral default — it claims exceptionally clean air")
                    .isNull();
        }

        @Test
        @DisplayName("a null element inside the array yields null, never zero")
        void nullElementInsideArray_yieldsNullNotZero() {
            // The ragged tail: the array is long enough, but the values are absent.
            OpenMeteoAirQualityResponse aq = airQuality(
                    TIMES, Arrays.asList(8.0, null, 9.0),
                    Arrays.asList(3.0, null, 4.0), Arrays.asList(0.12, null, 0.14));

            AerosolData aerosol = OpenMeteoResponseParser.extractAtmosphericData(
                    forecast(), aq, "Castlerigg", EVENT, TargetType.SUNSET).aerosol();

            assertThat(aerosol.pm25()).isNull();
            assertThat(aerosol.dustUgm3()).isNull();
            assertThat(aerosol.aerosolOpticalDepth()).isNull();
        }

        @Test
        @DisplayName("a genuine zero reading is preserved as zero")
        void measuredZero_isStillZero() {
            // The other half of the contract: absence must be distinguishable from a real zero,
            // which means a real zero has to survive untouched.
            OpenMeteoAirQualityResponse aq = airQuality(
                    TIMES, List.of(8.0, 0.0, 9.0), List.of(3.0, 0.0, 4.0),
                    List.of(0.12, 0.0, 0.14));

            AerosolData aerosol = OpenMeteoResponseParser.extractAtmosphericData(
                    forecast(), aq, "Castlerigg", EVENT, TargetType.SUNSET).aerosol();

            assertThat(aerosol.pm25()).isEqualByComparingTo(BigDecimal.ZERO);
            assertThat(aerosol.dustUgm3()).isEqualByComparingTo(BigDecimal.ZERO);
            assertThat(aerosol.aerosolOpticalDepth()).isEqualByComparingTo(BigDecimal.ZERO);
        }
    }

    @Nested
    @DisplayName("Prompt")
    class Prompt {

        private final PromptBuilder promptBuilder = new PromptBuilder();

        @Test
        @DisplayName("absent readings render as N/A, not as null or a fabricated zero")
        void absentReadings_renderAsNotAvailable() {
            AtmosphericData data = TestAtmosphericData.builder()
                    .pm25(null).dust(null).aod(null).build();

            String prompt = promptBuilder.buildUserMessage(data);

            assertThat(prompt).contains("PM2.5: N/A, Dust: N/A, AOD: N/A");
            assertThat(prompt)
                    .as("`null` would be an unexplained token; N/A is this prompt's existing "
                            + "vocabulary for a missing reading")
                    .doesNotContain("null");
        }

        @Test
        @DisplayName("present readings keep their units and formatting")
        void presentReadings_keepUnits() {
            AtmosphericData data = TestAtmosphericData.builder()
                    .pm25(new BigDecimal("12.30"))
                    .dust(new BigDecimal("4.50"))
                    .aod(new BigDecimal("0.180"))
                    .build();

            String prompt = promptBuilder.buildUserMessage(data);

            assertThat(prompt).contains("PM2.5: 12.30µg/m³, Dust: 4.50µg/m³, AOD: 0.180");
        }
    }

    @Nested
    @DisplayName("Dust signal")
    class DustSignal {

        @Test
        @DisplayName("absent readings cannot raise the dust signal")
        void absentReadings_doNotElevate() {
            // isDustElevated was always null-guarded. The zero had been DEFEATING that guard: it
            // passes the null check and then fails the threshold, so the branch was unreachable
            // for a slot with no data rather than correctly declining it.
            assertThat(PromptBuilder.isDustElevated(new AerosolData(null, null, null, 800)))
                    .isFalse();
        }

        @Test
        @DisplayName("a real elevated reading still raises it")
        void measuredDust_stillElevates() {
            assertThat(PromptBuilder.isDustElevated(
                    new AerosolData(new BigDecimal("10.0"), null, new BigDecimal("0.45"), 800)))
                    .isTrue();
        }
    }

    private static OpenMeteoForecastResponse forecast() {
        OpenMeteoForecastResponse response = new OpenMeteoForecastResponse();
        OpenMeteoForecastResponse.Hourly hourly = new OpenMeteoForecastResponse.Hourly();
        hourly.setTime(TIMES);
        hourly.setCloudCoverLow(List.of(10, 20, 30));
        hourly.setCloudCoverMid(List.of(15, 25, 35));
        hourly.setCloudCoverHigh(List.of(5, 10, 15));
        hourly.setVisibility(List.of(20000.0, 21000.0, 22000.0));
        hourly.setWindSpeed10m(List.of(3.0, 3.5, 4.0));
        hourly.setWindDirection10m(List.of(200, 210, 220));
        hourly.setPrecipitation(List.of(0.0, 0.0, 0.0));
        hourly.setRelativeHumidity2m(List.of(70, 72, 74));
        hourly.setWeatherCode(List.of(1, 1, 2));
        hourly.setShortwaveRadiation(List.of(100.0, 50.0, 0.0));
        hourly.setBoundaryLayerHeight(List.of(800.0, 750.0, 700.0));
        response.setHourly(hourly);
        return response;
    }

    private static OpenMeteoAirQualityResponse airQuality(
            List<String> time, List<Double> pm25, List<Double> dust, List<Double> aod) {
        OpenMeteoAirQualityResponse response = new OpenMeteoAirQualityResponse();
        OpenMeteoAirQualityResponse.Hourly hourly = new OpenMeteoAirQualityResponse.Hourly();
        hourly.setTime(time);
        hourly.setPm25(pm25);
        hourly.setDust(dust);
        hourly.setAerosolOpticalDepth(aod);
        response.setHourly(hourly);
        return response;
    }
}

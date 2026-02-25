package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.entity.TargetType;
import com.gregochr.goldenhour.model.AtmosphericData;
import com.gregochr.goldenhour.model.ForecastRequest;
import com.gregochr.goldenhour.model.OpenMeteoAirQualityResponse;
import com.gregochr.goldenhour.model.OpenMeteoForecastResponse;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * Retrieves atmospheric forecast data from the Open-Meteo Forecast and Air Quality APIs.
 *
 * <p>Makes two parallel GET requests and extracts the values nearest to the solar event time.
 * Both APIs are free and require no API key.
 */
@Service
public class OpenMeteoService {

    private static final Logger LOG = LoggerFactory.getLogger(OpenMeteoService.class);

    private static final String FORECAST_PARAMS =
            "cloud_cover_low,cloud_cover_mid,cloud_cover_high,visibility,"
            + "wind_speed_10m,wind_direction_10m,precipitation,weather_code,"
            + "relative_humidity_2m,surface_pressure,shortwave_radiation,boundary_layer_height";

    private static final String AIR_QUALITY_PARAMS = "pm2_5,dust,aerosol_optical_depth";

    private static final int MAX_RETRIES = 2;
    private static final Duration RETRY_BACKOFF = Duration.ofSeconds(5);

    private static final int WIND_SPEED_SCALE = 2;
    private static final int PRECIP_SCALE = 2;
    private static final int RADIATION_SCALE = 2;
    private static final int AOD_SCALE = 3;

    private final WebClient webClient;

    /**
     * Constructs an {@code OpenMeteoService}.
     *
     * @param webClient shared WebClient for outbound HTTP calls
     */
    public OpenMeteoService(WebClient webClient) {
        this.webClient = webClient;
    }

    /**
     * Fetches atmospheric data from the Open-Meteo APIs and extracts values
     * for the slot nearest to the solar event time.
     *
     * @param request        the forecast request (location, date, target type)
     * @param solarEventTime UTC time of the sunrise or sunset being evaluated
     * @return pre-processed atmospheric data for the ±30-minute window
     */
    public AtmosphericData getAtmosphericData(ForecastRequest request, LocalDateTime solarEventTime) {
        LOG.info("Open-Meteo ← {} {} {}", request.locationName(), request.targetType(),
                solarEventTime.toLocalDate());
        long startMs = System.currentTimeMillis();

        Mono<OpenMeteoForecastResponse> forecastMono = webClient.get()
                .uri(uriBuilder -> uriBuilder
                        .scheme("https").host("api.open-meteo.com").path("/v1/forecast")
                        .queryParam("latitude", request.latitude())
                        .queryParam("longitude", request.longitude())
                        .queryParam("hourly", FORECAST_PARAMS)
                        .queryParam("wind_speed_unit", "ms")
                        .queryParam("timezone", "UTC")
                        .build())
                .retrieve()
                .bodyToMono(OpenMeteoForecastResponse.class)
                .retryWhen(retryOnTransient());

        Mono<OpenMeteoAirQualityResponse> airQualityMono = webClient.get()
                .uri(uriBuilder -> uriBuilder
                        .scheme("https").host("air-quality-api.open-meteo.com")
                        .path("/v1/air-quality")
                        .queryParam("latitude", request.latitude())
                        .queryParam("longitude", request.longitude())
                        .queryParam("hourly", AIR_QUALITY_PARAMS)
                        .queryParam("timezone", "UTC")
                        .build())
                .retrieve()
                .bodyToMono(OpenMeteoAirQualityResponse.class)
                .retryWhen(retryOnTransient());

        AtmosphericData data = Mono.zip(forecastMono, airQualityMono)
                .map(tuple -> extractAtmosphericData(tuple.getT1(), tuple.getT2(),
                        request.locationName(), solarEventTime, request.targetType()))
                .block();
        if (data == null) {
            throw new IllegalStateException("Open-Meteo API returned null response");
        }
        LOG.info("Open-Meteo → {} {}: {}ms", request.locationName(), request.targetType(),
                System.currentTimeMillis() - startMs);
        return data;
    }

    /**
     * Returns a retry spec that retries up to {@value #MAX_RETRIES} times with exponential
     * backoff for transient errors: 5xx server errors and 429 Too Many Requests.
     * Other 4xx client errors are not retried.
     *
     * @return configured {@link Retry} spec
     */
    private Retry retryOnTransient() {
        return Retry.backoff(MAX_RETRIES, RETRY_BACKOFF)
                .filter(ex -> ex instanceof WebClientResponseException wex
                        && (wex.getStatusCode().is5xxServerError()
                            || wex.getStatusCode().value() == 429));
    }

    /**
     * Extracts the forecast values nearest to the solar event time from the API responses.
     *
     * <p>Package-private for unit testing.
     *
     * @param forecast       the Open-Meteo forecast response
     * @param airQuality     the Open-Meteo air quality response
     * @param locationName   human-readable location name
     * @param solarEventTime UTC time of the solar event
     * @param targetType     SUNRISE or SUNSET
     * @return pre-processed atmospheric data for the closest forecast slot
     */
    AtmosphericData extractAtmosphericData(OpenMeteoForecastResponse forecast,
            OpenMeteoAirQualityResponse airQuality, String locationName,
            LocalDateTime solarEventTime, TargetType targetType) {
        List<String> times = forecast.getHourly().getTime();
        int idx = findNearestIndex(times, solarEventTime);

        OpenMeteoForecastResponse.Hourly h = forecast.getHourly();
        OpenMeteoAirQualityResponse.Hourly aq = airQuality.getHourly();

        Double pm25Raw = getAirQualityValue(aq.getPm25(), idx);
        Double dustRaw = getAirQualityValue(aq.getDust(), idx);
        Double aodRaw = getAirQualityValue(aq.getAerosolOpticalDepth(), idx);

        return new AtmosphericData(
                locationName,
                solarEventTime,
                targetType,
                h.getCloudCoverLow().get(idx),
                h.getCloudCoverMid().get(idx),
                h.getCloudCoverHigh().get(idx),
                h.getVisibility().get(idx).intValue(),
                BigDecimal.valueOf(h.getWindSpeed10m().get(idx))
                        .setScale(WIND_SPEED_SCALE, RoundingMode.HALF_UP),
                h.getWindDirection10m().get(idx),
                BigDecimal.valueOf(h.getPrecipitation().get(idx))
                        .setScale(PRECIP_SCALE, RoundingMode.HALF_UP),
                h.getRelativeHumidity2m().get(idx),
                h.getWeatherCode().get(idx),
                h.getBoundaryLayerHeight().get(idx).intValue(),
                BigDecimal.valueOf(h.getShortwaveRadiation().get(idx))
                        .setScale(RADIATION_SCALE, RoundingMode.HALF_UP),
                toDecimal(pm25Raw, PRECIP_SCALE),
                toDecimal(dustRaw, PRECIP_SCALE),
                toDecimal(aodRaw, AOD_SCALE),
                null, // tideState - populated by TideService if location is coastal
                null, // nextHighTideTime
                null, // nextHighTideHeightMetres
                null, // nextLowTideTime
                null, // nextLowTideHeightMetres
                null); // tideAligned
    }

    private Double getAirQualityValue(List<Double> values, int idx) {
        if (values == null || idx >= values.size()) {
            return null;
        }
        return values.get(idx);
    }

    private BigDecimal toDecimal(Double value, int scale) {
        if (value == null) {
            return BigDecimal.ZERO;
        }
        return BigDecimal.valueOf(value).setScale(scale, RoundingMode.HALF_UP);
    }

    private int findNearestIndex(List<String> times, LocalDateTime targetTime) {
        int nearestIdx = 0;
        long minDiff = Long.MAX_VALUE;
        for (int i = 0; i < times.size(); i++) {
            LocalDateTime slotTime = LocalDateTime.parse(times.get(i));
            long diff = Math.abs(ChronoUnit.SECONDS.between(slotTime, targetTime));
            if (diff < minDiff) {
                minDiff = diff;
                nearestIdx = i;
            }
        }
        return nearestIdx;
    }
}

package com.gregochr.goldenhour.service;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.gregochr.goldenhour.entity.LocationEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Fetches current weather conditions from Open-Meteo for aurora-eligible locations.
 *
 * <p>Used by {@link BriefingAuroraSummaryBuilder} to enrich aurora cells with temperature,
 * wind speed, and weather code alongside the existing cloud triage data.
 */
@Service
public class AuroraWeatherEnricher {

    private static final Logger LOG = LoggerFactory.getLogger(AuroraWeatherEnricher.class);

    private final RestClient restClient;

    /**
     * Constructs the enricher with a shared REST client.
     *
     * @param restClient shared HTTP client for Open-Meteo calls
     */
    public AuroraWeatherEnricher(RestClient restClient) {
        this.restClient = restClient;
    }

    /**
     * Fetches weather conditions for each location at the target hour.
     *
     * @param locations  aurora-eligible locations to enrich
     * @param targetHour UTC hour to sample (e.g. current hour for tonight, ~midnight tomorrow)
     * @return map from location ID to weather data; missing entries indicate fetch failure
     */
    public Map<Long, AuroraWeather> fetchWeather(List<LocationEntity> locations,
            ZonedDateTime targetHour) {
        Map<Long, AuroraWeather> result = new HashMap<>();
        String targetTimeStr = targetHour.truncatedTo(ChronoUnit.HOURS)
                .format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm"));

        for (LocationEntity loc : locations) {
            try {
                WeatherResponse response = restClient.get()
                        .uri(uriBuilder -> uriBuilder
                                .scheme("https")
                                .host("api.open-meteo.com")
                                .path("/v1/forecast")
                                .queryParam("latitude", loc.getLat())
                                .queryParam("longitude", loc.getLon())
                                .queryParam("hourly",
                                        "cloud_cover,temperature_2m,wind_speed_10m,weather_code")
                                .queryParam("timezone", "UTC")
                                .queryParam("forecast_days", "2")
                                .build())
                        .retrieve()
                        .body(WeatherResponse.class);

                if (response != null && response.hourly() != null) {
                    AuroraWeather weather = extractAtHour(response.hourly(), targetTimeStr);
                    if (weather != null) {
                        result.put(loc.getId(), weather);
                    }
                }
            } catch (RestClientException e) {
                LOG.debug("Aurora weather fetch failed for {}: {}", loc.getName(), e.getMessage());
            }
        }
        return result;
    }

    /**
     * Extracts weather values at the target hour from the hourly arrays.
     */
    private AuroraWeather extractAtHour(HourlyData hourly, String targetTimeStr) {
        if (hourly.time() == null) {
            return null;
        }
        int idx = hourly.time().indexOf(targetTimeStr);
        if (idx < 0) {
            return null;
        }
        Integer cloud = safeGet(hourly.cloudCover(), idx);
        Double temp = safeGetDouble(hourly.temperature2m(), idx);
        Double wind = safeGetDouble(hourly.windSpeed10m(), idx);
        Integer code = safeGet(hourly.weatherCode(), idx);
        return new AuroraWeather(
                cloud != null ? cloud : 50,
                temp, wind, code);
    }

    private static Integer safeGet(List<Integer> list, int idx) {
        return list != null && idx < list.size() ? list.get(idx) : null;
    }

    private static Double safeGetDouble(List<Double> list, int idx) {
        return list != null && idx < list.size() ? list.get(idx) : null;
    }

    /**
     * Weather conditions at a single aurora location.
     *
     * @param cloudPercent       cloud cover 0–100
     * @param temperatureCelsius temperature in °C, or {@code null}
     * @param windSpeedMs        wind speed in m/s, or {@code null}
     * @param weatherCode        WMO weather code, or {@code null}
     */
    public record AuroraWeather(
            int cloudPercent,
            Double temperatureCelsius,
            Double windSpeedMs,
            Integer weatherCode) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record WeatherResponse(HourlyData hourly) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record HourlyData(
            List<String> time,
            @JsonProperty("cloud_cover") List<Integer> cloudCover,
            @JsonProperty("temperature_2m") List<Double> temperature2m,
            @JsonProperty("wind_speed_10m") List<Double> windSpeed10m,
            @JsonProperty("weather_code") List<Integer> weatherCode) {
    }
}

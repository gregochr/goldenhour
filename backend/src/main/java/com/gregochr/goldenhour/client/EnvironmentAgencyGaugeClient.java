package com.gregochr.goldenhour.client;

import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Reads tide-gauge water levels from the Environment Agency flood-monitoring API.
 *
 * <p>Contains Environment Agency data licensed under the Open Government Licence v3.0. The readings
 * are used in memory by the weekly gauge check and never stored.
 *
 * <p>Uses the shared {@link RestClient} bean, which carries the connect and read timeouts. Any failure
 * propagates as a runtime exception for the caller to log; the caller treats it as "skip the check".
 */
@Component
public class EnvironmentAgencyGaugeClient {

    private static final String READINGS_URL =
            "https://environment.data.gov.uk/flood-monitoring/id/measures/{measureId}/readings"
                    + "?_sorted&startdate={startDate}&enddate={endDate}&_limit={limit}";

    /** Seven days of 15-minute readings is 672; this leaves room for a finer cadence. */
    static final int READINGS_LIMIT = 2000;

    private final RestClient restClient;

    /**
     * Constructs the client.
     *
     * @param restClient the shared RestClient bean
     */
    public EnvironmentAgencyGaugeClient(RestClient restClient) {
        this.restClient = restClient;
    }

    /**
     * Fetches a measure's readings for a date range, oldest first.
     *
     * @param measureId the Environment Agency measure id
     * @param startDate first UTC day, inclusive
     * @param endDate   last UTC day, inclusive
     * @return the readings in ascending time order; empty if the API returned none
     */
    public List<GaugeReading> fetchReadings(String measureId, LocalDate startDate, LocalDate endDate) {
        ReadingsResponse response = restClient.get()
                .uri(READINGS_URL, measureId, startDate.toString(), endDate.toString(), READINGS_LIMIT)
                .retrieve()
                .body(ReadingsResponse.class);
        List<GaugeReading> readings = new ArrayList<>();
        if (response != null && response.items() != null) {
            for (Item item : response.items()) {
                if (item != null && item.dateTime() != null && item.value() != null) {
                    readings.add(new GaugeReading(Instant.parse(item.dateTime()), item.value()));
                }
            }
        }
        readings.sort(Comparator.comparing(GaugeReading::time));
        return readings;
    }

    /**
     * One measured water level.
     *
     * @param time   when it was measured (UTC)
     * @param height water level in metres above Ordnance Datum
     */
    public record GaugeReading(Instant time, double height) { }

    /**
     * The flood-monitoring readings envelope.
     *
     * @param items the readings
     */
    public record ReadingsResponse(List<Item> items) { }

    /**
     * One reading as the API returns it.
     *
     * @param dateTime ISO-8601 UTC timestamp, e.g. {@code 2026-10-30T04:15:00Z}
     * @param value    water level in metres
     */
    public record Item(String dateTime, Double value) { }
}

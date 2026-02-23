package com.gregochr.goldenhour.config;

import com.gregochr.goldenhour.entity.GoldenHourType;
import com.gregochr.goldenhour.entity.LocationType;
import com.gregochr.goldenhour.entity.TideType;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Bound to the {@code forecast} section of {@code application.yml}.
 *
 * <p>Configures the locations to evaluate and the cron schedule for automatic runs.
 */
@Component
@ConfigurationProperties(prefix = "forecast")
@Getter
@Setter
public class ForecastProperties {

    /** Locations for which forecasts are evaluated automatically. */
    private List<Location> locations = new ArrayList<>();

    /** Schedule configuration for automatic forecast runs. */
    private Schedule schedule = new Schedule();

    /**
     * A single configured location.
     */
    @Getter
    @Setter
    public static class Location {

        /** Human-readable name used as the location identifier (e.g. "Durham UK"). */
        private String name;

        /** Latitude in decimal degrees. */
        private double lat;

        /** Longitude in decimal degrees. */
        private double lon;

        /**
         * Which solar events are worth photographing here.
         * Defaults to {@code BOTH_TIMES} if not specified.
         */
        private GoldenHourType goldenHourType = GoldenHourType.BOTH_TIMES;

        /**
         * The photographer's tide preference for this location.
         * Defaults to {@code NOT_COASTAL} if not specified.
         */
        private TideType tideType = TideType.NOT_COASTAL;

        /**
         * Photography type tags (e.g. {@code LANDSCAPE}, {@code SEASCAPE}, {@code WILDLIFE}).
         * A location may have multiple types. Leave empty for untagged.
         */
        private Set<LocationType> locationType = new HashSet<>();
    }

    /**
     * Schedule configuration for the automatic forecast job.
     */
    @Getter
    @Setter
    public static class Schedule {

        /** Cron expression for forecast runs (default: 06:00 and 18:00 daily). */
        private String cron = "0 0 6,18 * * *";
    }
}

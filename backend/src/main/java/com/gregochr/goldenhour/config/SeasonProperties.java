package com.gregochr.goldenhour.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Bound to the {@code photocast.season} section of {@code application.yml}.
 *
 * <p>Holds the calendar boundaries of the seasonal feature windows so they are tunable
 * without a redeploy. Pass 3 of the forecast-score re-architecture moved the bluebell
 * window off a hardcoded {@code MonthDay} constant and onto this config so the five call
 * sites that gate on "is it bluebell season" all read the same configured window via the
 * {@code bluebellSeasonWindow} bean.
 *
 * <p>Dates are expressed as {@code MM-dd} strings (e.g. {@code "04-18"}). They are parsed
 * into a {@link com.gregochr.goldenhour.model.SeasonalWindow} by {@link SeasonConfig}.
 */
@Component
@ConfigurationProperties(prefix = "photocast.season")
@Getter
@Setter
public class SeasonProperties {

    /** The bluebell season window. */
    private Bluebell bluebell = new Bluebell();

    /**
     * Bluebell season boundaries. Defaults mirror the historic hardcoded window —
     * mid-April to mid-May — so an unconfigured deployment behaves exactly as before.
     */
    @Getter
    @Setter
    public static class Bluebell {

        /** First active day of bluebell season, inclusive, as {@code MM-dd}. */
        private String start = "04-18";

        /** Last active day of bluebell season, inclusive, as {@code MM-dd}. */
        private String end = "05-18";
    }
}

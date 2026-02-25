package com.gregochr.goldenhour.entity;

/**
 * External API service names tracked in the API call log.
 */
public enum ServiceName {
    /** Open-Meteo weather forecast data API. */
    OPEN_METEO_FORECAST,

    /** Open-Meteo air quality (CAMS) API. */
    OPEN_METEO_AIR_QUALITY,

    /** WorldTides API for tide extremes. */
    WORLD_TIDES,

    /** Anthropic Claude API for evaluation. */
    ANTHROPIC
}

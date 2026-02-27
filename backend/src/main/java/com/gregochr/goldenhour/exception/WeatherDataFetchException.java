package com.gregochr.goldenhour.exception;

/**
 * Thrown when weather data fetch from Open-Meteo fails.
 *
 * <p>This exception distinguishes weather data unavailability from other failures
 * (e.g., Anthropic API errors). When thrown, it signals that a forecast evaluation
 * cannot proceed and should not attempt to call Anthropic.
 *
 * <p>The exception preserves location name and target type (sunrise/sunset) for
 * context-rich error logging and observability.
 */
public class WeatherDataFetchException extends RuntimeException {
    private final String locationName;
    private final String targetType;

    /**
     * Constructs a new WeatherDataFetchException.
     *
     * @param message     human-readable error message
     * @param locationName the location name (e.g., "Durham", "Seacliff")
     * @param targetType  the target type (e.g., "SUNRISE", "SUNSET")
     * @param cause       the underlying exception, or null if not applicable
     */
    public WeatherDataFetchException(String message, String locationName, String targetType, Throwable cause) {
        super(message, cause);
        this.locationName = locationName;
        this.targetType = targetType;
    }

    /**
     * Gets the location name associated with the failed weather fetch.
     *
     * @return the location name
     */
    public String getLocationName() {
        return locationName;
    }

    /**
     * Gets the target type (sunrise or sunset) for the failed weather fetch.
     *
     * @return the target type
     */
    public String getTargetType() {
        return targetType;
    }
}
